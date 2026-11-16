package de.donutlarp.modules;

import de.donutlarp.BaseStore;
import de.donutlarp.DonutLarp;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;

public class DLChunkFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> toast = sgGeneral.add(new BoolSetting.Builder()
        .name("achievement-toast").description("Shows an achievement message when a base chunk is found.")
        .defaultValue(true).build());
    private final Setting<Boolean> sound = sgGeneral.add(new BoolSetting.Builder()
        .name("sound").description("Plays the challenge sound when a base chunk is found.")
        .defaultValue(true).build());
    private final Setting<Integer> maxDistance = sgGeneral.add(new IntSetting.Builder()
        .name("max-distance").description("Only render base chunks within this many chunks.")
        .defaultValue(64).min(1).sliderRange(1, 256).build());

    private final Setting<Integer> yLevel = sgRender.add(new IntSetting.Builder()
        .name("y-level").description("Height the golden chunk is rendered at.")
        .defaultValue(64).range(-64, 320).sliderRange(-64, 320).build());
    private final Setting<Integer> height = sgRender.add(new IntSetting.Builder()
        .name("height").description("Thickness of the golden chunk in blocks.")
        .defaultValue(1).range(1, 64).sliderRange(1, 64).build());
    private final Setting<Boolean> beam = sgRender.add(new BoolSetting.Builder()
        .name("beam").description("Draws a tall pillar flag above each base.")
        .defaultValue(true).build());
    private final Setting<Integer> beamTop = sgRender.add(new IntSetting.Builder()
        .name("beam-top").description("Y level the pillar reaches.")
        .defaultValue(320).range(-64, 320).sliderRange(-64, 320).build());
    private final Setting<Boolean> rainbow = sgRender.add(new BoolSetting.Builder()
        .name("rainbow").description("Cycles the flag through rainbow colors (keeps the alpha of your colors).")
        .defaultValue(false).build());
    private final Setting<Double> rainbowSpeed = sgRender.add(new DoubleSetting.Builder()
        .name("rainbow-speed").description("How fast the rainbow cycles (cycles per 10 seconds).")
        .defaultValue(3.0).min(0.1).sliderRange(0.1, 20).visible(rainbow::get).build());
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("How the chunk is rendered.").defaultValue(ShapeMode.Both).build());
    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").description("Fill color of the chunk.")
        .defaultValue(new SettingColor(255, 200, 40, 70)).build());
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("Outline color of the chunk.")
        .defaultValue(new SettingColor(255, 200, 40, 255)).build());

    public DLChunkFinder() {
        super(DonutLarp.CATEGORY, "DL-chunk-finder", "Marks a golden chunk above every base on DonutLarp.");
        BaseStore.onNewBase(this::announce);
    }

    private void announce(BaseStore.Base b) {
        int x = b.chunkX() * 16 + 8, z = b.chunkZ() * 16 + 8;
        if (toast.get() && mc.getToastManager() != null)
            mc.getToastManager().add(new MeteorToast.Builder("Base Detected")
                .icon(Items.GOLD_BLOCK).text("X " + x + " Y " + b.y() + " Z " + z).build());
        if (sound.get() && mc.player != null)
            mc.player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;
        int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z;
        int max = maxDistance.get();
        double y = yLevel.get(), h = Math.max(0.02, height.get());
        Color side = sideColor.get(), line = lineColor.get();
        if (rainbow.get()) {
            float hue = (float) ((System.currentTimeMillis() * rainbowSpeed.get() / 10000.0) % 1.0);
            int rgb = java.awt.Color.HSBtoRGB(hue, 1f, 1f);
            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, bl = rgb & 0xFF;
            side = new Color(r, g, bl, sideColor.get().a);
            line = new Color(r, g, bl, lineColor.get().a);
        }
        for (BaseStore.Base b : BaseStore.all()) {
            int cx = b.chunkX(), cz = b.chunkZ();
            if (Math.abs(cx - pcx) > max || Math.abs(cz - pcz) > max) continue;
            double x = cx * 16.0, z = cz * 16.0;
            event.renderer.box(x, y, z, x + 16, y + h, z + 16,
                side, line, shapeMode.get(), 0);
            if (beam.get())
                event.renderer.box(x + 7.5, y, z + 7.5, x + 8.5, beamTop.get(), z + 8.5,
                    side, line, ShapeMode.Both, 0);
        }
    }
}
