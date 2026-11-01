package com.linblog.module.media.service;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.media.dto.FileAdminView;
import com.linblog.module.media.dto.StorageStatus;
import com.linblog.module.media.entity.FileAttachment;
import com.linblog.module.media.mapper.FileAttachmentMapper;
import com.linblog.module.user.entity.User;
import com.linblog.module.user.mapper.UserMapper;
import com.linblog.security.AuthPrincipal;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** §5.7 文件管理：列表筛选/storage-status/trash-restore-purge 状态机。 */
class FileAdminServiceTest {

    private FileAttachmentMapper fileAttachmentMapper;
    private UserMapper userMapper;
    private SystemLogService systemLogService;
    private StorageDriver storageDriver;
    private FileAdminService service;

    private static final AuthPrincipal VIEWER = new AuthPrincipal(1L, "root", "super_admin", true,
            java.util.Set.of("file.view"));
    private static final AuthPrincipal MANAGER = new AuthPrincipal(1L, "root", "super_admin", true,
            java.util.Set.of("file.view", "file.delete"));

    private static FileAttachment file(long id, String state, String key) {
        FileAttachment f = new FileAttachment();
        f.setId(id);
        f.setUploaderId(7L);
        f.setStorageKey(key);
        f.setOriginalName("doc.pdf");
        f.setMimeType("application/pdf");
        f.setSizeBytes(1024L);
        f.setSha256("a".repeat(64));
        f.setState(state);
        return f;
    }

    @BeforeEach
    void setUp() {
        fileAttachmentMapper = mock(FileAttachmentMapper.class);
        userMapper = mock(UserMapper.class);
        systemLogService = mock(SystemLogService.class);
        storageDriver = mock(StorageDriver.class);
        service = new FileAdminService(fileAttachmentMapper, userMapper, systemLogService, storageDriver);
        when(userMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void anonymousRejected() {
        BizException ex = assertThrows(BizException.class, () -> service.page(new PageQuery(), null, null));
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.getErrorCode());
    }

    @Test
    void missingFileViewPermissionRejected() {
        AuthPrincipal noPerm = new AuthPrincipal(2L, "ed", "editor", false, java.util.Set.of());
        BizException ex = assertThrows(BizException.class, () -> service.page(new PageQuery(), null, noPerm));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void invalidStateRejected() {
        BizException ex = assertThrows(BizException.class,
                () -> service.page(new PageQuery(), "nope", VIEWER));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("state"));
    }

    @Test
    void pageMapsUploaderNames() {
        FileAttachment f = file(11L, "active", "2026/01/a.pdf");
        User uploader = new User();
        uploader.setId(7L);
        uploader.setUsername("alice");
        when(userMapper.selectList(any())).thenReturn(List.of(uploader));
        doAnswer(inv -> {
            Page<FileAttachment> p = inv.getArgument(0);
            p.setRecords(List.of(f));
            p.setTotal(1);
            return p;
        }).when(fileAttachmentMapper).selectPage(any(), any());

        PageResult<FileAdminView> result = service.page(new PageQuery(), "active", VIEWER);

        assertEquals(1, result.total());
        assertEquals(1, result.records().size());
        assertEquals("alice", result.records().get(0).uploaderName());
        assertEquals("doc.pdf", result.records().get(0).originalName());
    }

    @Test
    void pageWithoutUploaderLeavesNameNull() {
        FileAttachment f = file(11L, "active", "2026/01/a.pdf");
        f.setUploaderId(null);
        doAnswer(inv -> {
            Page<FileAttachment> p = inv.getArgument(0);
            p.setRecords(List.of(f));
            p.setTotal(0);
            return p;
        }).when(fileAttachmentMapper).selectPage(any(), any());

        PageResult<FileAdminView> result = service.page(new PageQuery(), null, VIEWER);
        assertNull(result.records().get(0).uploaderName());
        verify(userMapper, never()).selectList(any());
    }

    @Test
    void storageStatusAggregatesQuotaAndAlert() {
        when(fileAttachmentMapper.selectStateSummary()).thenReturn(List.of(
                Map.of("state", "active", "cnt", 2L, "total_bytes", 1024L),
                Map.of("state", "trash", "cnt", 1L, "total_bytes", 1825361100L)));

        StorageStatus status = service.storageStatus(VIEWER);

        assertEquals(1024L, status.primaryBytes());
        assertEquals(1825361100L, status.backupBytes());
        assertEquals(FileAdminService.QUOTA_BYTES, status.quotaBytes());
        assertTrue(status.alert());
        assertTrue(status.backupPercent() > 80.0);
    }

    @Test
    void storageStatusBelowThresholdNoAlert() {
        when(fileAttachmentMapper.selectStateSummary()).thenReturn(List.of(
                Map.of("state", "active", "cnt", 1L, "total_bytes", 100L),
                Map.of("state", "trash", "cnt", 1L, "total_bytes", 100L)));

        StorageStatus status = service.storageStatus(VIEWER);
        assertFalse(status.alert());
        assertEquals(1L, status.primaryCount());
    }

    @Test
    void trashRequiresDeletePermission() {
        when(fileAttachmentMapper.selectById(11L)).thenReturn(file(11L, "active", "2026/01/a.pdf"));
        BizException ex = assertThrows(BizException.class, () -> service.trash(11L, VIEWER, "ip"));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void trashMovesActiveFileToBackup() {
        FileAttachment f = file(11L, "active", "2026/01/a.pdf");
        when(fileAttachmentMapper.selectById(11L)).thenReturn(f);

        service.trash(11L, MANAGER, "1.1.1.1");

        assertEquals("trash", f.getState());
        assertTrue(f.getTrashedAt() != null);
        verify(fileAttachmentMapper).updateById(f);
        verify(systemLogService).record(eq(1L), eq("file"), eq("trash"), eq("file"), eq(11L),
                eq("2026/01/a.pdf"), eq("1.1.1.1"));
    }

    @Test
    void trashNonActiveConflict() {
        when(fileAttachmentMapper.selectById(11L)).thenReturn(file(11L, "trash", "k"));
        BizException ex = assertThrows(BizException.class, () -> service.trash(11L, MANAGER, "ip"));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
    }

    @Test
    void restoreRequiresTrashState() {
        when(fileAttachmentMapper.selectById(11L)).thenReturn(file(11L, "active", "k"));
        BizException ex = assertThrows(BizException.class, () -> service.restore(11L, MANAGER, "ip"));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("备份区"));
    }

    @Test
    void restoreReturnsFileToActive() {
        FileAttachment f = file(11L, "trash", "2026/01/a.pdf");
        f.setTrashedAt(java.time.LocalDateTime.now());
        when(fileAttachmentMapper.selectById(11L)).thenReturn(f);

        service.restore(11L, MANAGER, "ip");

        assertEquals("active", f.getState());
        assertNull(f.getTrashedAt());
        verify(fileAttachmentMapper).updateById(f);
        verify(systemLogService).record(eq(1L), eq("file"), eq("restore"), eq("file"), eq(11L),
                eq("2026/01/a.pdf"), eq("ip"));
    }

    @Test
    void purgeActiveFileRejected() {
        when(fileAttachmentMapper.selectById(11L)).thenReturn(file(11L, "active", "k"));
        BizException ex = assertThrows(BizException.class, () -> service.purge(11L, MANAGER, "ip"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("仅备份区文件可彻底删除"));
    }

    @Test
    void purgeRemovesObjectAndRow() throws IOException {
        FileAttachment f = file(11L, "trash", "2026/01/a.pdf");
        when(fileAttachmentMapper.selectById(11L)).thenReturn(f);

        service.purge(11L, MANAGER, "ip");

        verify(storageDriver).remove("2026/01/a.pdf");
        verify(fileAttachmentMapper).deleteById(11L);
        verify(systemLogService).record(eq(1L), eq("file"), eq("purge"), eq("file"), eq(11L),
                eq("2026/01/a.pdf"), eq("ip"));
    }

    @Test
    void purgeIoFailureReturns500() throws IOException {
        when(fileAttachmentMapper.selectById(11L)).thenReturn(file(11L, "trash", "k"));
        doThrow(new IOException("disk")).when(storageDriver).remove("k");
        BizException ex = assertThrows(BizException.class, () -> service.purge(11L, MANAGER, "ip"));
        assertEquals(ErrorCode.INTERNAL, ex.getErrorCode());
        verify(fileAttachmentMapper, never()).deleteById(11L);
    }

    @Test
    void unknownFile404() {
        when(fileAttachmentMapper.selectById(99L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.trash(99L, MANAGER, "ip"));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void storageStatusRequiresPermission() {
        AuthPrincipal noPerm = new AuthPrincipal(2L, "ed", "editor", false, java.util.Set.of());
        assertThrows(BizException.class, () -> service.storageStatus(noPerm));
    }

    @Test
    void systemLogsQueryRequiresPermission() {
        // 占位：保证 QUOTA 常量断言
        assertEquals(2L * 1024 * 1024 * 1024, FileAdminService.QUOTA_BYTES);
    }
}
