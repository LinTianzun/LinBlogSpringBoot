package com.linblog.module.diary.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.linblog.common.GlobalExceptionHandler;
import com.linblog.common.PageResult;
import com.linblog.module.diary.dto.DiaryCreateRequest;
import com.linblog.module.diary.dto.DiaryView;
import com.linblog.module.diary.service.DiaryService;
import com.linblog.module.permission.PermissionKeys;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 管理端日记 Web 层：scope 判定（viewAll/manageOwn/create）、40400/40100 映射、委派。 */
class DiaryAdminControllerTest {

    private DiaryService diaryService;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private static DiaryView view(long id) {
        return new DiaryView(id, 7L, null, LocalDate.of(2026, 1, 1), "晴天", null,
                "{\"type\":\"doc\"}", "draft", 0, 0, LocalDateTime.now(), LocalDateTime.now());
    }

    @BeforeEach
    void setUp() {
        diaryService = Mockito.mock(DiaryService.class);
        DiaryAdminController controller = new DiaryAdminController(diaryService);
        // DiaryView 含 LocalDate/LocalDateTime，standalone 默认 ObjectMapper 无 JavaTimeModule
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
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
        loginAs(new AuthPrincipal(7L, "admin", "admin", false, Set.of(PermissionKeys.DIARY_VIEW_ALL)));
        when(diaryService.page(any(), eq("published"), eq(true), eq(7L)))
                .thenReturn(PageResult.of(List.of(view(1L)), 1));

        mockMvc.perform(get("/api/v1/admin/diaries").param("state", "published"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(1));
    }

    @Test
    void pageWithManageOwnFallsBackToOwnScope() throws Exception {
        loginAs(new AuthPrincipal(7L, "editor", "editor", false,
                Set.of(PermissionKeys.DIARY_CREATE)));
        when(diaryService.page(any(), eq(null), eq(false), eq(7L)))
                .thenReturn(PageResult.of(List.of(), 0));

        mockMvc.perform(get("/api/v1/admin/diaries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void pageWithoutAnyScopeMapsTo40400() throws Exception {
        loginAs(new AuthPrincipal(7L, "viewer", "viewer", false, Set.of()));

        mockMvc.perform(get("/api/v1/admin/diaries"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void pageAnonymousMapsTo40100() throws Exception {
        mockMvc.perform(get("/api/v1/admin/diaries"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void getRequiresViewAllOrManageOwn() throws Exception {
        loginAs(new AuthPrincipal(7L, "editor", "editor", false,
                Set.of(PermissionKeys.DIARY_MANAGE_OWN)));
        when(diaryService.get(eq(3L), eq(false), eq(7L))).thenReturn(view(3L));

        mockMvc.perform(get("/api/v1/admin/diaries/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(3));
    }

    @Test
    void createDelegatesToService() throws Exception {
        loginAs(new AuthPrincipal(7L, "editor", "editor", false,
                Set.of(PermissionKeys.DIARY_CREATE)));
        when(diaryService.create(any(DiaryCreateRequest.class))).thenReturn(view(2L));

        String body = objectMapper.writeValueAsString(new DiaryCreateRequest(
                LocalDate.of(2026, 1, 1), "晴天", null, "{\"type\":\"doc\"}", "draft"));
        mockMvc.perform(post("/api/v1/admin/diaries")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(2));
    }

    @Test
    void deleteDelegates() throws Exception {
        loginAs(new AuthPrincipal(7L, "admin", "admin", false,
                Set.of(PermissionKeys.DIARY_MANAGE_ALL)));

        mockMvc.perform(delete("/api/v1/admin/diaries/4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(diaryService).delete(4L);
    }
}
