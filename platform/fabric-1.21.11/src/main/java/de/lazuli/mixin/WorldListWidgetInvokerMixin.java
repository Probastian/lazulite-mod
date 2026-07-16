package de.lazuli.mixin;

import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.client.gui.widget.EntryListWidget;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link EntryListWidget}'s otherwise-{@code protected}
 * {@code addEntry}/{@code clearEntries} methods, needed to append the FR6.9
 * synthetic cloud-only world rows onto {@code WorldListWidget} (a
 * {@code ui-guidelines.md} Pattern 2 injection).
 *
 * <p><strong>Confirmed via {@code javap} against this repo's actual resolved
 * 1.21.11 Minecraft jar</strong> (the implementation plan's own mandatory
 * first step, Risk 1): {@code WorldListWidget} extends
 * {@code AlwaysSelectedEntryListWidget<Entry>} extends
 * {@code EntryListWidget<E>}; {@code addEntry(E)}/{@code clearEntries()} are
 * declared {@code protected} on {@code EntryListWidget} itself -- exactly
 * matching the spec's/plan's working assumption for both the class name and
 * method visibility on this side of the obfuscation boundary.
 *
 * <p><strong>A second, more subtle finding beyond the plan's own working
 * assumption</strong>, only visible via {@code javap -v}'s {@code InnerClasses}
 * attribute (plain, non-verbose {@code javap} on the nested class file in
 * isolation misleadingly reports it as {@code public}): the {@code Entry}
 * type declared directly on {@code EntryListWidget} is itself
 * {@code protected}, not just its methods -- identical to the 26.x/Mojang
 * side's own {@code AbstractSelectionList.Entry}, so this is a structural
 * property of the class hierarchy shape, not an artifact of one mapping. The
 * {@code @Invoker} method below therefore declares its parameter as
 * {@link WorldListWidget}'s own concrete, <strong>public</strong> {@code Entry}
 * subtype rather than the inaccessible protected base type -- this compiles
 * cleanly and is the correct approach for {@code clearEntries()} (arity 0,
 * no parameter-visibility problem at all), and for javac's own view of
 * {@code addEntry(E)} on the unobfuscated 26.x/26.1 side (see
 * {@code WorldSelectionListInvokerMixin}, which uses the identical pattern
 * successfully with zero warnings, since those modules have no remap step).
 *
 * <p><strong>Open item, not resolved by this implementation pass -- flagged
 * honestly rather than asserted fixed:</strong> on <em>this</em> module only
 * (the sole platform module using {@code fabric-loom-remap}'s real,
 * obfuscated-Minecraft remap pipeline), a real {@code :remapJar} run prints
 * {@code "Cannot remap addEntry because it does not exist in any of the
 * targets [net/minecraft/client/gui/widget/EntryListWidget] or their
 * parents"} for the {@code addEntry} invoker specifically (never for
 * {@code clearEntries}, which has no generic-erased parameter) --
 * independent of whether this interface declares that parameter as
 * {@code WorldListWidget.Entry} or as plain {@link Object} (both tried; the
 * warning is identical either way, which rules out this being fixable purely
 * by choice of declared Java type on our end). This matches a documented,
 * known class of Fabric Loom/tiny-remapper limitation around remapping
 * {@code @Invoker}/{@code @Accessor} targets whose real parameter type is a
 * generic type variable (see e.g. FabricMC/tiny-remapper issues #124/#126),
 * not a mistake specific to this mixin. The overall Gradle build still
 * succeeds (a warning, not a build failure) and this class is confirmed
 * packaged correctly into the built jar (`jar tf`); whether Sponge
 * Mixin's own runtime name resolution (independent of this specific
 * build-time pre-remap pass) still correctly applies {@code addEntry} against
 * a real, obfuscated, launched 1.21.11 client has <strong>not</strong> been
 * confirmed here -- manual in-game verification is explicitly out of scope
 * for this implementation pass and is the single highest-priority check for
 * the verification phase to run first for Group 6 on this platform module.
 *
 * <p>See {@code .claude/context/minecraft.md}'s Known Cross-Version API
 * Differences table for the full confirmed method/visibility/remap record.
 *
 * <p>Usage example (from {@code FabricCloudOnlyWorldListInjector}):
 * <pre>{@code
 * WorldListWidgetInvokerMixin invoker = (WorldListWidgetInvokerMixin) worldListWidget;
 * invoker.lazuli$invokeAddEntry(new CloudOnlyWorldListEntry(summary, restoreHook));
 * }</pre>
 */
@Mixin(EntryListWidget.class)
public interface WorldListWidgetInvokerMixin {

    @Invoker("addEntry")
    int lazuli$invokeAddEntry(WorldListWidget.Entry entry);

    @Invoker("clearEntries")
    void lazuli$invokeClearEntries();
}
