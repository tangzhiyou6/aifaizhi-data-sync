package com.hd.sync.service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.hd.sync.common.SqlIdentifierValidator;
import com.hd.sync.config.DataSyncProperties;
import com.hd.sync.datasource.DataSourceNames;
import com.hd.sync.mapper.FileInfoMapper;
import com.hd.sync.mapper.FileSyncRecordMapper;
import com.hd.sync.model.FileInfo;
import com.hd.sync.model.FileSyncRecord;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 目标端（szsjzzkk）写入服务：业务落地表 + 同步记录表。
 *
 * <p>类上的 {@code @DS("szsjzzkk")} 保证写操作落到目标库；dynamic-datasource 的 AOP order
 * 默认是最高优先级，{@code @DS} 的上下文会先于事务建立，因此事务内的连接与写入目标是同一个库。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@DS(DataSourceNames.SZSJZZKK)
public class TargetSyncService {

    private final FileInfoMapper fileInfoMapper;

    private final FileSyncRecordMapper fileSyncRecordMapper;

    private final DataSyncProperties properties;

    /**
     * 查询已同步成功的 file_id 集合；命中则本次跳过，不再重复同步。
     */
    public Set<String> querySyncedFileIds(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return Collections.emptySet();
        }
        List<String> synced = fileSyncRecordMapper.selectSyncedFileIds(fileIds, recordSchema(), recordTable());
        return synced == null ? Collections.emptySet() : new HashSet<>(synced);
    }

    /**
     * 同步成功：业务落地表与同步记录表在同一个事务里写入。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveSuccess(List<FileInfo> files, List<FileSyncRecord> records) {
        if (files != null && !files.isEmpty()) {
            fileInfoMapper.upsertBatch(files, targetSchema(), fileInfoTable());
        }
        if (records != null && !records.isEmpty()) {
            fileSyncRecordMapper.upsertBatch(records, recordSchema(), recordTable());
        }
    }

    /**
     * 同步失败：只写同步记录表（幂等键冲突时更新为失败状态）。
     *
     * <p>刻意不写业务落地表：业务表可能正因约束/字段问题而写不进去，
     * 把失败状态留在记录表里才能保证「失败可被记录、下次可重投」。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveFailure(List<FileSyncRecord> records) {
        if (records != null && !records.isEmpty()) {
            fileSyncRecordMapper.upsertBatch(records, recordSchema(), recordTable());
        }
    }

    /**
     * 尽力而为地把失败状态写进业务落地表；写不进去不影响主流程。
     */
    public void saveFailedFileInfoBestEffort(List<FileInfo> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        try {
            fileInfoMapper.upsertBatch(files, targetSchema(), fileInfoTable());
        } catch (Exception e) {
            log.warn("[数据同步] 失败状态写入业务落地表失败（不影响同步记录）：{}", e.getMessage());
        }
    }

    /**
     * 只写同步记录（用于接口页面级失败记录）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveRecords(List<FileSyncRecord> records) {
        if (records != null && !records.isEmpty()) {
            fileSyncRecordMapper.upsertBatch(records, recordSchema(), recordTable());
        }
    }

    private String targetSchema() {
        return SqlIdentifierValidator.validate(properties.getTarget().getSchema(), "target.schema");
    }

    private String fileInfoTable() {
        return SqlIdentifierValidator.validate(properties.getTarget().getFileInfoTable(), "target.file-info-table");
    }

    private String recordSchema() {
        return targetSchema();
    }

    private String recordTable() {
        return SqlIdentifierValidator.validate(properties.getTarget().getRecordTable(), "target.record-table");
    }
}
