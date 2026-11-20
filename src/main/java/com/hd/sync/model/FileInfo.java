package com.hd.sync.model;

import java.io.Serializable;
import java.time.LocalDateTime;

import lombok.Data;

/**
 * 目标端业务落地表：ods_dsjsj.ods_dsjsj_file_info。
 *
 * <p>以 file_id 为唯一键，同步采用 upsert（ON CONFLICT DO UPDATE），重复同步不会产生脏数据。</p>
 */
@Data
public class FileInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** proj_biz_dm_stg_m.relevant_id */
    private String relevantId;

    /** proj_biz_dm_stg_m.file_id = con_file_upload.id，唯一键 */
    private String fileId;

    /** con_file_upload.file_url */
    private String fileUrl;

    /** con_file_upload.bucket */
    private String bucket;

    /** 由 file_url 解析出的文件名 */
    private String fileName;

    /** 同步状态：1=成功，2=失败 */
    private Integer syncStatus;

    /** 累计重试次数 */
    private Integer retryCount;

    /** 失败原因，成功时为空 */
    private String failReason;

    /** 来源系统 */
    private String sourceSystem;

    /** 业务类型 */
    private String bizType;

    /** 数据创建时间 */
    private LocalDateTime dataTime;

    /** 数据更新时间 */
    private LocalDateTime updateTime;
}
