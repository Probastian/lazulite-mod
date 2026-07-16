package de.lazuli.api.cloudsync;

/**
 * An opaque identity for one in-flight {@link WorldRestoreHook#beginRestore}
 * attempt, usable only to cancel that same attempt via
 * {@link WorldRestoreHook#cancelRestore(RestoreHandle)}. Callers should treat
 * this as an opaque token -- its only meaningful field is the world slug it
 * was issued for, exposed solely for logging/debugging.
 *
 * <p>Usage example:
 * <pre>{@code
 * RestoreHandle handle = hook.beginRestore(worldSlug, listener);
 * // ... later, e.g. from a Cancel button:
 * hook.cancelRestore(handle);
 * }</pre>
 *
 * @param worldSlug the world slug this handle's restore attempt was issued for
 */
public record RestoreHandle(String worldSlug) {
}
