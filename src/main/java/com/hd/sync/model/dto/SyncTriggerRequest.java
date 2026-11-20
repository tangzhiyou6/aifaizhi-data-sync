package com.hd.sync.model.dto;

import lombok.Data;

/**
 * 手动触发同步任务的请求参数。
 */
@Data
public class SyncTriggerRequest {

    /** 同步模式：db（直连数据库）/ api（消费分页接口），为空取配置默认值 */
    private String mode;

    /** 只同步指定的 relevant_id，为空表示全部 */
    private String relevantId;
}
