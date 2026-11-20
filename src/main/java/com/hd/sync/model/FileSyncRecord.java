package com.hd.sync.model;

import java.io.Serializable;
import java.time.LocalDateTime;

import lombok.Data;

/**
 * 同步记录表：ods_dsjsj.ods_dsjsj_file_sync_record（幂等控制）。
 *
 * <p>record_key 为唯一键：文件级记录取 file_id，接口页面级记录取 {@code page:{relevantId}:{page}}，
 * 两者不会冲突（真实 file_id 为纯数字串）。</p>
 *
 * <p>sync_status = 1 表示已同步成功，下次任务直接跳过；
 * sync_status = 2 表示同步失败，下次任务会重新同步。</p>
 */
@Data
public class FileSyncRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 幂等键：file_id 或 page:{relevantId}:{page} */
    private String recordKey;

    /** 记录类型：1=文件记录，2=接口页面记录 */
    private Integer recordType;

    /** 查询条件 relevant_id */
    private String relevantId;

    /** 文件ID（页面级记录为空） */
    private String fileId;

    /** con_file_upload.file_url */
    private String fileUrl;

    /** con_file_upload.bucket */
    private String bucket;

    /** 同步方式：db / api */
    private String syncMode;

    /** 同步状态：1=成功，2=失败 */
    private Integer syncStatus;

    /** 累计重试次数 */
    private Integer retryCount;

    /** 失败原因 */
    private String failReason;

    /** 所属批次号 */
    private Integer batchNo;

    /** 任务号 */
    private String taskId;

    /** 数据创建时间 */
    private LocalDateTime dataTime;

    /** 数据更新时间 */
    private LocalDateTime updateTime;
}
