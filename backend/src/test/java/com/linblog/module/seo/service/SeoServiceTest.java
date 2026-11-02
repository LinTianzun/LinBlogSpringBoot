package com.linblog.module.seo.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linblog.config.AppProperties;
import com.linblog.module.content.entity.Content;
import com.linblog.module.content.mapper.ContentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SeoService 单测：sitemap 结构、robots 文案、baseUrl 三级回退（publicUrl → 首个 origin → 快速失败）。 */
class SeoServiceTest {

    private ContentMapper contentMapper;
    private SeoService service;

    @BeforeAll
    static void initEntityMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Content.class);
    }

    @BeforeEach
    void setUp() {
        contentMapper = mock(ContentMapper.class);
        service = new SeoService(contentMapper,
                props("https://blog.example.com/", "https://a.example.com,https://b.example.com"));
    }

    private static AppProperties props(String publicUrl, String allowedOrigins) {
        return new AppProperties(publicUrl, allowedOrigins, "secret", Duration.ofMinutes(30), 7, null, false,
                "local", "data/storage", null, null, null, null, "320,480",
                DataSize.ofMegabytes(10), 30, 90, 7, false, 5, 5, null);
    }

    private static Content content(long id, String contentType, LocalDateTime updatedAt) {
        Content content = new Content();
        content.setId(id);
        content.setContentType(contentType);
        content.setUpdatedAt(updatedAt);
        return content;
    }

    @Test
    void robotsDisallowsAdminAndApiAndPointsToSitemap() {
        assertEquals("User-agent: *\nDisallow: /admin\nDisallow: /api\n\nSitemap: https://blog.example.com/sitemap.xml\n",
                service.robots());
    }

    @Test
    void baseUrlFallsBackToFirstAllowedOriginWhenPublicUrlMissing() {
        SeoService fallback = new SeoService(contentMapper,
                props("  ", "https://one.example.com,https://two.example.com"));

        assertTrue(fallback.robots().contains("Sitemap: https://one.example.com/sitemap.xml"));
    }

    @Test
    void baseUrlFailsFastWhenNothingConfigured() {
        SeoService unconfigured = new SeoService(contentMapper, props(" ", ", ,"));

        IllegalStateException error = assertThrows(IllegalStateException.class, unconfigured::robots);
        assertEquals("PUBLIC_URL 与 ALLOWED_ORIGINS 均未配置，无法生成 SEO 文件", error.getMessage());
    }

    @Test
    void sitemapListsStaticWallsAndPublishedContentWithLastmod() {
        when(contentMapper.selectList(any())).thenReturn(List.of(
                content(7L, "article", LocalDateTime.of(2026, 1, 2, 10, 15)),
                content(9L, "image", LocalDateTime.of(2025, 12, 31, 23, 59))));

        String xml = service.sitemap();

        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(xml.contains("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">"));
        assertTrue(xml.contains("<loc>https://blog.example.com</loc>"));
        assertTrue(xml.contains("<loc>https://blog.example.com/articles</loc>"));
        assertTrue(xml.contains("<loc>https://blog.example.com/gallery</loc>"));
        assertTrue(xml.contains("<loc>https://blog.example.com/diary</loc>"));
        assertTrue(xml.contains("<loc>https://blog.example.com/guestbook</loc>"));
        assertTrue(xml.contains("<loc>https://blog.example.com/articles/7</loc>"));
        assertTrue(xml.contains("<lastmod>2026-01-02</lastmod>"));
        assertTrue(xml.contains("<loc>https://blog.example.com/gallery/9</loc>"));
        assertTrue(xml.contains("<lastmod>2025-12-31</lastmod>"));
        assertTrue(xml.contains("</urlset>"));
        assertFalse(xml.contains("/articles/9"));
        verify(contentMapper).selectList(any());
    }

    @Test
    void sitemapOnEmptyDatabaseStillRendersFiveWallPages() {
        when(contentMapper.selectList(any())).thenReturn(List.of());

        String xml = service.sitemap();

        assertEquals(5, xml.split("<url>", -1).length - 1);
        assertTrue(xml.contains("</urlset>"));
    }

    @Test
    void sitemapOmitsLastmodWhenUpdatedAtMissing() {
        when(contentMapper.selectList(any())).thenReturn(List.of(
                content(11L, "image", null)));

        String xml = service.sitemap();

        assertTrue(xml.contains("<loc>https://blog.example.com/gallery/11</loc>"));
        assertFalse(xml.contains("<lastmod>"));
    }
}
