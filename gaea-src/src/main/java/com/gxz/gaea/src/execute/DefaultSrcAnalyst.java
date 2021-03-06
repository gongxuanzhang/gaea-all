package com.gxz.gaea.src.execute;

import cn.hutool.core.io.FileUtil;
import com.google.common.collect.Lists;
import com.gxz.gaea.core.component.ConcurrentFileAppender;
import com.gxz.gaea.core.component.FileAppender;
import com.gxz.gaea.core.component.Filter;
import com.gxz.gaea.core.component.FilterException;
import com.gxz.gaea.core.component.GaeaComponentSorter;
import com.gxz.gaea.core.component.SimpleFileAppender;
import com.gxz.gaea.core.config.GaeaEnvironment;
import com.gxz.gaea.core.execute.analyst.Analyst;
import com.gxz.gaea.core.execute.analyst.LineAnalyst;
import com.gxz.gaea.core.factory.ThreadPoolFactory;
import com.gxz.gaea.core.util.DateUtils;
import com.gxz.gaea.src.annotation.SrcLine;
import com.gxz.gaea.src.config.SrcEnvironment;
import com.gxz.gaea.src.model.SrcData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.CollectionUtils;

import javax.annotation.PostConstruct;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @author gxz gongxuanzhang@foxmail.com
 **/
@Slf4j
public class DefaultSrcAnalyst<M extends SrcData> implements Analyst<File> {

    protected static ThreadPoolExecutor executorService = ThreadPoolFactory.createThreadPool();

    @Autowired
    private LineAnalyst<M> lineAnalyst;

    @Autowired(required = false)
    private List<Filter<M>> filters;

    @Autowired
    private SrcEnvironment srcEnvironment;

    @Autowired
    private GaeaEnvironment gaeaEnvironment;

    /**
     * 此map存放 用fileName 信息做key  行内容作为value的map
     * Map<FileName,Lines>
     */
    protected Map<String, FileAppender> csvMap;

    private boolean isSerial = false;

    @Override
    public void analysis(File file) {
        List<String> lines = FileUtil.readLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            log.warn("{},没有内容，跳过本次执行", file.getAbsolutePath());
            return;
        }
        if ((isSerial = isSerial(lines))) {
            serialAnalysisLines(file, lines);
        } else {
            parallelAnalysisLines(file, lines);
        }
    }


    private void parallelAnalysisLines(File file, List<String> lines) {
        csvMap = new ConcurrentHashMap<>();
        if (log.isTraceEnabled()) {
            log.trace("[{}],有{}行数据,将多线程处理", file.getAbsolutePath(), lines.size());
        }
        List<List<String>> partitions = Lists.partition(lines, (lines.size() / srcEnvironment.getExecutor()) + 1);
        CountDownLatch countDownLatch = new CountDownLatch(partitions.size());
        for (List<String> partition : partitions) {
            executorService.submit(() -> {
                try {
                    analysisLines(partition);
                } catch (Exception e) {
                    log.error(e.getMessage());
                    log.error("解析实体时出现特殊异常");
                } finally {
                    countDownLatch.countDown();
                }
            });
        }
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }


    }

    private void serialAnalysisLines(File file, List<String> lines) {
        csvMap = new HashMap<>();
        if (log.isTraceEnabled()) {
            log.trace("[{}],有{}行数据,将单线程处理", file.getAbsolutePath(), lines.size());
        }
        analysisLines(lines);
    }

    private boolean isSerial(List<String> lines) {
        // 当行数小于阈值 或者配置的线程数小于等于1 使用单线程
        return lines.size() <= srcEnvironment.getSerialMax() || srcEnvironment.getExecutor() <= 1;
    }

    private void analysisLines(List<String> lines) {
        for (String line : lines) {
            M pack;
            try {
                pack = lineAnalyst.pack(line);
            } catch (Exception e) {
                log.error("错误信息:{},错误SRC：{}", e.getMessage(), line);
                continue;
            }
            if (!CollectionUtils.isEmpty(filters)) {
                for (Filter<M> filter : filters) {
                    if (!filter.filter(pack)) {
                        // 执行策略
                        Filter.Policy policy = filter.policy();
                        if (policy == Filter.Policy.STOP) {
                            break;
                        } else if (policy == Filter.Policy.THROW) {
                            throw new FilterException("过滤中出现问题", filter, pack);
                        }
                    }
                }
            }
            // 添加
            appendCsvData(pack);
        }
    }

    /**
     * 把实体添加到csv缓存中  如果缓存超过了一定量 就输出一次 释放缓存
     * 如果单线程执行  使用{@link SimpleFileAppender}类
     * 如果多线程执行  使用{@link ConcurrentFileAppender}类
     */
    protected void appendCsvData(M data) {
        String csvLine = data.toCsv();
        String csvFilePath = getCsvFilePath(data);
        Objects.requireNonNull(this.csvMap.computeIfAbsent(csvFilePath,
                (k) -> {
                    final int outPutMaxLine = srcEnvironment.getOutPutMaxLine();
                    return isSerial ?
                            new SimpleFileAppender(outPutMaxLine, () -> new File(csvFilePath), data.getHead()) :
                            new ConcurrentFileAppender(outPutMaxLine, () -> new File(csvFilePath), data.getHead());
                })).add(csvLine);
    }


    /**
     * 根据数据的捕获时间返回数据路径
     *
     * @param srcData src数据实体
     * @return 文件名称 ${dataWareHouse}/csv/${模块名}/${捕获时间 yyyyMMdd} / ${捕获时间 yyyyMMddHHmm}.${节点名称}.csv
     **/
    private String getCsvFilePath(SrcData srcData) {
        String dataWarehouseCsvPath = gaeaEnvironment.getDataWarehouseCsvPath();
        String nodeName = gaeaEnvironment.getNodeName();
        Long captureTime = srcData.getCaptureTime();
        String dirName = DateUtils.format(captureTime, "yyyyMMdd");
        String fileName = srcEnvironment.getProtocol() + "_"
                + DateUtils.format(captureTime, "yyyyMMddHHmm") + "." + nodeName + ".csv";
        return String.join(File.separator,
                new String[]{dataWarehouseCsvPath, srcEnvironment.getModule(), dirName, fileName});
    }


    /**
     * 初始化的时候给过滤器排序
     **/
    @PostConstruct
    public void init() {
        if (!CollectionUtils.isEmpty(filters)) {
            filters.sort(GaeaComponentSorter.getInstance());
        }
    }

    @Override
    public void free(File file) {
        this.csvMap.forEach((k, v) -> v.write());
        this.csvMap = null;
    }

}
