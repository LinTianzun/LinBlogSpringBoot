package com.linblog.module.seo.controller;

import com.linblog.module.seo.service.SeoService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * SEO 端点（§5.8，M7）：公开匿名（SecurityConfig PUBLIC_PATHS）。
 * 双路径：根 /sitemap.xml、/robots.txt（Caddyfile @backend 直反代）与 /api/v1/ 前缀口径。
 * sitemap=application/xml，robots=text/plain；内容按小时缓存（§7 无 SEO 专用缓存条款，取保守 1h）。
 */
@RestController
public class SeoController {

    private final SeoService seoService;

    public SeoController(SeoService seoService) {
        this.seoService = seoService;
    }

    @GetMapping(value = {"/sitemap.xml", "/api/v1/sitemap.xml"},
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap() {
        return withCache(MediaType.APPLICATION_XML, seoService.sitemap());
    }

    @GetMapping(value = {"/robots.txt", "/api/v1/robots.txt"},
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> robots() {
        return withCache(MediaType.TEXT_PLAIN, seoService.robots());
    }

    private ResponseEntity<String> withCache(MediaType mediaType, String body) {
        return ResponseEntity.ok()
                .contentType(new MediaType(mediaType, StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .body(body);
    }
}
