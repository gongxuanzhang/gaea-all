package com.gxz.gaea.core.component.annotation;


import com.gxz.gaea.core.component.GaeaComponentSorter;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author gxz gongxuanzhang@foxmail.com
 * 排序规则见 {@link GaeaComponentSorter}
 **/
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Documented
public @interface Order {

    /**
     * value越小  优先级越高
     */
    int value() default Integer.MAX_VALUE;

}
