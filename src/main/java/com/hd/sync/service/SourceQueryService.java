package com.hd.sync.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.hd.sync.common.SqlIdentifierValidator;
import com.hd.sync.config.DataSyncProperties;
import com.hd.sync.datasource.DataSourceNames;
import com.hd.sync.mapper.SourceFileMapper;
import com.hd.sync.model.SourceFileDTO;
import com.hd.sync.model.dto.ApiPageResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 源端（jm_cloud）查询服务。
 *
 * <p>类上的 {@code @DS("jm_cloud")} 保证本服务内所有查询都走源端数据源。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@DS(DataSourceNames.JM_CLOUD)
public class SourceQueryService {

    private final SourceFileMapper sourceFileMapper;

    private final DataSyncProperties properties;

    /**
     * offset 分页查询，供方案一的服务端分页 API 使用。
     *
     * @param relevantId 关联的 relevant_id，为空表示查全部
     * @param page       页码，从 1 开始
     * @param size       每页条数，超出配置批次上限时按上限截断
     */
    public ApiPageResult queryByPage(String relevantId, int page, int size) {
        int limit = Math.max(1, Math.min(size, properties.getBatchSize()));
        int current = Math.max(1, page);
        long offset = (long) (current - 1) * limit;
        // 多取一条用于判断是否还有下一页
        List<SourceFileDTO> rows = doQuery(relevantId, null, limit + 1, offset);
        boolean hasNext = rows.size() > limit;
        if (hasNext) {
            rows = rows.subList(0, limit);
        }
        return ApiPageResult.of(current, limit, hasNext, rows);
    }

    /**
     * 游标分页查询，供方案二直连同步使用，避免大偏移量下的深分页问题。
     *
     * @param relevantId 关联的 relevant_id，为空表示查全部
     * @param cursor     游标，取 file_id 大于该值的记录，为空表示从头开始
     * @param limit      取多少条
     */
    public List<SourceFileDTO> queryByCursor(String relevantId, String cursor, int limit) {
        return doQuery(relevantId, cursor, limit, null);
    }

    private List<SourceFileDTO> doQuery(String relevantId, String cursor, int limit, Long offset) {
        DataSyncProperties.Source source = properties.getSource();
        String schema = SqlIdentifierValidator.validate(source.getSchema(), "source.schema");
        String stgTable = SqlIdentifierValidator.validate(source.getTable(), "source.table");
        String fileSchema = SqlIdentifierValidator.validate(source.getFileSchema(), "source.file-schema");
        String fileTable = SqlIdentifierValidator.validate(source.getFileTable(), "source.file-table");
        return sourceFileMapper.selectSourceFiles(schema, stgTable, fileSchema, fileTable,
                relevantId, cursor, limit, offset);
    }
}
