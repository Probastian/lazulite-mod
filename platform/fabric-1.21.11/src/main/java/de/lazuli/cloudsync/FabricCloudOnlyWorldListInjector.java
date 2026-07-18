package de.lazuli.cloudsync;

import de.lazuli.api.cloudsync.CloudOnlyWorldSummary;
import de.lazuli.api.cloudsync.CloudOnlyWorldsHook;
import de.lazuli.api.cloudsync.WorldRestoreHook;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.world.level.storage.LevelSummary;

import java.util.ArrayList;
import java.util.List;

/**
 * Version Adapter for Group 6's cloud-only synthetic world rows
 * (FR6.8/FR6.9) on Minecraft 1.21.11 (Yarn-mapped, obfuscated).
 *
 * <p>A Pattern 2 injection ({@code ui-guidelines.md}): locates the
 * {@link SelectWorldScreen}'s {@link WorldListWidget} child, calls
 * {@link CloudOnlyWorldsHook#listCloudOnlyWorlds(List)} with the local save
 * folder names read from every real {@link WorldListWidget.WorldEntry}
 * currently populated, and appends one {@link CloudOnlyWorldListEntry} per
 * summary via {@link EntryListWidgetReflection#addEntry} (a plain
 * reflective call, never a public API on this list otherwise -- see that
 * class's own JavaDoc for why reflection is used here instead of a
 * {@code @Mixin}).
 *
 * <p>Usage example (from this module's composition root):
 * <pre>{@code
 * new FabricCloudOnlyWorldListInjector(cloudOnlyWorldsHook, worldRestoreHook);
 * }</pre>
 */
public final class FabricCloudOnlyWorldListInjector {

    private final CloudOnlyWorldsHook cloudOnlyWorldsHook;
    private final WorldRestoreHook restoreHook;

    /**
     * @param cloudOnlyWorldsHook resolves the current cloud-only world list
     * @param restoreHook         opens/drives the restore flow when a
     *                             synthetic entry is "played" (FR6.10)
     */
    public FabricCloudOnlyWorldListInjector(CloudOnlyWorldsHook cloudOnlyWorldsHook, WorldRestoreHook restoreHook) {
        this.cloudOnlyWorldsHook = cloudOnlyWorldsHook;
        this.restoreHook = restoreHook;
        ScreenEvents.AFTER_INIT.register(this::onScreenInit);
    }

    private void onScreenInit(MinecraftClient client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!(screen instanceof SelectWorldScreen)) {
            return;
        }

        for (Object child : screen.children()) {
            if (!(child instanceof WorldListWidget list)) {
                continue;
            }

            List<String> localWorldFolderNames = new ArrayList<>();
            for (WorldListWidget.Entry entry : list.children()) {
                LevelSummary summary = entry.getLevel();
                if (summary != null) {
                    localWorldFolderNames.add(summary.getName());
                }
            }

            List<CloudOnlyWorldSummary> cloudOnlyWorlds = cloudOnlyWorldsHook.listCloudOnlyWorlds(localWorldFolderNames);
            for (CloudOnlyWorldSummary summary : cloudOnlyWorlds) {
                EntryListWidgetReflection.addEntry(list, new CloudOnlyWorldListEntry(summary, this::onPlaySelected));
            }
            return;
        }
    }

    private void onPlaySelected(CloudOnlyWorldSummary summary) {
        MinecraftClient.getInstance().setScreen(new WorldRestoreScreen(summary, restoreHook));
    }
}
