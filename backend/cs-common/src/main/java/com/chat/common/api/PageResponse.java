package com.chat.common.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * PageResponse - 分页响应包装 (V3.1 性能优化).
 * ----------------------------------------------------------------------------
 * 业务: 列表接口 (会话/消息/用户) 数据量可能很大, 一次拉取会卡 UI.
 *       统一分页响应格式: 列表 + 总数 + 当前页 + 每页大小.
 *
 * 用法:
 *   - 后端: PageResponse.of(list, total, page, size)
 *   - 前端: response.data.items / response.data.total
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {
    private List<T> items;
    private long total;
    private int page;
    private int size;

    public static <T> PageResponse<T> of(List<T> items, long total, int page, int size) {
        return new PageResponse<>(items, total, page, size);
    }

    public int getTotalPages() {
        return size == 0 ? 0 : (int) Math.ceil((double) total / size);
    }
}
