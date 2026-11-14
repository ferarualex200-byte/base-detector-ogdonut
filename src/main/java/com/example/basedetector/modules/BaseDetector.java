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
import net.minecraft.client.toast.SystemToast;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
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

    private final Setting<Boolean> toast = sgRender.add(new BoolSetting.Builder()
        .name("toast").description("Show a BASE FOUND toast when a new base is detected.")
        .defaultValue(true).build());

    private final Setting<Integer> toastCooldown = sgRender.add(new IntSetting.Builder()
        .name("toast-cooldown-seconds").description("Minimum time between toasts.")
        .defaultValue(3).min(0).sliderMax(30).build());

    private long lastToastMs = 0;

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
        .name("color").description("Color of the column and tracer.")
        .defaultValue(new SettingColor(0, 0, 0, 255)).build());

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
    private record Base(BlockPos pos, double score) {}

    private final Map<ChunkPos, Base> bases = new ConcurrentHashMap<>();
    private final Map<Block, Double> weights = new HashMap<>();
    private final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();

    private static final String[] DECOR = {
        "banner", "lantern", "candle", "flower_pot", "end_rod", "beacon", "enchanting_table"
    };

    public BaseDetector() {
        super(BaseDetectorAddon.CATEGORY, "base-detector",
            "Scores chunks by suspicious blocks and shows where to dig down.");
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

    // ---------- lifecycle ----------
    @Override
    public void onDeactivate() {
        bases.clear();
        queue.clear();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        bases.clear();
        queue.clear();
    }

    // ---------- scanning ----------
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (overworldOnly.get() && mc.world.getRegistryKey() != World.OVERWORLD) return;

        // start a new pass every scan-interval ticks
        if (mc.world.getTime() % scanInterval.get() == 0 && queue.isEmpty()) {
            ChunkPos c = mc.player.getChunkPos();
            int r = Math.min(scanRadius.get(), mc.options.getViewDistance().getValue());
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++)
                    queue.add(new ChunkPos(c.x + dx, c.z + dz));

            // forget bases in chunks that are no longer loaded
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
        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = chunk.getBottomY();
        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();
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

        if (score >= minScore.get()) {
            BlockPos center = new BlockPos((int) (sx / score), (int) (sy / score), (int) (sz / score));
            boolean isNew = !bases.containsKey(chunk.getPos());
            bases.put(chunk.getPos(), new Base(center, score));
            if (isNew) notifyFound(center);
        } else {
            bases.remove(chunk.getPos());
        }
    }

    private void notifyFound(BlockPos pos) {
        if (!toast.get()) return;
        long now = System.currentTimeMillis();
        if (now - lastToastMs < toastCooldown.get() * 1000L) return;
        lastToastMs = now;

        SystemToast.add(mc.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION,
            Text.literal("BASE FOUND"),
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

        if (esp.get()) {
            for (Base b : bases.values()) {
                BlockPos p = b.pos();
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

        String msg;
        if (horiz > 6) {
            msg = "SUS BASE (score " + (int) n.score() + "): X " + t.getX() + " Z " + t.getZ()
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
