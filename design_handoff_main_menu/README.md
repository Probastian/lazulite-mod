# Handoff: Main Menu Rework (Stonebound Overhaul Mod)

## Overview
A redesigned main menu screen for the mod: a full-bleed 3D-style backdrop (sky, mountains, idle player character) behind a right-hand tab system (Worlds / Servers / Store / Wardrobe) and a collapsible right sidebar (player card, join policy, friends list, settings/quit). Replaces the game's default main menu / in-game pause-adjacent screen.

## About the Design Files
The bundled file (`Main Menu.dc.html`) is a **design reference built in HTML/CSS/JS** — a clickable prototype showing intended layout, styling, and interaction behavior. It is NOT code to port line-by-line. Recreate this UI **inside the mod's actual rendering environment** (Minecraft's `Screen`/`GuiGraphics` API, or whatever GUI framework the mod already uses — Forge/Fabric/NeoForge screen classes, widgets, etc.), matching this design pixel-for-pixel in spirit: same layout structure, spacing, colors, typography choices, and interaction states, expressed with the target engine's actual rendering primitives (textures, `GuiGraphics.fill`, `Font.draw`, `Button`/custom widgets) rather than HTML.

## Fidelity
**High-fidelity.** Colors, spacing, typography sizes, and interaction states below are final — implement them precisely, adapting only where HTML concepts (backdrop-filter blur, CSS gradients) need a Minecraft-native equivalent (e.g. `GuiGraphics` blur/vignette, custom texture, or an approximated tinted overlay).

## Layout Overview
Canvas reference size: **1920×1080** (design scales proportionally to actual game window / GUI scale).

Full-bleed background layer (z-index 0), full screen:
- Vertical sky gradient, top to bottom: deep violet-blue → warm orange → gold → pale gold (dusk sky). Approx stops: 0% oklch(28% 0.06 300), 45% oklch(52% 0.13 45), 68% oklch(74% 0.13 68), 100% oklch(80% 0.1 75).
- A soft sun/glow circle, upper right (~90px from right, 120px from top), 150px diameter, radial gradient white-gold fading to transparent, with an outer soft glow (box-shadow equivalent, 90px blur, gold, 50% opacity).
- Two mountain silhouette layers near the bottom (jagged polygon shapes), semi-transparent warm browns, positioned at ~bottom 270px/height 120px and bottom 230px/height 100px.
- A flat ground/grass strip along the very bottom, 240px tall, green gradient (oklch 38%→30%, hue 140), with a 4px lighter-green top border edge.
- Title text top-left (44px from top, 48px from left): "Stonebound" — 34px, weight 700, uppercase, 3px letter-spacing, off-white, subtle drop shadow. Subtitle below: "Overhaul Mod · v2.1" — 13px, weight 500, uppercase, 4px letter-spacing, warm off-white at 85% opacity.
- An idle blocky player character (Minecraft-style voxel figure), bottom-left area (bottom 150px, left 230px), 180×340px, gently bobbing (±6px, 2.6s ease-in-out loop) with independent arm-swing and leg-sway animations on the same loop. Built from flat rectangular blocks: head (skin tone) with hair cap, tunic torso (green), separate swinging arms, separate swaying legs (dark trousers) — all blocks outlined with a thin dark border, matching the game's own player-skin blockiness.

Foreground UI layer (z-index 1), flex row spanning full width/height:
1. **Spacer** — 620px wide, transparent (lets the background scene show through on the left).
2. **Center panel** — flexible width. Transparent/inactive when no tab is selected (background scene fully visible). When a tab is active: semi-transparent dark panel (oklch 21% 0.015 95 at 55% opacity) with backdrop blur (6px), 36px/32px padding, holding that tab's content (see Screens below).
3. **Tab bar** — fixed 108px wide, vertical stack of 4 tab buttons, centered, background oklch(22% 0.016 95 / 0.7) with blur, 1px borders left/right.
4. **Right sidebar** — collapsed 84px, expands to 320px on hover (see Sidebar below), overlaid absolutely (does not reflow siblings), dark blue-gray (oklch 27% 0.006 240).

## Screens / Views

### Tab Bar (always visible, right of center panel)
4 vertical buttons, one per tab: **Worlds, Servers, Store, Wardrobe**. Each button: full width of the 108px column, 14px/6px padding, 8px gap between icon and label, rounded corners (6px), 3px left border accent.
- Icon: 26×26px swatch — circular for Worlds/Servers, 4px-rounded square for Store/Wardrobe.
- Label: 11.5px, weight 600, uppercase, 1px letter-spacing, centered.
- **Active state**: background oklch(30% 0.02 95 / 0.55), left border + icon fill = moss green oklch(65% 0.13 145), label color near-white.
- **Inactive state**: transparent background, left border transparent, icon fill oklch(32% 0.015 95), label color oklch(65% 0.02 95).
- **Hover**: background oklch(30% 0.02 95 / 0.6).
- Click toggles that tab open; clicking the already-active tab closes it (returns center panel to transparent).

### Worlds Panel
- Header row: "Singleplayer Worlds" label (15px, weight 600, uppercase, 3px letter-spacing, muted) + **Create New World** button (moss-green pill, 10px/18px padding, "+" icon, uppercase 13.5px weight 600 text). Hover: lighter moss green.
- Scrollable list of saved worlds, 10px gap between rows.
  - **Selected row** (expanded card): 18px padding, 2px moss-green border, semi-transparent dark card, 96×96px 2×2 thumbnail grid (4 colored tiles representing a world preview), world name (22px/600), mode·difficulty (14px), "Last played X" (13px), and Play (moss green pill) + Edit (neutral gray pill) buttons.
  - **Unselected row** (compact): 48×48px thumbnail, name (17px/500, truncates with ellipsis), mode·last-played (12.5px). Hover: slightly lighter background.
  - Clicking a row toggles its selected/expanded state (accordion-style — only one expands at a time in the demo, but this isn't enforced by mutual exclusion logic here — confirm intended behavior: single-expand vs multi-expand).
- Footer note: "Create New World opens the vanilla world-creation menu." (12.5px, muted).
- Clicking **Create New World** shows a centered modal-style toast for ~1.6s: "Launching vanilla world creation…" over a dark blurred scrim. In the real mod this should invoke Minecraft's native "Create World" screen instead of a toast — the toast in this mock is a placeholder for that transition.

### Servers Panel
Two sub-views, toggled by a header button:

**Saved view** (default): list of saved multiplayer servers, same card pattern as Worlds (selected = expanded card with Connect button; unselected = compact row with a colored ping-status dot + player count).

**Browser view** ("Server Browser"): a public server list.
- Filter bar: server-name search input, latency `<select>` (Any / <50ms / <100ms / <200ms), two toggle switches ("Hide Full", "Hide Password Protected").
- Sortable column header row: Lock icon / Name / Players / Latency — click a column to sort (▲/▼ arrow indicator, moss-green), toggling asc/desc.
- Rows: lock icon (padlock glyph) if password-protected, server name + address·tag, player count "X/Y", latency in ms, Connect button (moss-green pill).

Header-row buttons (both views): toggle Saved/Browser, **Direct Connect** (opens modal: server-address input, Cancel/Connect), **+ Add Server** (moss green; opens modal: name + address inputs, Cancel/Add), and a circular **Refresh** icon button (spins 360° on click).

### Store Panel
- "Store" section title.
- Featured banner: large item swatch (130×130px, diagonal-stripe placeholder texture) with a "Featured" ribbon badge (top-left corner, amber), item name (22px/700), description, price with strikethrough original price, and a "Buy Now" pill (moss green, scales up slightly + lightens on hover).
- "All Cosmetics" grid: 3-column grid of item cards. Each card: square swatch (striped placeholder, aspect-ratio 1:1), item name (15px/600), category (12.5px muted), price (14px/600, amber/gold) + "Buy" pill button. Card hover: lifts up 3px, border turns moss green.

### Wardrobe Panel
- "Wardrobe" title.
- Slot selector row: 4 equal-width slot buttons (Head / Torso / Legs / Feet), each showing a small swatch of the currently equipped item + slot label + equipped item name (truncated). Active slot: highlighted background/border/text. Click switches which slot's options show below.
- Options grid below: 3-column grid of items belonging to the selected slot. Each card: square swatch, item name, and a status label ("Equipped" in moss green, or "Owned" in muted gray) with matching border highlight when equipped. Click equips that item into the current slot.

### Right Sidebar (player/social panel)
- Collapsed state (default, 84px wide): only icon/avatar column visible — player avatar (56×56px, moss-green rounded square with initial "P"), friend avatars (36×36px rounded squares with initials, colored per-friend, border color indicates online (moss green) vs offline (gray)), Settings icon, Quit icon.
- **Expands to 320px on mouse hover** (0.22s ease width transition), revealing:
  - Player name "Playername" (19px/600) + "View profile" (13px muted) next to avatar.
  - "Who can join?" label + a select dropdown (Nobody / Friends / Everyone).
  - Divider line.
  - "Friends" section label (13px, uppercase, 3px letter-spacing).
  - Scrollable friends list: avatar + name (15px/500) + status line (12px muted, e.g. "Online — Hollowbrook Vale" or "Offline — 4h ago").
  - Footer buttons: **Settings** (neutral gray pill, gear-circle icon) and **Quit Game** (warm red/amber pill oklch(30% 0.06 35), square icon), both full-width, uppercase 14px/500 text, labels hidden while collapsed (icon-only), revealed on expand.
- Sidebar overlays the tab bar/center panel region absolutely so it doesn't push other UI when expanding.

## Interactions & Behavior Summary
- **Tab click**: selects/deselects a tab; selecting shows that tab's panel (with dark blurred backdrop over the 3D scene) and content; deselecting (clicking active tab again) returns to fully transparent center (scene fully visible, no side panel).
- **World/Server row click**: toggles that row's expanded/collapsed state.
- **Wardrobe slot click**: switches active slot; item click equips it (single item equipped per slot, swap replaces prior).
- **Sidebar hover**: expand/collapse (mouseenter/mouseleave), not click-toggle.
- **Server browser sort**: click column header cycles asc → desc → asc.
- **Modals** (Direct Connect / Add Server / world-create toast): centered overlay on a dark blurred scrim; click on scrim (outside modal) or Cancel closes it; click inside modal does not close it.
- **Refresh button**: spins 360° each click (should trigger an actual server-list refresh in the real implementation).
- All interactive rows/buttons have a hover state — see per-section notes above for exact hover colors.
- No keyboard navigation, loading states, or error states are modeled in this prototype — decide with design whether they're needed (e.g. server ping timeout, no-worlds-found empty state) before shipping.

## State Management (for reference — reimplement in-engine)
- Selected/active tab (nullable — no tab open by default is a valid interaction target).
- Selected singleplayer world id (nullable, one at a time in this prototype).
- Selected multiplayer/server id (nullable).
- Servers sub-view: saved vs. browser.
- Server browser filters: name search string, latency ceiling, hide-full toggle, hide-password toggle; sort key + direction.
- Modal visibility flags: direct-connect open, add-server open, world-create toast visible (auto-dismiss ~1.6s).
- Wardrobe: selected slot, map of slot → equipped item id.
- Sidebar expanded (hover-driven boolean).
- Join policy select value (Nobody/Friends/Everyone).

## Design Tokens

**Typography**: Google Font "Oswald" (weights 400/500/600/700), fallback sans-serif. Use condensed-uppercase styling for labels/buttons/section titles throughout (uppercase + letter-spacing 0.5–4px depending on size); body/name text is sentence case.

**Color palette** (OKLCH):
- Background base: `oklch(19% 0.014 95)` (warm near-black)
- Panel surface: `oklch(21–27% 0.015–0.02 95)`, usually at 40–70% opacity over the scene
- Borders/dividers: `oklch(28–34% 0.012–0.02 95)`
- Primary text: `oklch(93% 0.01 95)` (warm off-white)
- Muted text: `oklch(58–70% 0.02 95)`
- **Accent / primary action (moss green)**: `oklch(58% 0.1 145)`, hover `oklch(64–66% 0.1–0.11 145)`; used for active tab, primary buttons (Play, Connect, Buy, Create), toggle-on switches, online-status dots/borders.
- **Warm gold/amber** (price highlights, featured badge): `oklch(78–85% 0.11–0.13 85)`
- **Warning/destructive (Quit Game)**: `oklch(30% 0.06 35)` background, `oklch(92% 0.02 40)` text
- Sidebar surface (distinct cool gray-blue, separates it from warm main palette): `oklch(27% 0.006 240)`
- Item/world/server thumbnail swatches: diagonal repeating-stripe placeholders in varied hues — replace with real block/item icon renders in-engine.

**Spacing/radius**: card padding 12–26px depending on density; border-radius 4–8px on cards/buttons/pills; 10–20px gaps between list items/grid cells.

**Shadows/blur**: `backdrop-filter: blur(3–6px)` on panels over the 3D scene; soft glow (large blur, low opacity) behind the sun only.

## Assets
No real game assets are used — all thumbnails/icons/character are flat CSS placeholders (colors, gradients, simple shapes). Replace with:
- Actual player skin render (or live 3D player model) for the idle character.
- Real world/server preview thumbnails.
- Real item/cosmetic icons for Store and Wardrobe grids.
- A proper padlock icon glyph for password-protected servers (currently a hand-drawn CSS shape).
Mod logo/wordmark "Stonebound" is placeholder text — swap for the mod's actual name/logo treatment.

## Files
- `Main Menu.dc.html` — the full interactive HTML/CSS/JS prototype (single file, view directly in a browser). Contains inline styles for every element (exact colors/sizes/spacing) and a small state machine (`Component` class) documenting all interactive behavior described above — read it alongside this README for exact values not called out here (e.g. precise per-item colors in the sample data arrays).
