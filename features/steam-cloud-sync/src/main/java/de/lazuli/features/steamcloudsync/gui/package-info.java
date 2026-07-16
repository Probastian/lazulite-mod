/**
 * Deliberately empty. Every screen/widget this feature needs
 * ({@code WorldRestoreScreen}, the Multiplayer bookmark-toggle widget, the
 * Singleplayer sync-toggle icon, the cloud-only synthetic world-select rows)
 * necessarily imports {@code net.minecraft.*}, which this repo's Dependency
 * Rules table restricts to {@code platform/} modules only. This feature's
 * own business logic exposes everything a platform Version Adapter needs
 * through plain {@code api}-layer hook interfaces
 * ({@code de.lazuli.api.cloudsync.*}) instead (see
 * {@code .claude/context/ui-guidelines.md}).
 */
package de.lazuli.features.steamcloudsync.gui;
