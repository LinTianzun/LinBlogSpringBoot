package com.linblog.module.content.dto;

/** PATCH /api/v1/admin/subsets/{id} 请求体：null 字段不更新。 */
public record SubsetUpdateRequest(
        String name,
        String description,
        Long coverMediaId) {
}
