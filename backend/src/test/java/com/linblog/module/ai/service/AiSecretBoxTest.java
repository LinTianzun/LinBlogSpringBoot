package com.linblog.module.ai.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AI 密钥保险箱：GCM 往返、掩码、密钥轮换后旧密文失效。 */
class AiSecretBoxTest {

    @Test
    void encryptDecryptRoundTrip() {
        AiSecretBox box = new AiSecretBox("secret-0123456789abcdef-secret-0123456789abcdef");
        String cipher = box.encrypt("sk-test-abc123");
        assertTrue(cipher.startsWith("v1:"));
        assertEquals("sk-test-abc123", box.decrypt(cipher));
    }

    @Test
    void sameSecretSamePrefixDifferentCipherText() {
        AiSecretBox box = new AiSecretBox("secret-0123456789abcdef-secret-0123456789abcdef");
        assertNotEquals(box.encrypt("same"), box.encrypt("same")); // 随机 IV
    }

    @Test
    void rotatedSecretInvalidatesOldCipher() {
        AiSecretBox boxA = new AiSecretBox("secret-A-0123456789abcdef-secret-0123456789");
        AiSecretBox boxB = new AiSecretBox("secret-B-0123456789abcdef-secret-0123456789");
        String cipher = boxA.encrypt("sk-test");
        assertThrows(IllegalArgumentException.class, () -> boxB.decrypt(cipher));
    }

    @Test
    void maskKeepsTailOnly() {
        AiSecretBox box = new AiSecretBox("secret");
        assertEquals("••••cdef", box.mask("abcdef"));
        assertEquals("••••", box.mask("abc"));
    }
}
