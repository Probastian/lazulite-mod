/**
 * Deliberately empty. Fabric event <em>registration</em> itself is a
 * {@code net.fabricmc.fabric.api.*} import ({@code ClientTickEvents},
 * {@code ClientLifecycleEvents}, {@code ClientPlayConnectionEvents}), which
 * this repo's layering restricts to {@code platform/} Version Adapters and
 * composition roots only -- never a {@code features/*} module (mirroring
 * {@code features/hello-world-main-menu}'s own FR8 precedent). This feature
 * also defines no new cross-feature event bus type (none exists yet in this
 * repo, {@code architecture.md:26}, and this feature has no second-feature
 * reason to build one) -- internal group-to-group signaling (e.g. "a note
 * was added, sync it") is plain in-process method calls within
 * {@code features/steam-cloud-sync}.
 */
package de.lazuli.features.steamcloudsync.events;
