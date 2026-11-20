package com.hd.sync.config;

import java.time.Duration;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 方案一客户端使用的 HTTP 模板。
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestTemplate sourceApiRestTemplate(RestTemplateBuilder builder, DataSyncProperties properties) {
        DataSyncProperties.Api api = properties.getApi();
        return builder
                .setConnectTimeout(Duration.ofMillis(api.getConnectTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(api.getReadTimeoutMs()))
                .build();
    }
}
