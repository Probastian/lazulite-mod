package de.lazuli.features.waypoints.services;

/**
 * Spec R5: deterministic color assignment computed from a waypoint's UUID
 * string, not creation order or a small fixed palette -- keeps colors stable
 * even if waypoints are added/deleted out of order, and avoids two
 * waypoints ever colliding on the exact same color from a small palette
 * running out of distinct entries.
 *
 * <p>Kept separate from {@link WaypointRegistry} specifically so it is
 * directly, deterministically unit-testable (same UUID in -&gt; same color
 * out, every time, across JVM runs -- no {@code Random}/timestamp involved).
 */
public final class WaypointColorAssigner {

    private static final float SATURATION = 0.65f;
    private static final float LIGHTNESS = 0.55f;

    private WaypointColorAssigner() {
    }

    /**
     * @param waypointId a waypoint's stable UUID string (spec R1)
     * @return a packed, fully-opaque ARGB color deterministically derived
     *         from {@code waypointId}
     */
    public static int colorFor(String waypointId) {
        // java.lang.String.hashCode() is specified by the JLS to be a fixed
        // algorithm (s[0]*31^(n-1) + ... + s[n-1] + s[n-1]) -- guaranteed
        // stable across JVM runs/versions, safe to rely on for a
        // deterministic, non-Random-based hue derivation.
        int hash = waypointId == null ? 0 : waypointId.hashCode();
        float hue = Math.floorMod(hash, 360) / 360f;
        int rgb = hslToRgb(hue, SATURATION, LIGHTNESS);
        return 0xFF000000 | rgb;
    }

    private static int hslToRgb(float h, float s, float l) {
        float c = (1f - Math.abs(2f * l - 1f)) * s;
        float hPrime = h * 6f;
        float x = c * (1f - Math.abs(hPrime % 2f - 1f));
        float r1;
        float g1;
        float b1;
        if (hPrime < 1f) {
            r1 = c;
            g1 = x;
            b1 = 0f;
        } else if (hPrime < 2f) {
            r1 = x;
            g1 = c;
            b1 = 0f;
        } else if (hPrime < 3f) {
            r1 = 0f;
            g1 = c;
            b1 = x;
        } else if (hPrime < 4f) {
            r1 = 0f;
            g1 = x;
            b1 = c;
        } else if (hPrime < 5f) {
            r1 = x;
            g1 = 0f;
            b1 = c;
        } else {
            r1 = c;
            g1 = 0f;
            b1 = x;
        }
        float m = l - c / 2f;
        int r = clamp(Math.round((r1 + m) * 255f));
        int g = clamp(Math.round((g1 + m) * 255f));
        int b = clamp(Math.round((b1 + m) * 255f));
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
