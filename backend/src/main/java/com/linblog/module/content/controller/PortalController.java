package com.linblog.module.content.controller;

import com.linblog.common.ApiResult;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.content.dto.ContentView;
import com.linblog.module.content.entity.Label;
import com.linblog.module.content.service.ContentService;
import com.linblog.module.diary.dto.DiaryView;
import com.linblog.module.diary.service.DiaryService;
import com.linblog.module.stats.service.ViewService;
import com.linblog.module.ai.service.AiService;
import com.linblog.security.RateLimiter;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import com.linblog.security.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 门户公开读（§5.3/§5.4）：仅 published；/home 聚合 {latest, pinned, labels}；
 * /contents/{id}?preview=1 作者/管理者可预览非 published，浏览顺带计数（§7.6）。
 */
@RestController
@RequestMapping("/api/v1/portal")
public class PortalController {

    private final ContentService contentService;
    private final DiaryService diaryService;
    private final AiService aiService;
    private final ViewService viewService;
    private final RateLimiter rateLimiter;

    public PortalController(ContentService contentService, DiaryService diaryService,
                            AiService aiService, ViewService viewService, RateLimiter rateLimiter) {
        this.contentService = contentService;
        this.diaryService = diaryService;
        this.aiService = aiService;
        this.viewService = viewService;
        this.rateLimiter = rateLimiter;
    }

    public record PortalHome(List<ContentView> latest, List<ContentView> pinned, List<Label> labels) {
    }

    @GetMapping("/home")
    public ApiResult<PortalHome> home(@RequestParam(defaultValue = "1") Integer page,
                                      @RequestParam(defaultValue = "10") Integer pageSize) {
        PageQuery query = new PageQuery();
        query.setPage(page);
        query.setPageSize(pageSize);
        PageResult<ContentView> latest = contentService.portalPage(query, null, null);
        return ApiResult.ok(new PortalHome(latest.records(), contentService.portalPinned(),
                contentService.portalLabels()));
    }

    @GetMapping("/contents")
    public ApiResult<PageResult<ContentView>> contents(
            @RequestParam(required = false) String contentType,
            @RequestParam(required = false) Long labelId,
            @RequestParam(required = false) String sort,
            @Valid PageQuery query) {
        return ApiResult.ok(contentService.portalPage(query, contentType, labelId));
    }

    @GetMapping("/contents/{id}")
    public ApiResult<ContentView> contentDetail(@PathVariable long id,
                                                @RequestParam(required = false) Integer preview,
                                                HttpServletRequest request) {
        AuthPrincipal principal = CurrentUser.get();
        ContentView view = contentService.portalGet(id, preview != null && preview == 1, principal);
        // §5.3：门户详情顺带记浏览（30 分钟窗口内去重）
        viewService.report(ViewService.TYPE_CONTENT, id, IpUtil.clientIp(request),
                request.getHeader("User-Agent"));
        return ApiResult.ok(view);
    }

    /** 图集媒体（gallery 多图浏览）：目标必须可见。 */
    @GetMapping("/contents/{id}/media")
    public ApiResult<List<ContentService.PortalMediaItem>> contentMedia(@PathVariable long id) {
        return ApiResult.ok(contentService.listPublicMedia(id));
    }

    /**
     * 门户语义搜索（匿名开放）：AI 把自然语言扩成关键词（失败降级原词），
     * 命中与排序仍由数据库决定。每 IP 限流 RATE_LIMIT_AI_SEARCH 次/分钟。
     */
    @PostMapping("/search")
    public ApiResult<Map<String, Object>> search(@RequestBody Map<String, String> body,
                                                 HttpServletRequest request) {
        String query = body.getOrDefault("query", "").trim();
        if (query.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先输入要搜索的内容");
        }
        if (query.length() > 100) {
            query = query.substring(0, 100);
        }
        if (!rateLimiter.tryAiSearch(IpUtil.clientIp(request))) {
            throw new BizException(ErrorCode.RATE_LIMITED);
        }
        List<String> terms = new ArrayList<>(List.of(query));
        try {
            for (String term : aiService.expandSearchQuery(query)) {
                if (!terms.contains(term)) {
                    terms.add(term);
                }
            }
        } catch (RuntimeException e) {
            // 扩展失败不阻塞搜索：按原关键词检索即可（与原版语义一致）
        }
        return ApiResult.ok(Map.of(
                "terms", terms,
                "records", contentService.searchPublished(terms)));
    }

    @GetMapping("/diaries")
    public ApiResult<PageResult<DiaryView>> diaries(@Valid PageQuery query) {
        return ApiResult.ok(diaryService.page(query, "published", true, null));
    }

    @GetMapping("/diaries/{id}")
    public ApiResult<DiaryView> diaryDetail(@PathVariable long id, HttpServletRequest request) {
        DiaryView view = diaryService.portalGet(id);
        viewService.report(ViewService.TYPE_DIARY, id, IpUtil.clientIp(request),
                request.getHeader("User-Agent"));
        return ApiResult.ok(view);
    }
}
