package com.linblog.module.content.controller;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.GlobalExceptionHandler;
import com.linblog.module.content.dto.LabelRequest;
import com.linblog.module.content.entity.Label;
import com.linblog.module.content.service.LabelService;
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

import java.util.List;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 标签 Web 层：公开读 + label.manage 写 + 匿名 40100。 */
class LabelControllerTest {

    private LabelService labelService;
    private MockMvc mockMvc;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @BeforeEach
    void setUp() {
        labelService = Mockito.mock(LabelService.class);
        LabelController controller = new LabelController(labelService);
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
    void listIsPublic() throws Exception {
        Label label = new Label();
        label.setId(1L);
        label.setName("Java");
        Mockito.when(labelService.listAll()).thenReturn(List.of(label));

        mockMvc.perform(get("/api/v1/labels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].name").value("Java"));
    }

    @Test
    void createDelegatesWhenAuthenticated() throws Exception {
        loginAs(new AuthPrincipal(7L, "editor", "editor", false, Set.of("label.manage")));
        Label label = new Label();
        label.setId(2L);
        label.setName(" 前端 ");
        Mockito.when(labelService.create(Mockito.any(LabelRequest.class))).thenReturn(label);

        mockMvc.perform(post("/api/v1/admin/labels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LabelRequest(" 前端 "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(2));
    }

    @Test
    void writeWithoutLoginReturns40100() throws Exception {
        mockMvc.perform(post("/api/v1/admin/labels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LabelRequest("x"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void blankNameReturns40001() throws Exception {
        loginAs(new AuthPrincipal(7L, "editor", "editor", false, Set.of("label.manage")));

        mockMvc.perform(post("/api/v1/admin/labels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LabelRequest(""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.data.fieldErrors").isArray());
    }

    @Test
    void deletePropagatesReferencedConflict() throws Exception {
        loginAs(new AuthPrincipal(7L, "editor", "editor", false, Set.of("label.manage")));
        Mockito.doThrow(new BizException(ErrorCode.CONFLICT, "标签仍被内容引用，无法删除"))
                .when(labelService).delete(1L);

        mockMvc.perform(delete("/api/v1/admin/labels/1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900))
                .andExpect(jsonPath("$.message").value("标签仍被内容引用，无法删除"));
    }

    @Test
    void updateMapsToPatchEndpoint() throws Exception {
        loginAs(new AuthPrincipal(7L, "editor", "editor", false, Set.of("label.manage")));
        Label label = new Label();
        label.setId(1L);
        label.setName("Java");
        Mockito.when(labelService.update(Mockito.eq(1L), Mockito.any(LabelRequest.class)))
                .thenReturn(label);

        mockMvc.perform(patch("/api/v1/admin/labels/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LabelRequest("Java"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Java"));
    }
}
