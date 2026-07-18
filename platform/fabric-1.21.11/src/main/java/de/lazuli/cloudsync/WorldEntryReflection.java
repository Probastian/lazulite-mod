package de.lazuli.cloudsync;

import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.world.level.storage.LevelSummary;

import java.lang.reflect.Method;

/**
 * Reads {@code WorldListWidget.WorldEntry}'s bounds (`getX()`/`getY()`/
 * `getWidth()`) and identity (`getLevel()`) accessors via reflection instead
 * of {@code @Shadow}.
 *
 * <p>All four are {@code public}, but declared on an <em>ancestor</em> class
 * ({@code EntryListWidget.Entry}), not on {@code WorldEntry} itself --
 * {@code @Shadow} only resolves members declared directly on the exact
 * {@code @Mixin} target class, so it fails with
 * {@code "was not located in the target class ... $WorldEntry"} for anything
 * merely inherited (confirmed via a real in-game crash). {@link Class#getMethod}
 * (unlike {@code getDeclaredMethod}) searches the full public inheritance
 * chain, so it finds these without needing to know or name the exact
 * ancestor class that declares them.
 *
 * <p><strong>Deliberately lives outside {@code de.lazuli.mixin}</strong> (a
 * real crash, confirmed in-game): {@code lazuli.mixins.json} declares
 * {@code "package": "de.lazuli.mixin"}, and Sponge Mixin's classloader
 * forbids directly referencing <em>any</em> class inside a mod's declared
 * mixin package from ordinary code -- even a plain helper class with no
 * {@code @Mixin} annotation at all. The mixin class that uses this helper
 * ({@code WorldEntrySyncIconMixin}) stays in {@code de.lazuli.mixin} and
 * simply imports this class from here; that direction (mixin package
 * importing outward) is unaffected by the restriction, which only blocks
 * outside code reaching *into* the mixin package.
 */
public final class WorldEntryReflection {

    private static final Method GET_X = findMethod("getX");
    private static final Method GET_Y = findMethod("getY");
    private static final Method GET_WIDTH = findMethod("getWidth");
    private static final Method GET_LEVEL = findMethod("getLevel");

    private WorldEntryReflection() {
    }

    public static int getX(Object entry) {
        return (int) invoke(GET_X, entry);
    }

    public static int getY(Object entry) {
        return (int) invoke(GET_Y, entry);
    }

    public static int getWidth(Object entry) {
        return (int) invoke(GET_WIDTH, entry);
    }

    public static LevelSummary getLevel(Object entry) {
        return (LevelSummary) invoke(GET_LEVEL, entry);
    }

    private static Object invoke(Method method, Object entry) {
        try {
            return method.invoke(entry);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to invoke WorldListWidget.WorldEntry#" + method.getName() + " via reflection", e);
        }
    }

    private static Method findMethod(String name) {
        try {
            return WorldListWidget.WorldEntry.class.getMethod(name);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("WorldListWidget.WorldEntry has no public method named \"" + name + "\"", e);
        }
    }
}
