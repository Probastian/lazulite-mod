/**
 * Placeholder package, permanently empty for this feature.
 *
 * <p>Unlike {@code events/} and {@code gui/}, this is not "empty for now":
 * because FR8 of this feature's {@code specification.md} bans
 * {@code net.minecraft.*} imports outside {@code platform/fabric-*}, and a
 * Mixin by definition targets {@code net.minecraft.*} classes, no feature
 * module in this architecture can ever legally contain a real
 * {@code @Mixin} class. This feature also does not need one -- it hooks the
 * title screen entirely through Fabric API's documented, non-mixin
 * {@code ScreenEvents}/{@code Screens} API (see NFR1). Any future mixin need
 * for this feature would live in {@code platform/} instead. This package
 * exists only so the required {@code mixins/} folder from
 * {@code feature-guidelines.md} is present and discoverable.
 */
package de.lazuli.features.helloworldmainmenu.mixins;


