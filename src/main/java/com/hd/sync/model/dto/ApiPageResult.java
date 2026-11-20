package com.hd.sync.model.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.hd.sync.model.SourceFileDTO;

import lombok.Data;

/**
 * 方案一服务端分页 API 的返回体。
 */
@Data
public class ApiPageResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前页码，从 1 开始 */
    private int page;

    /** 本页条数上限 */
    private int size;

    /** 是否还有下一页 */
    private boolean hasNext;

    /** 本页数据 */
    private List<SourceFileDTO> records = new ArrayList<>();

    public static ApiPageResult of(int page, int size, boolean hasNext, List<SourceFileDTO> records) {
        ApiPageResult result = new ApiPageResult();
        result.setPage(page);
        result.setSize(size);
        result.setHasNext(hasNext);
        result.setRecords(records);
        return result;
    }
}
