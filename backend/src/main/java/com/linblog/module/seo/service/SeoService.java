package com.linblog.module.seo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linblog.config.AppProperties;
import com.linblog.module.content.entity.Content;
import com.linblog.module.content.mapper.ContentMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SEO（§5.8，M7）：sitemap.xml 由 published 内容动态生成 + 门户静态页；robots.txt 禁爬后台。
 * 仅输出真实可访问的 URL：文章详情 /articles/{id}、图库详情 /gallery/{id}；
 * 日记只有时间轴墙页（前端无 /diary/{id} 详情路由），故日记只出墙页一条。
 * PUBLIC_URL 缺省回退 ALLOWED_ORIGINS[0]（§4.3）。
 */
@Service
public class SeoService {

    private static final String STATE_PUBLISHED = "published";

    private final ContentMapper contentMapper;
    private final AppProperties props;

    public SeoService(ContentMapper contentMapper, AppProperties props) {
        this.contentMapper = contentMapper;
        this.props = props;
    }

    /** 站点绝对地址：PUBLIC_URL 优先，否则 ALLOWED_ORIGINS 第一个来源。 */
    public String baseUrl() {
        String base = props.publicUrl();
        if (base == null || base.isBlank()) {
            List<String> origins = props.allowedOriginList();
            if (origins.isEmpty()) {
                throw new IllegalStateException("PUBLIC_URL 与 ALLOWED_ORIGINS 均未配置，无法生成 SEO 文件");
            }
            base = origins.get(0);
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** GET /sitemap.xml：门户静态页 + 已发布文章/图库（lastmod=updated_at 的日期部分）。 */
    public String sitemap() {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        String base = baseUrl();
        for (String wall : List.of("", "/articles", "/gallery", "/diary", "/guestbook")) {
            appendUrl(xml, base + wall, null);
        }
        for (Content content : publishedContents()) {
            String path = "image".equals(content.getContentType()) ? "/gallery/" : "/articles/";
            appendUrl(xml, base + path + content.getId(), content.getUpdatedAt());
        }
        xml.append("</urlset>\n");
        return xml.toString();
    }

    /** GET /llms.txt（llmstxt.org 最小规范）：H1 + 简介 + 门户结构链接，供 AI 爬虫理解站点。 */
    public String llmsTxt() {
        String base = baseUrl();
        return """
                # LinBlog

                > 林知雨的个人博客与作品集：工程文章、摄影画廊与日常日记。一篇文章，一件完整的作品。

                ## 门户页面

                - [首页](__BASE__/): 最新文章与精选内容
                - [文章列表](__BASE__/articles): 工程与生活随笔
                - [画廊](__BASE__/gallery): 摄影作品
                - [日记](__BASE__/diary): 日常记录
                - [留言墙](__BASE__/guestbook): 读者留言

                ## 文档

                - [sitemap.xml](__BASE__/sitemap.xml): 全站 URL 索引
                """.replace("__BASE__", base);
    }

    /** GET /.well-known/ard.json（ARD manifest）：供 AI agent 与注册表发现站点资源。 */
    public String ardManifest() {
        String base = baseUrl();
        return """
                {
                  "name": "LinBlog",
                  "description": "个人博客与作品集：工程文章、摄影画廊与日常日记。",
                  "url": "__BASE__",
                  "version": "1.0.0",
                  "resources": [
                    { "type": "page", "name": "文章列表", "url": "__BASE__/articles" },
                    { "type": "page", "name": "画廊", "url": "__BASE__/gallery" },
                    { "type": "page", "name": "日记", "url": "__BASE__/diary" },
                    { "type": "page", "name": "留言墙", "url": "__BASE__/guestbook" },
                    { "type": "document", "name": "sitemap", "url": "__BASE__/sitemap.xml" }
                  ]
                }
                """.replace("__BASE__", base);
    }

    /** GET /robots.txt：允许门户，禁爬后台与 API，并指引 sitemap。 */
    public String robots() {
        return "User-agent: *\n"
                + "Disallow: /admin\n"
                + "Disallow: /api\n"
                + "\n"
                + "Sitemap: " + baseUrl() + "/sitemap.xml\n";
    }

    private List<Content> publishedContents() {
        return contentMapper.selectList(new LambdaQueryWrapper<Content>()
                .select(Content::getId, Content::getContentType, Content::getUpdatedAt)
                .eq(Content::getState, STATE_PUBLISHED)
                .in(Content::getContentType, "article", "image")
                .orderByDesc(Content::getUpdatedAt));
    }

    private void appendUrl(StringBuilder xml, String loc, LocalDateTime lastmod) {
        xml.append("  <url>\n")
                .append("    <loc>").append(loc).append("</loc>\n");
        if (lastmod != null) {
            xml.append("    <lastmod>").append(lastmod.toLocalDate()).append("</lastmod>\n");
        }
        xml.append("  </url>\n");
    }
}
