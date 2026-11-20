package com.hd.sync.model.dto;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import lombok.Data;

/**
 * 同步任务运行状态，用于进度展示与问题排查。
 */
@Data
public class SyncTaskStatus {

    /** 任务号 */
    private volatile String taskId;

    /** 同步模式：db / api */
    private volatile String mode;

    /** 本次同步的 relevant_id，为空表示全部 */
    private volatile String relevantId;

    /** 任务是否正在运行 */
    private volatile boolean running;

    /** 当前批次号 */
    private volatile int batchNo;

    /** 从源端取到的总条数 */
    private final AtomicLong fetched = new AtomicLong();

    /** 已同步过被跳过的条数 */
    private final AtomicLong skipped = new AtomicLong();

    /** 本次同步成功条数 */
    private final AtomicLong success = new AtomicLong();

    /** 本次同步失败条数 */
    private final AtomicLong failed = new AtomicLong();

    /** 开始时间 */
    private volatile LocalDateTime startTime;

    /** 结束时间 */
    private volatile LocalDateTime endTime;

    /** 任务结果描述 */
    private volatile String message;

    public long getFetched() {
        return fetched.get();
    }

    public long getSkipped() {
        return skipped.get();
    }

    public long getSuccess() {
        return success.get();
    }

    public long getFailed() {
        return failed.get();
    }

    public void addFetched(long count) {
        fetched.addAndGet(count);
    }

    public void addSkipped(long count) {
        skipped.addAndGet(count);
    }

    public void addSuccess(long count) {
        success.addAndGet(count);
    }

    public void addFailed(long count) {
        failed.addAndGet(count);
    }
}
