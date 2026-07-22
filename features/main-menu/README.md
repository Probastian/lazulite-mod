# Main Menu

See `specification.md` and `plan.md` in this directory for the full
specification and implementation plan.

Replaces the vanilla title screen with `MainMenuScreen` ("Stonebound"): a
continuously-rendered 3D background, a right-hand tab bar (Worlds / Servers /
Store / Wardrobe), and a right sidebar reusing `features/friends-sidebar`'s
existing state/data. The Servers panel's Browser sub-view reuses
`features/server-browser`'s existing session/table-model logic. The Store
panel is backed by real Steamworks Inventory ownership data, with a DLC
App ID fallback for items not yet configured with an inventory item
definition.
