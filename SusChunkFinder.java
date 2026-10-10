package com.moneydebug.modules;

import com.moneydebug.MoneyDebug;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import it.unimi.dsi.fastutil.longs.*;
import net.minecraft.block.*;
import net.minecraft.block.entity.*;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Sus chunk finder.
 *
 * Amethyst mode (default, from Anubis): amethyst only grows in chunks that get random-ticked,
 * i.e. chunks inside a player's simulation distance. Grown clusters/buds add "heat" to every chunk
 * within the simulation radius; an ungrown bud (still on budding amethyst) vetoes the area.
 * The hottest chunk (local peak) is where the player most likely stood -> their base.
 *
 * Classic mode: storage blocks, spawners, grown kelp, rotated deepslate, player-made blocks.
 */
public class SusChunkFinder extends Module {
    public enum Method { Amethyst, Classic }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Method> method = sgGeneral.add(new EnumSetting.Builder<Method>()
        .name("method").defaultValue(Method.Amethyst).onChanged(m -> rescan()).build());
    private final Setting<Integer> spreadRadius = sgGeneral.add(new IntSetting.Builder()
        .name("simulation-distance").description("Server simulation distance in chunks (amethyst mode).")
        .defaultValue(5).range(2, 16).sliderRange(2, 16).visible(() -> method.get() == Method.Amethyst).onChanged(v -> rescan()).build());
    private final Setting<Integer> sensitivity = sgGeneral.add(new IntSetting.Builder()
        .name("sensitivity").description("Heat needed to mark a chunk. Lower = more results.")
        .defaultValue(6).range(1, 50).sliderRange(1, 20).build());
    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder().name("chat-notify").defaultValue(true).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>().name("shape-mode").defaultValue(ShapeMode.Both).build());
    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder().name("side-color").defaultValue(new SettingColor(160, 60, 255, 50)).build());
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder().name("line-color").defaultValue(new SettingColor(160, 60, 255, 255)).build());
    private final Setting<Integer> renderY = sgRender.add(new IntSetting.Builder().name("render-y").defaultValue(63).range(-64, 320).sliderRange(-64, 320).build());
    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder().name("tracers").defaultValue(true).build());

    private final Long2IntOpenHashMap heat = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap vetoes = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap chunkScore = new Long2IntOpenHashMap();
    private final Long2BooleanOpenHashMap chunkUngrown = new Long2BooleanOpenHashMap();
    private final LongOpenHashSet scanned = new LongOpenHashSet();
    private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
    private final LongOpenHashSet queued = new LongOpenHashSet();
    private final LongOpenHashSet announced = new LongOpenHashSet();
    private Long2IntOpenHashMap visible = new Long2IntOpenHashMap();
    private boolean dirty = true;
    private int age, appliedRadius;
    private ClientWorld lastWorld;

    public SusChunkFinder() {
        super(MoneyDebug.CATEGORY, "sus-chunk-finder", "Finds chunks with signs of players on DonutSMP.");
    }

    @Override
    public void onActivate() { rescan(); }

    @Override
    public void onDeactivate() { clear(); }

    private void clear() {
        heat.clear(); vetoes.clear(); chunkScore.clear(); chunkUngrown.clear(); scanned.clear();
        queue.clear(); queued.clear(); announced.clear(); visible = new Long2IntOpenHashMap(); dirty = true;
        appliedRadius = method.get() == Method.Amethyst ? spreadRadius.get() : 0;
    }

    private void rescan() {
        clear();
        if (mc.world == null || mc.player == null) return;
        lastWorld = mc.world;
        int r = mc.options.getViewDistance().getValue() + 1;
        ChunkPos c = mc.player.getChunkPos();
        for (int x = c.x - r; x <= c.x + r; x++) for (int z = c.z - r; z <= c.z + r; z++)
            if (mc.world.getChunkManager().isChunkLoaded(x, z)) enqueue(ChunkPos.toLong(x, z));
    }

    private void enqueue(long key) { if (queued.add(key)) queue.enqueue(key); }

    @EventHandler
    private void onChunk(ChunkDataEvent event) { enqueue(event.chunk().getPos().toLong()); }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        ClientWorld world = mc.world;
        if (world != lastWorld) { rescan(); return; }
        if (world == null) return;

        long deadline = System.nanoTime() + 3_000_000L;
        while (!queue.isEmpty() && System.nanoTime() < deadline) {
            long key = queue.dequeueLong();
            queued.remove(key);
            int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
            if (!world.getChunkManager().isChunkLoaded(cx, cz)) continue;
            update(key, world.getChunk(cx, cz), world);
            scanned.add(key);
            dirty = true;
        }

        if (dirty || ++age >= 20) {
            visible = computeVisible(world);
            dirty = false; age = 0;
            if (notify.get()) for (long k : visible.keySet()) if (announced.add(k))
                ChatUtils.info("Sus chunk at (highlight)%d, %d(default) heat (highlight)%d",
                    ChunkPos.getPackedX(k) * 16 + 8, ChunkPos.getPackedZ(k) * 16 + 8, visible.get(k));
        }
    }

    private void update(long key, WorldChunk chunk, ClientWorld world) {
        int r = method.get() == Method.Amethyst ? appliedRadius : 0;
        int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
        int oldHeat = chunkScore.getOrDefault(key, 0);
        if (oldHeat > 0) forArea(cx, cz, r, a -> subtract(heat, a, oldHeat));
        if (chunkUngrown.getOrDefault(key, false)) forArea(cx, cz, r, a -> subtract(vetoes, a, 1));

        int self; boolean ungrown;
        if (method.get() == Method.Amethyst) {
            int[] res = scoreAmethyst(chunk, world);
            self = res[0]; ungrown = res[1] > 0;
        } else {
            self = scoreClassic(chunk); ungrown = false;
        }
        chunkScore.put(key, self);
        chunkUngrown.put(key, ungrown);
        if (ungrown) forArea(cx, cz, r, a -> vetoes.addTo(a, 1));
        if (self > 0) forArea(cx, cz, r, a -> heat.addTo(a, self));
    }

    private static void forArea(int cx, int cz, int r, java.util.function.LongConsumer action) {
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) action.accept(ChunkPos.toLong(cx + dx, cz + dz));
    }

    private static void subtract(Long2IntOpenHashMap map, long key, int amount) {
        int left = map.get(key) - amount;
        if (left > 0) map.put(key, left); else map.remove(key);
    }

    private static boolean isBud(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.SMALL_AMETHYST_BUD || b == Blocks.MEDIUM_AMETHYST_BUD || b == Blocks.LARGE_AMETHYST_BUD;
    }

    private static boolean isGrowth(BlockState s) { return s.isOf(Blocks.AMETHYST_CLUSTER) || isBud(s); }

    /** @return {grown, ungrown} */
    private int[] scoreAmethyst(WorldChunk chunk, ClientWorld world) {
        ChunkSection[] sections = chunk.getSectionArray();
        int minSection = chunk.getBottomSectionCoord();
        ChunkPos pos = chunk.getPos();
        String brand = mc.getNetworkHandler() == null ? null : mc.getNetworkHandler().getBrand();
        boolean paletteOnly = brand != null && brand.contains("DonutFolia");
        int grown = 0, ungrown = 0;
        boolean sawBud = false, sawCluster = false;
        BlockPos.Mutable cursor = new BlockPos.Mutable();

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;
            if (paletteOnly) {
                // DonutSMP (Folia) strips detail; only the palette is reliable
                if (!sawBud && section.hasAny(SusChunkFinder::isBud)) sawBud = true;
                if (!sawCluster && section.hasAny(s -> s.isOf(Blocks.AMETHYST_CLUSTER))) sawCluster = true;
            } else if (section.hasAny(SusChunkFinder::isGrowth)) {
                int baseY = (minSection + i) << 4;
                for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                    BlockState s = section.getBlockState(x, y, z);
                    if (s.isOf(Blocks.AMETHYST_CLUSTER)) grown++;
                    else if (isBud(s) && s.contains(Properties.FACING)) {
                        Direction facing = s.get(Properties.FACING);
                        cursor.set(pos.getStartX() + x, baseY + y, pos.getStartZ() + z).move(facing.getOpposite());
                        if (world.getBlockState(cursor).isOf(Blocks.BUDDING_AMETHYST)) ungrown++; else grown++;
                    }
                }
            }
        }
        if (paletteOnly) { if (sawBud) ungrown++; else if (sawCluster) grown++; }
        return new int[]{grown, ungrown};
    }

    private int scoreClassic(WorldChunk chunk) {
        int score = 0;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof MobSpawnerBlockEntity) score += 15;
            else if (be instanceof ShulkerBoxBlockEntity || be instanceof BarrelBlockEntity || be instanceof HopperBlockEntity
                || be instanceof EnderChestBlockEntity || be instanceof AbstractFurnaceBlockEntity || be instanceof DispenserBlockEntity) score += 3;
            else if (be instanceof ChestBlockEntity) score += 1;
        }
        ChunkSection[] sections = chunk.getSectionArray();
        int minSection = chunk.getBottomSectionCoord();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;
            int baseY = (minSection + i) << 4;
            if (!section.hasAny(s -> s.isOf(Blocks.KELP) || (s.isOf(Blocks.DEEPSLATE) && baseY + 15 > 8) || isUnnatural(s.getBlock()))) continue;
            for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                BlockState s = section.getBlockState(x, y, z);
                if (s.isOf(Blocks.KELP) && s.get(Properties.AGE_25) == 25) score += 2;
                else if (s.isOf(Blocks.DEEPSLATE) && baseY + y > 8 && s.get(Properties.AXIS) != Direction.Axis.Y) score += 4;
                else if (isUnnatural(s.getBlock())) score += 1;
            }
        }
        return score;
    }

    private static boolean isUnnatural(Block b) {
        return b instanceof PistonBlock || b instanceof ObserverBlock || b instanceof AbstractRailBlock || b instanceof BedBlock
            || b instanceof CraftingTableBlock || b instanceof RedstoneWireBlock || b instanceof RepeaterBlock || b instanceof ComparatorBlock
            || b instanceof AnvilBlock || b == Blocks.BEACON || b == Blocks.ENCHANTING_TABLE || b == Blocks.RESPAWN_ANCHOR;
    }

    private Long2IntOpenHashMap computeVisible(ClientWorld world) {
        Long2IntOpenHashMap eligible = new Long2IntOpenHashMap();
        for (Long2IntMap.Entry e : heat.long2IntEntrySet()) {
            long key = e.getLongKey();
            if (vetoes.containsKey(key)) continue;
            int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
            if (world.getChunkManager().isChunkLoaded(cx, cz) && scannedNeighbours(cx, cz) >= 3) eligible.put(key, e.getIntValue());
        }
        int reach = Math.max(1, appliedRadius);
        Long2IntOpenHashMap kept = new Long2IntOpenHashMap();
        for (Long2IntMap.Entry e : eligible.long2IntEntrySet()) {
            if (e.getIntValue() >= sensitivity.get() && isPeak(eligible, e.getLongKey(), e.getIntValue(), reach)) kept.put(e.getLongKey(), e.getIntValue());
        }
        return kept;
    }

    private static boolean isPeak(Long2IntOpenHashMap field, long key, int value, int reach) {
        int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
        for (int dx = -reach; dx <= reach; dx++) for (int dz = -reach; dz <= reach; dz++)
            if ((dx != 0 || dz != 0) && field.get(ChunkPos.toLong(cx + dx, cz + dz)) > value) return false;
        return true;
    }

    private int scannedNeighbours(int cx, int cz) {
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            if ((dx != 0 || dz != 0) && scanned.contains(ChunkPos.toLong(cx + dx, cz + dz))) n++;
        return n;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        int y = renderY.get();
        for (long key : visible.keySet()) {
            int x = ChunkPos.getPackedX(key) * 16, z = ChunkPos.getPackedZ(key) * 16;
            event.renderer.box(x, y, z, x + 16, y + 0.2, z + 16, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            if (tracers.get()) event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, x + 8, y, z + 8, lineColor.get());
        }
    }

    @Override
    public String getInfoString() { return String.valueOf(visible.size()); }
}
