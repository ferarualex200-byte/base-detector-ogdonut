package com.example.basedetector.modules;

import com.example.basedetector.BaseDetectorAddon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BaseDetector extends Module {
    // ---------- settings ----------
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgScan = settings.createGroup("Scanning");
    private final SettingGroup sgAmethyst = settings.createGroup("Amethyst");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> overworldOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("overworld-only")
        .description("Only run in the overworld.")
        .defaultValue(true).build());

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug").description("Print a scan summary in chat after each pass.")
        .defaultValue(false).build());

    // ----- scanning -----
    private final Setting<Integer> scanRadius = sgScan.add(new IntSetting.Builder()
        .name("scan-radius-chunks")
        .description("How many chunks around you to scan.")
        .defaultValue(8).min(1).sliderMax(32).build());

    private final Setting<Integer> scanInterval = sgScan.add(new IntSetting.Builder()
        .name("scan-interval-ticks")
        .description("How often to start a new scan pass.")
        .defaultValue(100).min(20).sliderMax(400).build());

    private final Setting<Integer> chunksPerTick = sgScan.add(new IntSetting.Builder()
        .name("chunks-per-tick")
        .description("Lower this if you get lag spikes.")
        .defaultValue(4).min(1).sliderMax(20).build());

    // ----- amethyst -----
    private final Setting<Integer> amethystMaxY = sgAmethyst.add(new IntSetting.Builder()
        .name("amethyst-max-y")
        .description("Only look for budding amethyst at or below this Y (geodes are mostly below 70).")
        .defaultValue(70).range(-64, 320).sliderRange(-64, 128).build());

    private final Setting<Integer> minFaces = sgAmethyst.add(new IntSetting.Builder()
        .name("min-growth-faces")
        .description("Minimum number of budding faces in a chunk before its growth is trusted.")
        .defaultValue(6).min(1).sliderMax(60).build());

    private final Setting<Double> minGrowth = sgAmethyst.add(new DoubleSetting.Builder()
        .name("min-growth")
        .description("Average growth (0-4) a chunk needs to be flagged. Higher = chunk was loaded longer.")
        .defaultValue(3.2).min(0.5).max(4).sliderMax(4).build());

    private final Setting<Integer> ignoreNearOrigin = sgAmethyst.add(new IntSetting.Builder()
        .name("ignore-near-origin")
        .description("Ignore chunks closer than this many blocks to 0,0 (spawn chunks stay loaded).")
        .defaultValue(1000).min(0).sliderMax(10000).build());

    private final Setting<Integer> markerOffset = sgAmethyst.add(new IntSetting.Builder()
        .name("marker-offset")
        .description("Move the marker this many blocks away from the amethyst, toward the middle of the chunk.")
        .defaultValue(10).min(0).sliderMax(32).build());

    // ----- render -----
    private final Setting<Boolean> toast = sgRender.add(new BoolSetting.Builder()
        .name("toast").description("Show a toast when a new possible base is detected.")
        .defaultValue(true).build());

    private final Setting<Integer> toastCooldown = sgRender.add(new IntSetting.Builder()
        .name("toast-cooldown-seconds").description("Minimum time between toasts.")
        .defaultValue(3).min(0).sliderMax(30).build());

    private final Setting<Boolean> esp = sgRender.add(new BoolSetting.Builder()
        .name("column-esp").description("Draw a tall column from each marker up to the sky.")
        .defaultValue(true).build());

    private final Setting<Boolean> chunkBox = sgRender.add(new BoolSetting.Builder()
        .name("chunk-box").description("Draw a square outline around the flagged chunk.")
        .defaultValue(true).build());

    private final Setting<Boolean> tracer = sgRender.add(new BoolSetting.Builder()
        .name("tracer").description("Draw a line to the nearest marker.")
        .defaultValue(true).build());

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("color").description("Color of the column, box and tracer.")
        .defaultValue(new SettingColor(0, 0, 0, 255)).build());

    private final Setting<Integer> outlineAlpha = sgRender.add(new IntSetting.Builder()
        .name("outline-alpha").description("Opacity of the outline (0-255).")
        .defaultValue(255).range(0, 255).sliderRange(0, 255).build());

    private final Setting<Integer> fillAlpha = sgRender.add(new IntSetting.Builder()
        .name("fill-alpha").description("Opacity of the filled sides (0 = outline only).")
        .defaultValue(30).range(0, 255).sliderRange(0, 255).build());

    // ---------- data ----------
    private record Base(BlockPos pos, double growth) {}

    private record Amethyst(double growth, int faces, BlockPos center) {}

    private final Map<ChunkPos, Base> bases = new ConcurrentHashMap<>();
    private final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();

    private long lastToastMs = 0;
    private int tickCounter = 0;
    private int dbgScanned = 0;
    private double dbgBest = 0;

    public BaseDetector() {
        super(BaseDetectorAddon.CATEGORY, "base-detector",
            "Finds possible bases from budding amethyst growth and shows where to dig down.");
    }

    /** 0 = nothing, 1 = small bud, 2 = medium, 3 = large, 4 = full cluster. */
    private static int stage(BlockState s) {
        if (s.isOf(Blocks.SMALL_AMETHYST_BUD)) return 1;
        if (s.isOf(Blocks.MEDIUM_AMETHYST_BUD)) return 2;
        if (s.isOf(Blocks.LARGE_AMETHYST_BUD)) return 3;
        if (s.isOf(Blocks.AMETHYST_CLUSTER)) return 4;
        return 0;
    }

    // ---------- lifecycle ----------
    @Override
    public void onActivate() {
        tickCounter = scanInterval.get(); // start a scan on the next tick
    }

    @Override
    public void onDeactivate() {
        clearAll();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        clearAll();
    }

    private void clearAll() {
        bases.clear();
        queue.clear();
    }

    // ---------- scanning ----------
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (overworldOnly.get() && mc.world.getRegistryKey() != World.OVERWORLD) return;

        // start a new pass every scan-interval ticks
        tickCounter++;
        if (tickCounter >= scanInterval.get() && queue.isEmpty()) {
            tickCounter = 0;
            ChunkPos c = mc.player.getChunkPos();
            int r = Math.min(scanRadius.get(), mc.options.getViewDistance().getValue());
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++)
                    queue.add(new ChunkPos(c.x + dx, c.z + dz));

            // forget markers in chunks that are no longer loaded
            bases.keySet().removeIf(p -> mc.world.getChunkManager().getWorldChunk(p.x, p.z) == null);
        }

        // process a few chunks per tick so we never lag
        for (int i = 0; i < chunksPerTick.get() && !queue.isEmpty(); i++) {
            ChunkPos p = queue.poll();
            WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(p.x, p.z);
            if (chunk != null) scan(chunk);
        }

        if (debug.get() && queue.isEmpty() && dbgScanned > 0) {
            info("Scanned " + dbgScanned + " chunks, best growth " + Math.round(dbgBest * 10) / 10.0
                + ", flagged " + bases.size());
            dbgScanned = 0;
            dbgBest = 0;
        }
    }

    private void scan(WorldChunk chunk) {
        ChunkPos cp = chunk.getPos();
        dbgScanned++;

        Base found = null;
        Amethyst a = scanAmethyst(chunk);
        if (a != null && a.faces() >= minFaces.get()) {
            if (a.growth() > dbgBest) dbgBest = a.growth();

            if (a.growth() >= minGrowth.get()
                && Math.hypot(a.center().getX(), a.center().getZ()) >= ignoreNearOrigin.get()) {
                found = new Base(offsetFromGeode(a.center(), cp, markerOffset.get()), a.growth());
            }
        }

        if (found != null) {
            boolean isNew = !bases.containsKey(cp);
            bases.put(cp, found);
            if (isNew) notifyFound(found);
        } else {
            bases.remove(cp);
        }
    }

    /** Average growth (0..4) of all budding amethyst faces in this chunk, or null if it has none. */
    private Amethyst scanAmethyst(WorldChunk chunk) {
        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = chunk.getBottomY();
        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();
        int limit = amethystMaxY.get();

        int faces = 0, totalStage = 0, count = 0;
        long bx = 0, by = 0, bz = 0;
        BlockPos.Mutable p = new BlockPos.Mutable();

        for (int i = 0; i < sections.length; i++) {
            int baseY = bottom + i * 16;
            if (baseY > limit) continue;

            ChunkSection s = sections[i];
            if (s == null || s.isEmpty() || !s.hasAny(st -> st.isOf(Blocks.BUDDING_AMETHYST))) continue;

            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        if (!s.getBlockState(x, y, z).isOf(Blocks.BUDDING_AMETHYST)) continue;

                        int wx = startX + x;
                        int wy = baseY + y;
                        int wz = startZ + z;
                        count++;
                        bx += wx;
                        by += wy;
                        bz += wz;

                        for (Direction d : Direction.values()) {
                            p.set(wx, wy, wz).move(d);
                            BlockState n = mc.world.getBlockState(p);
                            int st = stage(n);
                            // only count faces where a bud could grow (air/water) or already has
                            if (st > 0 || n.isAir() || n.isOf(Blocks.WATER)) {
                                faces++;
                                totalStage += st;
                            }
                        }
                    }
        }

        if (count == 0 || faces == 0) return null;
        BlockPos center = new BlockPos((int) (bx / count), (int) (by / count), (int) (bz / count));
        return new Amethyst((double) totalStage / faces, faces, center);
    }

    /** Pushes the marker `dist` blocks away from the geode toward the middle of its chunk (kept inside the chunk). */
    private BlockPos offsetFromGeode(BlockPos geode, ChunkPos cp, int dist) {
        if (dist <= 0) return geode;

        double dx = (cp.getStartX() + 8) - geode.getX();
        double dz = (cp.getStartZ() + 8) - geode.getZ();
        double len = Math.hypot(dx, dz);
        if (len < 1) { // geode is dead center, pick a fixed direction
            dx = 1;
            dz = 0;
            len = 1;
        }

        int x = (int) Math.round(geode.getX() + dx / len * dist);
        int z = (int) Math.round(geode.getZ() + dz / len * dist);
        x = Math.max(cp.getStartX(), Math.min(cp.getStartX() + 15, x));
        z = Math.max(cp.getStartZ(), Math.min(cp.getStartZ() + 15, z));
        return new BlockPos(x, geode.getY(), z);
    }

    private void notifyFound(Base b) {
        if (!toast.get()) return;
        long now = System.currentTimeMillis();
        if (now - lastToastMs < toastCooldown.get() * 1000L) return;
        lastToastMs = now;

        BlockPos pos = b.pos();
        SystemToast.add(mc.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION,
            Text.literal("Base Detected"),
            Text.literal("X " + pos.getX() + "  Y " + pos.getY() + "  Z " + pos.getZ()));
    }

    private Base nearest() {
        if (mc.player == null) return null;
        BlockPos me = mc.player.getBlockPos();
        Base best = null;
        double bestD = Double.MAX_VALUE;
        for (Base b : bases.values()) {
            double d = b.pos().getSquaredDistance(me);
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        return best;
    }

    // ---------- rendering ----------
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null) return;
        double top = mc.world.getBottomY() + mc.world.getHeight();

        SettingColor c = color.get();
        Color line = new Color(c.r, c.g, c.b, outlineAlpha.get());
        Color fill = new Color(c.r, c.g, c.b, fillAlpha.get());

        for (Map.Entry<ChunkPos, Base> e : bases.entrySet()) {
            ChunkPos cp = e.getKey();
            BlockPos p = e.getValue().pos();

            // square outline around the whole chunk, drawn flat at the marker's Y
            if (chunkBox.get()) {
                event.renderer.box(
                    cp.getStartX(), p.getY(), cp.getStartZ(),
                    cp.getStartX() + 16, p.getY(), cp.getStartZ() + 16,
                    fill, line, ShapeMode.Lines, 0);
            }

            // tall column up to the sky
            if (esp.get()) {
                event.renderer.box(
                    p.getX(), p.getY(), p.getZ(),
                    p.getX() + 1, top, p.getZ() + 1,
                    fill, line, ShapeMode.Both, 0);
            }
        }

        Base n = nearest();
        if (tracer.get() && n != null) {
            event.renderer.line(
                RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                n.pos().getX() + 0.5, n.pos().getY() + 0.5, n.pos().getZ() + 0.5, line);
        }
    }

    @Override
    public String getInfoString() {
        return bases.isEmpty() ? null : String.valueOf(bases.size());
    }
}
