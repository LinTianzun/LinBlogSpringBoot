package com.linblog.module.seo.controller;

import com.linblog.module.seo.service.SeoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** SeoController 单测：Content-Type + 公共缓存头。 */
class SeoControllerTest {

    private SeoService seoService;
    private SeoController controller;

    @BeforeEach
    void setUp() {
        seoService = mock(SeoService.class);
        controller = new SeoController(seoService);
    }

    @Test
    void sitemapServesXmlWithPublicCache() {
        when(seoService.sitemap()).thenReturn("<urlset/>");

        ResponseEntity<String> response = controller.sitemap();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(new MediaType(MediaType.APPLICATION_XML, StandardCharsets.UTF_8),
                response.getHeaders().getContentType());
        assertEquals("public, max-age=3600", response.getHeaders().getCacheControl());
        assertEquals("<urlset/>", response.getBody());
    }

    @Test
    void robotsServesPlainTextWithPublicCache() {
        when(seoService.robots()).thenReturn("User-agent: *");

        ResponseEntity<String> response = controller.robots();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8),
                response.getHeaders().getContentType());
        assertEquals("public, max-age=3600", response.getHeaders().getCacheControl());
        assertEquals("User-agent: *", response.getBody());
    }
}
