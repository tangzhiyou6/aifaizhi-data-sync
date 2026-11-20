package com.hd.sync.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 统一重试执行器。
 *
 * <p>语义：首次执行失败后最多再重试 {@code maxRetryTimes} 次，即最多尝试
 * {@code maxRetryTimes + 1} 次；第 n 次重试前等待 {@code intervalMs * n} 毫秒。</p>
 */
@Component
public class RetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(RetryExecutor.class);

    /**
     * 执行动作，失败自动重试。
     *
     * @param desc           动作描述，用于日志
     * @param maxRetryTimes  最大重试次数（不含首次执行）
     * @param intervalMs     重试间隔基数（毫秒）
     */
    public <T> T execute(String desc, int maxRetryTimes, long intervalMs, SyncAction<T> action) {
        int maxAttempts = Math.max(1, maxRetryTimes + 1);
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.execute();
            } catch (Exception e) {
                lastException = e;
                log.warn("[数据同步] {} 第{}/{}次尝试失败：{}", desc, attempt, maxAttempts, rootMessage(e));
                if (attempt < maxAttempts) {
                    long waitMs = Math.max(0L, intervalMs) * attempt;
                    log.info("[数据同步] {} 将在 {}ms 后进行第{}次重试", desc, waitMs, attempt + 1);
                    if (!sleep(waitMs)) {
                        log.warn("[数据同步] {} 等待重试被中断，放弃后续重试", desc);
                        break;
                    }
                } else {
                    log.error("[数据同步] {} 已达到最大尝试次数({})，本次失败不再重试：{}", desc, maxAttempts, rootMessage(e));
                }
            }
        }
        throw new SyncRetryExhaustedException(desc + " 重试 " + maxRetryTimes + " 次后仍失败", lastException);
    }

    private boolean sleep(long millis) {
        if (millis <= 0) {
            return !Thread.currentThread().isInterrupted();
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }
}
