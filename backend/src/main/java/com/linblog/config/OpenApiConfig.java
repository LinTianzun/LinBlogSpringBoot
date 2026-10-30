package com.linblog.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / springdoc 配置：/v3/api-docs 产出 openapi.json，/swagger-ui.html 可视化。
 * 认证基于 HttpOnly Cookie（linlog_at），在文档中以 cookie 安全方案标注。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI linblogOpenApi() {
        SecurityScheme cookieAuth = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.COOKIE)
                .name("linlog_at")
                .description("认证 Cookie；XSRF 写操作另需 X-XSRF-TOKEN 头");
        return new OpenAPI()
                .info(new Info()
                        .title("LinBlog API")
                        .version("v1")
                        .description("LinBlog Vue3 + SpringBoot 重实现后端（/api/v1，统一响应壳 ApiResult）"))
                .components(new Components().addSecuritySchemes("cookieAuth", cookieAuth))
                .addSecurityItem(new SecurityRequirement().addList("cookieAuth"));
    }
}
