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
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SpawnerDetector extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> scanRadius = sgGeneral.add(new IntSetting.Builder()
        .name("scan-radius-chunks")
        .description("How many chunks around you to check (limited by your render distance).")
        .defaultValue(16).min(1).sliderMax(32).build());

    private final Setting<Integer> scanInterval = sgGeneral.add(new IntSetting.Builder()
        .name("scan-interval-ticks")
        .description("How often to check for spawners (20 ticks = 1 second).")
        .defaultValue(20).min(5).sliderMax(100).build());

    private final Setting<Boolean> toast = sgRender.add(new BoolSetting.Builder()
        .name("toast").description("Show a toast with the coordinates when a new spawner is found.")
        .defaultValue(true).build());

    private final Setting<Double> beamWidth = sgRender.add(new DoubleSetting.Builder()
        .name("beam-width").description("How wide the beam is, in blocks, on each side of the spawner.")
        .defaultValue(1.0).min(0.1).max(5).sliderMax(3).build());

    private final Setting<SettingColor> beamColor = sgRender.add(new ColorSetting.Builder()
        .name("beam-color").description("Color of the beam.")
        .defaultValue(new SettingColor(255, 0, 0, 255)).build());

    private final Setting<Integer> outlineAlpha = sgRender.add(new IntSetting.Builder()
        .name("outline-alpha").description("Opacity of the beam outline (0-255).")
        .defaultValue(255).range(0, 255).sliderRange(0, 255).build());

    private final Setting<Integer> fillAlpha = sgRender.add(new IntSetting.Builder()
        .name("fill-alpha").description("Opacity of the beam sides (0 = outline only).")
        .defaultValue(90).range(0, 255).sliderRange(0, 255).build());

    private final Set<BlockPos> spawners = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> announced = new HashSet<>();

    private int tickCounter = 0;

    public SpawnerDetector() {
        super(BaseDetectorAddon.CATEGORY, "spawner-detector",
            "Finds monster spawners, draws a big red beam and shows a toast with the coordinates.");
    }

    @Override
    public void onActivate() {
        tickCounter = scanInterval.get();
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
        spawners.clear();
        announced.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        tickCounter++;
        if (tickCounter < scanInterval.get()) return;
        tickCounter = 0;

        ChunkPos c = mc.player.getChunkPos();
        int r = Math.min(scanRadius.get(), mc.options.getViewDistance().getValue());

        Set<BlockPos> found = new HashSet<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(c.x + dx, c.z + dz);
                if (chunk == null) continue;

                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be.getCachedState().isOf(Blocks.SPAWNER)) found.add(be.getPos().toImmutable());
                }
            }
        }

        spawners.retainAll(found);
        spawners.addAll(found);

        BlockPos me = mc.player.getBlockPos();
        BlockPos closest = null;
        int newCount = 0;
        for (BlockPos p : found) {
            if (!announced.add(p)) continue;
            newCount++;
            if (closest == null || p.getSquaredDistance(me) < closest.getSquaredDistance(me)) closest = p;
        }

        if (closest != null && toast.get()) {
            String coords = "X " + closest.getX() + "  Y " + closest.getY() + "  Z " + closest.getZ();
            if (newCount > 1) coords += "  (+" + (newCount - 1) + " more)";

            SystemToast.add(mc.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION,
                Text.literal("SPAWNER DETECTED !"),
                Text.literal(coords));
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || spawners.isEmpty()) return;

        double bottom = mc.world.getBottomY();
        double top = bottom + mc.world.getHeight();
        double w = beamWidth.get();

        SettingColor c = beamColor.get();
        Color line = new Color(c.r, c.g, c.b, outlineAlpha.get());
        Color fill = new Color(c.r, c.g, c.b, fillAlpha.get());

        for (BlockPos p : spawners) {
            event.renderer.box(
                p.getX() + 0.5 - w, bottom, p.getZ() + 0.5 - w,
                p.getX() + 0.5 + w, top, p.getZ() + 0.5 + w,
                fill, line, ShapeMode.Both, 0);
        }
    }

    @Override
    public String getInfoString() {
        return spawners.isEmpty() ? null : String.valueOf(spawners.size());
    }
}
