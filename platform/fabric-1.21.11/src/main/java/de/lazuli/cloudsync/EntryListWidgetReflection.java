package de.lazuli.cloudsync;

import net.minecraft.client.gui.widget.EntryListWidget;

import java.lang.reflect.Method;

/**
 * Calls {@link EntryListWidget}'s otherwise-{@code protected}
 * {@code addEntry}/{@code clearEntries} methods via plain reflection, needed
 * to append the FR6.9 synthetic cloud-only world rows onto
 * {@code WorldListWidget} (a {@code ui-guidelines.md} Pattern 2 injection --
 * no public API exists to insert a real, scrolling/selectable row into this
 * list from outside its own package).
 *
 * <p>The 1.21.11 (Yarn-mapped) counterpart of
 * {@code AbstractSelectionListReflection} -- see that class's own JavaDoc
 * for the full history of why reflection is used here instead of a
 * {@code @Mixin} (three different Mixin-based designs each failed for a
 * different confirmed reason: wrong compiled descriptor, Sponge Mixin's
 * hierarchy validator rejecting a mixin class that extends its own target,
 * and Mixin's package-ownership mechanism claiming an entire vanilla
 * package). Reflection sidesteps all three at once.
 */
final class EntryListWidgetReflection {

    private static final Method ADD_ENTRY = findMethod("addEntry", 1);
    private static final Method CLEAR_ENTRIES = findMethod("clearEntries", 0);

    private EntryListWidgetReflection() {
    }

    /**
     * @param list  the list to append to
     * @param entry must be an instance of the list's own {@code Entry} type
     *              (e.g. {@code CloudOnlyWorldListEntry})
     * @return the new entry's index
     */
    static int addEntry(EntryListWidget<?> list, Object entry) {
        try {
            return (int) ADD_ENTRY.invoke(list, entry);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to invoke EntryListWidget#addEntry via reflection", e);
        }
    }

    /** Removes every entry from {@code list}. */
    static void clearEntries(EntryListWidget<?> list) {
        try {
            CLEAR_ENTRIES.invoke(list);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to invoke EntryListWidget#clearEntries via reflection", e);
        }
    }

    private static Method findMethod(String name, int parameterCount) {
        for (Method method : EntryListWidget.class.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new IllegalStateException(
                "EntryListWidget has no declared method named \"" + name + "\" with " + parameterCount + " parameter(s)");
    }
}
