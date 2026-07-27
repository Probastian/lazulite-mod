/**
 * Placeholder package: this feature introduces no new cross-module event bus
 * (spec Events section) -- {@code TweakRegistry} mutation methods both mutate
 * in-memory state and write-through immediately; there is no separate
 * pub/sub layer. Matches the sibling {@code features/*} modules' convention
 * of a placeholder {@code package-info.java} when a package is unused.
 */
package de.lazuli.features.tweaks.events;
