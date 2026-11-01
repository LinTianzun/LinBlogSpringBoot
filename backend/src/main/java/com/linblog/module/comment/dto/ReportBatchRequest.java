package com.linblog.module.comment.dto;

import java.util.List;

/** POST /admin/comment-reports/batch（§5.5）：{ids≤200, action}（对应原 batch 1–200）。 */
public record ReportBatchRequest(List<Long> ids, String action) {
}
