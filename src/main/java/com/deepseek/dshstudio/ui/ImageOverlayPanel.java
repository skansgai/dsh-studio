package com.deepseek.dshstudio.ui;

import org.jetbrains.annotations.Nullable;

import javax.swing.ImageIcon;
import javax.swing.JPanel;
import java.awt.AlphaComposite;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 半透明覆盖层：把一张背景图按给定不透明度画在组件上（叠加在网页之上）。
 * 能否盖住原生 JCEF 窗口取决于渲染模式（实验性）。
 */
public final class ImageOverlayPanel extends JPanel {

    @Nullable
    private Image image;
    private float alpha = 0.4f;

    public ImageOverlayPanel() {
        super();
        setOpaque(false);
    }

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

    /** @param alpha 0.0（完全透明）~ 1.0（完全不透明）。 */
    public void setAlpha(float alpha) {
        this.alpha = Math.max(0f, Math.min(1f, alpha));
        repaint();
    }

    private static Image toImage(Image src) {
        int w = Math.max(1, src.getWidth(null));
        int h = Math.max(1, src.getHeight(null));
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
        if (image == null) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
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
            }
        }
        g2.dispose();
    }
}
