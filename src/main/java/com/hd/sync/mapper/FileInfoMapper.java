package com.hd.sync.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.hd.sync.model.FileInfo;

/**
 * 目标端（szsjzzkk）业务落地表 Mapper：ods_dsjsj.ods_dsjsj_file_info。
 */
@Mapper
public interface FileInfoMapper {

    /**
     * 批量 upsert：以 file_id 为冲突键，已存在则更新字段，保证重复同步幂等。
     */
    @Insert("<script>"
            + "INSERT INTO ${schema}.${table} "
            + "(relevant_id, file_id, file_url, bucket, file_name, sync_status, retry_count, fail_reason, source_system, biz_type, data_time, update_time) "
            + "VALUES "
            + "<foreach collection='list' item='it' separator=','>"
            + "(#{it.relevantId}, #{it.fileId}, #{it.fileUrl}, #{it.bucket}, #{it.fileName}, #{it.syncStatus}, "
            + " #{it.retryCount}, #{it.failReason}, #{it.sourceSystem}, #{it.bizType}, now(), now())"
            + "</foreach>"
            + "ON CONFLICT (file_id) DO UPDATE SET "
            + "relevant_id = EXCLUDED.relevant_id, "
            + "file_url = EXCLUDED.file_url, "
            + "bucket = EXCLUDED.bucket, "
            + "file_name = EXCLUDED.file_name, "
            + "sync_status = EXCLUDED.sync_status, "
            + "retry_count = EXCLUDED.retry_count, "
            + "fail_reason = EXCLUDED.fail_reason, "
            + "source_system = EXCLUDED.source_system, "
            + "biz_type = EXCLUDED.biz_type, "
            + "update_time = now()"
            + "</script>")
    int upsertBatch(@Param("list") List<FileInfo> list,
                    @Param("schema") String schema,
                    @Param("table") String table);
}
