/**
 * Placeholder package, deliberately empty for v1.
 *
 * <p>GUI/widget code necessarily touches {@code net.minecraft.*} classes,
 * which FR8 of this feature's {@code specification.md} forbids outside
 * {@code platform/fabric-*}. There is therefore no legal place for real GUI
 * source inside this feature module: the widget-adding code lives entirely
 * in each platform module's {@code FabricMainMenuHook}
 * ({@code de.lazuli.mainmenu.FabricMainMenuHook}). This
 * package exists only so the required {@code gui/} folder from
 * {@code feature-guidelines.md} is present and discoverable.
 */
package de.lazuli.features.helloworldmainmenu.gui;


