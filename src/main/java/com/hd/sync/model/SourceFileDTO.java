package com.hd.sync.model;

import java.io.Serializable;

import lombok.Data;

/**
 * 源端附件记录：proj_biz_dm_stg_m 关联 con_file_upload 的查询结果。
 */
@Data
public class SourceFileDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** proj_biz_dm_stg_m.file_id = con_file_upload.id */
    private String fileId;

    /** con_file_upload.file_url */
    private String fileUrl;

    /** con_file_upload.bucket */
    private String bucket;

    /** proj_biz_dm_stg_m.relevant_id */
    private String relevantId;
}
