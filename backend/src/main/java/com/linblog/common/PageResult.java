package com.linblog.common;

import java.util.List;

/**
 * 分页响应（§4.2）：data = { "records": [...], "total": 123 }。
 */
public record PageResult<T>(List<T> records, long total) {

    public static <T> PageResult<T> of(List<T> records, long total) {
        return new PageResult<>(records == null ? List.of() : records, total);
    }
}
