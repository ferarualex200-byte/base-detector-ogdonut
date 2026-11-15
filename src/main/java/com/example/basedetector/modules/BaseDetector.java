package com.example.basedetector.modules;

import com.example.basedetector.BaseDetectorAddon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BaseDetector extends Module {
    // ---------- settings ----------
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgScan = settings.createGroup("Scanning");
    private final SettingGroup sgAmethyst = settings.createGroup("Amethyst");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> minScore = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-score")
        .description("Score a chunk needs to be flagged as a base.")
        .defaultValue(6).min(1).sliderMax(40).build());

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y")
        .description("Only look at blocks at or below this Y (0 = deepslate layer).")
        .defaultValue(0).range(-64, 320).sliderRange(-64, 320).build());

    private final Setting<Boolean> overworldOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("overworld-only")
        .description("Blackstone and netherite are natural in the Nether, so skip other dimensions.")
        .defaultValue(true).build());

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
    private final Setting<Boolean> useAmethyst = sgAmethyst.add(new BoolSetting.Builder()
        .name("use-amethyst")
        .description("Use budding amethyst growth to tell how long a chunk has been loaded.")
        .defaultValue(true).build());

    private final Setting<Integer> amethystMaxY = sgAmethyst.add(new IntSetting.Builder()
        .name("amethyst-max-y")
        .description("Only look for budding amethyst at or below this Y (geodes are mostly below 70).")
        .defaultValue(70).range(-64, 320).sliderRange(-64, 128).build());

    private final Setting<Integer> minFaces = sgAmethyst.add(new IntSetting.Builder()
        .name("min-growth-faces")
        .description("Minimum number of budding faces in a chunk before its growth is trusted.")
        .defaultValue(6).min(1).sliderMax(60).build());

    private final Setting<Double> fullGrowth = sgAmethyst.add(new DoubleSetting.Builder()
        .name("full-growth")
        .description("Average growth (0-4) that counts as long-loaded.")
        .defaultValue(3.0).min(0.5).max(4).sliderMax(4).build());

    private final Setting<Double> midGrowth = sgAmethyst.add(new DoubleSetting.Builder()
        .name("mid-growth")
        .description("Average growth (0-4) that counts as medium-loaded.")
        .defaultValue(2.0).min(0.5).max(4).sliderMax(4).build());

    private final Setting<Double> fullBonus = sgAmethyst.add(new DoubleSetting.Builder()
        .name("full-growth-bonus")
        .description("Score added to a chunk that has suspicious blocks AND long-loaded amethyst.")
        .defaultValue(5).min(0).sliderMax(20).build());

    private final Setting<Double> midBonus = sgAmethyst.add(new DoubleSetting.Builder()
        .name("mid-growth-bonus")
        .description("Score added for medium-loaded amethyst.")
        .defaultValue(2).min(0).sliderMax(20).build());

    private final Setting<Double> neighborBonus = sgAmethyst.add(new DoubleSetting.Builder()
        .name("neighbor-bonus")
        .description("Score added per neighboring chunk that also has long-loaded amethyst.")
        .defaultValue(1).min(0).sliderMax(10).build());

    private final Setting<Boolean> guessPlaces = sgAmethyst.add(new BoolSetting.Builder()
        .name("guess-places")
        .description("Also flag chunks with long-loaded amethyst but no suspicious blocks as possible bases.")
        .defaultValue(true).build());

    private final Setting<Double> guessGrowth = sgAmethyst.add(new DoubleSetting.Builder()
        .name("guess-growth")
        .description("Average growth (0-4) a chunk needs to be flagged as a guess.")
        .defaultValue(3.2).min(0.5).max(4).sliderMax(4).build());

    private final Setting<Integer> ignoreNearOrigin = sgAmethyst.add(new IntSetting.Builder()
        .name("ignore-near-origin")
        .description("Ignore guesses closer than this many blocks to 0,0 (spawn chunks stay loaded).")
        .defaultValue(1000).min(0).sliderMax(10000).build());

    // ----- render -----
    private final Setting<Boolean> toast = sgRender.add(new BoolSetting.Builder()
        .name("toast").description("Show a toast when a new base or possible base is detected.")
        .defaultValue(true).build());

    private final Setting<Integer> toastCooldown = sgRender.add(new IntSetting.Builder()
        .name("toast-cooldown-seconds").description("Minimum time between toasts.")
        .defaultValue(3).min(0).sliderMax(30).build());

    private final Setting<Boolean> hud = sgRender.add(new BoolSetting.Builder()
        .name("hud-message").description("Show the dig-down message on screen.")
        .defaultValue(true).build());

    private final Setting<Boolean> esp = sgRender.add(new BoolSetting.Builder()
        .name("column-esp").description("Draw a tall column from each base up to the sky.")
        .defaultValue(true).build());

    private final Setting<Boolean> tracer = sgRender.add(new BoolSetting.Builder()
        .name("tracer").description("Draw a line to the nearest base.")
        .defaultValue(true).build());

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("base-beam-color").description("Color of the beam (and tracer) for blocks-based finds.")
        .defaultValue(new SettingColor(255, 40, 40, 255)).build());

    private final Setting<SettingColor> guessColor = sgRender.add(new ColorSetting.Builder()
        .name("guess-beam-color").description("Color of the beam (and tracer) for amethyst guesses.")
        .defaultValue(new SettingColor(255, 140, 0, 255)).build());

    private final Setting<Integer> guessBeamOffset = sgRender.add(new IntSetting.Builder()
        .name("guess-beam-offset")
        .description("Shift the guess beam this many blocks sideways so it isn't right on the amethyst (0 = on it).")
        .defaultValue(3).range(0, 16).sliderRange(0, 16).build());

    private final Setting<Integer> outlineAlpha = sgRender.add(new IntSetting.Builder()
        .name("outline-alpha").description("Opacity of the outline (0-255).")
        .defaultValue(255).range(0, 255).sliderRange(0, 255).build());

    private final Setting<Integer> fillAlpha = sgRender.add(new IntSetting.Builder()
        .name("fill-alpha").description("Opacity of the filled sides (0 = outline only).")
        .defaultValue(30).range(0, 255).sliderRange(0, 255).build());

    private final Setting<SettingColor> hudColor = sgRender.add(new ColorSetting.Builder()
        .name("hud-text-color").description("Color of the on-screen message.")
        .defaultValue(new SettingColor(255, 80, 80, 255)).build());

    // ---------- data ----------
    /** guess = true means flagged only because of amethyst growth (no suspicious blocks). */
    private record Base(BlockPos pos, double score, double growth, boolean guess) {}

    private record Amethyst(double growth, int faces, BlockPos center) {}

    private final Map<ChunkPos, Base> bases = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Double> growthCache = new ConcurrentHashMap<>();
    private final Map<Block, Double> weights = new HashMap<>();
    private final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();

    private long lastToastMs = 0;
    private int tickCounter = 0;

    private static final String[] DECOR = {
        "banner", "lantern", "candle", "flower_pot", "end_rod", "beacon", "enchanting_table"
    };

    public BaseDetector() {
        super(BaseDetectorAddon.CATEGORY, "base-detector",
            "Scores chunks by suspicious blocks and amethyst growth, and shows where to dig down.");
    }

    // ---------- block weights (edit these to taste) ----------
    private static double computeWeight(Block block) {
        String id = Registries.BLOCK.getId(block).getPath();
        if (id.equals("netherite_block")) return 6;
        if (id.equals("gilded_blackstone")) return 5;
        if (id.contains("shulker_box")) return 3;
        if (id.equals("chest") || id.equals("trapped_chest")
            || id.equals("barrel") || id.equals("ender_chest")) return 1.5;
        if (id.equals("hopper") || id.equals("dropper") || id.equals("dispenser")) return 1;
        if (id.equals("white_concrete") || id.equals("black_concrete")) return 1;
        if (id.contains("blackstone")) return 1; // every other blackstone variant
        for (String d : DECOR) if (id.contains(d)) return 0.5;
        return 0;
    }

    private double weight(BlockState state) {
        return weights.computeIfAbsent(state.getBlock(), BaseDetector::computeWeight);
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
        growthCache.clear();
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

            // forget data for chunks that are no longer loaded
            bases.keySet().removeIf(p -> mc.world.getChunkManager().getWorldChunk(p.x, p.z) == null);
            growthCache.keySet().removeIf(p -> mc.world.getChunkManager().getWorldChunk(p.x, p.z) == null);
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

        // ----- 1) suspicious blocks -----
        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = chunk.getBottomY();
        int startX = cp.getStartX();
        int startZ = cp.getStartZ();
        int limit = maxY.get();

        double score = 0, sx = 0, sy = 0, sz = 0;

        for (int i = 0; i < sections.length; i++) {
            int baseY = bottom + i * 16;
            if (baseY > limit) continue;

            ChunkSection s = sections[i];
            // cheap palette check: skip sections with nothing interesting
            if (s == null || s.isEmpty() || !s.hasAny(st -> weight(st) > 0)) continue;

            for (int y = 0; y < 16 && baseY + y <= limit; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        double w = weight(s.getBlockState(x, y, z));
                        if (w <= 0) continue;
                        score += w;
                        sx += (startX + x) * w;
                        sy += (baseY + y) * w;
                        sz += (startZ + z) * w;
                    }
        }

        // ----- 2) amethyst growth -----
        double growth = -1;
        BlockPos geode = null;
        if (useAmethyst.get()) {
            Amethyst a = scanAmethyst(chunk);
            if (a != null && a.faces() >= minFaces.get()) {
                growth = a.growth();
                geode = a.center();
                growthCache.put(cp, growth);
            } else {
                growthCache.remove(cp);
            }
        }

        // ----- 3) combine -----
        double total = score;
        if (score > 0 && growth >= 0) {
            if (growth >= fullGrowth.get()) total += fullBonus.get();
            else if (growth >= midGrowth.get()) total += midBonus.get();
            total += neighborBonus.get() * grownNeighbors(cp);
        }

        Base found = null;
        if (score > 0 && total >= minScore.get()) {
            // confirmed: suspicious blocks (helped along by amethyst growth)
            BlockPos center = new BlockPos((int) (sx / score), (int) (sy / score), (int) (sz / score));
            found = new Base(center, total, growth, false);
        } else if (guessPlaces.get() && geode != null && growth >= guessGrowth.get()
            && Math.hypot(geode.getX(), geode.getZ()) >= ignoreNearOrigin.get()) {
            // guess: long-loaded chunk with a geode, but no suspicious blocks found
            found = new Base(geode, growth, growth, true);
        }

        if (found != null) {
            Base old = bases.get(cp);
            boolean isNew = old == null || old.guess() != found.guess();
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

    private int grownNeighbors(ChunkPos cp) {
        int n = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                Double g = growthCache.get(new ChunkPos(cp.x + dx, cp.z + dz));
                if (g != null && g >= fullGrowth.get()) n++;
            }
        return n;
    }

    private void notifyFound(Base b) {
        if (!toast.get()) return;
        long now = System.currentTimeMillis();
        if (now - lastToastMs < toastCooldown.get() * 1000L) return;
        lastToastMs = now;

        BlockPos pos = b.pos();
        SystemToast.add(mc.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION,
            Text.literal(b.guess() ? "POSSIBLE BASE" : "BASE FOUND"),
            Text.literal("X " + pos.getX() + "  Y " + pos.getY() + "  Z " + pos.getZ()));
    }

    /** Where to draw the beam. Guess beams are nudged sideways (fixed direction per chunk) so they sit near the geode, not on it. */
    private BlockPos beamPos(ChunkPos cp, Base b) {
        int off = guessBeamOffset.get();
        if (!b.guess() || off <= 0) return b.pos();
        int h = (cp.x * 73856093) ^ (cp.z * 19349663);
        double ang = ((h >>> 4) & 0xFFFF) / 65536.0 * Math.PI * 2;
        return new BlockPos(
            b.pos().getX() + (int) Math.round(Math.cos(ang) * off),
            b.pos().getY(),
            b.pos().getZ() + (int) Math.round(Math.sin(ang) * off));
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
        Color baseLine = new Color(c.r, c.g, c.b, outlineAlpha.get());
        Color baseFill = new Color(c.r, c.g, c.b, fillAlpha.get());

        SettingColor g = guessColor.get();
        Color guessLine = new Color(g.r, g.g, g.b, outlineAlpha.get());
        Color guessFill = new Color(g.r, g.g, g.b, fillAlpha.get());

        if (esp.get()) {
            for (Map.Entry<ChunkPos, Base> e : bases.entrySet()) {
                Base b = e.getValue();
                BlockPos p = beamPos(e.getKey(), b);
                event.renderer.box(
                    p.getX(), p.getY(), p.getZ(),
                    p.getX() + 1, top, p.getZ() + 1,
                    b.guess() ? guessFill : baseFill,
                    b.guess() ? guessLine : baseLine,
                    ShapeMode.Both, 0);
            }
        }

        Base n = nearest();
        if (tracer.get() && n != null) {
            BlockPos tp = beamPos(new ChunkPos(n.pos()), n);
            event.renderer.line(
                RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                tp.getX() + 0.5, tp.getY() + 0.5, tp.getZ() + 0.5,
                n.guess() ? guessLine : baseLine);
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!hud.get() || mc.player == null) return;
        Base n = nearest();
        if (n == null) return;

        BlockPos me = mc.player.getBlockPos();
        BlockPos t = n.pos();
        int dx = t.getX() - me.getX();
        int dz = t.getZ() - me.getZ();
        int horiz = (int) Math.sqrt(dx * dx + dz * dz);
        int dy = me.getY() - t.getY();

        String label = n.guess()
            ? "POSSIBLE BASE (amethyst " + Math.round(n.growth() * 10) / 10.0 + ")"
            : "SUS BASE (score " + (int) n.score() + ")";

        String msg;
        if (horiz > 6) {
            msg = label + ": X " + t.getX() + " Z " + t.getZ()
                + " (" + horiz + "m), then dig down to Y " + t.getY();
        } else if (dy > 3) {
            msg = "DIG DOWN " + dy + " blocks to Y " + t.getY();
        } else {
            msg = "You're at base level";
        }

        TextRenderer text = TextRenderer.get();
        text.begin(1, false, true);
        double x = mc.getWindow().getScaledWidth() / 2.0 - text.getWidth(msg) / 2.0;
        text.render(msg, x, 20, hudColor.get(), true);
        text.end();
    }

    @Override
    public String getInfoString() {
        return bases.isEmpty() ? null : String.valueOf(bases.size());
    }
}
