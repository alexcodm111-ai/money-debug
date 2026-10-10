package com.moneydebug.modules;

import com.moneydebug.MoneyDebug;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.GameMode;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

/**
 * Tracks DonutSMP staff. Staff that the server still knows about but hides from TAB
 * (unlisted entries) are shown as VANISHED; staff in spectator as SPECTATOR.
 * Game-mode packets for staff mean they are in your region; bodies nearby are tracked with distance.
 */
public class StaffList extends Module {
    public static final List<String> DONUT_STAFF = List.of(
        "FluffyMaster07", "archivePedro", "Munkerlich", "Frenk_Btw", "Napooo_", "auzzitech",
        "CryptoDaveYT", "W1zox_", "ZEEF69", "showered", "CaptainMoose35", "BobIsFound",
        "NoahvdAa", "0GSummer", "LzouzMP5", "Pastagamer08", "u_vv", "Owen1212055",
        "Splaterd", "Fallerfly", "Bloodspulse"
    );
    private static final Pattern ACCOUNT_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final SettingGroup sg = settings.getDefaultGroup();
    public final Setting<List<String>> staffNames = sg.add(new StringListSetting.Builder()
        .name("staff-names").description("DonutSMP staff usernames.").defaultValue(new ArrayList<>(DONUT_STAFF)).build());
    public final Setting<List<String>> rankKeywords = sg.add(new StringListSetting.Builder()
        .name("rank-keywords").description("Also count players whose TAB name or team prefix contains one of these ranks.")
        .defaultValue(new ArrayList<>(List.of("owner", "co-owner", "manager", "admin", "developer", "sr-mod", "senior-mod", "moderator", "mod", "trial-mod", "sr-helper", "helper", "trial-helper", "staff", "support"))).build());
    private final Setting<Boolean> chatAlerts = sg.add(new BoolSetting.Builder().name("chat-alerts").defaultValue(true).build());
    private final Setting<Boolean> sound = sg.add(new BoolSetting.Builder().name("alert-sound").defaultValue(true).build());
    private final Setting<Boolean> regionAlert = sg.add(new BoolSetting.Builder()
        .name("region-alert").description("Alert when staff game-mode updates reach you (they are in your region).").defaultValue(true).build());

    public enum Presence { ONLINE, SPECTATOR, VANISHED }

    public static final class Staff {
        public final UUID id;
        public String name;
        public Presence presence = Presence.ONLINE;
        public boolean nearby;
        public float distance;
        public long watchingSince;
        int offTabScans;
        Staff(UUID id, String name) { this.id = id; this.name = name; }
    }

    private final Map<UUID, Staff> staff = new LinkedHashMap<>();
    private final Queue<UUID> switches = new ConcurrentLinkedQueue<>();
    private final Map<UUID, Long> lastAlert = new HashMap<>();
    private List<Staff> sorted = List.of();
    private ClientPlayNetworkHandler connection;
    private int settle, ticks;

    public StaffList() {
        super(MoneyDebug.CATEGORY, "staff-list", "Lists online DonutSMP staff, including vanished and spectating staff.");
    }

    @Override
    public void onActivate() { reset(); }

    @Override
    public void onDeactivate() { reset(); }

    private void reset() {
        staff.clear(); switches.clear(); lastAlert.clear(); sorted = List.of(); connection = null; settle = 60;
    }

    public boolean isStaffName(String name) {
        if (name == null) return false;
        for (String s : staffNames.get()) if (s.equalsIgnoreCase(name)) return true;
        return false;
    }

    public List<Staff> getStaff() { return sorted; }

    public int tabSize() { return mc.getNetworkHandler() == null ? 0 : mc.getNetworkHandler().getPlayerList().size(); }

    private static final Pattern WORD = Pattern.compile("[^a-z-]+");

    private boolean hasStaffRank(PlayerListEntry e) {
        StringBuilder sb = new StringBuilder();
        if (e.getDisplayName() != null) sb.append(e.getDisplayName().getString()).append(' ');
        var team = e.getScoreboardTeam();
        if (team != null) sb.append(team.getPrefix().getString()).append(' ').append(team.getSuffix().getString()).append(' ').append(team.getName());
        String name = e.getProfile().name().toLowerCase(Locale.ROOT);
        for (String w : WORD.split(sb.toString().toLowerCase(Locale.ROOT))) {
            if (w.isEmpty() || w.equals(name)) continue;
            for (String k : rankKeywords.get()) if (w.equals(k.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (!regionAlert.get() || !(event.packet instanceof PlayerListS2CPacket p)) return;
        EnumSet<PlayerListS2CPacket.Action> actions = p.getActions();
        if (actions.contains(PlayerListS2CPacket.Action.UPDATE_GAME_MODE) && !actions.contains(PlayerListS2CPacket.Action.ADD_PLAYER)) {
            for (PlayerListS2CPacket.Entry e : p.getEntries()) if (e != null && e.profileId() != null) switches.offer(e.profileId());
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        ClientPlayNetworkHandler conn = mc.getNetworkHandler();
        if (conn == null || mc.player == null || mc.world == null) { reset(); return; }
        if (conn != connection) { reset(); connection = conn; }
        if (settle > 0) settle--;
        long now = System.currentTimeMillis();

        UUID id;
        while ((id = switches.poll()) != null) {
            Staff s = staff.get(id);
            if (s != null && !id.equals(mc.player.getUuid())) alert(s, "is in your region (game mode update)", now);
        }

        // nearby bodies
        for (Staff s : staff.values()) s.nearby = false;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            Staff s = staff.get(p.getUuid());
            if (s == null) continue;
            s.distance = mc.player.distanceTo(p);
            if (!s.nearby && s.watchingSince == 0) { s.watchingSince = now; alert(s, "is near you (" + Math.round(s.distance) + "m)", now); }
            s.nearby = true;
        }
        for (Staff s : staff.values()) if (!s.nearby) s.watchingSince = 0;

        if (++ticks % 10 == 0) scan(conn);
    }

    private void scan(ClientPlayNetworkHandler conn) {
        Set<UUID> listed = new HashSet<>();
        for (PlayerListEntry e : conn.getListedPlayerListEntries()) listed.add(e.getProfile().id());

        Set<UUID> inTab = new HashSet<>();
        for (PlayerListEntry e : conn.getPlayerList()) {
            UUID id = e.getProfile().id();
            String name = e.getProfile().name();
            if (id == null || id.equals(mc.player.getUuid()) || id.version() == 2 || name == null || !ACCOUNT_NAME.matcher(name).matches()) continue;
            if (!isStaffName(name) && !hasStaffRank(e)) continue;
            inTab.add(id);
            Presence pres = e.getGameMode() == GameMode.SPECTATOR ? Presence.SPECTATOR
                : listed.contains(id) ? Presence.ONLINE : Presence.VANISHED;
            Staff s = staff.get(id);
            if (s == null) {
                s = new Staff(id, name);
                s.presence = pres;
                staff.put(id, s);
                if (settle == 0) announce(s.name + " joined" + note(pres), pres != Presence.ONLINE);
            } else {
                Presence before = s.presence;
                s.name = name; s.presence = pres; s.offTabScans = 0;
                if (before != pres && settle == 0) announce(s.name + " " + switch (pres) {
                    case ONLINE -> "is visible again";
                    case SPECTATOR -> "switched to spectator";
                    case VANISHED -> "vanished (hidden from TAB)";
                }, pres != Presence.ONLINE);
            }
        }

        Iterator<Staff> it = staff.values().iterator();
        while (it.hasNext()) {
            Staff s = it.next();
            if (inTab.contains(s.id)) continue;
            if (mc.world.getPlayerByUuid(s.id) != null) {
                if (++s.offTabScans >= 2 && s.presence != Presence.VANISHED) {
                    s.presence = Presence.VANISHED;
                    announce(s.name + " vanished (off TAB, still nearby)", true);
                }
            } else {
                it.remove();
                if (settle == 0) announce(s.name + (s.presence == Presence.VANISHED ? " is out of sight" : " left"), false);
            }
        }

        List<Staff> view = new ArrayList<>(staff.values());
        view.sort(Comparator.<Staff>comparingInt(s -> s.nearby ? 0 : s.presence != Presence.ONLINE ? 1 : 2)
            .thenComparing(s -> s.name, String.CASE_INSENSITIVE_ORDER));
        sorted = List.copyOf(view);
    }

    private static String note(Presence p) {
        return switch (p) { case ONLINE -> ""; case SPECTATOR -> " (spectating)"; case VANISHED -> " (vanished)"; };
    }

    private void alert(Staff s, String what, long now) {
        Long last = lastAlert.get(s.id);
        if (last != null && now - last < 120_000L) return;
        lastAlert.put(s.id, now);
        announce(s.name + " " + what, true);
    }

    private void announce(String msg, boolean urgent) {
        if (chatAlerts.get()) {
            if (urgent) ChatUtils.warning("(highlight)%s", msg); else ChatUtils.info("%s", msg);
        }
        if (urgent && sound.get() && mc.player != null) mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1f, 1.25f);
    }

    @Override
    public String getInfoString() { return String.valueOf(staff.size()); }
}
