package com.linblog.module.media.controller;

import com.linblog.common.ApiResult;
import com.linblog.common.ErrorCode;
import com.linblog.common.GlobalExceptionHandler;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.media.dto.FileAdminView;
import com.linblog.module.media.dto.StorageStatus;
import com.linblog.module.media.service.FileAdminService;
import com.linblog.security.AuthPrincipal;
import com.linblog.support.TestAppProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** §5.7 文件管理接口：分页/汇总/trash/restore/purge 委派与门禁。 */
class FileAdminControllerTest {

    private FileAdminService fileAdminService;
    private MockMvc mvc;

    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal(1L, "root", "super_admin", true,
            Set.of("file.view", "file.delete"));

    @BeforeEach
    void setUp() {
        fileAdminService = mock(FileAdminService.class);
        mvc = MockMvcBuilders.standaloneSetup(new FileAdminController(fileAdminService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(AuthPrincipal principal) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, null));
    }

    @Test
    void anonymousRejected() throws Exception {
        mvc.perform(request(HttpMethod.GET, "/api/v1/admin/files"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.code()));
    }

    @Test
    void pageDelegatesWithState() throws Exception {
        loginAs(PRINCIPAL);
        FileAdminView view = new FileAdminView(11L, 7L, "alice", "2026/01/a.pdf", "doc.pdf",
                "application/pdf", 1024L, "a".repeat(64), "active", null, null);
        when(fileAdminService.page(any(PageQuery.class), eq("active"), eq(PRINCIPAL)))
                .thenReturn(PageResult.of(List.of(view), 1));

        mvc.perform(request(HttpMethod.GET, "/api/v1/admin/files").param("state", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].uploaderName").value("alice"));
    }

    @Test
    void storageStatusDelegates() throws Exception {
        loginAs(PRINCIPAL);
        when(fileAdminService.storageStatus(PRINCIPAL))
                .thenReturn(new StorageStatus(1024L, 1L, 512L, 1L,
                        FileAdminService.QUOTA_BYTES, 0.0002, false));

        mvc.perform(request(HttpMethod.GET, "/api/v1/admin/files/storage-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.primaryBytes").value(1024))
                .andExpect(jsonPath("$.data.quotaBytes").value(FileAdminService.QUOTA_BYTES))
                .andExpect(jsonPath("$.data.alert").value(false));
    }

    @Test
    void trashAndRestoreDelegate() throws Exception {
        loginAs(PRINCIPAL);

        mvc.perform(request(HttpMethod.PATCH, "/api/v1/admin/files/11/trash"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(request(HttpMethod.PATCH, "/api/v1/admin/files/11/restore"))
                .andExpect(status().isOk());

        verify(fileAdminService).trash(eq(11L), eq(PRINCIPAL), eq("127.0.0.1"));
        verify(fileAdminService).restore(eq(11L), eq(PRINCIPAL), eq("127.0.0.1"));
    }

    @Test
    void purgeDelegates() throws Exception {
        loginAs(PRINCIPAL);

        mvc.perform(request(HttpMethod.DELETE, "/api/v1/admin/files/11"))
                .andExpect(status().isOk());

        verify(fileAdminService).purge(eq(11L), eq(PRINCIPAL), eq("127.0.0.1"));
    }

    @Test
    void serviceErrorsMapToShell() throws Exception {
        loginAs(PRINCIPAL);
        when(fileAdminService.page(any(PageQuery.class), isNull(), eq(PRINCIPAL)))
                .thenThrow(new com.linblog.common.BizException(ErrorCode.FORBIDDEN, "无权访问"));

        mvc.perform(request(HttpMethod.GET, "/api/v1/admin/files"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.code()));
    }
}
