package com.linblog.module.content.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * §7.4 Tiptap JSON 校验：递归白名单 node/attr、深度 ≤ 30、body ≤ 200KB。
 * 校验通过时顺手提取纯文本（供 plain_text 检索列），服务端写入时消毒。
 */
@Component
public class ContentJsonValidator {

    public static final int MAX_DEPTH = 30;
    public static final int MAX_BODY_BYTES = 200 * 1024;

    private static final Set<String> ALLOWED_NODE_TYPES = Set.of(
            "doc", "paragraph", "text", "heading", "bulletList", "orderedList", "listItem",
            "blockquote", "codeBlock", "horizontalRule", "hardBreak",
            "image", "link", "bold", "italic", "strike", "code", "underline",
            "table", "tableRow", "tableCell", "tableHeader");

    private static final Set<String> ALLOWED_ATTRS = Set.of(
            "src", "alt", "title", "level", "href", "target", "rel", "language");

    private final ObjectMapper objectMapper;

    public ContentJsonValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 校验并返回提取出的纯文本；body 为空时返回空串。 */
    public String validateAndExtract(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "正文超出大小限制");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "正文不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "正文结构不合法");
        }
        StringBuilder text = new StringBuilder();
        walk(root, 0, text);
        return text.toString().trim();
    }

    private void walk(JsonNode node, int depth, StringBuilder text) {
        if (depth > MAX_DEPTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "正文嵌套过深");
        }
        if (node.isTextual()) {
            text.append(node.asText());
            return;
        }
        if (!node.isObject()) {
            if (node.isArray()) {
                for (JsonNode child : node) {
                    walk(child, depth + 1, text);
                }
            }
            return;
        }
        JsonNode type = node.get("type");
        if (type != null && type.isTextual() && !ALLOWED_NODE_TYPES.contains(type.asText())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "正文包含不支持的节点类型");
        }
        if (type != null && type.isTextual() && "text".equals(type.asText())) {
            JsonNode value = node.get("text");
            if (value != null && value.isTextual()) {
                text.append(value.asText());
            }
            return;
        }
        JsonNode attrs = node.get("attrs");
        if (attrs != null && attrs.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = attrs.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> attr = it.next();
                if (!ALLOWED_ATTRS.contains(attr.getKey())) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "正文包含不支持的属性");
                }
            }
        }
        JsonNode content = node.get("content");
        if (content != null && content.isArray()) {
            boolean first = true;
            for (JsonNode child : content) {
                if (!first && isBlockBoundary(child)) {
                    text.append('\n');
                }
                first = false;
                walk(child, depth + 1, text);
            }
        }
    }

    private boolean isBlockBoundary(JsonNode child) {
        JsonNode type = child.get("type");
        if (type == null || !type.isTextual()) {
            return false;
        }
        return switch (type.asText()) {
            case "paragraph", "heading", "blockquote", "codeBlock", "listItem",
                 "tableCell", "tableHeader", "image", "horizontalRule" -> true;
            default -> false;
        };
    }
}
