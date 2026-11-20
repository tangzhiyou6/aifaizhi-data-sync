package com.hd.sync.datasource;

/**
 * 多数据源名称常量，与 application.yml 中 spring.datasource.dynamic.datasource 下的 key 对应。
 */
public final class DataSourceNames {

    /** 源端库：jm_cloud */
    public static final String JM_CLOUD = "jm_cloud";

    /** 目标端库：szsjzzkk */
    public static final String SZSJZZKK = "szsjzzkk";

    private DataSourceNames() {
    }
}
