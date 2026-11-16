package de.donutlarp.modules;

import de.donutlarp.ActivityStore;
import de.donutlarp.DonutLarp;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.sound.SoundEvents;

import java.util.HashSet;
import java.util.Set;

public class DLActivityInspector extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> chat = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-message").description("Writes into the chat when new activity shows up.")
        .defaultValue(true).build());
    private final Setting<Boolean> sound = sgGeneral.add(new BoolSetting.Builder()
        .name("sound").description("Plays a sound when new activity shows up.")
        .defaultValue(true).build());

    public enum Style { Flat, Column, Pillar, Pulse }

    private final Setting<Style> style = sgRender.add(new EnumSetting.Builder<Style>()
        .name("style").description("Flat: slab. Column: chunk-sized column going down. Pillar: slab plus tall beam. Pulse: slab that breathes up and down.")
        .defaultValue(Style.Column).build());
    private final Setting<Integer> depth = sgRender.add(new IntSetting.Builder()
        .name("column-depth").description("How far the column reaches below the y-level.")
        .defaultValue(48).range(1, 384).sliderRange(1, 192).visible(() -> style.get() == Style.Column).build());
    private final Setting<Integer> pillarTop = sgRender.add(new IntSetting.Builder()
        .name("pillar-top").description("Y level the pillar reaches.")
        .defaultValue(320).range(-64, 320).sliderRange(-64, 320).visible(() -> style.get() == Style.Pillar).build());
    private final Setting<Integer> yLevel = sgRender.add(new IntSetting.Builder()
        .name("y-level").description("Height the red chunk is rendered at.")
        .defaultValue(64).range(-64, 320).sliderRange(-64, 320).build());
    private final Setting<Integer> height = sgRender.add(new IntSetting.Builder()
        .name("height").description("Thickness of the red chunk in blocks.")
        .defaultValue(1).range(1, 64).sliderRange(1, 64).build());
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("How the chunk is rendered.").defaultValue(ShapeMode.Both).build());
    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").description("Fill color of the chunk.")
        .defaultValue(new SettingColor(255, 40, 40, 70)).build());
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("Outline color of the chunk.")
        .defaultValue(new SettingColor(255, 40, 40, 255)).build());

    private final Set<Long> seen = new HashSet<>();

    public DLActivityInspector() {
        super(DonutLarp.CATEGORY, "DL-activity-inspector", "Marks a red chunk where a player is underground on DonutLarp.");
    }

    @Override public void onActivate() { seen.clear(); }

    @EventHandler private void onGameLeft(GameLeftEvent e) { seen.clear(); }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        Set<Long> now = new HashSet<>();
        for (ActivityStore.Activity a : ActivityStore.all()) {
            long k = ((long) a.chunkX() << 32) | (a.chunkZ() & 0xFFFFFFFFL);
            now.add(k);
            if (seen.contains(k)) continue;
            if (chat.get())
                info("Activity below chunk (highlight)%d(default), (highlight)%d(default) (%d %s).",
                    a.chunkX() * 16 + 8, a.chunkZ() * 16 + 8, a.players(), a.players() == 1 ? "player" : "players");
            if (sound.get() && mc.player != null)
                mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.8f, 0.6f);
        }
        seen.clear();
        seen.addAll(now);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        double y = yLevel.get(), h = Math.max(0.02, height.get());
        Style st = style.get();
        double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 350.0);
        for (ActivityStore.Activity a : ActivityStore.all()) {
            double x = a.chunkX() * 16.0, z = a.chunkZ() * 16.0;
            switch (st) {
                case Flat -> box(event, x, y, z, x + 16, y + h, z + 16);
                case Column -> box(event, x, y - depth.get(), z, x + 16, y + h, z + 16);
                case Pillar -> {
                    box(event, x, y, z, x + 16, y + h, z + 16);
                    box(event, x + 7.5, y, z + 7.5, x + 8.5, pillarTop.get(), z + 8.5);
                }
                case Pulse -> box(event, x, y, z, x + 16, y + Math.max(0.02, h * (0.2 + 0.8 * pulse)) + 1.5 * pulse, z + 16);
            }
        }
    }

    private void box(Render3DEvent e, double x1, double y1, double z1, double x2, double y2, double z2) {
        e.renderer.box(x1, y1, z1, x2, y2, z2, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }
}
