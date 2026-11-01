package com.linblog.module.content.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linblog.common.GlobalExceptionHandler;
import com.linblog.common.PageResult;
import com.linblog.module.content.dto.SubsetCreateRequest;
import com.linblog.module.content.entity.Subset;
import com.linblog.module.content.entity.SubsetItem;
import com.linblog.module.content.service.SubsetService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 合集管理 Web 层（自设计端点）：scope 判定与委派。 */
class SubsetAdminControllerTest {

    private SubsetService subsetService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static Subset subset(long id) {
        Subset s = new Subset();
        s.setId(id);
        s.setName("合集");
        s.setOwnerId(7L);
        s.setCreatedAt(LocalDateTime.now());
        return s;
    }

    @BeforeEach
    void setUp() {
        subsetService = Mockito.mock(SubsetService.class);
        SubsetAdminController controller = new SubsetAdminController(subsetService);
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
        loginAs(new AuthPrincipal(7L, "admin", "admin", false,
                Set.of(PermissionKeys.ARTICLE_VIEW_ALL)));
        when(subsetService.page(any(), eq(true), eq(7L)))
                .thenReturn(PageResult.of(List.of(subset(1L)), 1));

        mockMvc.perform(get("/api/v1/admin/subsets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(1));
    }

    @Test
    void pageWithViewOwnScope() throws Exception {
        loginAs(new AuthPrincipal(7L, "editor", "editor", false,
                Set.of(PermissionKeys.GALLERY_VIEW_OWN)));
        when(subsetService.page(any(), eq(false), eq(7L)))
                .thenReturn(PageResult.of(List.of(), 0));

        mockMvc.perform(get("/api/v1/admin/subsets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void pageWithoutScopeMapsTo40400() throws Exception {
        loginAs(new AuthPrincipal(7L, "viewer", "viewer", false, Set.of()));

        mockMvc.perform(get("/api/v1/admin/subsets"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void createDelegatesToService() throws Exception {
        loginAs(new AuthPrincipal(7L, "editor", "editor", false,
                Set.of(PermissionKeys.ARTICLE_CREATE)));
        when(subsetService.create(any(SubsetCreateRequest.class))).thenReturn(subset(2L));

        String body = objectMapper.writeValueAsString(new SubsetCreateRequest(
                "合集", null, null, null));
        mockMvc.perform(post("/api/v1/admin/subsets")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(2));
    }

    @Test
    void itemsEndpointsDelegate() throws Exception {
        loginAs(new AuthPrincipal(7L, "admin", "admin", false,
                Set.of(PermissionKeys.ARTICLE_MANAGE_ALL)));
        SubsetItem item = new SubsetItem();
        item.setSubsetId(2L);
        item.setContentId(100L);
        item.setSort(0);
        when(subsetService.itemsOf(2L)).thenReturn(List.of(item));

        mockMvc.perform(get("/api/v1/admin/subsets/2/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].contentId").value(100));

        String body = objectMapper.writeValueAsString(
                new com.linblog.module.content.dto.SubsetItemsRequest(List.of(100L, 101L)));
        mockMvc.perform(put("/api/v1/admin/subsets/2/items")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(subsetService).replaceItems(eq(2L), any());
    }

    @Test
    void deleteDelegates() throws Exception {
        loginAs(new AuthPrincipal(7L, "admin", "admin", false,
                Set.of(PermissionKeys.ARTICLE_MANAGE_ALL)));

        mockMvc.perform(delete("/api/v1/admin/subsets/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(subsetService).delete(2L);
    }

    @Test
    void anonymousGetItemsMapsTo40100() throws Exception {
        mockMvc.perform(get("/api/v1/admin/subsets/2/items"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }
}
