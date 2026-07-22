# Own-Profile Presence — Split Into Two Specs

This document previously bundled two unrelated pieces of work. It has been
split into two independently-shippable specs:

- `specification-own-profile-ingame-status.md` — own-row generic "In Game"
  status bug fix (plus the context-menu `isOwnProfile` regression check).
  Complete and ready for planning.
- `features/rich-presence/specification.md` — new Rich Presence publishing
  system (dimension/biome/movement-derived status, etc.). **Relocated out of
  friends-sidebar** into its own standalone feature directory, since it
  publishes to Steam's own Rich Presence system (visible in the real Steam
  friends list) independent of whether this mod's sidebar UI is open at all
  — it only shares the `SteamFriendsGateway` plumbing with this feature, not
  any sidebar-specific code. Ready for the user's approval pass as of this
  relocation (no longer on hold; see that file's status banner).
</content>
