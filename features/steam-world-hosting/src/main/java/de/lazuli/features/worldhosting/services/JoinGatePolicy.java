package de.lazuli.features.worldhosting.services;

/**
 * This feature's own three-value join-gate policy (implementation plan
 * Decision 5) -- deliberately does <strong>not</strong> import
 * {@code features/friends-sidebar}'s {@code JoinPolicy} enum (Non-goals'
 * Feature-to-Feature dependency ban); the platform composition root
 * translates between the two (see {@code JoinPolicyBridge}).
 */
public enum JoinGatePolicy {
    NOBODY,
    FRIENDS,
    EVERYONE
}
