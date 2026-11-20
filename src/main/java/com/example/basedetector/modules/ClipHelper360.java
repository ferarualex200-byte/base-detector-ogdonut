package com.example.basedetector.modules;

import com.example.basedetector.BaseDetectorAddon;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Xray;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;

import java.util.*;

public class ClipHelper360 extends Module {
    // Every block that can show up in caves. Only these appear in the block picker.
    private static final Set<Block> CAVE_BLOCKS = new LinkedHashSet<>(Arrays.asList(
        // Stone and terrain
        Blocks.STONE, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE, Blocks.TUFF,
        Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.DIRT,
        Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.GRAVEL, Blocks.SAND,
        Blocks.SANDSTONE, Blocks.CLAY, Blocks.MUD, Blocks.CALCITE,
        Blocks.SMOOTH_BASALT, Blocks.BASALT, Blocks.OBSIDIAN, Blocks.MAGMA_BLOCK,
        Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.SPAWNER,
        Blocks.INFESTED_STONE, Blocks.INFESTED_DEEPSLATE,
        Blocks.SNOW_BLOCK, Blocks.POWDER_SNOW, Blocks.ICE, Blocks.PACKED_ICE,

        // Ores
        Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
        Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
        Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
        Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
        Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
        Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
        Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
        Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,

        // Dripstone caves
        Blocks.DRIPSTONE_BLOCK, Blocks.POINTED_DRIPSTONE,

        // Lush caves
        Blocks.MOSS_BLOCK, Blocks.MOSS_CARPET, Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT,
        Blocks.GLOW_LICHEN, Blocks.AZALEA, Blocks.FLOWERING_AZALEA,
        Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM, Blocks.SMALL_DRIPLEAF,
        Blocks.SPORE_BLOSSOM, Blocks.HANGING_ROOTS,

        // Amethyst geodes
        Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST, Blocks.AMETHYST_CLUSTER,
        Blocks.LARGE_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.SMALL_AMETHYST_BUD,

        // Deep dark
        Blocks.SCULK, Blocks.SCULK_VEIN, Blocks.SCULK_CATALYST,
        Blocks.SCULK_SHRIEKER, Blocks.SCULK_SENSOR, Blocks.CALIBRATED_SCULK_SENSOR,

        // Mineshafts
        Blocks.COBWEB, Blocks.OAK_PLANKS, Blocks.OAK_FENCE, Blocks.RAIL,
        Blocks.TORCH, Blocks.WALL_TORCH,

        // Nether
        Blocks.NETHERRACK, Blocks.BLACKSTONE, Blocks.SOUL_SAND, Blocks.SOUL_SOIL,
        Blocks.GLOWSTONE, Blocks.NETHER_QUARTZ_ORE, Blocks.NETHER_GOLD_ORE,
        Blocks.ANCIENT_DEBRIS,

        // End
        Blocks.END_STONE
    ));

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Cave blocks to hide while the module is on.")
        .defaultValue(new ArrayList<>(CAVE_BLOCKS))
        .filter(CAVE_BLOCKS::contains)
        .build()
    );

    private boolean wasXrayOn;
    private Object oldMode, oldBlocks, oldOpacity;

    public ClipHelper360() {
        super(BaseDetectorAddon.CATEGORY, "360-clip-helper", "Hides caves and natural terrain so they don't ruin your 360 clips.");
    }

    // Finds an enum value by name (e.g. "blacklist") without needing the exact enum class.
    private Object findEnum(Object current, String name) {
        if (!(current instanceof Enum<?> e)) return null;
        for (Object c : e.getDeclaringClass().getEnumConstants()) {
            if (((Enum<?>) c).name().equalsIgnoreCase(name)) return c;
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public void onActivate() {
        Xray xray = Modules.get().get(Xray.class);
        wasXrayOn = xray.isActive();

        Setting mode = xray.settings.get("list-mode");
        Setting list = xray.settings.get("blocks");
        Setting opacity = xray.settings.get("opacity");

        oldMode = mode.get();
        oldBlocks = new ArrayList<>((List<Block>) list.get());
        oldOpacity = opacity.get();

        Object blacklist = findEnum(oldMode, "blacklist");
        if (blacklist != null) mode.set(blacklist);
        list.set(new ArrayList<>(blocks.get()));
        opacity.set(0);

        if (wasXrayOn) xray.toggle(); // re-toggle so it reloads with the new settings
        xray.toggle();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public void onDeactivate() {
        Xray xray = Modules.get().get(Xray.class);

        if (xray.isActive()) xray.toggle();

        Setting mode = xray.settings.get("list-mode");
        Setting list = xray.settings.get("blocks");
        Setting opacity = xray.settings.get("opacity");

        mode.set(oldMode);
        list.set(oldBlocks);
        opacity.set(oldOpacity);

        if (wasXrayOn) xray.toggle();
    }
}
