/**
 * Permanently empty. A {@code @Mixin} class by definition targets
 * {@code net.minecraft.*} classes, and this repo's layering forbids
 * {@code net.minecraft.*} imports outside {@code platform/fabric-*}. This
 * feature's Group 6 cloud-only synthetic world-select rows (FR6.8/FR6.9) do
 * need a real {@code @Mixin} (Pattern 2, per
 * {@code .claude/context/ui-guidelines.md}), but that mixin lives in each
 * {@code platform/fabric-<version>/.../mixin/} package instead, registered
 * in that module's own {@code *.mixins.json} -- never here, no matter how
 * this feature grows (mirroring
 * {@code features/hello-world-main-menu/.../mixins/package-info.java}).
 */
package de.lazuli.features.steamcloudsync.mixins;
