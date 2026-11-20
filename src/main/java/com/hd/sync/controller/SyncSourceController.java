package com.hd.sync.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hd.sync.model.dto.ApiPageResult;
import com.hd.sync.service.SourceQueryService;

import lombok.RequiredArgsConstructor;

/**
 * 方案一的服务端分页接口：对外提供源端附件数据的分页查询。
 *
 * <p>客户端按 page/参数逐页消费，接口只关心源端数据，不感知目标库。</p>
 */
@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
public class SyncSourceController {

    private final SourceQueryService sourceQueryService;

    /**
     * 分页查询源端附件数据。
     *
     * @param relevantId 关联的 relevant_id，为空表示查全部
     * @param page       页码，从 1 开始
     * @param size       每页条数，默认 1000
     */
    @GetMapping("/files")
    public ApiPageResult files(@RequestParam(required = false) String relevantId,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "1000") int size) {
        return sourceQueryService.queryByPage(relevantId, page, size);
    }
}
