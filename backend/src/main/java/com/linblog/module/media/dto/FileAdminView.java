package com.linblog.module.media.dto;

import java.time.LocalDateTime;

/** 后台文件列表项（§5.7 GET /admin/files）。 */
public record FileAdminView(Long id, Long uploaderId, String uploaderName, String storageKey,
                            String originalName, String mimeType, Long sizeBytes, String sha256,
                            String state, LocalDateTime trashedAt, LocalDateTime createdAt) {
}
