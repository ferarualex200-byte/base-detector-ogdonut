package com.example.basedetector.modules;

import com.example.basedetector.BaseDetectorAddon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
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
import net.minecraft.world.Heightmap;
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
        .description("Move the shown chunks this many blocks away from the amethyst.")
        .defaultValue(35).min(0).sliderMax(128).build());

    private final Setting<Integer> mergeRadius = sgAmethyst.add(new IntSetting.Builder()
        .name("merge-radius")
        .description("Geodes closer than this many blocks count as one base. Only the strongest one gets a flag.")
        .defaultValue(96).min(16).sliderMax(400).build());

    // ----- render -----
    private final Setting<Boolean> toast = sgRender.add(new BoolSetting.Builder()
        .name("toast").description("Show a toast when a new possible base is detected.")
        .defaultValue(true).build());

    private final Setting<Integer> toastCooldown = sgRender.add(new IntSetting.Builder()
        .name("toast-cooldown-seconds").description("Minimum time between toasts.")
        .defaultValue(3).min(0).sliderMax(30).build());

    private final Setting<Boolean> chunkBox = sgRender.add(new BoolSetting.Builder()
        .name("chunk-box").description("Draw the 3x3 block of chunks around the flagged chunk.")
        .defaultValue(true).build());

    private final Setting<SettingColor> chunkLineColor = sgRender.add(new ColorSetting.Builder()
        .name("chunk-line-color").description("Color of the thin outline around each chunk.")
        .defaultValue(new SettingColor(255, 0, 255, 255)).build());

    private final Setting<Boolean> chunkGrid = sgRender.add(new BoolSetting.Builder()
        .name("chunk-grid").description("Draw a faint 4x4 grid inside every chunk.")
        .defaultValue(true).build());

    private final Setting<Boolean> chunkGlow = sgRender.add(new BoolSetting.Builder()
        .name("chunk-glow").description("Soft glow around every chunk outline.")
        .defaultValue(true).build());

    private final Setting<Boolean> rainbow = sgRender.add(new BoolSetting.Builder()
        .name("rainbow").description("Cycle the chunk boxes, glow and fill through rainbow colors.")
        .defaultValue(false).build());

    private final Setting<Double> rainbowSpeed = sgRender.add(new DoubleSetting.Builder()
        .name("rainbow-speed").description("Seconds for one full color cycle.")
        .defaultValue(4.0).min(0.5).sliderMax(20)
        .visible(rainbow::get).build());

    private final Setting<Integer> chunkBoxLift = sgRender.add(new IntSetting.Builder()
        .name("chunk-box-lift").description("Blocks above the ground surface the chunk boxes float, so they don't clip into hills.")
        .defaultValue(1).min(0).sliderMax(10).build());

    private final Setting<Integer> chunkBoxHeight = sgRender.add(new IntSetting.Builder()
        .name("chunk-box-height").description("How tall the chunk walls and glow are.")
        .defaultValue(40).min(1).sliderMax(200).build());

    private final Setting<SettingColor> chunkFillColor = sgRender.add(new ColorSetting.Builder()
        .name("chunk-fill-color").description("Color that fills every flagged chunk (one flat shade).")
        .defaultValue(new SettingColor(128, 0, 128, 110)).build());

    // ---------- data ----------
    private record Base(BlockPos pos, BlockPos geode, double growth, int faces) {
        /** Higher = stronger candidate. Ties are broken by how many faces were seen. */
        double score() {
            return growth + faces * 0.0001;
        }
    }

    private record Amethyst(double growth, int faces, BlockPos center) {}

    private final Map<ChunkPos, Base> bases = new ConcurrentHashMap<>();
    private final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();

    private long lastToastMs = 0;
    private int tickCounter = 0;

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
    }

    private void scan(WorldChunk chunk) {
        ChunkPos cp = chunk.getPos();

        Base found = null;
        Amethyst a = scanAmethyst(chunk);
        if (a != null && a.faces() >= minFaces.get()) {
            if (a.growth() >= minGrowth.get()
                && Math.hypot(a.center().getX(), a.center().getZ()) >= ignoreNearOrigin.get()) {
                found = new Base(offsetFromGeode(a.center(), cp, markerOffset.get()),
                    a.center(), a.growth(), a.faces());
            }
        }

        if (found == null) {
            bases.remove(cp);
            return;
        }

        // only one flag per cluster: if a stronger (or equal, earlier) flag is nearby, skip this one
        final Base f = found;
        double r = mergeRadius.get();
        for (Map.Entry<ChunkPos, Base> e : bases.entrySet()) {
            if (e.getKey().equals(cp)) continue;
            if (geodeDist(e.getValue(), f) > r) continue;
            if (e.getValue().score() >= f.score()) {
                bases.remove(cp);
                return;
            }
        }

        // this one is the strongest: drop weaker flags around it
        bases.entrySet().removeIf(e -> !e.getKey().equals(cp) && geodeDist(e.getValue(), f) <= r);

        boolean isNew = !bases.containsKey(cp);
        bases.put(cp, f);
        if (isNew) notifyFound(f);
    }

    private static double geodeDist(Base a, Base b) {
        return Math.hypot(a.geode().getX() - b.geode().getX(), a.geode().getZ() - b.geode().getZ());
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

    /** Pushes the marker `dist` blocks away from the geode toward the middle of its chunk (can leave the chunk). */
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
            Text.literal("X " + pos.getX() + "  Z " + pos.getZ()));
    }

    /** Square outline of a chunk at height y, grown outward by `grow` blocks on every side. */
    private void rect(Render3DEvent event, double sx, double sz, double y, double grow, Color col) {
        double x1 = sx - grow, z1 = sz - grow, x2 = sx + 16 + grow, z2 = sz + 16 + grow;
        event.renderer.line(x1, y, z1, x2, y, z1, col);
        event.renderer.line(x2, y, z1, x2, y, z2, col);
        event.renderer.line(x2, y, z2, x1, y, z2, col);
        event.renderer.line(x1, y, z2, x1, y, z1, col);
    }

    /** Four flat vertical walls around a chunk, pushed outward by `grow` blocks. */
    private void walls(Render3DEvent event, double sx, double sz, double y1, double y2, double grow, Color col) {
        double x1 = sx - grow, z1 = sz - grow, x2 = sx + 16 + grow, z2 = sz + 16 + grow;
        event.renderer.box(x1, y1, z1, x2, y2, z1, col, col, ShapeMode.Sides, 0);
        event.renderer.box(x1, y1, z2, x2, y2, z2, col, col, ShapeMode.Sides, 0);
        event.renderer.box(x1, y1, z1, x1, y2, z2, col, col, ShapeMode.Sides, 0);
        event.renderer.box(x2, y1, z1, x2, y2, z2, col, col, ShapeMode.Sides, 0);
    }

    /** Draws one chunk: flat fill, 4x4 grid, soft glow and bright outline. */
    private void renderChunk(Render3DEvent event, int chunkX, int chunkZ, double fallbackY, Color chunkFill, Color chunkLine) {
        double sx = chunkX * 16.0, sz = chunkZ * 16.0;

        // chunk box sits on the ground (ignoring leaves) at the middle of the chunk
        double y = fallbackY;
        int surf = mc.world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, (int) sx + 8, (int) sz + 8);
        if (surf > mc.world.getBottomY()) y = surf + chunkBoxLift.get();

        double h = chunkBoxHeight.get();
        double yl = y + 0.05; // lift lines slightly so they don't z-fight with the fill

        // flat fill, one shade
        event.renderer.box(sx, y, sz, sx + 16, y, sz + 16, chunkFill, chunkFill, ShapeMode.Sides, 0);

        // faint 4x4 grid
        if (chunkGrid.get()) {
            Color gridCol = new Color(chunkLine.r, chunkLine.g, chunkLine.b, Math.max(chunkLine.a / 2, 60));
            for (int i = 1; i < 4; i++) {
                double o = i * 4;
                event.renderer.line(sx + o, yl, sz, sx + o, yl, sz + 16, gridCol);
                event.renderer.line(sx, yl, sz + o, sx + 16, yl, sz + o, gridCol);
            }
        }

        // soft glow: translucent walls that fade outward
        if (chunkGlow.get()) {
            int a = Math.max(chunkLine.a, 120);
            double[] offs = {0.0, 0.25, 0.5, 0.8, 1.2};
            int[] alphas = {a / 5, a / 7, a / 10, a / 16, a / 28};
            for (int i = 0; i < offs.length; i++) {
                walls(event, sx, sz, y, y + h, offs[i],
                    new Color(chunkLine.r, chunkLine.g, chunkLine.b, Math.max(alphas[i], 4)));
            }
        }

        // bright outline: bottom, top and the four corner edges
        rect(event, sx, sz, yl, 0, chunkLine);
        rect(event, sx, sz, y + h, 0, chunkLine);
        event.renderer.line(sx, y, sz, sx, y + h, sz, chunkLine);
        event.renderer.line(sx + 16, y, sz, sx + 16, y + h, sz, chunkLine);
        event.renderer.line(sx + 16, y, sz + 16, sx + 16, y + h, sz + 16, chunkLine);
        event.renderer.line(sx, y, sz + 16, sx, y + h, sz + 16, chunkLine);
    }

    // ---------- rendering ----------
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null) return;
        if (!chunkBox.get()) return;

        SettingColor cf = chunkFillColor.get();
        Color chunkFill = new Color(cf.r, cf.g, cf.b, cf.a);
        Color chunkLine = new Color(chunkLineColor.get());

        if (rainbow.get()) {
            long period = Math.max(500L, (long) (rainbowSpeed.get() * 1000));
            float hue = (System.currentTimeMillis() % period) / (float) period;
            int rgb = java.awt.Color.HSBtoRGB(hue, 0.9f, 1f);
            int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
            chunkFill = new Color(r, g, b, cf.a);
            chunkLine = new Color(r, g, b, chunkLineColor.get().a);
        }

        for (Map.Entry<ChunkPos, Base> e : bases.entrySet()) {
            BlockPos p = e.getValue().pos();
            ChunkPos cp = new ChunkPos(p); // the middle chunk (shifted away from the amethyst)

            // 3x3 block of chunks around the middle one
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    renderChunk(event, cp.x + dx, cp.z + dz, p.getY(), chunkFill, chunkLine);
        }
    }

    @Override
    public String getInfoString() {
        return bases.isEmpty() ? null : String.valueOf(bases.size());
    }
}
