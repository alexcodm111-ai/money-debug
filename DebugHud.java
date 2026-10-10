package com.moneydebug.hud;

import com.moneydebug.MoneyDebug;
import com.moneydebug.modules.StaffList;
import com.moneydebug.modules.SusChunkFinder;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.decoration.EndCrystalEntity;

import java.util.ArrayList;
import java.util.List;

/** Money Debug info panel: label / value rows with colored values. */
public class DebugHud extends HudElement {
    public static final HudElementInfo<DebugHud> INFO =
        new HudElementInfo<>(MoneyDebug.HUD_GROUP, "money-debug", "Compact debug info.", DebugHud::new);

    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<SettingColor> background = sg.add(new ColorSetting.Builder().name("background").defaultValue(new SettingColor(12, 12, 16, 170)).build());
    private final Setting<SettingColor> accent = sg.add(new ColorSetting.Builder().name("accent").defaultValue(new SettingColor(60, 220, 90, 255)).build());
    private final Setting<Boolean> shadow = sg.add(new BoolSetting.Builder().name("text-shadow").defaultValue(true).build());
    private final Setting<Boolean> showCoords = sg.add(new BoolSetting.Builder().name("coords").defaultValue(true).build());
    private final Setting<Boolean> showCrystals = sg.add(new BoolSetting.Builder().name("crystals").defaultValue(true).build());
    private final Setting<Boolean> showModules = sg.add(new BoolSetting.Builder().name("module-status").defaultValue(true).build());

    private static final Color LABEL = new Color(165, 165, 175);
    private static final Color VALUE = new Color(240, 240, 245);
    private static final Color ON = new Color(90, 255, 120);
    private static final Color OFF = new Color(120, 120, 130);

    private record Row(String label, String value, Color color) {}

    public DebugHud() { super(INFO); }

    @Override
    public void render(HudRenderer r) {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean sh = shadow.get();
        List<Row> rows = new ArrayList<>();

        if (mc.player != null && mc.world != null) {
            var p = mc.player;
            int fps = mc.getCurrentFps();
            var entry = mc.getNetworkHandler() != null ? mc.getNetworkHandler().getPlayerListEntry(p.getUuid()) : null;
            int ping = entry != null ? entry.getLatency() : 0;
            rows.add(new Row("FPS", String.valueOf(fps), Panel.heat(1 - Math.min(fps, 144) / 144.0)));
            rows.add(new Row("Ping", ping + "ms", Panel.heat(Math.min(ping, 300) / 300.0)));
            if (showCoords.get()) {
                rows.add(new Row("XYZ", String.format("%.0f, %.0f, %.0f", p.getX(), p.getY(), p.getZ()), VALUE));
                rows.add(new Row("Chunk", p.getChunkPos().x + ", " + p.getChunkPos().z, VALUE));
                rows.add(new Row("Facing", cap(p.getHorizontalFacing().asString()), VALUE));
            }
            if (showCrystals.get()) {
                long crystals = 0;
                for (var e : mc.world.getEntities()) if (e instanceof EndCrystalEntity && e.distanceTo(p) < 12) crystals++;
                rows.add(new Row("Crystals", String.valueOf(crystals), crystals > 0 ? new Color(200, 120, 255) : VALUE));
                rows.add(new Row("Entities", String.valueOf(mc.world.getRegularEntityCount()), VALUE));
            }
            if (showModules.get()) {
                SusChunkFinder f = Modules.get().get(SusChunkFinder.class);
                StaffList s = Modules.get().get(StaffList.class);
                rows.add(new Row("Sus chunks", f.isActive() ? f.getInfoString() : "off", f.isActive() ? (f.getInfoString().equals("0") ? VALUE : new Color(200, 120, 255)) : OFF));
                rows.add(new Row("Staff", s.isActive() ? String.valueOf(s.getStaff().size()) : "off", !s.isActive() ? OFF : s.getStaff().isEmpty() ? ON : new Color(255, 70, 70)));
            }
        } else rows.add(new Row("Status", "not in world", OFF));

        double th = r.textHeight(sh), rowH = th + Panel.ROW_GAP;
        double labelW = 0, valueW = 0;
        for (Row row : rows) { labelW = Math.max(labelW, r.textWidth(row.label(), sh)); valueW = Math.max(valueW, r.textWidth(row.value(), sh)); }
        String title = "MONEY DEBUG";
        double w = Math.max(r.textWidth(title, sh) + 20, labelW + valueW + 18) + Panel.PAD * 2;
        double headerH = Panel.ACCENT_H + Panel.PAD + th + Panel.PAD;
        double h = headerH + rows.size() * rowH + Panel.PAD + 2;
        setSize(w, h);

        Color acc = accent.get();
        Panel.frame(r, x, y, w, h, background.get(), acc);
        double hy = y + Panel.ACCENT_H + Panel.PAD;
        Panel.dot(r, x + Panel.PAD + 2, hy + th / 2 - 2, 4, acc);
        r.text(title, x + Panel.PAD + 10, hy, acc, sh);
        r.quad(x + Panel.PAD, y + headerH - 2, w - Panel.PAD * 2, 1, Panel.dim(LABEL, 50));

        double ry = y + headerH + 2;
        for (Row row : rows) {
            r.text(row.label(), x + Panel.PAD + 2, ry, LABEL, sh);
            r.text(row.value(), x + w - Panel.PAD - r.textWidth(row.value(), sh), ry, row.color(), sh);
            ry += rowH;
        }
    }

    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
