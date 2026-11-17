package com.linblog.module.ai.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AI 水印裁除：合成「白字 + 深色底板」徽标 → 命中裁除；无徽标图原样返回。 */
class AiWatermarkServiceTest {

    private final AiWatermarkService service = new AiWatermarkService();

    /** 合成右下角徽标：深色底板矩形 + 中性白文字像素（满足命中判据的极端形态） */
    private byte[] imageWithBadge(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(40, 120, 200));
        g.fillRect(0, 0, width, height);
        // 徽标底板：右下角深色圆角块（贴边，略大）
        int textHeight = Math.max(8, (int) Math.round(height * 0.0365));
        int textWidth = Math.max(16, (int) Math.round(textHeight * 3.14));
        int badgePad = textHeight / 3;
        int badgeLeft = width - textWidth - badgePad * 2;
        int badgeTop = height - textHeight - badgePad * 2;
        g.setColor(new Color(30, 30, 34));
        g.fillRect(badgeLeft, badgeTop, textWidth + badgePad * 2, textHeight + badgePad * 2);
        // 白色文字像素：铺满文字块高度（判据要求包围盒高 ≥ 0.6×textHeight）
        g.setColor(new Color(200, 200, 205));
        g.fillRect(badgeLeft + badgePad, badgeTop + badgePad,
                textWidth, textHeight);
        g.dispose();
        return toPng(image);
    }

    /** 无徽标的纯渐变图（中部亮带，不落右下角判据） */
    private byte[] imageWithoutBadge(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, Color.HSBtoRGB(x / (float) width, 0.5f, 0.9f));
            }
        }
        return toPng(image);
    }

    private byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void badgeImageGetsCropped() throws IOException {
        int width = 800;
        int height = 520;
        byte[] original = imageWithBadge(width, height);

        AiWatermarkService.Watermarked result = service.removeWatermark(original, "image/png", true, "crop");

        assertTrue(result.removed(), "命中徽标应裁除");
        BufferedImage cropped = ImageIO.read(new java.io.ByteArrayInputStream(result.data()));
        assertTrue(cropped.getWidth() < width, "crop 模式宽度应变小");
        assertTrue(cropped.getHeight() < height, "crop 模式高度应变小");
    }

    @Test
    void cropResizeKeepsOriginalSize() throws IOException {
        int width = 800;
        int height = 520;
        byte[] original = imageWithBadge(width, height);

        AiWatermarkService.Watermarked result = service.removeWatermark(original, "image/png", true, "crop-resize");

        assertTrue(result.removed());
        BufferedImage resized = ImageIO.read(new java.io.ByteArrayInputStream(result.data()));
        assertEquals(width, resized.getWidth());
        assertEquals(height, resized.getHeight());
    }

    @Test
    void imageWithoutBadgeKeptAsIs() throws IOException {
        byte[] original = imageWithoutBadge(800, 520);

        AiWatermarkService.Watermarked result = service.removeWatermark(original, "image/png", true, "crop");

        assertFalse(result.removed());
        assertNotEquals(0, result.data().length);
    }

    @Test
    void disabledOrUnsupportedSkipsProcessing() throws IOException {
        byte[] original = imageWithBadge(800, 520);
        assertFalse(service.removeWatermark(original, "image/png", false, "crop").removed());
        // webp 不被 Java ImageIO 支持 → 原样返回（降级为不去标）
        assertFalse(service.removeWatermark(original, "image/webp", true, "crop").removed());
    }
}
