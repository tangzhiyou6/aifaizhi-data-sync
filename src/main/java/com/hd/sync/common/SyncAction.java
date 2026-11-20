package com.hd.sync.common;

/**
 * 同步过程中执行的动作，允许抛出受检异常。
 */
@FunctionalInterface
public interface SyncAction<T> {

    T execute() throws Exception;
}
