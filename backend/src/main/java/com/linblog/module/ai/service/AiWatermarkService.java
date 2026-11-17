package com.linblog.module.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * AI 成图右下角「AI生成」徽标裁除（自原版 aiWatermark.js 逐像素移植，sharp → ImageIO）。
 *
 * 徽标几何（智谱 cogview 系列实测）：白色文字 + 半透明深色圆角底板，尺寸随图高等比缩放、
 * 始终贴右下角。先按比例算文字块理论位置，再用像素特征在容差窗口内确认——
 * 不做全图找亮斑，避免把夜景灯光误当成徽标。
 *
 * 两层处理的兜底层：请求层 watermark_enabled=false 对签了免责声明的账号生效；
 * 未签署的服务商仍会强制打标，则由本服务裁掉徽标一角。
 * 任何失败都保留原图（去水印失败不该让整次生成失败）。
 */
@Service
public class AiWatermarkService {

    private static final Logger log = LoggerFactory.getLogger(AiWatermarkService.class);

    /** 徽标文字块相对图片高度的比例（实测：88x28 文字块 / 768 高） */
    private static final double BADGE_TEXT_HEIGHT_RATIO = 0.0365;
    private static final double BADGE_TEXT_ASPECT = 3.14;
    private static final double BADGE_MARGIN_RIGHT_RATIO = 0.043;
    private static final double BADGE_MARGIN_BOTTOM_RATIO = 0.03;

    /** 文字块搜索容差（上下左右各偏移的比例）与步进 */
    private static final double SEARCH_TOLERANCE_RATIO = 0.02;
    private static final double SEARCH_STEP_RATIO = 0.01;

    /** 命中判据（文字像素：接近中性白；底板：半透明深色） */
    private static final int TEXT_LEVEL = 150;
    private static final int TEXT_SPREAD = 60;
    private static final double MIN_TEXT_RATIO = 0.08;
    private static final double MAX_TEXT_RATIO = 0.75;
    private static final double DARK_LEVEL = 90;
    private static final double MIN_DARK_RATIO = 0.12;
    private static final double MIN_ASPECT = 2;
    private static final double MAX_ASPECT = 4.6;

    /** 裁剪时相对文字高度留出的余量（确保半透明底板整块切掉） */
    private static final double CROP_PADDING_LEFT = 1;
    private static final double CROP_PADDING_TOP = 0.62;

    public record Watermarked(byte[] data, String mimeType, boolean removed) {
    }

    private static boolean isSupportedFormat(String mimeType) {
        return "image/png".equals(mimeType) || "image/jpeg".equals(mimeType);
    }

    private static boolean isTextPixel(int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return min >= TEXT_LEVEL && (max - min) <= TEXT_SPREAD;
    }

    private static boolean isDarkPixel(int r, int g, int b) {
        return (0.299 * r + 0.587 * g + 0.114 * b) <= DARK_LEVEL;
    }

    private record TextBox(int left, int top, int width, int height) {
    }

    private record Measurement(int textCount, double textRatio, double darkRatio, TextBox textBox) {
    }

    /** 统计矩形内文字像素的包围盒、数量与深色底板占比（坐标换算回整图）。 */
    private Measurement measureBox(BufferedImage image, int left, int top, int width, int height) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        int textCount = 0;
        int darkCount = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(left + x, top + y);
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                if (isDarkPixel(r, g, b)) {
                    darkCount++;
                }
                if (!isTextPixel(r, g, b)) {
                    continue;
                }
                textCount++;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }

        int total = width * height;
        TextBox textBox = maxX >= 0
                ? new TextBox(left + minX, top + minY, maxX - minX + 1, maxY - minY + 1)
                : null;
        return new Measurement(textCount,
                total > 0 ? (double) textCount / total : 0,
                total > 0 ? (double) darkCount / total : 0,
                textBox);
    }

    /** 在右下角按理论位置（带容差）寻找徽标文字块；未命中返回 null。 */
    private TextBox detectBadge(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }

        int textHeight = Math.max(8, (int) Math.round(height * BADGE_TEXT_HEIGHT_RATIO));
        int textWidth = Math.max(16, (int) Math.round(textHeight * BADGE_TEXT_ASPECT));
        int baseLeft = width - (int) Math.round(height * BADGE_MARGIN_RIGHT_RATIO) - textWidth;
        int baseTop = height - (int) Math.round(height * BADGE_MARGIN_BOTTOM_RATIO) - textHeight;

        int tolerance = Math.max(4, (int) Math.round(height * SEARCH_TOLERANCE_RATIO));
        int step = Math.max(2, (int) Math.round(height * SEARCH_STEP_RATIO));

        TextBox best = null;
        int bestTextCount = -1;
        for (int dx = -tolerance; dx <= tolerance; dx += step) {
            for (int dy = -tolerance; dy <= tolerance; dy += step) {
                int left = baseLeft + dx;
                int top = baseTop + dy;
                if (left < 0 || top < 0 || left + textWidth > width || top + textHeight > height) {
                    continue;
                }
                Measurement measured = measureBox(image, left, top, textWidth, textHeight);
                if (measured.textBox() == null) continue;
                if (measured.textRatio() < MIN_TEXT_RATIO || measured.textRatio() > MAX_TEXT_RATIO) continue;
                if (measured.darkRatio() < MIN_DARK_RATIO) continue;
                double aspect = (double) measured.textBox().width() / measured.textBox().height();
                if (aspect < MIN_ASPECT || aspect > MAX_ASPECT) continue;
                // 文字像素应铺满文字块大部分高度，否则多半只是零散高光
                if (measured.textBox().height() < textHeight * 0.6) continue;
                if (measured.textCount() > bestTextCount) {
                    best = measured.textBox();
                    bestTextCount = measured.textCount();
                }
            }
        }
        return best;
    }

    /**
     * 裁除右下角徽标。未检测到徽标时原样返回；失败保留原图。
     * mode：crop（默认，只裁右下角）/ crop-resize（裁后缩放回原尺寸，画幅轻微拉伸）。
     * Java ImageIO 默认不读 webp：webp 入参原样返回（降级为不去标）。
     */
    public Watermarked removeWatermark(byte[] data, String mimeType, boolean enabled, String mode) {
        String normalizedMode = "crop-resize".equals(mode) ? "crop-resize" : "crop";
        if (!enabled || !isSupportedFormat(mimeType)) {
            return new Watermarked(data, mimeType, false);
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image == null) {
                return new Watermarked(data, mimeType, false);
            }
            int originalWidth = image.getWidth();
            int originalHeight = image.getHeight();
            TextBox textBox = detectBadge(image);
            if (textBox == null) {
                return new Watermarked(data, mimeType, false);
            }

            // 徽标底板比文字更大：按文字高度留余量后把右下角整块裁掉
            int keepWidth = (int) Math.round(textBox.left() - textBox.height() * CROP_PADDING_LEFT);
            int keepHeight = (int) Math.round(textBox.top() - textBox.height() * CROP_PADDING_TOP);
            if (keepWidth < 64 || keepHeight < 64) {
                return new Watermarked(data, mimeType, false);
            }

            BufferedImage cropped = image.getSubimage(0, 0, keepWidth, keepHeight);
            if ("crop-resize".equals(normalizedMode)) {
                BufferedImage resized = new BufferedImage(originalWidth, originalHeight, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = resized.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(cropped, 0, 0, originalWidth, originalHeight, null);
                g.dispose();
                cropped = resized;
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String format = "image/png".equals(mimeType) ? "png" : "jpeg";
            if (!ImageIO.write(cropped, format, out) || out.size() == 0) {
                log.warn("AI 水印裁除写出失败，保留原图");
                return new Watermarked(data, mimeType, false);
            }
            return new Watermarked(out.toByteArray(), mimeType, true);
        } catch (IOException | RuntimeException e) {
            log.warn("AI 去除水印失败，保留原图: {}", e.getMessage());
            return new Watermarked(data, mimeType, false);
        }
    }
}
