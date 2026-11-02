package com.linblog.module.message.controller;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.GlobalExceptionHandler;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.message.service.MessageService;
import com.linblog.module.permission.PermissionKeys;
import com.linblog.module.permission.service.PermissionService;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 私信端点：列表需 message.private，其余仅需登录；委派与响应壳（§5.6）。
 */
class MessageControllerTest {

    private static final AuthPrincipal ALICE = new AuthPrincipal(1L, "alice", "editor", false, Set.of());

    private MessageService messageService;
    private PermissionService permissionService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        messageService = mock(MessageService.class);
        permissionService = mock(PermissionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new MessageController(messageService, permissionService))
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
    void conversationsRejectAnonymous() throws Exception {
        mvc.perform(request(HttpMethod.GET, "/api/v1/messages/conversations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.code()));
    }

    @Test
    void conversationsRequirePermissionKey() throws Exception {
        loginAs(ALICE);
        doThrow(new BizException(ErrorCode.FORBIDDEN))
                .when(permissionService).require(any(), eq(PermissionKeys.MESSAGE_PRIVATE));

        mvc.perform(request(HttpMethod.GET, "/api/v1/messages/conversations"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.code()));
    }

    @Test
    void conversationsDelegateWithPagination() throws Exception {
        loginAs(ALICE);
        when(messageService.conversations(eq(ALICE), any(PageQuery.class)))
                .thenReturn(PageResult.of(List.of(), 0));

        mvc.perform(request(HttpMethod.GET, "/api/v1/messages/conversations").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0));
        verify(permissionService).require(ALICE, PermissionKeys.MESSAGE_PRIVATE);
    }

    @Test
    void createDelegatesToStart() throws Exception {
        loginAs(ALICE);
        when(messageService.start(eq(ALICE), eq(2L), eq("hi"), eq("127.0.0.1")))
                .thenReturn(new MessageService.DirectMessageView(100L, 10L, 1L, "hi", null,
                        LocalDateTime.of(2026, 5, 1, 12, 0)));

        mvc.perform(request(HttpMethod.POST, "/api/v1/messages/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientId\":2,\"body\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.conversationId").value(10));
    }

    @Test
    void createValidationErrorsAreAggregated() throws Exception {
        loginAs(ALICE);

        mvc.perform(request(HttpMethod.POST, "/api/v1/messages/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientId\":null,\"body\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION.code()))
                .andExpect(jsonPath("$.data.fieldErrors.length()").value(2));
    }

    @Test
    void messagesPassCursorAndReturnPage() throws Exception {
        loginAs(ALICE);
        when(messageService.messages(ALICE, 10L, 5L))
                .thenReturn(new MessageService.MessagePage(List.of(), false));

        mvc.perform(request(HttpMethod.GET, "/api/v1/messages/conversations/10").param("before", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.hasMore").value(false));
    }

    @Test
    void sendDelegatesToConversationSend() throws Exception {
        loginAs(ALICE);
        when(messageService.send(eq(ALICE), eq(10L), eq("yo"), eq("127.0.0.1")))
                .thenReturn(new MessageService.DirectMessageView(101L, 10L, 1L, "yo", null,
                        LocalDateTime.of(2026, 5, 1, 12, 0)));

        mvc.perform(request(HttpMethod.POST, "/api/v1/messages/conversations/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"yo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(101));
    }

    @Test
    void markReadDelegates() throws Exception {
        loginAs(ALICE);

        mvc.perform(request(HttpMethod.PATCH, "/api/v1/messages/conversations/10/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(messageService).markRead(ALICE, 10L);
    }

    @Test
    void unreadCountDelegates() throws Exception {
        loginAs(ALICE);
        when(messageService.unreadCount(ALICE)).thenReturn(7L);

        mvc.perform(request(HttpMethod.GET, "/api/v1/messages/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(7));
    }
}
