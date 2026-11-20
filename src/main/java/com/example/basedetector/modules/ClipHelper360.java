package com.example.basedetector.modules;

import com.example.basedetector.BaseDetectorAddon;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Xray;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;

import java.util.*;

public class ClipHelper360 extends Module {
    // Natural terrain, ores, liquids and cave-only blocks get hidden.
    // Anything players build or decorate with is NOT in this list.
    private static final Set<Block> CAVE_BLOCKS = new LinkedHashSet<>(Arrays.asList(
        // Natural stone and terrain
        Blocks.STONE, Blocks.DEEPSLATE, Blocks.TUFF,
        Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE,
        Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.GRASS_BLOCK,
        Blocks.GRAVEL, Blocks.SAND, Blocks.RED_SAND, Blocks.CLAY, Blocks.MUD,
        Blocks.MAGMA_BLOCK, Blocks.BEDROCK,
        Blocks.INFESTED_STONE, Blocks.INFESTED_DEEPSLATE,

        // Liquids
        Blocks.WATER, Blocks.LAVA,

        // Ores
        Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
        Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
        Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
        Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
        Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
        Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
        Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
        Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,

        // Cave-only features
        Blocks.DRIPSTONE_BLOCK, Blocks.POINTED_DRIPSTONE,
        Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT, Blocks.HANGING_ROOTS,
        Blocks.SCULK, Blocks.SCULK_VEIN, Blocks.REINFORCED_DEEPSLATE,

        // Nether natural
        Blocks.NETHERRACK, Blocks.NETHER_QUARTZ_ORE, Blocks.NETHER_GOLD_ORE,
        Blocks.ANCIENT_DEBRIS
    ));

    private boolean wasXrayOn;
    private Object oldBlocks, oldOpacity;

    public ClipHelper360() {
        super(BaseDetectorAddon.CATEGORY, "360-clip-helper", "Hides caves and natural terrain so they don't ruin your 360 clips.");
    }

    private Setting<?> getListSetting(Xray xray) {
        Setting<?> s = xray.settings.get("whitelist");
        if (s == null) s = xray.settings.get("blocks"); // very old versions
        return s;
    }

    private String dumpNames(Xray xray) {
        StringBuilder sb = new StringBuilder();
        for (SettingGroup g : xray.settings) for (Setting<?> s : g) sb.append(s.name).append(", ");
        return sb.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public void onActivate() {
        Xray xray = Modules.get().get(Xray.class);
        wasXrayOn = xray.isActive();

        Setting list = getListSetting(xray);
        Setting opacity = xray.settings.get("opacity");

        if (list == null || opacity == null) {
            error("Couldn't find Xray's settings. Found: " + dumpNames(xray));
            toggle();
            return;
        }

        oldBlocks = new ArrayList<>((List<Block>) list.get());
        oldOpacity = opacity.get();

        // Xray only has a whitelist (blocks that stay visible),
        // so whitelist everything EXCEPT the cave blocks.
        List<Block> visible = new ArrayList<>();
        for (Block b : Registries.BLOCK) {
            if (b == Blocks.AIR) continue;
            if (!CAVE_BLOCKS.contains(b)) visible.add(b);
        }
        list.set(visible);
        opacity.set(0);

        if (wasXrayOn) xray.toggle(); // reload with the new settings
        xray.toggle();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public void onDeactivate() {
        Xray xray = Modules.get().get(Xray.class);

        if (xray.isActive()) xray.toggle();

        Setting list = getListSetting(xray);
        Setting opacity = xray.settings.get("opacity");
        if (list == null || opacity == null || oldBlocks == null) return;

        list.set(oldBlocks);
        opacity.set(oldOpacity);

        if (wasXrayOn) xray.toggle();
    }
}
