package com.linblog.module.guestbook.controller;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.GlobalExceptionHandler;
import com.linblog.module.comment.dto.CommentView;
import com.linblog.module.guestbook.dto.GuestbookCreateRequest;
import com.linblog.module.guestbook.dto.GuestbookCreateResult;
import com.linblog.module.guestbook.dto.GuestbookView;
import com.linblog.module.guestbook.service.GuestbookService;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 留言墙 Web 层（§5.6）：公开读 + 登录写 + 作者/审核人删除。 */
class GuestbookControllerTest {

    private GuestbookService guestbookService;
    private MockMvc mockMvc;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @BeforeEach
    void setUp() {
        guestbookService = Mockito.mock(GuestbookService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new GuestbookController(guestbookService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listIsPublic() throws Exception {
        GuestbookView view = new GuestbookView(1L, 7L, "user2", "留言1",
                LocalDateTime.of(2026, 1, 1, 0, 0), List.of());
        Mockito.when(guestbookService.list()).thenReturn(List.of(view));

        mockMvc.perform(get("/api/v1/guestbook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].body").value("留言1"))
                .andExpect(jsonPath("$.data[0].replies").isEmpty());
    }

    @Test
    void createDelegatesAndDistinguishesEntryFromReply() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new com.linblog.security.AuthPrincipal(7L, "user1", "editor", false, java.util.Set.of()), null, null));
        CommentView comment = CommentView.of(500L, "guestbook", 1L, 7L, "user1",
                null, 1L, 2L, "user2", "回复", 0, false, LocalDateTime.of(2026, 1, 1, 0, 0));
        Mockito.when(guestbookService.create(Mockito.any(GuestbookCreateRequest.class)))
                .thenReturn(GuestbookCreateResult.reply(comment));

        mockMvc.perform(post("/api/v1/guestbook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GuestbookCreateRequest("回复", 1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("reply"))
                .andExpect(jsonPath("$.data.comment.id").value(500));
    }

    @Test
    void createWithoutLoginReturns40100() throws Exception {
        Mockito.when(guestbookService.create(Mockito.any(GuestbookCreateRequest.class)))
                .thenThrow(new BizException(ErrorCode.UNAUTHENTICATED, "未登录"));

        mockMvc.perform(post("/api/v1/guestbook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GuestbookCreateRequest("hi", null))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void deletePropagatesNotFoundAndForbidden() throws Exception {
        Mockito.doThrow(new BizException(ErrorCode.NOT_FOUND, "留言不存在"))
                .when(guestbookService).delete(404L);
        Mockito.doThrow(new BizException(ErrorCode.FORBIDDEN, "无留言板管理权限"))
                .when(guestbookService).delete(403L);

        mockMvc.perform(delete("/api/v1/guestbook/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));

        mockMvc.perform(delete("/api/v1/guestbook/403"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }
}
