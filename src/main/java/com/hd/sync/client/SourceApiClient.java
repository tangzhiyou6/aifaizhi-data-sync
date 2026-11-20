package com.hd.sync.client;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.hd.sync.config.DataSyncProperties;
import com.hd.sync.model.dto.ApiPageResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * 方案一的 API 客户端：按页消费源端提供的分页接口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SourceApiClient {

    private final RestTemplate sourceApiRestTemplate;

    private final DataSyncProperties properties;

    /**
     * 拉取一页源端数据。
     *
     * @param relevantId 关联的 relevant_id，为空表示查全部
     * @param page       页码，从 1 开始
     * @param size       每页条数
     */
    public ApiPageResult fetchPage(String relevantId, int page, int size) {
        String baseUrl = properties.getApi().getBaseUrl();
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/api/sync/files")
                .queryParam("page", page)
                .queryParam("size", size);
        if (StringUtils.isNotBlank(relevantId)) {
            builder.queryParam("relevantId", relevantId);
        }
        String url = builder.build().encode().toUriString();

        ApiPageResult result = sourceApiRestTemplate.getForObject(url, ApiPageResult.class);
        if (result == null) {
            throw new IllegalStateException("调用源端分页 API 返回空结果, url=" + url);
        }
        return result;
    }
}
