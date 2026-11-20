package com.hd.sync.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.hd.sync.client.SourceApiClient;
import com.hd.sync.common.FileNameUtils;
import com.hd.sync.common.RetryExecutor;
import com.hd.sync.config.DataSyncProperties;
import com.hd.sync.model.FileInfo;
import com.hd.sync.model.FileSyncRecord;
import com.hd.sync.model.SourceFileDTO;
import com.hd.sync.model.dto.ApiPageResult;
import com.hd.sync.model.dto.SyncTaskStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 数据同步核心逻辑。
 *
 * <p>两种方案共用同一套「拉取 -&gt; 过滤已同步 -&gt; 写入 -&gt; 记录」的流程：</p>
 * <ul>
 *     <li>方案二 {@link #syncByDb}：直接查源库，游标分页，批量写入目标库；</li>
 *     <li>方案一 {@link #syncByApi}：客户端按页调用源端分页 API，批量写入目标库。</li>
 * </ul>
 *
 * <p>幂等策略：目标表以 file_id 唯一键 upsert；同步记录表记录每个 file_id 的同步状态，
 * 状态为成功的下次任务直接跳过，状态为失败的下次任务重新同步。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileSyncService {

    /** 方案二：直连数据库 */
    public static final String MODE_DB = "db";

    /** 方案一：调用分页 API */
    public static final String MODE_API = "api";

    /** 同步状态：成功 */
    public static final int SYNC_STATUS_SUCCESS = 1;

    /** 同步状态：失败 */
    public static final int SYNC_STATUS_FAILED = 2;

    /** 记录类型：文件 */
    private static final int RECORD_TYPE_FILE = 1;

    /** 记录类型：接口页面 */
    private static final int RECORD_TYPE_PAGE = 2;

    /** 接口连续失败多少次后结束本次任务 */
    private static final int MAX_CONSECUTIVE_API_FAILURES = 3;

    private final SourceQueryService sourceQueryService;

    private final TargetSyncService targetSyncService;

    private final SourceApiClient sourceApiClient;

    private final RetryExecutor retryExecutor;

    private final DataSyncProperties properties;

    // ==================== 方案二：直连数据库同步 ====================

    /**
     * 方案二：直接查询源端数据库表，再写入目标库。
     *
     * @param relevantId 只同步指定的 relevant_id，为空表示全部
     * @param status     任务状态对象
     */
    public SyncTaskStatus syncByDb(String relevantId, SyncTaskStatus status) {
        int batchSize = effectiveBatchSize();
        log.info("[数据同步][db] 任务开始, taskId={}, relevantId={}, 每批={}条, 失败重试={}次",
                status.getTaskId(), relevantId, batchSize, maxRetryTimes());

        String cursor = null;
        while (true) {
            List<SourceFileDTO> page;
            final String currentCursor = cursor;
            try {
                page = retryExecutor.execute("查询源端数据", maxRetryTimes(), retryIntervalMs(),
                        () -> sourceQueryService.queryByCursor(relevantId, currentCursor, batchSize));
            } catch (Exception e) {
                log.error("[数据同步][db] 查询源端数据失败，任务结束：{}", rootMessage(e), e);
                status.setMessage("查询源端数据失败：" + rootMessage(e));
                break;
            }
            if (page == null || page.isEmpty()) {
                break;
            }
            cursor = page.get(page.size() - 1).getFileId();
            processPage(page, status, MODE_DB);

            if (page.size() < batchSize) {
                break;
            }
            if (Thread.currentThread().isInterrupted()) {
                log.warn("[数据同步][db] 任务被中断，停止后续批次");
                status.setMessage("任务被中断");
                break;
            }
        }
        return status;
    }

    // ==================== 方案一：客户端消费分页 API ====================

    /**
     * 方案一：客户端按页调用源端分页 API，把数据同步到目标库。
     *
     * @param relevantId 只同步指定的 relevant_id，为空表示全部
     * @param status     任务状态对象
     */
    public SyncTaskStatus syncByApi(String relevantId, SyncTaskStatus status) {
        int page = 1;
        int size = Math.max(1, Math.min(properties.getApi().getPageSize(), effectiveBatchSize()));
        log.info("[数据同步][api] 任务开始, taskId={}, relevantId={}, 每页={}条, 失败重试={}次, 接口地址={}",
                status.getTaskId(), relevantId, size, maxRetryTimes(), properties.getApi().getBaseUrl());

        int consecutiveFailures = 0;
        while (true) {
            ApiPageResult result;
            final int currentPage = page;
            try {
                result = retryExecutor.execute("调用源端分页 API 第" + page + "页", maxRetryTimes(), retryIntervalMs(),
                        () -> sourceApiClient.fetchPage(relevantId, currentPage, size));
                consecutiveFailures = 0;
            } catch (Exception e) {
                consecutiveFailures++;
                log.error("[数据同步][api] 第{}页访问失败，已重试{}次仍不成功，跳过该页并记录失败状态：{}",
                        page, maxRetryTimes(), rootMessage(e));
                savePageFailureRecord(relevantId, page, status, rootMessage(e));
                if (consecutiveFailures >= MAX_CONSECUTIVE_API_FAILURES) {
                    log.error("[数据同步][api] 连续{}页访问失败，任务提前结束", consecutiveFailures);
                    status.setMessage("连续访问接口失败 " + consecutiveFailures + " 次，任务提前结束");
                    break;
                }
                page++;
                continue;
            }

            List<SourceFileDTO> records = result.getRecords();
            if (records == null || records.isEmpty()) {
                break;
            }
            processPage(records, status, MODE_API);

            if (!result.isHasNext()) {
                break;
            }
            page++;
            if (Thread.currentThread().isInterrupted()) {
                log.warn("[数据同步][api] 任务被中断，停止后续分页");
                status.setMessage("任务被中断");
                break;
            }
        }
        return status;
    }

    // ==================== 公共处理逻辑 ====================

    /**
     * 处理一批数据：过滤已同步记录 -> 批量写入目标库 -> 记录同步结果。
     */
    private void processPage(List<SourceFileDTO> page, SyncTaskStatus status, String mode) {
        int batchNo = status.getBatchNo() + 1;
        status.setBatchNo(batchNo);
        status.addFetched(page.size());

        List<SourceFileDTO> validRows = new ArrayList<>(page.size());
        Set<String> fileIds = new HashSet<>();
        for (SourceFileDTO dto : page) {
            if (dto == null || StringUtils.isBlank(dto.getFileId())) {
                continue;
            }
            validRows.add(dto);
            fileIds.add(dto.getFileId());
        }

        Set<String> synced = querySyncedFileIds(fileIds);
        List<SourceFileDTO> pending = new ArrayList<>(validRows.size());
        int skipped = 0;
        for (SourceFileDTO dto : validRows) {
            if (synced.contains(dto.getFileId())) {
                skipped++;
            } else {
                pending.add(dto);
            }
        }
        status.addSkipped(skipped);
        log.info("[数据同步][{}][第{}批] 本批{}条, 已同步跳过{}条, 待同步{}条",
                mode, batchNo, page.size(), skipped, pending.size());

        if (pending.isEmpty()) {
            return;
        }

        List<FileInfo> files = buildFileInfos(pending, SYNC_STATUS_SUCCESS, null, 0);
        List<FileSyncRecord> records = buildSyncRecords(pending, mode, SYNC_STATUS_SUCCESS, batchNo,
                status.getTaskId(), null, 0);

        int[] attempts = new int[]{0};
        try {
            retryExecutor.execute("第" + batchNo + "批写入目标库", maxRetryTimes(), retryIntervalMs(), () -> {
                attempts[0]++;
                targetSyncService.saveSuccess(files, records);
                return null;
            });
            int retryCount = Math.max(0, attempts[0] - 1);
            status.addSuccess(pending.size());
            log.info("[数据同步][{}][第{}批] 写入成功{}条, 实际重试{}次。累计：取数{}，成功{}，失败{}，跳过{}",
                    mode, batchNo, pending.size(), retryCount,
                    status.getFetched(), status.getSuccess(), status.getFailed(), status.getSkipped());
        } catch (Exception e) {
            int retryCount = maxRetryTimes();
            status.addFailed(pending.size());
            log.error("[数据同步][{}][第{}批] 写入目标库失败，已重试{}次，跳过本批数据并标记失败，下次任务会重新同步：{}",
                    mode, batchNo, retryCount, rootMessage(e));
            markFailure(pending, mode, batchNo, status, rootMessage(e), retryCount);
        }
    }

    /**
     * 查询已同步成功的 file_id；查询失败时按未同步处理（写入是幂等 upsert，重复同步不会产生脏数据）。
     */
    private Set<String> querySyncedFileIds(Set<String> fileIds) {
        if (fileIds.isEmpty()) {
            return Collections.emptySet();
        }
        try {
            return retryExecutor.execute("查询同步记录", maxRetryTimes(), retryIntervalMs(),
                    () -> targetSyncService.querySyncedFileIds(new ArrayList<>(fileIds)));
        } catch (Exception e) {
            log.warn("[数据同步] 查询同步记录失败，本批按未同步处理：{}", rootMessage(e));
            return Collections.emptySet();
        }
    }

    /**
     * 把失败状态写入库（业务落地表 + 同步记录表），下次任务会重新同步这些数据。
     */
    private void markFailure(List<SourceFileDTO> pending, String mode, int batchNo,
                             SyncTaskStatus status, String reason, int retryCount) {
        List<FileInfo> failedFiles = buildFileInfos(pending, SYNC_STATUS_FAILED, reason, retryCount);
        List<FileSyncRecord> failedRecords = buildSyncRecords(pending, mode, SYNC_STATUS_FAILED, batchNo,
                status.getTaskId(), reason, retryCount);
        try {
            retryExecutor.execute("第" + batchNo + "批记录失败状态", maxRetryTimes(), retryIntervalMs(), () -> {
                targetSyncService.saveFailure(failedRecords);
                return null;
            });
            log.warn("[数据同步][{}][第{}批] 失败状态已保存，{}条数据将在下次任务重新同步", mode, batchNo, pending.size());
        } catch (Exception e) {
            log.error("[数据同步][{}][第{}批] 失败状态保存失败：{}。这些数据没有成功记录，下次任务同样会重新同步",
                    mode, batchNo, rootMessage(e));
            return;
        }
        // 业务落地表里的失败状态只是补充信息，写不进去不影响失败记录的留存
        targetSyncService.saveFailedFileInfoBestEffort(failedFiles);
    }

    /**
     * 接口页面级失败记录：接口访问最终失败时留痕，下次任务重新拉取该页。
     */
    private void savePageFailureRecord(String relevantId, int page, SyncTaskStatus status, String reason) {
        FileSyncRecord record = new FileSyncRecord();
        record.setRecordKey("page:" + (StringUtils.isBlank(relevantId) ? "all" : relevantId) + ":" + page);
        record.setRecordType(RECORD_TYPE_PAGE);
        record.setRelevantId(relevantId);
        record.setSyncMode(MODE_API);
        record.setSyncStatus(SYNC_STATUS_FAILED);
        record.setRetryCount(maxRetryTimes());
        record.setFailReason(reason);
        record.setBatchNo(page);
        record.setTaskId(status.getTaskId());
        try {
            targetSyncService.saveRecords(Collections.singletonList(record));
        } catch (Exception e) {
            log.error("[数据同步][api] 第{}页的失败访问记录保存失败：{}", page, rootMessage(e));
        }
    }

    private List<FileInfo> buildFileInfos(List<SourceFileDTO> rows, int syncStatus, String failReason, int retryCount) {
        List<FileInfo> list = new ArrayList<>(rows.size());
        for (SourceFileDTO dto : rows) {
            FileInfo info = new FileInfo();
            info.setRelevantId(dto.getRelevantId());
            info.setFileId(dto.getFileId());
            info.setFileUrl(dto.getFileUrl());
            info.setBucket(dto.getBucket());
            info.setFileName(FileNameUtils.resolveFileName(dto.getFileUrl(), dto.getFileId()));
            info.setSyncStatus(syncStatus);
            info.setRetryCount(retryCount);
            info.setFailReason(failReason);
            info.setSourceSystem(properties.getSourceSystem());
            info.setBizType(properties.getBizType());
            list.add(info);
        }
        return list;
    }

    private List<FileSyncRecord> buildSyncRecords(List<SourceFileDTO> rows, String mode, int syncStatus,
                                                    int batchNo, String taskId, String failReason, int retryCount) {
        List<FileSyncRecord> list = new ArrayList<>(rows.size());
        for (SourceFileDTO dto : rows) {
            FileSyncRecord record = new FileSyncRecord();
            record.setRecordKey(dto.getFileId());
            record.setRecordType(RECORD_TYPE_FILE);
            record.setRelevantId(dto.getRelevantId());
            record.setFileId(dto.getFileId());
            record.setFileUrl(dto.getFileUrl());
            record.setBucket(dto.getBucket());
            record.setSyncMode(mode);
            record.setSyncStatus(syncStatus);
            record.setRetryCount(retryCount);
            record.setFailReason(failReason);
            record.setBatchNo(batchNo);
            record.setTaskId(taskId);
            list.add(record);
        }
        return list;
    }

    private int effectiveBatchSize() {
        return Math.max(1, properties.getBatchSize());
    }

    private int maxRetryTimes() {
        return Math.max(0, properties.getMaxRetryTimes());
    }

    private long retryIntervalMs() {
        return Math.max(0L, properties.getRetryIntervalMs());
    }

    private String rootMessage(Throwable e) {
        if (e == null) {
            return "未知错误";
        }
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }
}
