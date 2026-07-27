package de.lazuli.features.tweaks.services;

/** Minecraft-agnostic hook interface for T3 Chat Filter (spec Requirements T3). */
public interface ChatFilterHook {

    /**
     * @param plainText the plain-text message/sign line to filter
     * @return {@code plainText} with any matched prohibited term substrings
     *         replaced (e.g. with {@code "***"}); returns {@code plainText}
     *         unchanged if the tweak is disabled or nothing matched
     */
    String filterText(String plainText);
}
