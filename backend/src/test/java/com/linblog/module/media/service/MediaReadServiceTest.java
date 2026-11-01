package com.linblog.module.media.service;

import com.linblog.common.BizException;
import com.linblog.config.AppProperties;
import com.linblog.module.media.entity.Media;
import com.linblog.module.media.mapper.MediaMapper;
import com.linblog.support.TestAppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 媒体读取（§5.7 GET /media/{storageKey}）：404/403/缩略回退/Cache 由控制器负责。 */
class MediaReadServiceTest {

    private MediaMapper mediaMapper;
    private StorageDriver storageDriver;
    private MediaReadService service;

    private static Media media(String key, String kind, String state, String mime) {
        Media m = new Media();
        m.setId(1L);
        m.setStorageKey(key);
        m.setKind(kind);
        m.setState(state);
        m.setMimeType(mime);
        return m;
    }

    @BeforeEach
    void setUp() {
        mediaMapper = mock(MediaMapper.class);
        storageDriver = mock(StorageDriver.class);
        AppProperties props = TestAppProperties.defaults();
        service = new MediaReadService(mediaMapper, storageDriver, props);
    }

    @Test
    void missingMediaNotFound() {
        when(mediaMapper.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class, () -> service.load("2026/01/gone.png", null));
    }

    @Test
    void nonImageOrTrashedForbidden() {
        when(mediaMapper.selectOne(any()))
                .thenReturn(media("2026/01/doc.pdf", "file", "active", "application/pdf"));
        assertThrows(BizException.class, () -> service.load("2026/01/doc.pdf", null));

        when(mediaMapper.selectOne(any()))
                .thenReturn(media("2026/01/old.png", "image", "trash", "image/png"));
        assertThrows(BizException.class, () -> service.load("2026/01/old.png", null));
    }

    @Test
    void unsupportedThumbWidthRejected() {
        when(mediaMapper.selectOne(any()))
                .thenReturn(media("2026/01/a.png", "image", "active", "image/png"));
        assertThrows(BizException.class, () -> service.load("2026/01/a.png", 512));
    }

    @Test
    void thumbFallsBackToOriginalWhenMissing() throws IOException {
        Media m = media("2026/01/a.png", "image", "active", "image/png");
        when(mediaMapper.selectOne(any())).thenReturn(m);
        when(storageDriver.exists("t320/2026/01/a.png")).thenReturn(false);
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        when(storageDriver.load("2026/01/a.png")).thenReturn(original);

        MediaReadService.MediaPayload payload = service.load("2026/01/a.png", 320);
        assertEquals("a.png", payload.filename());
        assertEquals("image/png", payload.mediaType().toString());
        assertEquals(original.length, ((ByteArrayResource) payload.resource()).contentLength());
    }

    @Test
    void thumbServedAsWebpWhenPresent() throws IOException {
        Media m = media("2026/01/a.png", "image", "active", "image/png");
        when(mediaMapper.selectOne(any())).thenReturn(m);
        when(storageDriver.exists("t320/2026/01/a.png")).thenReturn(true);
        byte[] webp = "webpbytes".getBytes(StandardCharsets.UTF_8);
        when(storageDriver.load("t320/2026/01/a.png")).thenReturn(webp);

        MediaReadService.MediaPayload payload = service.load("2026/01/a.png", 320);
        assertEquals("image/webp", payload.mediaType().toString());
        assertEquals(webp.length, ((ByteArrayResource) payload.resource()).contentLength());
    }

    @Test
    void originalMissingIsNotFound() throws IOException {
        Media m = media("2026/01/b.png", "image", "active", "image/png");
        when(mediaMapper.selectOne(any())).thenReturn(m);
        when(storageDriver.exists("t640/2026/01/b.png")).thenReturn(false);
        when(storageDriver.load("2026/01/b.png")).thenReturn(null);
        assertThrows(BizException.class, () -> service.load("2026/01/b.png", 640));
    }

    @Test
    void expiredTrashedMediaReturnsList() {
        when(mediaMapper.selectList(any())).thenReturn(List.of(
                media("x", "image", "trash", "image/png")));
        assertEquals(1, service.expiredTrashedMedia(java.time.LocalDateTime.now()).size());
    }
}
