package com.linblog.module.stats.scheduler;

import com.linblog.config.AppProperties;
import com.linblog.module.audit.mapper.RequestAuditMapper;
import com.linblog.module.auth.mapper.RefreshTokenMapper;
import com.linblog.module.content.service.ContentService;
import com.linblog.module.media.entity.FileAttachment;
import com.linblog.module.media.entity.Media;
import com.linblog.module.media.mapper.FileAttachmentMapper;
import com.linblog.module.media.mapper.MediaMapper;
import com.linblog.module.media.service.StorageDriver;
import com.linblog.module.stats.mapper.ViewLogMapper;
import com.linblog.support.TestAppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** §7.9 定时清理：六段顺序执行、互不阻断、trash 对象+行一起删。 */
class CleanupSchedulerTest {

    private RequestAuditMapper requestAuditMapper;
    private ViewLogMapper viewLogMapper;
    private RefreshTokenMapper refreshTokenMapper;
    private ContentService contentService;
    private MediaMapper mediaMapper;
    private FileAttachmentMapper fileAttachmentMapper;
    private StorageDriver storageDriver;
    private CleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        requestAuditMapper = mock(RequestAuditMapper.class);
        viewLogMapper = mock(ViewLogMapper.class);
        refreshTokenMapper = mock(RefreshTokenMapper.class);
        contentService = mock(ContentService.class);
        mediaMapper = mock(MediaMapper.class);
        fileAttachmentMapper = mock(FileAttachmentMapper.class);
        storageDriver = mock(StorageDriver.class);
        AppProperties props = TestAppProperties.defaults();
        scheduler = new CleanupScheduler(requestAuditMapper, viewLogMapper, refreshTokenMapper,
                contentService, mediaMapper, fileAttachmentMapper, storageDriver, props);
    }

    @Test
    void runsAllSixSegments() {
        scheduler.cleanup();
        verify(requestAuditMapper).deleteOlderThan(any(LocalDateTime.class));
        verify(viewLogMapper).delete(any());
        verify(refreshTokenMapper).deleteExpiredBefore(any(LocalDateTime.class));
        verify(contentService).purgeExpired(any(LocalDateTime.class));
        verify(mediaMapper).selectList(any());
        verify(fileAttachmentMapper).selectList(any());
    }

    @Test
    void purgesExpiredTrashMediaAndFiles() throws Exception {
        Media media = new Media();
        media.setId(3L);
        media.setStorageKey("2026/01/a.png");
        media.setThumbKey("t320/2026/01/a.png");
        when(mediaMapper.selectList(any())).thenReturn(List.of(media));

        FileAttachment file = new FileAttachment();
        file.setId(4L);
        file.setStorageKey("2026/01/doc.pdf");
        when(fileAttachmentMapper.selectList(any())).thenReturn(List.of(file));

        scheduler.cleanup();

        verify(storageDriver).remove("2026/01/a.png");
        verify(storageDriver).remove("t320/2026/01/a.png");
        verify(mediaMapper).deleteById(3L);
        verify(storageDriver).remove("2026/01/doc.pdf");
        verify(fileAttachmentMapper).deleteById(4L);
    }

    @Test
    void failureInOneSegmentDoesNotStopOthers() throws Exception {
        doThrow(new RuntimeException("db down")).when(requestAuditMapper).deleteOlderThan(any());
        when(mediaMapper.selectList(any())).thenThrow(new RuntimeException("boom"));

        scheduler.cleanup();

        verify(refreshTokenMapper).deleteExpiredBefore(any(LocalDateTime.class));
        verify(contentService).purgeExpired(any(LocalDateTime.class));
        verify(fileAttachmentMapper).selectList(any());
        verify(storageDriver, never()).remove(any());
    }

    @Test
    void nullThumbKeySkippedQuietly() throws Exception {
        Media media = new Media();
        media.setId(5L);
        media.setStorageKey("2026/02/b.png");
        media.setThumbKey(null);
        when(mediaMapper.selectList(any())).thenReturn(List.of(media));

        scheduler.cleanup();

        verify(storageDriver).remove("2026/02/b.png");
        verify(mediaMapper).deleteById(5L);
    }

    @Test
    void viewLogRetentionIsSevenDaysConstant() {
        assertEquals(7, CleanupScheduler.VIEW_LOG_RETENTION_DAYS);
    }

    @Test
    void auditRetentionFromProperties() {
        // defaults() auditRetentionDays=90：cutoff ≈ now-90d
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        scheduler.cleanup();
        verify(requestAuditMapper).deleteOlderThan(captor.capture());
        assertTrue(captor.getValue().isBefore(LocalDateTime.now().minusDays(89)));
    }
}
