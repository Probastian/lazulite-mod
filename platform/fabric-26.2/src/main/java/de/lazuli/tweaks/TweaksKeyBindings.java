package de.lazuli.tweaks;

import de.lazuli.api.tweaks.TweakId;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;
import java.util.Map;

/**
 * Holds the 12 primary + 1 secondary (Anti-Drop) vanilla {@link KeyMapping}
 * instances (spec F1/F3), registered once at {@code onInitializeClient()}
 * time via {@code KeyMappingHelper.registerKeyMapping} under one shared
 * category. This is the platform-side equivalent of the spec's
 * {@code TweakRegistry.keyBindingOf(TweakId): KeyBinding} -- see
 * {@code de.lazuli.api.tweaks.TweakDefinition}'s Javadoc for why the actual
 * {@code KeyMapping} objects don't live in the plain-Java
 * {@code features/tweaks} module or {@code :api}.
 *
 * <p><strong>Confirmed via {@code javap} against this module's own resolved
 * Minecraft jar</strong> (26.2, Mojmap): {@code KeyMapping}'s category
 * parameter is a {@code KeyMapping.Category} record (registered via
 * {@code KeyMapping.Category.register(Identifier)}), not a plain
 * {@code String} as older Minecraft-modding convention/the spec's own
 * shorthand ("category translation key string") implied -- this diverges
 * from the pre-1.21 `String category` shape most existing modding docs
 * describe. See {@code .claude/context/minecraft.md}'s Known Cross-Version
 * API Differences table, "Key binding category" row, for the confirmed
 * Yarn-vs-Mojmap equivalents.
 */
public final class TweaksKeyBindings {

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("lazuli", "tweaks"));

    private final Map<TweakId, KeyMapping> primary = new EnumMap<>(TweakId.class);
    private final KeyMapping antiDropSecondary;

    public TweaksKeyBindings() {
        for (TweakId id : TweakId.values()) {
            String translationKey = "key.lazuli." + id.name().toLowerCase(java.util.Locale.ROOT);
            KeyMapping mapping = new KeyMapping(translationKey, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
            primary.put(id, KeyMappingHelper.registerKeyMapping(mapping));
        }
        antiDropSecondary = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.lazuli.anti_drop_toggle_whitelist", GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
    }

    public KeyMapping keyBindingOf(TweakId id) {
        return primary.get(id);
    }

    /** Non-null only for {@link TweakId#ANTI_DROP}; {@code null} for every other tweak. */
    public KeyMapping secondaryKeyBindingOf(TweakId id) {
        return id == TweakId.ANTI_DROP ? antiDropSecondary : null;
    }
}
