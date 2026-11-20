package com.hd.sync.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.hd.sync.model.SourceFileDTO;

/**
 * 源端（jm_cloud）查询 Mapper。
 *
 * <p>由 {@code @DS("jm_cloud")} 的 Service 调用，SQL 中的库表名来自配置文件，
 * 调用前会经过 {@code SqlIdentifierValidator} 校验，避免非法标识符拼接。</p>
 */
@Mapper
public interface SourceFileMapper {

    /**
     * 分页查询待同步的附件记录。
     *
     * <p>以 file_id 去重（GROUP BY），保证同一 file_id 只出现一次，游标分页不会漏数据；
     * LEFT JOIN 兼容 con_file_upload 中没有对应记录的情况。</p>
     *
     * @param relevantId 关联的 relevant_id，为空表示不按 relevant_id 过滤
     * @param cursor     游标，取 file_id 大于该值的记录，为空表示从头开始
     * @param limit      取多少条
     * @param offset     偏移量，游标分页时传 null
     */
    @Select("<script>"
            + "SELECT CAST(p.file_id AS VARCHAR) AS file_id, "
            + "       MIN(CAST(p.relevant_id AS VARCHAR)) AS relevant_id, "
            + "       MIN(c.file_url) AS file_url, "
            + "       MIN(c.bucket) AS bucket "
            + "FROM ${schema}.${stgTable} p "
            + "LEFT JOIN ${fileSchema}.${fileTable} c "
            + "       ON CAST(c.id AS VARCHAR) = CAST(p.file_id AS VARCHAR) "
            + "WHERE p.file_id IS NOT NULL AND CAST(p.file_id AS VARCHAR) &lt;&gt; '' "
            + "<if test=\"relevantId != null and relevantId != ''\">"
            + "  AND CAST(p.relevant_id AS VARCHAR) = #{relevantId} "
            + "</if>"
            + "<if test=\"cursor != null and cursor != ''\">"
            + "  AND CAST(p.file_id AS VARCHAR) &gt; #{cursor} "
            + "</if>"
            + "GROUP BY CAST(p.file_id AS VARCHAR) "
            + "ORDER BY file_id "
            + "LIMIT #{limit} "
            + "<if test=\"offset != null\">OFFSET #{offset}</if>"
            + "</script>")
    List<SourceFileDTO> selectSourceFiles(@Param("schema") String schema,
                                           @Param("stgTable") String stgTable,
                                           @Param("fileSchema") String fileSchema,
                                           @Param("fileTable") String fileTable,
                                           @Param("relevantId") String relevantId,
                                           @Param("cursor") String cursor,
                                           @Param("limit") int limit,
                                           @Param("offset") Long offset);
}
