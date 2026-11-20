package com.hd.sync.task;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import com.hd.sync.model.dto.SyncTaskStatus;

/**
 * 同步任务管理：保证同一时刻只有一个同步任务在跑，并对外暴露最新任务状态。
 */
@Component
public class SyncTaskManager {

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final AtomicReference<SyncTaskStatus> current = new AtomicReference<>(new SyncTaskStatus());

    /**
     * 抢占执行权。返回 false 表示上一次任务仍在执行。
     */
    public boolean tryStart() {
        return running.compareAndSet(false, true);
    }

    public void finish() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * 新建并登记一个任务状态。
     */
    public SyncTaskStatus start(String mode, String relevantId) {
        SyncTaskStatus status = new SyncTaskStatus();
        status.setTaskId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        status.setMode(mode);
        status.setRelevantId(relevantId);
        status.setRunning(true);
        status.setStartTime(LocalDateTime.now());
        status.setMessage("任务执行中");
        current.set(status);
        return status;
    }

    public SyncTaskStatus getStatus() {
        return current.get();
    }
}
