package com.linblog.module.stats.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linblog.config.AppProperties;
import com.linblog.module.audit.mapper.RequestAuditMapper;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.auth.mapper.RefreshTokenMapper;
import com.linblog.module.content.service.ContentService;
import com.linblog.module.media.entity.FileAttachment;
import com.linblog.module.media.entity.Media;
import com.linblog.module.media.mapper.FileAttachmentMapper;
import com.linblog.module.media.mapper.MediaMapper;
import com.linblog.module.media.service.FileAdminService;
import com.linblog.module.media.service.StorageDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时清理（§7.9，每日 04:00）：request_audits(90d/AUDIT_RETENTION_DAYS)、
 * view_logs(VIEW_RETENTION_DAYS，默认 7d)，过期 refresh_tokens，
 * 回收站 content 30d 物理删除，trash 媒体/附件删对象+行。
 * 各段独立 try/catch，一段失败不影响其余。
 */
@Component
public class CleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(CleanupScheduler.class);

    private final RequestAuditMapper requestAuditMapper;
    private final com.linblog.module.stats.mapper.ViewLogMapper viewLogMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final ContentService contentService;
    private final MediaMapper mediaMapper;
    private final FileAttachmentMapper fileAttachmentMapper;
    private final StorageDriver storageDriver;
    private final SystemLogService systemLogService;
    private final AppProperties props;

    public CleanupScheduler(RequestAuditMapper requestAuditMapper,
                            com.linblog.module.stats.mapper.ViewLogMapper viewLogMapper,
                            RefreshTokenMapper refreshTokenMapper, ContentService contentService,
                            MediaMapper mediaMapper, FileAttachmentMapper fileAttachmentMapper,
                            StorageDriver storageDriver, SystemLogService systemLogService,
                            AppProperties props) {
        this.requestAuditMapper = requestAuditMapper;
        this.viewLogMapper = viewLogMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.contentService = contentService;
        this.mediaMapper = mediaMapper;
        this.fileAttachmentMapper = fileAttachmentMapper;
        this.storageDriver = storageDriver;
        this.systemLogService = systemLogService;
        this.props = props;
    }

    @Scheduled(cron = "0 0 4 * * *")
    public void cleanup() {
        LocalDateTime now = LocalDateTime.now();
        cleanupRequestAudits(now);
        cleanupViewLogs(now);
        cleanupRefreshTokens(now);
        cleanupTrashedContents(now);
        cleanupTrashedMedia(now);
        cleanupTrashedFiles(now);
        checkBackupQuota();
    }

    void cleanupRequestAudits(LocalDateTime now) {
        try {
            LocalDateTime cutoff = now.minusDays(props.auditRetentionDays());
            int n = requestAuditMapper.deleteOlderThan(cutoff);
            if (n > 0) {
                log.info("cleanup request_audits deleted={}", n);
            }
        } catch (RuntimeException e) {
            log.warn("cleanup request_audits failed: {}", e.getMessage());
        }
    }

    void cleanupViewLogs(LocalDateTime now) {
        try {
            LocalDateTime cutoff = now.minusDays(props.viewRetentionDays());
            int n = viewLogMapper.deleteOlderThan(cutoff);
            if (n > 0) {
                log.info("cleanup view_logs deleted={}", n);
            }
        } catch (RuntimeException e) {
            log.warn("cleanup view_logs failed: {}", e.getMessage());
        }
    }

    void cleanupRefreshTokens(LocalDateTime now) {
        try {
            int n = refreshTokenMapper.deleteExpiredBefore(now);
            if (n > 0) {
                log.info("cleanup refresh_tokens deleted={}", n);
            }
        } catch (RuntimeException e) {
            log.warn("cleanup refresh_tokens failed: {}", e.getMessage());
        }
    }

    void cleanupTrashedContents(LocalDateTime now) {
        try {
            LocalDateTime cutoff = now.minusDays(props.trashRetentionDays());
            int n = contentService.purgeExpired(cutoff);
            if (n > 0) {
                log.info("cleanup trashed contents purged={}", n);
            }
        } catch (RuntimeException e) {
            log.warn("cleanup trashed contents failed: {}", e.getMessage());
        }
    }

    void cleanupTrashedMedia(LocalDateTime now) {
        try {
            LocalDateTime cutoff = now.minusDays(props.trashRetentionDays());
            List<Media> expired = mediaMapper.selectList(new LambdaQueryWrapper<Media>()
                    .eq(Media::getState, "trash")
                    .lt(Media::getTrashedAt, cutoff));
            for (Media media : expired) {
                removeObjectQuietly(media.getStorageKey());
                removeObjectQuietly(media.getThumbKey());
                mediaMapper.deleteById(media.getId());
            }
            if (!expired.isEmpty()) {
                log.info("cleanup trashed media purged={}", expired.size());
            }
        } catch (RuntimeException e) {
            log.warn("cleanup trashed media failed: {}", e.getMessage());
        }
    }

    void cleanupTrashedFiles(LocalDateTime now) {
        try {
            LocalDateTime cutoff = now.minusDays(props.trashRetentionDays());
            List<FileAttachment> expired = fileAttachmentMapper.selectList(new LambdaQueryWrapper<FileAttachment>()
                    .eq(FileAttachment::getState, "trash")
                    .lt(FileAttachment::getTrashedAt, cutoff));
            for (FileAttachment file : expired) {
                removeObjectQuietly(file.getStorageKey());
                fileAttachmentMapper.deleteById(file.getId());
            }
            if (!expired.isEmpty()) {
                log.info("cleanup trashed files purged={}", expired.size());
            }
        } catch (RuntimeException e) {
            log.warn("cleanup trashed files failed: {}", e.getMessage());
        }
    }

    /** 备份区 2GiB 检查告警（§7.9）：trash 用量 ≥80% → warn + system_log。 */
    void checkBackupQuota() {
        try {
            long backupBytes = fileAttachmentMapper.selectStateSummary().stream()
                    .filter(row -> "trash".equals(String.valueOf(row.get("state"))))
                    .mapToLong(row -> ((Number) row.get("total_bytes")).longValue())
                    .sum();
            if (backupBytes >= FileAdminService.QUOTA_BYTES * FileAdminService.ALERT_RATIO) {
                log.warn("backup area usage={} bytes >= 80% of 2GiB quota", backupBytes);
                systemLogService.record(null, "file", "backup_quota_alert", "files", null,
                        "backupBytes=" + backupBytes, null);
            }
        } catch (RuntimeException e) {
            log.warn("backup quota check failed: {}", e.getMessage());
        }
    }

    private void removeObjectQuietly(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            storageDriver.remove(key);
        } catch (Exception e) {
            log.warn("storage remove failed key={}: {}", key, e.getMessage());
        }
    }
}
