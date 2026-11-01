package com.linblog.module.stats.dto;

/** POST /api/v1/views 请求体（§5.6/§7.6）。 */
public record ViewReportRequest(
        String targetType,
        Long targetId) {
}
