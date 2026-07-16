package de.lazuli.mixin;

import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link AbstractSelectionList}'s otherwise-{@code protected}
 * {@code addEntry}/{@code clearEntries} methods, needed to append the FR6.9
 * synthetic cloud-only world rows onto {@code WorldSelectionList} (a
 * {@code ui-guidelines.md} Pattern 2 injection -- no public API exists to
 * insert a real, scrolling/selectable row into this list from outside its
 * own package).
 *
 * <p><strong>Confirmed via {@code javap} against this repo's actual resolved
 * {@code 26.2}/{@code 26.1} Minecraft jars</strong> (the implementation
 * plan's own mandatory first step, Risk 1): {@code WorldSelectionList}
 * extends {@code ObjectSelectionList<Entry>} extends
 * {@code AbstractSelectionList<E>} -- <strong>not</strong>
 * {@code EntryListWidget} (that Yarn-era base-class name does not exist
 * under Mojang's official mapping at all); {@code addEntry(E)}/
 * {@code clearEntries()} are declared {@code protected} on
 * {@code AbstractSelectionList} itself.
 *
 * <p><strong>A second, more subtle finding beyond the plan's own working
 * assumption</strong>, only visible via {@code javap -v}'s {@code InnerClasses}
 * attribute (plain, non-verbose {@code javap} on the nested class file in
 * isolation misleadingly reports it as {@code public}): the {@code Entry}
 * type declared directly on {@code AbstractSelectionList} is itself
 * {@code protected}, not just its methods -- so {@code addEntry}'s parameter
 * type cannot even be <em>named</em> from this mixin's package
 * ({@code de.lazuli.mixin}), which is neither the same package as
 * {@code AbstractSelectionList} nor a subclass of it. The fix used here
 * (a standard Sponge Mixin idiom for this exact situation): declare the
 * {@code @Invoker} method's parameter using {@link WorldSelectionList}'s own
 * concrete, <strong>public</strong> {@code Entry} subtype instead of the
 * protected base type -- Mixin generates the actual bytecode against the
 * real (protected) target descriptor directly from its own ASM-level
 * knowledge of the target method, and a subtype argument is always legal at
 * the JVM level (an ordinary widening reference conversion), so no cast or
 * further workaround is needed at the call site.
 *
 * <p>Mixing onto the class that actually declares these methods (rather than
 * the {@code WorldSelectionList} subclass) means the generated invoker still
 * resolves polymorphically to {@code WorldSelectionList}'s own overridden
 * {@code clearEntries()} when called on a {@code WorldSelectionList} instance
 * (virtual dispatch from within the mixin-applied class), so its
 * entry-closing cleanup is preserved. See
 * {@code .claude/context/minecraft.md}'s Known Cross-Version API Differences
 * table for the full confirmed method/visibility record.
 *
 * <p>Usage example (from {@code FabricCloudOnlyWorldListInjector}):
 * <pre>{@code
 * WorldSelectionListInvokerMixin invoker = (WorldSelectionListInvokerMixin) worldSelectionList;
 * invoker.lazuli$invokeAddEntry(new CloudOnlyWorldListEntry(summary, restoreHook));
 * }</pre>
 */
@Mixin(AbstractSelectionList.class)
public interface WorldSelectionListInvokerMixin {

    @Invoker("addEntry")
    int lazuli$invokeAddEntry(WorldSelectionList.Entry entry);

    @Invoker("clearEntries")
    void lazuli$invokeClearEntries();
}
