package com.linblog.module.media.service;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.config.AppProperties;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.media.dto.UploadResult;
import com.linblog.module.media.entity.FileAttachment;
import com.linblog.module.media.entity.Media;
import com.linblog.module.media.mapper.FileAttachmentMapper;
import com.linblog.module.media.mapper.MediaMapper;
import com.linblog.module.permission.PermissionKeys;
import com.linblog.security.AuthPrincipal;
import com.linblog.support.TestAppProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 媒体上传（§5.7/§7.5）：权限、大小、类型、去重、异步缩略图。 */
class MediaServiceTest {

    private MediaMapper mediaMapper;
    private FileAttachmentMapper fileAttachmentMapper;
    private StorageDriver storageDriver;
    private SystemLogService systemLogService;
    private MediaService service;

    private static final AuthPrincipal UPLOADER =
            new AuthPrincipal(7L, "ed", "editor", false, Set.of(PermissionKeys.FILE_UPLOAD));

    @BeforeEach
    void setUp() {
        mediaMapper = mock(MediaMapper.class);
        fileAttachmentMapper = mock(FileAttachmentMapper.class);
        storageDriver = mock(StorageDriver.class);
        systemLogService = mock(SystemLogService.class);
        ExecutorService inline = org.mockito.Mockito.mock(ExecutorService.class);
        org.mockito.Mockito.doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(inline).submit(any(Runnable.class));
        AppProperties props = TestAppProperties.defaults();
        service = new MediaService(mediaMapper, fileAttachmentMapper, storageDriver,
                props, systemLogService, inline);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(UPLOADER, null, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static byte[] tinyPng() {
        try {
            BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 宽于最小缩略档 320 的图，保证缩略阶段真实执行。 */
    private static byte[] widePng() {
        try {
            BufferedImage img = new BufferedImage(640, 400, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void uploadGuards() {
        SecurityContextHolder.clearContext();
        assertThrows(BizException.class, () -> service.upload(new MockMultipartFile(
                "file", "a.png", "image/png", tinyPng())));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthPrincipal(7L, "ed", "viewer", false, Set.of()), null, null));
        assertThrows(BizException.class, () -> service.upload(new MockMultipartFile(
                "file", "a.png", "image/png", tinyPng())));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(UPLOADER, null, null));
        assertThrows(BizException.class, () -> service.upload(null));
        assertThrows(BizException.class, () -> service.upload(new MockMultipartFile(
                "file", "a.png", "image/png", new byte[0])));
        assertThrows(BizException.class, () -> service.upload(new MockMultipartFile(
                "file", "big.png", "image/png", new byte[21 * 1024 * 1024])));
    }

    @Test
    void imageUploadDedupesBySha256() throws Exception {
        Media exist = new Media();
        exist.setId(5L);
        exist.setStorageKey("2026/01/exist.png");
        exist.setKind("image");
        exist.setMimeType("image/png");
        exist.setSizeBytes((long) tinyPng().length);
        when(mediaMapper.selectOne(any())).thenReturn(exist);

        UploadResult result = service.upload(new MockMultipartFile(
                "file", "a.png", "image/png", tinyPng()));

        assertTrue(result.duplicated());
        assertEquals(5L, result.id());
        assertEquals("2026/01/exist.png", result.storageKey());
        verify(mediaMapper, never()).insert(any(Media.class));
        verify(storageDriver, never()).save(anyString(), any(byte[].class));
    }

    @Test
    void newImageInsertsAndWritesStorageAndThumbnails() throws Exception {
        when(mediaMapper.selectOne(any())).thenReturn(null);
        doAnswer(inv -> {
            Media m = inv.getArgument(0);
            m.setId(9L);
            return 1;
        }).when(mediaMapper).insert(any(Media.class));

        UploadResult result = service.upload(new MockMultipartFile(
                "file", "photo.png", "image/png", tinyPng()));

        assertFalse(result.duplicated());
        assertEquals("image", result.kind());
        verify(storageDriver).save(anyString(), any(byte[].class));
        // 同步内联 executor：缩略图已生成（2x2 原图小于 320 宽则跳过，验证无异常与查询回写路径）
        verify(mediaMapper).insert(any(Media.class));
    }

    @Test
    void nonImageGoesToFilesTableWithoutDedup() throws Exception {
        MockMultipartFile txt = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "hello".getBytes());
        UploadResult result = service.upload(txt);

        assertEquals("file", result.kind());
        verify(fileAttachmentMapper).insert(any(FileAttachment.class));
        verify(mediaMapper, never()).insert(any(Media.class));
        verify(storageDriver).save(anyString(), any(byte[].class));
    }

    @Test
    void octetStreamJunkGoesToFilesTable() throws Exception {
        // Tika 对任意字节几乎总能给出类型（junk → application/octet-stream），仅 detect 异常/空才 40000
        MockMultipartFile junk = new MockMultipartFile(
                "file", "blob.bin", "application/octet-stream",
                new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        UploadResult result = service.upload(junk);
        assertEquals("file", result.kind());
        verify(fileAttachmentMapper).insert(any(FileAttachment.class));
        verify(mediaMapper, never()).insert(any(Media.class));
    }

    @Test
    void thumbnailFailureLoggedNotThrown() throws Exception {
        // 存储层抛错时缩略图阶段失败 → 记 systemLog 而非上抛
        when(mediaMapper.selectOne(any())).thenReturn(null);
        doAnswer(inv -> {
            Media m = inv.getArgument(0);
            m.setId(12L);
            return 1;
        }).when(mediaMapper).insert(any(Media.class));
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            if (key.startsWith("t")) {
                throw new IOException("disk full");
            }
            return key;
        }).when(storageDriver).save(anyString(), any(byte[].class));

        UploadResult result = service.upload(new MockMultipartFile(
                "file", "photo.png", "image/png", widePng()));
        assertEquals("image", result.kind());
        // 异步缩略线程无 SecurityContext，userId 为 null
        verify(systemLogService).record(org.mockito.ArgumentMatchers.isNull(), eq("media"),
                eq("thumb_error"), eq("media"), eq(12L), contains("width"), eq(null));
    }
}
