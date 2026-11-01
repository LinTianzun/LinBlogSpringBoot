package com.linblog.module.stats.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linblog.common.GlobalExceptionHandler;
import com.linblog.module.stats.dto.ViewReportRequest;
import com.linblog.module.stats.service.ViewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 浏览上报 Web 层（standalone MockMvc）：壳结构与 40000 映射。 */
class ViewControllerTest {

    private ViewService viewService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        viewService = Mockito.mock(ViewService.class);
        ViewController controller = new ViewController(viewService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void countedReportReturnsTrue() throws Exception {
        Mockito.when(viewService.report(Mockito.eq("content"), Mockito.eq(11L),
                        Mockito.anyString(), Mockito.any()))
                .thenReturn(true);

        String body = objectMapper.writeValueAsString(new ViewReportRequest("content", 11L));
        mockMvc.perform(post("/api/v1/views").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.counted").value(true));
    }

    @Test
    void dedupedReportReturnsFalse() throws Exception {
        Mockito.when(viewService.report(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(false);

        String body = objectMapper.writeValueAsString(new ViewReportRequest("diary", 5L));
        mockMvc.perform(post("/api/v1/views").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counted").value(false));
    }

    @Test
    void invalidTypeMapsTo40000() throws Exception {
        Mockito.when(viewService.report(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenThrow(new com.linblog.common.BizException(
                        com.linblog.common.ErrorCode.BAD_REQUEST, "浏览目标类型不合法"));

        String body = objectMapper.writeValueAsString(new ViewReportRequest("video", 5L));
        mockMvc.perform(post("/api/v1/views").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("浏览目标类型不合法"));
    }
}
