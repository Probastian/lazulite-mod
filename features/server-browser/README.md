# Server Browser

See `specification.md` and `plan.md` in this directory for the full
specification and implementation plan.

Adds a "Server Browser" button to the vanilla Multiplayer screen, opening a
new screen that lists Steam Internet/LAN matchmaking servers (via
`ISteamMatchmakingServers`) registered against this process's Steam App ID,
with sortable/filterable columns and a one-click connect flow reusing
vanilla's own client-connect path.

Password-protected servers show a password-entry prompt before connecting
(`ServerBrowserPasswordPromptScreen`), but the entered password is a v1 stub
-- collected by the UI and discarded, never transmitted or enforced, since no
server-side verification protocol exists yet (spec FR4.3, Non-goals).
