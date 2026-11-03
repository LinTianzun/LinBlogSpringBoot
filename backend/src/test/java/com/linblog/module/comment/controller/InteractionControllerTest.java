package com.linblog.module.comment.controller;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.GlobalExceptionHandler;
import com.linblog.module.comment.dto.InteractionRequest;
import com.linblog.module.comment.dto.PraiseResult;
import com.linblog.module.comment.service.InteractionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内容/日记点赞接口（§5.5 POST /interactions）Web 层：验证路由绑定与
 * {targetType,targetId} 透传、toggle 结果透出、业务异常（未登录/非法目标）经全局处理器回落。
 */
class InteractionControllerTest {

    private InteractionService interactionService;
    private MockMvc mockMvc;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @BeforeEach
    void setUp() {
        interactionService = Mockito.mock(InteractionService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new InteractionController(interactionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void login(long uid) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new com.linblog.security.AuthPrincipal(uid, "alice", "viewer", false, Set.of()), null, null));
    }

    @Test
    void toggleDelegatesAndReturnsActiveAndCount() throws Exception {
        login(5L);
        Mockito.when(interactionService.toggle(Mockito.any(InteractionRequest.class)))
                .thenReturn(new PraiseResult(true, 8L));

        mockMvc.perform(post("/api/v1/interactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InteractionRequest("content", 1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.count").value(8));

        ArgumentCaptor<InteractionRequest> captor = ArgumentCaptor.forClass(InteractionRequest.class);
        Mockito.verify(interactionService).toggle(captor.capture());
        assertEquals("content", captor.getValue().targetType());
        assertEquals(1L, captor.getValue().targetId());
    }

    @Test
    void toggleDiaryPassesThrough() throws Exception {
        login(5L);
        Mockito.when(interactionService.toggle(Mockito.any(InteractionRequest.class)))
                .thenReturn(new PraiseResult(false, 2L));

        mockMvc.perform(post("/api/v1/interactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InteractionRequest("diary", 4L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.count").value(2));
    }

    @Test
    void unauthenticatedPropagates() throws Exception {
        Mockito.when(interactionService.toggle(Mockito.any(InteractionRequest.class)))
                .thenThrow(new BizException(ErrorCode.UNAUTHENTICATED, "未登录"));

        mockMvc.perform(post("/api/v1/interactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InteractionRequest("content", 1L))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void invalidTargetTypePropagates() throws Exception {
        login(5L);
        Mockito.when(interactionService.toggle(Mockito.any(InteractionRequest.class)))
                .thenThrow(new BizException(ErrorCode.BAD_REQUEST, "点赞目标类型不合法"));

        mockMvc.perform(post("/api/v1/interactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InteractionRequest("bogus", 1L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }
}
