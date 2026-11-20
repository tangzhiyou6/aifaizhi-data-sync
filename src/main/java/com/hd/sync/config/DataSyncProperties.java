package com.hd.sync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 同步任务配置，对应 application.yml 中 data-sync 节点。
 */
@Data
@Component
@ConfigurationProperties(prefix = "data-sync")
public class DataSyncProperties {

    /** 启动时是否自动执行一次同步任务 */
    private boolean autoRunOnStartup = false;

    /** 命令行触发同步后是否退出进程（适合 crontab 一次性任务） */
    private boolean exitAfterRun = true;

    /**
     * 同步模式：
     * db  = 方案二，直接查源库再写目标库；
     * api = 方案一，客户端调用分页 API 拉取数据后写入目标库。
     */
    private String mode = "db";

    /** 每批同步条数 */
    private int batchSize = 1000;

    /** 失败后的最大重试次数（首次 + 3 次重试 = 最多 4 次尝试） */
    private int maxRetryTimes = 3;

    /** 重试间隔基数（毫秒），第 n 次重试等待 retryIntervalMs * n */
    private long retryIntervalMs = 2000;

    /** 同步的 relevant_id，为空表示同步全部 */
    private String relevantId;

    /** 是否启用定时任务 */
    private boolean scheduleEnabled = false;

    /** 定时任务 cron */
    private String cron = "0 0 3 * * ?";

    /** 来源系统标识，写入同步记录 */
    private String sourceSystem = "spic";

    /** 业务类型标识，写入同步记录 */
    private String bizType = "proj_biz_dm_stg_m";

    private Source source = new Source();

    private Target target = new Target();

    private Api api = new Api();

    /** 源端表配置 */
    @Data
    public static class Source {

        /** proj_biz_dm_stg_m 所在 schema */
        private String schema = "spic_base";

        /** 附件关联表 */
        private String table = "proj_biz_dm_stg_m";

        /** con_file_upload 所在 schema */
        private String fileSchema = "dws_dsjsj";

        /** 文件信息表 */
        private String fileTable = "con_file_upload";
    }

    /** 目标端表配置 */
    @Data
    public static class Target {

        /** 目标表所在 schema */
        private String schema = "ods_dsjsj";

        /** 业务落地表 */
        private String fileInfoTable = "ods_dsjsj_file_info";

        /** 同步记录表（幂等控制） */
        private String recordTable = "ods_dsjsj_file_sync_record";
    }

    /** 方案一 API 客户端配置 */
    @Data
    public static class Api {

        /** 源端分页 API 地址 */
        private String baseUrl = "http://127.0.0.1:13607";

        /** 单页条数 */
        private int pageSize = 1000;

        /** 连接超时（毫秒） */
        private int connectTimeoutMs = 5000;

        /** 读取超时（毫秒） */
        private int readTimeoutMs = 60000;
    }
}
