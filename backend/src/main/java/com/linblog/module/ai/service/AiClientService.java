package com.linblog.module.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.ai.entity.AiModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * AI 客户端：OpenAI 兼容 /chat/completions 协议（自原版 aiClient 移植的文本域）。
 * 密钥解密只在请求头组装瞬间发生；超时/网络错误映射为 50300 业务错误。
 */
@Service
public class AiClientService {

    private static final Logger log = LoggerFactory.getLogger(AiClientService.class);

    public record ChatResult(String text, int promptTokens, int completionTokens) {
    }

    private final AiSecretBox secretBox;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public AiClientService(AiSecretBox secretBox, ObjectMapper objectMapper) {
        this.secretBox = secretBox;
        this.objectMapper = objectMapper;
    }

    /** 单轮对话；json=true 时要求模型只输出 JSON（response_format 兼容层）。 */
    public ChatResult chatCompletion(AiModel model, String systemPrompt, String userPrompt, boolean json) {
        String apiKey = secretBox.decrypt(model.getApiKeyCipher());
        try {
            var messages = List.of(
                    Map.of("role", "system", "content", systemPrompt == null ? "" : systemPrompt),
                    Map.of("role", "user", "content", userPrompt));
            var bodyBuilder = objectMapper.createObjectNode()
                    .put("model", model.getChatModel())
                    .put("temperature", 0.6);
            bodyBuilder.set("messages", objectMapper.valueToTree(messages));
            if (json) {
                bodyBuilder.set("response_format", objectMapper.readTree("{\"type\":\"json_object\"}"));
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimBaseUrl(model.getBaseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofMillis(model.getTimeoutMs() == null ? 20000 : model.getTimeoutMs()))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(bodyBuilder)))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("AI 调用失败 model={} status={} body={}", model.getChatModel(), resp.statusCode(),
                        resp.body() == null ? "" : resp.body().substring(0, Math.min(200, resp.body().length())));
                throw new BizException(ErrorCode.INTERNAL, "AI 服务返回异常（" + resp.statusCode() + "）");
            }
            return parse(resp.body());
        } catch (BizException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new BizException(ErrorCode.INTERNAL, "AI 服务响应超时");
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.INTERNAL, "AI 密钥不可用：" + e.getMessage());
        } catch (Exception e) {
            log.warn("AI 调用异常: {}", e.getMessage());
            throw new BizException(ErrorCode.INTERNAL, "AI 服务暂时不可用");
        }
    }

    /** 解析 chat/completions 响应：取首 choice 文本与 usage token 数。 */
    private ChatResult parse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String text = root.path("choices").path(0).path("message").path("content").asText("");
            int prompt = root.path("usage").path("prompt_tokens").asInt(0);
            int completion = root.path("usage").path("completion_tokens").asInt(0);
            return new ChatResult(text, prompt, completion);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "AI 响应解析失败");
        }
    }

    private static String trimBaseUrl(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
