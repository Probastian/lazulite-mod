package de.lazuli.api.mainmenu;

/**
 * The two contexts {@code MainMenuScreen} (per platform module) can be
 * constructed in (main-menu-pause-integration spec FR1.2): fixed once at
 * construction time, never re-derived per frame from ambient state.
 *
 * <p>{@link #MAIN_MENU}: the title-screen replacement (unchanged behavior
 * from before this feature -- 3D background, {@link MainMenuTab#HOME} tab
 * present, {@code isPauseScreen()}/{@code shouldPause()} false).
 *
 * <p>{@link #PAUSE}: the in-world pause-menu replacement (Esc while a world
 * is running) -- blurred/frozen-world background instead of the 3D scene,
 * {@link MainMenuTab#PAUSE} tab in place of {@link MainMenuTab#HOME}.
 */
public enum MainMenuContext {
    MAIN_MENU,
    PAUSE
}
