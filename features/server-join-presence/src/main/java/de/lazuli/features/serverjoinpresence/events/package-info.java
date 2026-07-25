/**
 * Placeholder package. State changes this feature cares about (connecting to
 * / disconnecting from a real multiplayer server) are observed at the
 * platform composition-root level via Fabric API's
 * {@code ClientPlayConnectionEvents}, not a custom event bus (spec Events).
 * No shareable common-Java event code lives here.
 */
package de.lazuli.features.serverjoinpresence.events;
