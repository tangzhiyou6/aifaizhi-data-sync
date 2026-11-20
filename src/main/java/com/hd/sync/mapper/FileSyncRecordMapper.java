package com.hd.sync.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.hd.sync.model.FileSyncRecord;

/**
 * 目标端（szsjzzkk）同步记录表 Mapper：ods_dsjsj.ods_dsjsj_file_sync_record。
 */
@Mapper
public interface FileSyncRecordMapper {

    /**
     * 批量 upsert：以 record_key 为冲突键。
     */
    @Insert("<script>"
            + "INSERT INTO ${schema}.${table} "
            + "(record_key, record_type, relevant_id, file_id, file_url, bucket, sync_mode, sync_status, "
            + " retry_count, fail_reason, batch_no, task_id, data_time, update_time) "
            + "VALUES "
            + "<foreach collection='list' item='it' separator=','>"
            + "(#{it.recordKey}, #{it.recordType}, #{it.relevantId}, #{it.fileId}, #{it.fileUrl}, #{it.bucket}, "
            + " #{it.syncMode}, #{it.syncStatus}, #{it.retryCount}, #{it.failReason}, #{it.batchNo}, #{it.taskId}, now(), now())"
            + "</foreach>"
            + "ON CONFLICT (record_key) DO UPDATE SET "
            + "record_type = EXCLUDED.record_type, "
            + "relevant_id = EXCLUDED.relevant_id, "
            + "file_id = EXCLUDED.file_id, "
            + "file_url = EXCLUDED.file_url, "
            + "bucket = EXCLUDED.bucket, "
            + "sync_mode = EXCLUDED.sync_mode, "
            + "sync_status = EXCLUDED.sync_status, "
            + "retry_count = EXCLUDED.retry_count, "
            + "fail_reason = EXCLUDED.fail_reason, "
            + "batch_no = EXCLUDED.batch_no, "
            + "task_id = EXCLUDED.task_id, "
            + "update_time = now()"
            + "</script>")
    int upsertBatch(@Param("list") List<FileSyncRecord> list,
                    @Param("schema") String schema,
                    @Param("table") String table);

    /**
     * 查询已同步成功的 file_id 集合，用于增量同步时跳过。
     */
    @Select("<script>"
            + "SELECT file_id FROM ${schema}.${table} "
            + "WHERE sync_status = 1 AND record_type = 1 AND file_id IN "
            + "(<foreach collection='fileIds' item='fid' separator=','>#{fid}</foreach>)"
            + "</script>")
    List<String> selectSyncedFileIds(@Param("fileIds") List<String> fileIds,
                                     @Param("schema") String schema,
                                     @Param("table") String table);

    /**
     * 查询单条记录（按幂等键），用于接口页面级记录的排查。
     */
    @Select("SELECT record_key FROM ${schema}.${table} WHERE record_key = #{recordKey}")
    String selectRecordKey(@Param("recordKey") String recordKey,
                           @Param("schema") String schema,
                           @Param("table") String table);
}
