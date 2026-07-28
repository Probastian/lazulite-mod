package de.lazuli.tweaks;

import de.lazuli.api.tweaks.TweakId;
import de.lazuli.api.tweaks.TweakState;
import de.lazuli.features.tweaks.services.AntiDropHook;
import de.lazuli.features.tweaks.services.ChatFilterHook;
import de.lazuli.features.tweaks.services.ChatPlayerHeadsHook;
import de.lazuli.features.tweaks.services.ClearWaterHook;
import de.lazuli.features.tweaks.services.CustomCrosshairHook;
import de.lazuli.features.tweaks.services.DisableAnimationsHook;
import de.lazuli.features.tweaks.services.DisableBossBarsHook;
import de.lazuli.features.tweaks.services.DisableCosmeticsHook;
import de.lazuli.features.tweaks.services.DisableParticlesHook;
import de.lazuli.features.tweaks.services.ForceBrightnessHook;
import de.lazuli.features.tweaks.services.HidePlayerNamesHook;
import de.lazuli.features.tweaks.services.TweakRegistry;
import de.lazuli.features.tweaks.services.ZoomHook;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A single platform-side class implementing all 12 Minecraft-agnostic hook
 * interfaces (Architecture Decision 2 in the implementation plan), reading
 * live state straight off {@link TweakRegistry} on every call (no caching,
 * matching spec F6/Events' "no stale copy" requirement).
 *
 * <p><strong>Scope note (implementation batch 1 of this feature):</strong>
 * this class provides real, correct <em>state-reading</em> logic for every
 * hook per the tweak's own documented rules (spec Requirements T1-T12), but
 * the actual vanilla render/input <em>call sites</em> that invoke these hook
 * methods (mixins into item-drop handling, the lightmap/gamma pipeline, chat
 * message construction/render, animated-texture ticking, particle spawning,
 * name-tag rendering, the underwater overlay, the crosshair HUD element, FOV
 * computation, and the boss-bar HUD render) are the plan's own explicitly
 * flagged Risk #1 -- a full per-tweak, per-platform {@code javap}
 * verification pass, deliberately deferred to a follow-up implementation
 * batch per the plan's Risk #7 ("expect implementation to be delivered/
 * reviewed in multiple batches"). {@link de.lazuli.tweaks.ZoomTicker} tracks
 * Zoom's hold/toggle key-state, but note that state is NOT yet applied to the
 * client's actual FOV anywhere -- applyFov() here has no live call site, so
 * Zoom, like the other 11 tweaks, has no visible in-game effect yet.
 */
public final class TweakHooksImpl implements AntiDropHook, ForceBrightnessHook, ChatFilterHook, ChatPlayerHeadsHook,
        CustomCrosshairHook, DisableAnimationsHook, DisableParticlesHook, HidePlayerNamesHook, ClearWaterHook,
        DisableCosmeticsHook, ZoomHook, DisableBossBarsHook {

    private final TweakRegistry registry;
    private boolean zoomActive;
    private float zoomFactor = 1.0f;
    private long zoomTransitionStartNanos;
    private float zoomTransitionStartFactor = 1.0f;

    public TweakHooksImpl(TweakRegistry registry) {
        this.registry = registry;
    }

    private TweakState state(TweakId id) {
        return registry.stateOf(id);
    }

    @Override
    public boolean shouldCancelDrop(String itemId, boolean shiftHeld) {
        TweakState s = state(TweakId.ANTI_DROP);
        if (!s.enabled()) {
            return false;
        }
        boolean shiftForces = Boolean.TRUE.equals(s.configurable("shiftQForceDrop"));
        if (shiftHeld && shiftForces) {
            return false;
        }
        return whitelist(s).contains(itemId);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> whitelist(TweakState s) {
        Object raw = s.configurable("whitelist");
        if (raw instanceof List<?> list) {
            return Set.copyOf((List<String>) list);
        }
        return Set.of();
    }

    @Override
    public boolean isForceBrightnessActive() {
        return state(TweakId.FORCE_BRIGHTNESS).enabled();
    }

    @Override
    public float minBrightness() {
        Object raw = state(TweakId.FORCE_BRIGHTNESS).configurable("minBrightness");
        return raw instanceof Number n ? n.floatValue() : 4.0f;
    }

    @Override
    public String filterText(String plainText) {
        TweakState s = state(TweakId.CHAT_FILTER);
        if (!s.enabled() || plainText == null) {
            return plainText;
        }
        // Built-in list intentionally left empty in this batch (no shipped
        // slur/profanity list is checked into this repo) -- only user-supplied
        // custom terms are honored, so the tweak is never a no-op when enabled
        // with custom terms configured.
        String result = plainText;
        Object rawTerms = s.configurable("customTerms");
        if (rawTerms instanceof List<?> terms) {
            for (Object term : terms) {
                if (term instanceof String t && !t.isEmpty()) {
                    result = replaceIgnoreCase(result, t);
                }
            }
        }
        return result;
    }

    private static String replaceIgnoreCase(String text, String term) {
        StringBuilder sb = new StringBuilder();
        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerTerm = term.toLowerCase(Locale.ROOT);
        int i = 0;
        while (i < text.length()) {
            int idx = lowerText.indexOf(lowerTerm, i);
            if (idx < 0) {
                sb.append(text, i, text.length());
                break;
            }
            sb.append(text, i, idx).append("*".repeat(term.length()));
            i = idx + term.length();
        }
        return sb.toString();
    }

    @Override
    public boolean isShowPlayerHeadsActive() {
        return state(TweakId.CHAT_PLAYER_HEADS).enabled();
    }

    @Override
    public boolean headBeforeName() {
        return !"AFTER".equals(state(TweakId.CHAT_PLAYER_HEADS).configurable("position"));
    }

    @Override
    public boolean isCustomCrosshairActive() {
        return state(TweakId.CUSTOM_CROSSHAIR).enabled();
    }

    /**
     * Raw configurable passthrough for T5 Custom Crosshair's render-only
     * fields ({@code outline}/{@code gap}/{@code length}/{@code thickness}/
     * {@code centerDot}/{@code colorR}/{@code colorG}/{@code colorB}) --
     * these are pure rendering parameters with no state-reading business
     * logic of their own (unlike the other hooks' boolean/derived methods),
     * so {@link de.lazuli.features.tweaks.services.CustomCrosshairHook}
     * intentionally does not declare per-field accessors for them (see its
     * own Javadoc). Added for the T5 mixin implementation batch (hooks-
     * wiring plan, Batch C) -- not part of the original hook contract.
     */
    public Object crosshairConfigurable(String key) {
        return state(TweakId.CUSTOM_CROSSHAIR).configurable(key);
    }

    @Override
    public boolean shouldAnimate(String animatedTextureId) {
        return !modeExcludes(state(TweakId.DISABLE_ANIMATIONS), animatedTextureId);
    }

    @Override
    public boolean shouldSpawnParticle(String particleTypeId) {
        return !modeExcludes(state(TweakId.DISABLE_PARTICLES), particleTypeId);
    }

    @SuppressWarnings("unchecked")
    private static boolean modeExcludes(TweakState s, String id) {
        if (!s.enabled()) {
            return false;
        }
        String mode = String.valueOf(s.configurable("mode"));
        Object rawList = s.configurable("list");
        Set<String> list = rawList instanceof List<?> l ? Set.copyOf((List<String>) l) : Set.of();
        return switch (mode) {
            case "WHITELIST" -> !list.contains(id);
            case "BLACKLIST" -> list.contains(id);
            default -> true; // ALL
        };
    }

    @Override
    public boolean shouldHideName(double distanceToPlayer) {
        TweakState s = state(TweakId.HIDE_PLAYER_NAMES);
        if (!s.enabled()) {
            return false;
        }
        String mode = String.valueOf(s.configurable("mode"));
        double range = s.configurable("range") instanceof Number n ? n.doubleValue() : 16.0;
        return switch (mode) {
            case "RANGE_INCLUSIVE" -> distanceToPlayer <= range;
            case "RANGE_EXCLUSIVE" -> distanceToPlayer > range;
            default -> true; // GLOBAL
        };
    }

    @Override
    public float underwaterOverlayOpacityMultiplier() {
        TweakState s = state(TweakId.CLEAR_WATER);
        if (!s.enabled()) {
            return 1.0f;
        }
        Object raw = s.configurable("opacity");
        return raw instanceof Number n ? n.floatValue() : 0.0f;
    }

    @Override
    public boolean isSlotDisabled(String wardrobeSlotName) {
        TweakState s = state(TweakId.DISABLE_COSMETICS);
        if (!s.enabled()) {
            return false;
        }
        return Boolean.TRUE.equals(s.configurable(wardrobeSlotName));
    }

    void setZoomActive(boolean active) {
        if (this.zoomActive != active) {
            this.zoomTransitionStartFactor = zoomFactor;
            this.zoomTransitionStartNanos = System.nanoTime();
        }
        this.zoomActive = active;
    }

    Object holdToZoomConfigurable() {
        return state(TweakId.ZOOM).configurable("holdToZoom");
    }

    @Override
    public boolean isZoomActive() {
        return state(TweakId.ZOOM).enabled() && zoomActive;
    }

    @Override
    public float applyFov(float baseFov) {
        TweakState s = state(TweakId.ZOOM);
        if (!s.enabled()) {
            zoomFactor = 1.0f;
            return baseFov;
        }
        Object rawMagnification = s.configurable("magnification");
        float magnification = rawMagnification instanceof Number n ? n.floatValue() : 4.0f;
        float targetFactor = zoomActive ? 1.0f / Math.max(2.0f, magnification) : 1.0f;

        boolean transition = Boolean.TRUE.equals(s.configurable("transition"));
        if (!transition) {
            zoomFactor = targetFactor;
        } else {
            Object rawDuration = s.configurable("transitionDurationMs");
            float durationMs = rawDuration instanceof Number n ? n.floatValue() : 150.0f;
            if (durationMs <= 0.0f) {
                zoomFactor = targetFactor;
            } else {
                float elapsedMs = (System.nanoTime() - zoomTransitionStartNanos) / 1_000_000.0f;
                float progress = Math.min(1.0f, Math.max(0.0f, elapsedMs / durationMs));
                zoomFactor = zoomTransitionStartFactor + (targetFactor - zoomTransitionStartFactor) * progress;
            }
        }
        return baseFov * zoomFactor;
    }

    @Override
    public boolean adjustZoomByScroll(double verticalAmount) {
        TweakState s = state(TweakId.ZOOM);
        if (!s.enabled() || !zoomActive || verticalAmount == 0.0) {
            return false;
        }
        if (!Boolean.TRUE.equals(s.configurable("scrollToAdjust"))) {
            return false;
        }
        Object rawMagnification = s.configurable("magnification");
        double magnification = rawMagnification instanceof Number n ? n.doubleValue() : 4.0;
        magnification = Math.min(20.0, Math.max(2.0, magnification + verticalAmount));
        if (Boolean.TRUE.equals(s.configurable("transition"))) {
            this.zoomTransitionStartFactor = zoomFactor;
            this.zoomTransitionStartNanos = System.nanoTime();
        }
        registry.setConfigurable(TweakId.ZOOM, "magnification", magnification);
        return true;
    }

    @Override
    public boolean shouldHideBossBar(String bossBarName) {
        return modeExcludes(state(TweakId.DISABLE_BOSS_BARS), bossBarName);
    }
}
