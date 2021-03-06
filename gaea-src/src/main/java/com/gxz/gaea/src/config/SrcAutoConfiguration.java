package com.gxz.gaea.src.config;

import com.gxz.gaea.core.config.GaeaEnvironment;
import com.gxz.gaea.core.execute.analyst.Analyst;
import com.gxz.gaea.src.execute.DefaultSrcAnalyst;
import com.gxz.gaea.src.execute.SrcReceive;
import com.gxz.gaea.src.listener.DeleteFileListener;
import com.gxz.gaea.src.model.SrcData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * @author gxz gongxuanzhang@foxmail.com
 **/
@EnableConfigurationProperties(SrcEnvironment.class)
public class SrcAutoConfiguration {


    @Bean
    public DeleteFileListener deleteFileListener(SrcEnvironment config, GaeaEnvironment environment){
        return new DeleteFileListener(config,environment);
    }

    @Bean
    public SrcReceive srcReceive(){
        return new SrcReceive();
    }

    @Bean
    @ConditionalOnMissingBean(Analyst.class)
    public Analyst<?> analyst(){
        return new DefaultSrcAnalyst();
    }


}
