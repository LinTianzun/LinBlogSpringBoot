package com.linblog.module.media.dto;

/** POST /api/v1/files/upload 成功响应。 */
public record UploadResult(
        Long id,
        String storageKey,
        String url,
        String kind,
        String mimeType,
        Long sizeBytes,
        boolean duplicated) {
}
