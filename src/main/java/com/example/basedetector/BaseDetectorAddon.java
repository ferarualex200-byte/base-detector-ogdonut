package com.example.basedetector;
import com.example.basedetector.modules.BaseDetector;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import com.example.basedetector.modules.SpawnerDetector;
import meteordevelopment.meteorclient.systems.modules.Category;
import com.example.basedetector.modules.ClipHelper360;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class BaseDetectorAddon extends MeteorAddon {
    public static final Category CATEGORY = new Category("BaseDetector");

    @Override
    public void onInitialize() {
        Modules.get().add(new BaseDetector());
        Modules.get().add(new SpawnerDetector());
        Modules.get().add(new ClipHelper360());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.example.basedetector";
    }
}
