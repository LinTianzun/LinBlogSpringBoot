package com.linblog.module.audit.dto;

import java.time.LocalDateTime;

/** 系统日志列表项（§5.8 GET /admin/system-logs）。 */
public record SystemLogView(Long id, Long userId, String userName, String module, String action,
                            String targetType, Long targetId, String detail, String ip,
                            LocalDateTime createdAt) {
}
