package com.moneydebug.modules;

import com.mojang.brigadier.suggestion.Suggestion;
import com.moneydebug.MoneyDebug;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.RequestCommandCompletionsC2SPacket;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.TranslatableTextContent;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Detects staff going into vanish on DonutSMP:
 *  - TAB hide: staff entry flipped to unlisted
 *  - Silent TAB removal: staff removed from TAB with no leave message, or their body is still nearby
 *  - Completion probe: /msg tab-completion still offers a staff name that is not in TAB
 *  - Invisible players: staff entities near you with the invisible flag
 */
public class AntiVanish extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Boolean> completionProbe = sg.add(new BoolSetting.Builder()
        .name("completion-probe").description("Asks the server for /msg completions every 5s to find hidden staff.").defaultValue(true).build());
    private final Setting<Boolean> invisCheck = sg.add(new BoolSetting.Builder().name("invisible-check").defaultValue(true).build());
    private final Setting<Boolean> sound = sg.add(new BoolSetting.Builder().name("alert-sound").defaultValue(true).build());

    private record Pending(UUID id, String name, int due) {}
    private record Message(String text, long at) {}

    private final Queue<Runnable> inbound = new ConcurrentLinkedQueue<>();
    private final Map<UUID, String> known = new HashMap<>();
    private final Map<UUID, Long> listedSince = new HashMap<>();
    private final Deque<Pending> pending = new ArrayDeque<>();
    private final Deque<Message> recent = new ArrayDeque<>();
    private final Map<String, Long> cooldown = new HashMap<>();
    private final Set<Integer> probeIds = new HashSet<>();
    private boolean serverSendsLeaveMessages;
    private int tick, nextProbeId = 30000;

    public AntiVanish() {
        super(MoneyDebug.CATEGORY, "anti-vanish", "Detects DonutSMP staff going into vanish.");
    }

    @Override
    public void onActivate() {
        inbound.clear(); known.clear(); listedSince.clear(); pending.clear(); recent.clear(); cooldown.clear(); probeIds.clear();
        serverSendsLeaveMessages = false; tick = 0;
    }

    private boolean isStaff(String name) {
        return Modules.get().get(StaffList.class).isStaffName(name);
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerRemoveS2CPacket p) {
            if (p.profileIds().size() < 4) for (UUID id : p.profileIds()) inbound.add(() -> onTabRemove(id));
        } else if (event.packet instanceof PlayerListS2CPacket p && p.getActions().contains(PlayerListS2CPacket.Action.UPDATE_LISTED)) {
            List<UUID> hidden = new ArrayList<>();
            for (PlayerListS2CPacket.Entry e : p.getEntries()) if (e != null && !e.listed()) hidden.add(e.profileId());
            if (!hidden.isEmpty() && hidden.size() < 4) for (UUID id : hidden) inbound.add(() -> onTabHide(id));
        } else if (event.packet instanceof GameMessageS2CPacket p && p.content() != null) {
            String text = p.content().getString();
            if (p.content().getContent() instanceof TranslatableTextContent t && "multiplayer.player.left".equals(t.getKey())) serverSendsLeaveMessages = true;
            inbound.add(() -> recent.addLast(new Message(text, System.currentTimeMillis())));
        } else if (event.packet instanceof CommandSuggestionsS2CPacket p && probeIds.remove(p.id())) {
            List<String> names = new ArrayList<>();
            for (Suggestion s : p.getSuggestions().getList()) if (s.getText() != null) names.add(s.getText().trim());
            inbound.add(() -> onProbe(names));
            event.cancel();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.getNetworkHandler() == null || mc.player == null || mc.world == null) return;
        tick++;
        long now = System.currentTimeMillis();
        Runnable r;
        while ((r = inbound.poll()) != null) r.run();
        while (!recent.isEmpty() && (now - recent.peekFirst().at() > 8000 || recent.size() > 64)) recent.removeFirst();

        if (tick % 10 == 0) {
            for (PlayerListEntry e : mc.getNetworkHandler().getListedPlayerListEntries()) {
                UUID id = e.getProfile().id();
                if (id == null || id.equals(mc.player.getUuid())) continue;
                listedSince.putIfAbsent(id, now);
                known.put(id, e.getProfile().name());
            }
        }

        while (!pending.isEmpty() && pending.peekFirst().due() <= tick) {
            Pending p = pending.removeFirst();
            if (mc.getNetworkHandler().getPlayerListEntry(p.id()) != null) continue;
            if (leaveMessageFor(p.name())) { serverSendsLeaveMessages = true; continue; }
            PlayerEntity body = mc.world.getPlayerByUuid(p.id());
            if (body != null && !body.isRemoved()) report(p.name(), "vanished (off TAB, still nearby)");
            else if (serverSendsLeaveMessages) report(p.name(), "vanished (left TAB with no leave message)");
            else report(p.name(), "possibly vanished (silent TAB removal)");
        }

        if (completionProbe.get() && tick % 100 == 0) {
            int id = nextProbeId++;
            if (nextProbeId > 40000) nextProbeId = 30000;
            probeIds.add(id);
            if (probeIds.size() > 8) probeIds.remove(probeIds.iterator().next());
            mc.getNetworkHandler().sendPacket(new RequestCommandCompletionsC2SPacket(id, "msg "));
        }

        if (invisCheck.get() && tick % 5 == 0) {
            for (PlayerEntity p : mc.world.getPlayers()) {
                if (p != mc.player && p.isInvisible() && isStaff(p.getGameProfile().name()))
                    report(p.getGameProfile().name(), "is invisible near you (" + Math.round(mc.player.distanceTo(p)) + "m)");
            }
        }
    }

    private boolean trusted(UUID id) {
        Long since = listedSince.get(id);
        return since != null && System.currentTimeMillis() - since >= 1500;
    }

    private void onTabRemove(UUID id) {
        String name = known.get(id);
        if (name == null || !isStaff(name) || !trusted(id)) return;
        pending.removeIf(p -> p.id().equals(id));
        pending.addLast(new Pending(id, name, tick + 20));
    }

    private void onTabHide(UUID id) {
        String name = known.get(id);
        if (name == null && mc.getNetworkHandler() != null) {
            PlayerListEntry e = mc.getNetworkHandler().getPlayerListEntry(id);
            if (e != null) name = e.getProfile().name();
        }
        if (name == null || !isStaff(name) || !trusted(id) || leaveMessageFor(name)) return;
        report(name, "vanished (hidden from TAB)");
    }

    private void onProbe(List<String> names) {
        if (mc.getNetworkHandler() == null || mc.player == null) return;
        Set<String> tab = new HashSet<>();
        for (PlayerListEntry e : mc.getNetworkHandler().getListedPlayerListEntries()) tab.add(e.getProfile().name().toLowerCase(Locale.ROOT));
        String self = mc.player.getGameProfile().name();
        List<String> hidden = new ArrayList<>();
        for (String n : names) {
            if (isStaff(n) && !n.equalsIgnoreCase(self) && !tab.contains(n.toLowerCase(Locale.ROOT)) && !leaveMessageFor(n)) hidden.add(n);
        }
        if (hidden.size() <= 3) for (String n : hidden) report(n, "is hidden but still targetable (vanished)");
    }

    private boolean leaveMessageFor(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        for (Message m : recent) {
            String t = m.text().toLowerCase(Locale.ROOT);
            if (now - m.at() <= 8000 && t.contains(lower) && (t.contains("left") || t.contains("quit") || t.contains("disconnect") || t.contains("[-]") || t.startsWith("-"))) return true;
        }
        return false;
    }

    private void report(String name, String what) {
        long now = System.currentTimeMillis();
        String key = name.toLowerCase(Locale.ROOT) + what;
        Long last = cooldown.get(key);
        if (last != null && now - last < 60_000) return;
        cooldown.put(key, now);
        ChatUtils.warning("(highlight)[Anti-Vanish] %s(default) %s", name, what);
        if (sound.get() && mc.player != null) mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1f, 1.5f);
    }
}
