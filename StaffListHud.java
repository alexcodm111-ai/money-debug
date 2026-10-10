package com.moneydebug.hud;

import com.moneydebug.MoneyDebug;
import com.moneydebug.modules.StaffList;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import java.util.List;

public class StaffListHud extends HudElement {
    public static final HudElementInfo<StaffListHud> INFO =
        new HudElementInfo<>(MoneyDebug.HUD_GROUP, "staff-list", "Shows DonutSMP staff online.", StaffListHud::new);

    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<SettingColor> background = sg.add(new ColorSetting.Builder().name("background").defaultValue(new SettingColor(12, 12, 16, 170)).build());
    private final Setting<SettingColor> accent = sg.add(new ColorSetting.Builder().name("accent").defaultValue(new SettingColor(60, 220, 90, 255)).build());
    private final Setting<Boolean> shadow = sg.add(new BoolSetting.Builder().name("text-shadow").defaultValue(true).build());
    private final Setting<Boolean> hideWhenEmpty = sg.add(new BoolSetting.Builder().name("hide-when-empty").description("Only show the header when no staff are in TAB.").defaultValue(false).build());
    private final Setting<Integer> minWidth = sg.add(new IntSetting.Builder().name("min-width").defaultValue(130).range(60, 400).sliderRange(60, 300).build());

    private static final Color ONLINE = new Color(90, 255, 120);
    private static final Color SPEC = new Color(255, 180, 40);
    private static final Color VANISH = new Color(255, 70, 70);
    private static final Color MUTED = new Color(170, 170, 180);
    private static final Color WHITE = new Color(240, 240, 245);

    public StaffListHud() { super(INFO); }

    @Override
    public void render(HudRenderer r) {
        StaffList module = Modules.get().get(StaffList.class);
        if (!module.isActive() && !isInEditor()) module.toggle(); // HUD needs the tracker running
        boolean sh = shadow.get();
        List<StaffList.Staff> staff = module.isActive() ? module.getStaff() : List.of();
        double th = r.textHeight(sh), rowH = th + Panel.ROW_GAP + 2;

        // measure
        String title = "STAFF";
        String count = String.valueOf(staff.size());
        String empty = module.isActive() ? "none in TAB  ·  " + module.tabSize() + " players" : "module off";
        double w = Math.max(minWidth.get(), r.textWidth(title, sh) + r.textWidth(count, sh) + 30);
        if (staff.isEmpty() && !hideWhenEmpty.get()) w = Math.max(w, r.textWidth(empty, sh) + Panel.PAD * 2 + 4);
        for (StaffList.Staff s : staff) w = Math.max(w, rowWidth(r, s, sh) + Panel.PAD * 2 + 10);

        double headerH = Panel.ACCENT_H + Panel.PAD + th + Panel.PAD;
        double h = headerH + (staff.isEmpty() ? (hideWhenEmpty.get() ? 0 : rowH + Panel.PAD) : staff.size() * rowH + Panel.PAD);
        setSize(w, h);

        Color acc = accent.get();
        boolean alarm = staff.stream().anyMatch(s -> s.nearby || s.presence != StaffList.Presence.ONLINE);
        if (alarm) acc = Panel.lerp(acc, VANISH, 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 180.0));
        Panel.frame(r, x, y, w, h, background.get(), acc);

        // header
        double hy = y + Panel.ACCENT_H + Panel.PAD;
        r.text(title, x + Panel.PAD + 2, hy, acc, sh);
        double cw = r.textWidth(count, sh) + 6;
        Panel.pill(r, count, x + w - Panel.PAD - cw, hy, staff.isEmpty() ? MUTED : (alarm ? VANISH : ONLINE), sh);
        r.quad(x + Panel.PAD, y + headerH - 2, w - Panel.PAD * 2, 1, Panel.dim(MUTED, 50));

        double ry = y + headerH + 2;
        if (staff.isEmpty()) {
            if (!hideWhenEmpty.get()) r.text(empty, x + Panel.PAD + 2, ry, MUTED, sh);
            return;
        }

        for (StaffList.Staff s : staff) {
            Color c = s.nearby ? VANISH : switch (s.presence) { case ONLINE -> ONLINE; case SPECTATOR -> SPEC; case VANISHED -> VANISH; };
            Panel.dot(r, x + Panel.PAD + 2, ry + th / 2 - 2, 4, c);
            r.text(s.name, x + Panel.PAD + 10, ry, WHITE, sh);

            // right side: tag + distance
            double rx = x + w - Panel.PAD;
            if (s.nearby) {
                String d = Math.round(s.distance) + "m";
                rx -= r.textWidth(d, sh);
                r.text(d, rx, ry, VANISH, sh);
                rx -= 4;
            }
            String tag = tag(s);
            if (tag != null) {
                double tw = r.textWidth(tag, sh) + 6;
                rx -= tw;
                Panel.pill(r, tag, rx, ry, c, sh);
            }
            ry += rowH;
        }
    }

    private static String tag(StaffList.Staff s) {
        return switch (s.presence) { case ONLINE -> s.nearby ? "NEAR" : null; case SPECTATOR -> "SPEC"; case VANISHED -> "VANISH"; };
    }

    private static double rowWidth(HudRenderer r, StaffList.Staff s, boolean sh) {
        double w = r.textWidth(s.name, sh);
        String tag = tag(s);
        if (tag != null) w += r.textWidth(tag, sh) + 12;
        if (s.nearby) w += r.textWidth("999m", sh) + 4;
        return w;
    }
}
