package com.linblog.module.media.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linblog.common.GlobalExceptionHandler;
import com.linblog.module.media.dto.UploadResult;
import com.linblog.module.media.service.MediaReadService;
import com.linblog.module.media.service.MediaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 媒体 Web 层：上传壳结构、公开 serving 缓存头、路径穿越 404。 */
class MediaControllerTest {

    private MediaService mediaService;
    private MediaReadService mediaReadService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mediaService = Mockito.mock(MediaService.class);
        mediaReadService = Mockito.mock(MediaReadService.class);
        MediaController controller = new MediaController(mediaService, mediaReadService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void uploadReturnsShell() throws Exception {
        when(mediaService.upload(any()))
                .thenReturn(new UploadResult(1L, "2026/01/a.png",
                        "/api/v1/media/2026/01/a.png", "image", "image/png", 10L, false));

        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png",
                "png".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/files/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.storageKey").value("2026/01/a.png"))
                .andExpect(jsonPath("$.data.duplicated").value(false));
    }

    @Test
    void serveReturnsImmutableCache() throws Exception {
        byte[] bytes = "webp".getBytes(StandardCharsets.UTF_8);
        when(mediaReadService.load(eq("2026/01/a.png"), isNull()))
                .thenReturn(new MediaReadService.MediaPayload(
                        new ByteArrayResource(bytes), MediaType.IMAGE_PNG, "a.png"));

        mockMvc.perform(get("/api/v1/media/2026/01/a.png"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes))
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("immutable")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG));
    }

    @Test
    void traversalKeyMapsTo40400() throws Exception {
        mockMvc.perform(get("/api/v1/media/../secret.png"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void blankKeyMapsTo40400() throws Exception {
        mockMvc.perform(get("/api/v1/media/"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }
}
