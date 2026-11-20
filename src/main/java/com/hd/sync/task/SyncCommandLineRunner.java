package com.hd.sync.task;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import com.hd.sync.config.DataSyncProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 命令行触发同步任务。
 *
 * <pre>
 * java -jar aifaizhi-data-sync.jar --sync                  # 同步配置里的 relevant_id
 * java -jar aifaizhi-data-sync.jar --sync=2086724333076062209  # 同步指定 relevant_id
 * java -jar aifaizhi-data-sync.jar --sync --mode=api        # 用方案一（消费分页接口）执行
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncCommandLineRunner implements ApplicationRunner {

    private final SyncTaskRunner taskRunner;

    private final DataSyncProperties properties;

    private final ApplicationContext applicationContext;

    @Override
    public void run(ApplicationArguments args) {
        String relevantId = null;
        String mode = properties.getMode();
        boolean syncRequested = false;

        for (String arg : args.getSourceArgs()) {
            if ("--sync".equals(arg)) {
                syncRequested = true;
            } else if (arg.startsWith("--sync=")) {
                syncRequested = true;
                relevantId = arg.substring("--sync=".length());
            } else if (arg.startsWith("--mode=")) {
                mode = arg.substring("--mode=".length());
            } else if (!arg.startsWith("-")) {
                relevantId = arg;
            }
        }

        if (!syncRequested) {
            return;
        }
        if (relevantId == null || relevantId.trim().isEmpty()) {
            relevantId = properties.getRelevantId();
        }
        log.info("[数据同步] 命令行触发同步任务, mode={}, relevantId={}", mode, relevantId);
        taskRunner.triggerSync(mode, relevantId);

        if (properties.isExitAfterRun()) {
            int code = SpringApplication.exit(applicationContext);
            System.exit(code);
        }
    }
}
