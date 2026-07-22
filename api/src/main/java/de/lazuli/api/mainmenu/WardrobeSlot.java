package de.lazuli.api.mainmenu;

/**
 * The four cosmetic equip slots the Wardrobe panel manages (spec FR6.1).
 *
 * <p>Doubles as {@link StoreItem#category()}'s expected value set for
 * wardrobe-eligible catalog entries (spec FR6.2) -- a Store item is
 * filterable into a given slot's item grid when its {@code category} string
 * matches one of these enum names.
 */
public enum WardrobeSlot {
    HEAD,
    TORSO,
    LEGS,
    FEET
}
