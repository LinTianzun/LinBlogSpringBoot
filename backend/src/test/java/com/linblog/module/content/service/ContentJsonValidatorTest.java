package com.linblog.module.content.service;

import com.linblog.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §7.4 Tiptap JSON 校验：白名单、深度、大小、纯文本提取。 */
class ContentJsonValidatorTest {

    private ContentJsonValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ContentJsonValidator(new ObjectMapper());
    }

    private static String doc(String... paragraphs) {
        StringBuilder sb = new StringBuilder("{\"type\":\"doc\",\"content\":[");
        for (int i = 0; i < paragraphs.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"")
                    .append(paragraphs[i]).append("\"}]}");
        }
        return sb.append("]}").toString();
    }

    @Test
    void blankBodyReturnsEmptyText() {
        assertEquals("", validator.validateAndExtract(null));
        assertEquals("", validator.validateAndExtract("   "));
    }

    @Test
    void extractsPlainTextWithBlockBoundaries() {
        String plain = validator.validateAndExtract(doc("第一段", "第二段"));
        assertEquals("第一段\n第二段", plain);
    }

    @Test
    void invalidJsonRejected() {
        BizException ex = assertThrows(BizException.class,
                () -> validator.validateAndExtract("{not json"));
        assertEquals(40000, ex.getErrorCode().code());
    }

    @Test
    void nonObjectRootRejected() {
        assertThrows(BizException.class, () -> validator.validateAndExtract("[1,2,3]"));
    }

    @Test
    void oversizeBodyRejected() {
        String filler = "x".repeat(ContentJsonValidator.MAX_BODY_BYTES + 1);
        String body = "{\"type\":\"doc\",\"attrs\":{\"x\":\"" + filler + "\"}}";
        BizException ex = assertThrows(BizException.class, () -> validator.validateAndExtract(body));
        assertEquals("正文超出大小限制", ex.getMessage());
    }

    @Test
    void deepNestingRejected() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= ContentJsonValidator.MAX_DEPTH + 2; i++) {
            sb.append("{\"type\":\"blockquote\",\"content\":[");
        }
        sb.append("{\"type\":\"text\",\"text\":\"deep\"}");
        for (int i = 0; i <= ContentJsonValidator.MAX_DEPTH + 2; i++) {
            sb.append("]}");
        }
        BizException ex = assertThrows(BizException.class, () -> validator.validateAndExtract(sb.toString()));
        assertEquals("正文嵌套过深", ex.getMessage());
    }

    @Test
    void disallowedNodeTypeRejected() {
        String body = "{\"type\":\"doc\",\"content\":[{\"type\":\"iframe\",\"content\":[]}]}";
        BizException ex = assertThrows(BizException.class, () -> validator.validateAndExtract(body));
        assertEquals("正文包含不支持的节点类型", ex.getMessage());
    }

    @Test
    void disallowedAttributeRejected() {
        String body = "{\"type\":\"doc\",\"content\":[{\"type\":\"image\","
                + "\"attrs\":{\"src\":\"a.png\",\"onerror\":\"evil\"}}]}";
        BizException ex = assertThrows(BizException.class, () -> validator.validateAndExtract(body));
        assertEquals("正文包含不支持的属性", ex.getMessage());
    }

    @Test
    void allowedAttributesPass() {
        String body = "{\"type\":\"doc\",\"content\":[{\"type\":\"image\","
                + "\"attrs\":{\"src\":\"a.png\",\"alt\":\"图\"}},{\"type\":\"heading\","
                + "\"attrs\":{\"level\":2},\"content\":[{\"type\":\"text\",\"text\":\"标题\"}]}]}";
        String plain = validator.validateAndExtract(body);
        assertTrue(plain.contains("标题"));
    }
}
