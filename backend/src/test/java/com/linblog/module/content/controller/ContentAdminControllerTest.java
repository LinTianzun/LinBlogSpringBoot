package com.linblog.module.content.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linblog.common.GlobalExceptionHandler;
import com.linblog.common.PageResult;
import com.linblog.module.content.dto.ContentCreateRequest;
import com.linblog.module.content.dto.ContentView;
import com.linblog.module.content.service.ContentService;
import com.linblog.module.permission.PermissionKeys;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 管理端内容 Web 层：scope 判定、防探测 40400、匿名 40100、回收站端点委派。 */
class ContentAdminControllerTest {

    private ContentService contentService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static ContentView view(long id) {
        return new ContentView(id, "article", "标题", null, null, null, null, null,
                "draft", null, 0, 0, 0, false, 7L, "作者",
                null, null, LocalDateTime.now(), LocalDateTime.now());
    }

    @BeforeEach
    void setUp() {
        contentService = Mockito.mock(ContentService.class);
        ContentAdminController controller = new ContentAdminController(contentService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(AuthPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, null));
    }

    @Test
    void pageWithViewAllScope() throws Exception {
        loginAs(new AuthPrincipal(7L, "admin", "admin", false, Set.of(PermissionKeys.ARTICLE_VIEW_ALL)));
        when(contentService.adminPage(any(), eq("article"), eq("published"), isNull(),
                eq(true), eq(7L)))
                .thenReturn(PageResult.of(List.of(view(1L)), 1));

        mockMvc.perform(get("/api/v1/admin/contents")
                        .param("contentType", "article").param("state", "published"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(1));
    }

    @Test
    void pageWithoutAnyScopeMapsTo40400() throws Exception {
        loginAs(new AuthPrincipal(7L, "viewer", "viewer", false, Set.of()));

        mockMvc.perform(get("/api/v1/admin/contents"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void pageAnonymousMapsTo40100() throws Exception {
        mockMvc.perform(get("/api/v1/admin/contents"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void createDelegatesToService() throws Exception {
        loginAs(new AuthPrincipal(7L, "editor", "editor", false,
                Set.of(PermissionKeys.ARTICLE_CREATE)));
        when(contentService.create(any(ContentCreateRequest.class))).thenReturn(view(5L));

        String body = objectMapper.writeValueAsString(new ContentCreateRequest(
                "article", "标题", null, "{\"type\":\"doc\"}", null, null, "draft"));
        mockMvc.perform(post("/api/v1/admin/contents")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(5));
    }

    @Test
    void trashAndPurgeDelegate() throws Exception {
        loginAs(new AuthPrincipal(7L, "admin", "admin", false,
                Set.of(PermissionKeys.ARTICLE_MANAGE_ALL)));

        mockMvc.perform(delete("/api/v1/admin/contents/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(contentService).delete(9L);

        mockMvc.perform(delete("/api/v1/admin/contents/9/purge"))
                .andExpect(status().isOk());
        verify(contentService).purge(9L);
    }

    @Test
    void stateChangeDelegates() throws Exception {
        loginAs(new AuthPrincipal(7L, "admin", "admin", false,
                Set.of(PermissionKeys.ARTICLE_MANAGE_ALL, PermissionKeys.ARTICLE_PUBLISH)));
        when(contentService.changeState(eq(9L), any()))
                .thenReturn(view(9L));

        String body = objectMapper.writeValueAsString(new com.linblog.module.content.dto.ContentStateRequest("published"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/admin/contents/9/state")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(9));
    }

    /* ==================== 图集媒体 ==================== */

    private ContentService.ContentMediaItem mediaItem(boolean cover) {
        return new ContentService.ContentMediaItem(3L, "2026/11/k.svg", null,
                800, 520, "image/svg+xml", cover);
    }

    @Test
    void listAlbumMediaDelegates() throws Exception {
        when(contentService.listMedia(9L)).thenReturn(new java.util.ArrayList<>(List.of(mediaItem(true))));
        mockMvc.perform(get("/api/v1/admin/contents/9/media"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].mediaId").value(3))
                .andExpect(jsonPath("$.data[0].cover").value(true));
    }

    @Test
    void attachMediaDelegates() throws Exception {
        when(contentService.attachMedia(9L, 3L)).thenReturn(mediaItem(false));
        mockMvc.perform(post("/api/v1/admin/contents/9/media/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mediaId").value(3));
        verify(contentService).attachMedia(9L, 3L);
    }

    @Test
    void detachMediaDelegates() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/contents/9/media/3"))
                .andExpect(status().isOk());
        verify(contentService).detachMedia(9L, 3L);
    }

    @Test
    void setCoverDelegates() throws Exception {
        when(contentService.setCoverFromMedia(9L, 3L)).thenReturn(mediaItem(true));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/admin/contents/9/media/3/cover"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cover").value(true));
        verify(contentService).setCoverFromMedia(9L, 3L);
    }
}
