package com.linblog.module.content.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linblog.common.GlobalExceptionHandler;
import com.linblog.common.PageResult;
import com.linblog.module.content.dto.ContentView;
import com.linblog.module.content.entity.Label;
import com.linblog.module.content.service.ContentService;
import com.linblog.module.diary.service.DiaryService;
import com.linblog.module.stats.service.ViewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 门户公开读 Web 层：/home 聚合、列表/详情、详情顺带浏览上报。 */
class PortalControllerTest {

    private ContentService contentService;
    private DiaryService diaryService;
    private ViewService viewService;
    private MockMvc mockMvc;

    private static ContentView view(long id) {
        return new ContentView(id, "article", "标题 " + id, null, null, null, null,
                "published", LocalDateTime.now(), 0, 0, 0, false, 7L, "作者",
                null, null, LocalDateTime.now(), LocalDateTime.now());
    }

    @BeforeEach
    void setUp() {
        contentService = Mockito.mock(ContentService.class);
        diaryService = Mockito.mock(DiaryService.class);
        viewService = Mockito.mock(ViewService.class);
        PortalController controller = new PortalController(contentService, diaryService, viewService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void homeAggregatesLatestPinnedLabels() throws Exception {
        when(contentService.portalPage(any(), isNull(), isNull()))
                .thenReturn(PageResult.of(List.of(view(1L)), 1));
        when(contentService.portalPinned()).thenReturn(List.of(view(2L)));
        Label label = new Label();
        label.setId(1L);
        label.setName("Java");
        when(contentService.portalLabels()).thenReturn(List.of(label));

        mockMvc.perform(get("/api/v1/portal/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.latest[0].id").value(1))
                .andExpect(jsonPath("$.data.pinned[0].id").value(2))
                .andExpect(jsonPath("$.data.labels[0].name").value("Java"));
    }

    @Test
    void contentsListDelegates() throws Exception {
        when(contentService.portalPage(any(), eq("article"), isNull()))
                .thenReturn(PageResult.of(List.of(view(3L)), 1));

        mockMvc.perform(get("/api/v1/portal/contents").param("contentType", "article"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(3))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void contentDetailReportsView() throws Exception {
        when(contentService.portalGet(eq(9L), eq(false), isNull())).thenReturn(view(9L));
        when(viewService.report(eq(ViewService.TYPE_CONTENT), eq(9L), any(), any()))
                .thenReturn(true);

        mockMvc.perform(get("/api/v1/portal/contents/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(9));

        verify(viewService).report(eq(ViewService.TYPE_CONTENT), eq(9L), any(), any());
    }

    @Test
    void missingContentMapsTo40400() throws Exception {
        when(contentService.portalGet(eq(404L), eq(false), isNull()))
                .thenThrow(new com.linblog.common.BizException(
                        com.linblog.common.ErrorCode.NOT_FOUND, "内容不存在"));

        mockMvc.perform(get("/api/v1/portal/contents/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void diariesPageUsesPublishedScope() throws Exception {
        when(diaryService.page(any(), eq("published"), eq(true), isNull()))
                .thenReturn(PageResult.of(List.of(), 0));

        mockMvc.perform(get("/api/v1/portal/diaries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }
}
