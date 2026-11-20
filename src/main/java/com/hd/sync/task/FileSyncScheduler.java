package com.hd.sync.task;

import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hd.sync.config.DataSyncProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 定时同步任务。
 *
 * <p>默认关闭，通过 {@code data-sync.schedule-enabled=true} 打开，
 * cron 表达式由 {@code data-sync.cron} 配置。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileSyncScheduler {

    private final SyncTaskRunner taskRunner;

    private final DataSyncProperties properties;

    @Scheduled(cron = "${data-sync.cron:0 0 3 * * ?}")
    public void run() {
        if (!properties.isScheduleEnabled()) {
            return;
        }
        String relevantId = properties.getRelevantId();
        log.info("[数据同步] 定时任务触发, mode={}, relevantId={}", properties.getMode(), relevantId);
        try {
            taskRunner.triggerAsync(properties.getMode(), relevantId);
        } catch (Exception e) {
            log.error("[数据同步] 定时任务启动失败：{}", e.getMessage());
        }
    }
}
