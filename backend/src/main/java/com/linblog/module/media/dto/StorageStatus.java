package com.linblog.module.media.dto;

/**
 * 存储用量汇总（§5.7 GET /admin/files/storage-status）：
 * 主区=active，备份区=trash；配额固定 2GiB，备份区 ≥80% 触发 alert。
 */
public record StorageStatus(long primaryBytes, long primaryCount,
                            long backupBytes, long backupCount,
                            long quotaBytes, double backupPercent, boolean alert) {
}
