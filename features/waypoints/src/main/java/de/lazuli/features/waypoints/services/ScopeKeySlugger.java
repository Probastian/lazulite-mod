package de.lazuli.features.waypoints.services;

/**
 * Spec R6: turns a scope key (a singleplayer world's save-folder name, or a
 * multiplayer server's {@code host:port} address, per R3) into a
 * filesystem-safe filename component.
 *
 * <p>Genuinely new code, not a reuse of an existing sanitizer -- see the
 * implementation plan's "Scope-key filename sanitization" note, which found
 * no existing sanitize/slugify helper anywhere in this codebase to reuse
 * (a save-folder name is already filesystem-safe by construction; only a
 * server address, which can contain {@code :}, needs this). Kept as its own
 * small class specifically so it is directly unit-testable in isolation.
 *
 * <p>Collision behavior (e.g. two different addresses slugging to the same
 * string) is an accepted, documented limitation, not solved here -- matches
 * this codebase's existing address-keying tradeoff already accepted for
 * bookmarks/last-played (spec R3's own carried-forward Open Question).
 */
public final class ScopeKeySlugger {

    private ScopeKeySlugger() {
    }

    /**
     * @param scopeKey the raw scope key (spec R3)
     * @return a lowercased, filesystem-safe slug: every character outside
     *         {@code [a-z0-9_-]} is replaced with {@code _}
     */
    public static String slug(String scopeKey) {
        if (scopeKey == null || scopeKey.isEmpty()) {
            return "_";
        }
        StringBuilder sb = new StringBuilder(scopeKey.length());
        for (int i = 0; i < scopeKey.length(); i++) {
            char c = Character.toLowerCase(scopeKey.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
