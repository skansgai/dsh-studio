package com.deepseek.dshstudio.ui;

import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.ImageIcon;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 可绘制背景图的 Panel：有背景图时按 cover 方式铺满并轻微压暗，否则用普通背景色。
 */
public final class ImageBackdropPanel extends JPanel {

    @Nullable
    private Image image;
    private final float overlayAlpha;

    public ImageBackdropPanel() {
        this(0.0f);
    }

    public ImageBackdropPanel(float overlayAlpha) {
        super();
        this.overlayAlpha = overlayAlpha;
        setOpaque(false);
    }

    /** 设置背景图（绝对路径）。路径无效或无法加载则清空背景图。 */
    public void setBackgroundImage(@Nullable String path) {
        Image loaded = null;
        if (path != null && !path.trim().isEmpty()) {
            File f = new File(path.trim());
            if (f.isFile()) {
                ImageIcon icon = new ImageIcon(f.getAbsolutePath());
                if (icon.getIconWidth() > 0) {
                    loaded = toImage(icon.getImage());
                }
            }
        }
        this.image = loaded;
        repaint();
    }

    private static Image toImage(Image src) {
        int w = Math.max(1, src.getWidth(null));
        int h = Math.max(1, src.getHeight(null));
        // 限制到合理尺寸，避免超大图占满内存
        int maxDim = 2048;
        double scale = Math.min(1.0, (double) maxDim / Math.max(w, h));
        int tw = (int) Math.round(w * scale);
        int th = (int) Math.round(h * scale);
        BufferedImage bi = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, tw, th, null);
        g.dispose();
        return bi;
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (image != null) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            // cover 方式：缩放图片铺满，裁掉溢出部分
            int pw = getWidth();
            int ph = getHeight();
            if (pw > 0 && ph > 0) {
                int iw = image.getWidth(null);
                int ih = image.getHeight(null);
                if (iw > 0 && ih > 0) {
                    double scale = Math.max((double) pw / iw, (double) ph / ih);
                    int dw = (int) Math.ceil(iw * scale);
                    int dh = (int) Math.ceil(ih * scale);
                    int dx = (pw - dw) / 2;
                    int dy = (ph - dh) / 2;
                    g2.drawImage(image, dx, dy, dw, dh, null);
                    if (overlayAlpha > 0) {
                        Color dim = new Color(0, 0, 0, (int) (255 * Math.min(1f, overlayAlpha)));
                        g2.setColor(dim);
                        g2.fillRect(0, 0, pw, ph);
                    }
                }
            }
            g2.dispose();
        } else {
            super.paintComponent(g);
        }
    }
}
