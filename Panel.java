package com.moneydebug.hud;

import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

/** Shared drawing helpers for the Money Debug panels. */
final class Panel {
    static final double PAD = 5, ROW_GAP = 2, ACCENT_H = 2;

    private Panel() {}

    /** Background with an accent strip on top and a thin accent line on the left. */
    static void frame(HudRenderer r, double x, double y, double w, double h, Color bg, Color accent) {
        r.quad(x, y, w, h, bg);
        r.quad(x, y, w, ACCENT_H, accent);
        r.quad(x, y + ACCENT_H, 1, h - ACCENT_H, dim(accent, 120));
    }

    /** Small filled square used as a status dot. */
    static void dot(HudRenderer r, double x, double y, double size, Color c) {
        r.quad(x, y, size, size, c);
    }

    /** Text "pill" tag with a tinted background. Returns its width. */
    static double pill(HudRenderer r, String text, double x, double y, Color c, boolean shadow) {
        double w = r.textWidth(text, shadow) + 6, h = r.textHeight(shadow) + 1;
        r.quad(x, y - 1, w, h, dim(c, 70));
        r.text(text, x + 3, y, c, shadow);
        return w;
    }

    static Color dim(Color c, int alpha) {
        return new Color(c.r, c.g, c.b, alpha);
    }

    static Color lerp(Color a, Color b, double t) {
        t = Math.max(0, Math.min(1, t));
        return new Color((int) (a.r + (b.r - a.r) * t), (int) (a.g + (b.g - a.g) * t), (int) (a.b + (b.b - a.b) * t), (int) (a.a + (b.a - a.a) * t));
    }

    /** green -> yellow -> red as t goes 0 -> 1 */
    static Color heat(double t) {
        Color g = new Color(90, 255, 120), y = new Color(255, 215, 60), red = new Color(255, 70, 70);
        return t < 0.5 ? lerp(g, y, t * 2) : lerp(y, red, (t - 0.5) * 2);
    }
}
