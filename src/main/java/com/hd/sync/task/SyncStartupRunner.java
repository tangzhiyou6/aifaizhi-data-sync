package com.hd.sync.task;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.hd.sync.config.DataSyncProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 启动后自动执行一次同步任务（由 {@code data-sync.auto-run-on-startup} 控制）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncStartupRunner {

    private final SyncTaskRunner taskRunner;

    private final DataSyncProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        if (!properties.isAutoRunOnStartup()) {
            return;
        }
        log.info("[数据同步] 应用启动完成，自动触发一次同步任务, mode={}, relevantId={}",
                properties.getMode(), properties.getRelevantId());
        try {
            taskRunner.triggerAsync(properties.getMode(), properties.getRelevantId());
        } catch (Exception e) {
            log.error("[数据同步] 启动后自动同步任务启动失败：{}", e.getMessage());
        }
    }
}
