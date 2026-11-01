package com.linblog.module.content.controller;

import com.linblog.common.ApiResult;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.content.dto.ContentView;
import com.linblog.module.content.entity.Label;
import com.linblog.module.content.service.ContentService;
import com.linblog.module.diary.dto.DiaryView;
import com.linblog.module.diary.service.DiaryService;
import com.linblog.module.stats.service.ViewService;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import com.linblog.security.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 门户公开读（§5.3/§5.4）：仅 published；/home 聚合 {latest, pinned, labels}；
 * /contents/{id}?preview=1 作者/管理者可预览非 published，浏览顺带计数（§7.6）。
 */
@RestController
@RequestMapping("/api/v1/portal")
public class PortalController {

    private final ContentService contentService;
    private final DiaryService diaryService;
    private final ViewService viewService;

    public PortalController(ContentService contentService, DiaryService diaryService,
                            ViewService viewService) {
        this.contentService = contentService;
        this.diaryService = diaryService;
        this.viewService = viewService;
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
