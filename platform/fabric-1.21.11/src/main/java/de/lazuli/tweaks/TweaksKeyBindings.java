package de.lazuli.tweaks;

import de.lazuli.api.tweaks.TweakId;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;
import java.util.Map;

/**
 * Holds the 12 primary + 1 secondary (Anti-Drop) vanilla {@link KeyBinding}
 * instances (spec F1/F3), registered once at {@code onInitializeClient()}
 * time via {@code KeyBindingHelper.registerKeyBinding} under one shared
 * category. Yarn-mapped port of {@code fabric-26.1}/{@code fabric-26.2}'s
 * class of the same name -- see {@code .claude/context/minecraft.md}'s Known
 * Cross-Version API Differences table, "Hotkey registration Fabric API
 * submodule" row, for the confirmed Yarn-vs-Mojmap equivalents (artifact
 * name, class names, and the {@code Category} record shape all differ, not
 * just the mapping).
 */
public final class TweaksKeyBindings {

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("lazuli", "tweaks"));

    private final Map<TweakId, KeyBinding> primary = new EnumMap<>(TweakId.class);
    private final KeyBinding antiDropSecondary;
    private final KeyBinding lootBinSecondary;

    public TweaksKeyBindings() {
        for (TweakId id : TweakId.values()) {
            String translationKey = "key.lazuli." + id.name().toLowerCase(java.util.Locale.ROOT);
            KeyBinding binding = new KeyBinding(translationKey, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
            primary.put(id, KeyBindingHelper.registerKeyBinding(binding));
        }
        antiDropSecondary = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.lazuli.anti_drop_toggle_whitelist", GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
        lootBinSecondary = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.lazuli.loot_bin_toggle_view", GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
    }

    public KeyBinding keyBindingOf(TweakId id) {
        return primary.get(id);
    }

    /**
     * Non-null for {@link TweakId#ANTI_DROP} (whitelist toggle) and
     * {@link TweakId#LOOT_BIN} (grouped/vanilla-grid view toggle, spec R15);
     * {@code null} for every other tweak.
     */
    public KeyBinding secondaryKeyBindingOf(TweakId id) {
        return switch (id) {
            case ANTI_DROP -> antiDropSecondary;
            case LOOT_BIN -> lootBinSecondary;
            default -> null;
        };
    }
}
