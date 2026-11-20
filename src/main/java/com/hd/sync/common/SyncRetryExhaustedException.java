package com.hd.sync.common;

/**
 * 重试次数耗尽后抛出，携带最后一次的原始异常。
 */
public class SyncRetryExhaustedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SyncRetryExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
