package com.linblog.module.message.controller;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.GlobalExceptionHandler;
import com.linblog.module.message.service.SsePushService;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SSE 订阅端点（契约冻结）：仅根路径 /events（frontend sse.ts BASE='/events'）；
 * 匿名 40100；登录后返回 event-stream 异步流。
 */
class EventControllerTest {

    private SsePushService ssePushService;
    private EventController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ssePushService = mock(SsePushService.class);
        controller = new EventController(ssePushService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
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

    /** 生产环境匿名请求被安全链拦截（anyRequest().authenticated()），本用例直接验证控制器自身的兜底契约。 */
    @Test
    void anonymousRejectedWith40100() {
        BizException error = assertThrows(BizException.class, controller::events);
        assertEquals(ErrorCode.UNAUTHENTICATED, error.getErrorCode());
        assertEquals("未登录", error.getMessage());
    }

    @Test
    void authenticatedStartsEventStreamForCurrentUser() throws Exception {
        AuthPrincipal principal = new AuthPrincipal(7L, "alice", "editor", false, Set.of());
        loginAs(principal);
        when(ssePushService.register(any(AuthPrincipal.class))).thenReturn(new SseEmitter());

        MvcResult result = mvc.perform(get("/events")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        ArgumentCaptor<AuthPrincipal> captor = ArgumentCaptor.forClass(AuthPrincipal.class);
        verify(ssePushService).register(captor.capture());
        assertEquals(7L, captor.getValue().uid());
        assertEquals("editor", captor.getValue().role());
    }

    @Test
    void apiV1PrefixIsNotServedPerFrozenContract() throws Exception {
        loginAs(new AuthPrincipal(7L, "alice", "editor", false, Set.of()));

        mvc.perform(get("/api/v1/events"))
                .andExpect(status().isNotFound());
    }
}
