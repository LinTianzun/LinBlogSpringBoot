package com.linblog.module.media.controller;

import com.linblog.common.ApiResult;
import com.linblog.module.media.dto.UploadResult;
import com.linblog.module.media.service.MediaReadService;
import com.linblog.module.media.service.MediaService;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 媒体（§5.7）：POST /api/v1/files/upload（file.upload）；
 * GET /api/v1/media/{storageKey} 公开，?w∈THUMB_WIDTHS，immutable 缓存 1 年。
 */
@RestController
public class MediaController {

    private static final String SERVE_PREFIX = "/api/v1/media/";

    private final MediaService mediaService;
    private final MediaReadService mediaReadService;

    public MediaController(MediaService mediaService, MediaReadService mediaReadService) {
        this.mediaService = mediaService;
        this.mediaReadService = mediaReadService;
    }

    @PostMapping("/api/v1/files/upload")
    public ApiResult<UploadResult> upload(@RequestParam("file") MultipartFile file) {
        return ApiResult.ok(mediaService.upload(file));
    }

    @GetMapping(SERVE_PREFIX + "**")
    public ResponseEntity<Resource> serve(HttpServletRequest request,
                                          @RequestParam(required = false) Integer width) {
        String key = storageKeyOf(request);
        MediaReadService.MediaPayload payload = mediaReadService.load(key, width);
        return ResponseEntity.ok()
                .contentType(payload.mediaType())
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePublic().immutable())
                .body(payload.resource());
    }

    /** 从通配路径提取 storageKey（URL 解码；拒绝路径穿越片段）。 */
    private String storageKeyOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        int idx = uri.indexOf(SERVE_PREFIX);
        String raw = idx >= 0 ? uri.substring(idx + SERVE_PREFIX.length()) : "";
        String key = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        if (key.isBlank() || key.contains("..") || key.startsWith("/")) {
            throw new com.linblog.common.BizException(com.linblog.common.ErrorCode.NOT_FOUND, "媒体不存在");
        }
        return key;
    }
}
