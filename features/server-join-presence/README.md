# Server Join Presence

Advertises the local player's currently-connected **real multiplayer server**
(saved server or server-browser entry) via Steam Rich Presence's `"connect"`
key, so a Steam friend can join the same server with one click (native
overlay "Join Game" button), and exposes a query for how many of the local
player's friends are currently on a given server address.

See `specification.md` and `implementation-plan.md` for the full design.
This feature is the multiplayer-client counterpart to
`features/steam-world-hosting`, which covers the disjoint singleplayer/
Steam-P2P-hosted-world case; the two share the same `"connect"` Rich Presence
key and the same `SteamFriendsGateway.setJoinRequestedListener` callback via
each platform module's `SteamJoinRequestDispatcher`, but never overlap at
runtime (only one connection is ever active at a time).
