package com.linblog.module.ai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AI 密钥保险箱：AES-256-GCM，密钥派生自 app.jwt-access-secret（SHA-256）。
 * 密文格式 v1:iv:tag:ct（base64url），与原版 LinBlog secretBox 同构——换 JWT 密钥即令旧密文失效。
 * 对外只暴露 encrypt/decrypt/mask；明文与完整密钥永不回传前端。
 */
@Service
public class AiSecretBox {

    private static final String PREFIX = "v1";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AiSecretBox(@Value("${app.jwt-access-secret}") String jwtAccessSecret) {
        this.key = deriveKey(jwtAccessSecret);
    }

    private static SecretKeySpec deriveKey(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(secret).getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("AI 密钥派生失败", e);
        }
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            // JDK GCM doFinal 输出 = 密文‖tag（tag 尾部 TAG_BITS/8 字节）
            byte[] ctWithTag = cipher.doFinal(String.valueOf(plain).getBytes(StandardCharsets.UTF_8));
            int tagLen = TAG_BITS / 8;
            byte[] ct = new byte[ctWithTag.length - tagLen];
            byte[] tag = new byte[tagLen];
            System.arraycopy(ctWithTag, 0, ct, 0, ct.length);
            System.arraycopy(ctWithTag, ct.length, tag, 0, tagLen);
            Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
            return PREFIX
                    + ":" + enc.encodeToString(iv)
                    + ":" + enc.encodeToString(tag)
                    + ":" + enc.encodeToString(ct);
        } catch (Exception e) {
            throw new IllegalStateException("AI 密钥加密失败", e);
        }
    }

    /** 解密；格式不符/密钥已轮换抛 IllegalArgumentException，由调用方转为可读错误。 */
    public String decrypt(String payload) {
        String[] parts = String.valueOf(payload).split(":");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            throw new IllegalArgumentException("密钥数据格式不正确");
        }
        try {
            Base64.Decoder dec = Base64.getUrlDecoder();
            byte[] iv = dec.decode(parts[1]);
            byte[] tag = dec.decode(parts[2]);
            byte[] ct = dec.decode(parts[3]);
            // GCM 解密输入 = 密文‖tag（encrypt 时两者分开存储）
            byte[] combined = new byte[ct.length + tag.length];
            System.arraycopy(ct, 0, combined, 0, ct.length);
            System.arraycopy(tag, 0, combined, ct.length, tag.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(combined), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("密钥解密失败（JWT 密钥是否已轮换？）", e);
        }
    }

    /** 掩码展示：只留尾 4 位。 */
    public String mask(String plain) {
        if (plain == null || plain.length() <= 4) {
            return "••••";
        }
        return "••••" + plain.substring(plain.length() - 4);
    }
}
