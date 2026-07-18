package de.lazuli.cloudsync;

import net.minecraft.client.gui.components.AbstractSelectionList;

import java.lang.reflect.Method;

/**
 * Calls {@link AbstractSelectionList}'s otherwise-{@code protected}
 * {@code addEntry}/{@code clearEntries} methods via plain reflection, needed
 * to append the FR6.9 synthetic cloud-only world rows onto
 * {@code WorldSelectionList} (a {@code ui-guidelines.md} Pattern 2 injection
 * -- no public API exists to insert a real, scrolling/selectable row into
 * this list from outside its own package).
 *
 * <p><strong>Reflection, not a {@code @Mixin}, after three Mixin-based
 * designs each failed for a different confirmed reason</strong> (full
 * history preserved in git and {@code minecraft.md}'s Known Cross-Version
 * API Differences table):
 * <ol>
 * <li>A plain {@code @Invoker} interface declaring the parameter as the
 * public {@code WorldSelectionList.Entry} subtype compiled, but produced the
 * wrong compiled descriptor (the real method erases its parameter to
 * {@code AbstractSelectionList$Entry}, not the subtype) -- a real in-game
 * {@code InvalidAccessorException} crash on every screen using any vanilla
 * scrolling list.</li>
 * <li>Fixing the descriptor required declaring the parameter as the real,
 * protected {@code AbstractSelectionList.Entry}, which in turn required an
 * abstract class extending the raw target (only a real subclass can name a
 * protected type from outside its package) plus a separate "duck interface"
 * for callers to cast to. This compiled and ran, but Sponge Mixin's own
 * hierarchy validator rejects a mixin class declaring
 * {@code extends TargetItself} -- a real, reproducible in-game
 * {@code InvalidMixinException}, present on every platform module.</li>
 * <li>Declaring the {@code @Invoker} interface directly inside
 * {@code AbstractSelectionList}'s own package (same-package protected access,
 * no {@code extends} needed) compiled and avoided the hierarchy-validator
 * problem, but Sponge Mixin's mixin-package-ownership mechanism claims the
 * *entire* declared package, not just the mixin classes inside it -- a real,
 * immediate crash trying to load vanilla's own unrelated
 * {@code Renderable}, itself also in {@code net.minecraft.client.gui.components}.</li>
 * </ol>
 *
 * <p>Reflection sidesteps every one of these constraints at once: no Java
 * source-level type needs to be written for the protected {@code Entry}
 * parameter at all (a {@link Method} found by name/arity and invoked with a
 * plain {@link Object} argument), so there is no descriptor to get wrong, no
 * type to gain access to via inheritance, and no Mixin package-ownership
 * question. {@code setAccessible(true)} is sufficient here because Fabric
 * Loader's classloader does not run Minecraft/mods under strict Java
 * Platform Module System encapsulation.
 */
final class AbstractSelectionListReflection {

    private static final Method ADD_ENTRY = findMethod("addEntry", 1);
    private static final Method CLEAR_ENTRIES = findMethod("clearEntries", 0);

    private AbstractSelectionListReflection() {
    }

    /**
     * @param list  the list to append to
     * @param entry must be an instance of the list's own {@code Entry} type
     *              (e.g. {@code CloudOnlyWorldListEntry})
     * @return the new entry's index
     */
    static int addEntry(AbstractSelectionList<?> list, Object entry) {
        try {
            return (int) ADD_ENTRY.invoke(list, entry);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to invoke AbstractSelectionList#addEntry via reflection", e);
        }
    }

    /** Removes every entry from {@code list}. */
    static void clearEntries(AbstractSelectionList<?> list) {
        try {
            CLEAR_ENTRIES.invoke(list);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to invoke AbstractSelectionList#clearEntries via reflection", e);
        }
    }

    private static Method findMethod(String name, int parameterCount) {
        for (Method method : AbstractSelectionList.class.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new IllegalStateException(
                "AbstractSelectionList has no declared method named \"" + name + "\" with " + parameterCount + " parameter(s)");
    }
}
