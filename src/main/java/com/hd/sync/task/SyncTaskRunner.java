package com.hd.sync.task;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;

import com.hd.sync.config.DataSyncProperties;
import com.hd.sync.model.dto.SyncTaskStatus;
import com.hd.sync.service.FileSyncService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 同步任务执行器：负责调度一次同步任务并维护任务状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncTaskRunner {

    private final SyncTaskManager taskManager;

    private final FileSyncService fileSyncService;

    private final DataSyncProperties properties;

    @Resource(name = "syncTaskExecutor")
    private ExecutorService syncTaskExecutor;

    /**
     * 异步触发一次同步任务。
     *
     * @throws IllegalStateException 上一次任务尚未结束
     */
    public SyncTaskStatus triggerAsync(String mode, String relevantId) {
        if (!taskManager.tryStart()) {
            throw new IllegalStateException("上一次同步任务仍在执行，请稍后再试");
        }
        SyncTaskStatus status = taskManager.start(mode, relevantId);
        try {
            syncTaskExecutor.execute(() -> runSync(status, mode, relevantId));
        } catch (Exception e) {
            taskManager.finish();
            status.setRunning(false);
            status.setEndTime(LocalDateTime.now());
            status.setMessage("任务提交失败：" + e.getMessage());
            throw e;
        }
        return status;
    }

    /**
     * 同步执行一次同步任务（命令行触发时使用）。
     */
    public SyncTaskStatus triggerSync(String mode, String relevantId) {
        if (!taskManager.tryStart()) {
            throw new IllegalStateException("上一次同步任务仍在执行，请稍后再试");
        }
        SyncTaskStatus status = taskManager.start(mode, relevantId);
        runSync(status, mode, relevantId);
        return status;
    }

    private void runSync(SyncTaskStatus status, String mode, String relevantId) {
        long start = System.currentTimeMillis();
        try {
            if (FileSyncService.MODE_API.equalsIgnoreCase(mode)) {
                fileSyncService.syncByApi(relevantId, status);
            } else {
                fileSyncService.syncByDb(relevantId, status);
            }
            if (status.getMessage() == null) {
                status.setMessage("任务完成");
            }
        } catch (Exception e) {
            log.error("[数据同步] 任务执行异常, taskId={}", status.getTaskId(), e);
            status.setMessage("任务异常：" + e.getMessage());
        } finally {
            status.setRunning(false);
            status.setEndTime(LocalDateTime.now());
            taskManager.finish();
            log.info("[数据同步] 任务结束, taskId={}, 模式={}, 耗时={}ms, 取数={}, 成功={}, 失败={}, 跳过={}",
                    status.getTaskId(), mode, System.currentTimeMillis() - start,
                    status.getFetched(), status.getSuccess(), status.getFailed(), status.getSkipped());
        }
    }

    /**
     * 读取配置中的默认模式。
     */
    public String defaultMode() {
        return properties.getMode();
    }
}
