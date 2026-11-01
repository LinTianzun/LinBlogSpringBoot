package com.linblog.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashUtilTest {

    @Test
    void sha256HexKnownVector() {
        // echo -n "abc" | sha256sum
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                HashUtil.sha256Hex("abc"));
        assertEquals(64, HashUtil.sha256Hex("").length());
        assertEquals(HashUtil.sha256Hex("linlog"), HashUtil.sha256Hex("linlog"));
        assertNotEquals(HashUtil.sha256Hex("linlog"), HashUtil.sha256Hex("linLog"));
    }

    @Test
    void randomTokenIs43CharBase64UrlAndUnique() {
        String a = HashUtil.randomToken();
        String b = HashUtil.randomToken();
        assertEquals(43, a.length());
        assertTrue(a.matches("[A-Za-z0-9_-]+"), "必须为 Base64URL 字符: " + a);
        assertNotEquals(a, b);
    }
}
