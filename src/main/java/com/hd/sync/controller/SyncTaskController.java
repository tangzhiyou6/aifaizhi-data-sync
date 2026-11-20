package com.hd.sync.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hd.sync.model.dto.SyncTaskStatus;
import com.hd.sync.model.dto.SyncTriggerRequest;
import com.hd.sync.task.SyncTaskManager;
import com.hd.sync.task.SyncTaskRunner;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

/**
 * 同步任务触发与状态查询。
 */
@RestController
@RequestMapping("/api/sync/task")
@RequiredArgsConstructor
public class SyncTaskController {

    private final SyncTaskRunner taskRunner;

    private final SyncTaskManager taskManager;

    /**
     * 手动触发一次同步任务（异步执行，立即返回任务状态）。
     */
    @PostMapping("/trigger")
    public Map<String, Object> trigger(@RequestBody(required = false) SyncTriggerRequest request) {
        String mode = request == null || StringUtils.isBlank(request.getMode())
                ? taskRunner.defaultMode() : request.getMode();
        String relevantId = request == null ? null : request.getRelevantId();
        SyncTaskStatus status = taskRunner.triggerAsync(mode, relevantId);
        Map<String, Object> result = new HashMap<>(4);
        result.put("success", true);
        result.put("message", "同步任务已启动");
        result.put("taskId", status.getTaskId());
        result.put("status", status);
        return result;
    }

    /**
     * 查询最近一次同步任务的状态与进度。
     */
    @GetMapping("/status")
    public SyncTaskStatus status() {
        return taskManager.getStatus();
    }
}
