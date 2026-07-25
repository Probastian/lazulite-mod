package de.lazuli.mainmenu;

import de.lazuli.LazuliMod;
import de.lazuli.api.friends.FriendSummary;
import de.lazuli.features.friendssidebar.services.FriendsSidebarFacade;
import de.lazuli.friends.AvatarTextureCache;
import de.lazuli.friends.FriendSidebarWidget;
import de.lazuli.services.steamworks.SteamAppIdResolver;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.level.storage.LevelSummary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Home/Activity tab panel (Batch 2 spec Item 2, FR-BB2.1-2.5; enriched by
 * batch-2-fixes Item F4: greeting, merged Recent section) -- {@code fabric-1.21.11}
 * (Yarn-mapped, obfuscated) port of the {@code fabric-26.1}/{@code fabric-26.2}
 * class of the same name.
 *
 * <p><strong>Recent section timestamp gap (batch-2-fixes plan Decision 3):
 * </strong> vanilla {@code ServerInfo} has no last-connected timestamp, so
 * this panel's merged list is "recency-sorted where real timestamps exist
 * (worlds, via {@code LevelSummary.getLastPlayed()}), then saved servers
 * appended in {@code ServersPanel}'s own existing saved-list order" -- an
 * explicit, documented scope reduction from a byte-for-byte single-timestamp
 * interleave, not a silent gap.
 */
public final class HomePanel {

    private static final int ROW_HEIGHT = 28;
    private static final int AVATAR_SIZE = 22;
    private static final int ROW_PADDING = 3;

    private static final int RECENT_ROW_HEIGHT = 30;
    private static final int RECENT_ICON_SIZE = 22;
    private static final int RECENT_MAX_ENTRIES = 6;
    private static final int ICON_TEX_SIZE = 64;
    private static final int CONTENT_LEFT_PAD = 8;
    private static final float GREETING_SCALE = 1.4f;

    private static final List<String> GREETINGS = List.of(
            "Welcome back, {Playername}",
            "Ready to dig in?",
            "The vale missed you",
            "Good to see you again",
            "Adventure awaits",
            "Back for more, are we?"
    );

    private final FriendsSidebarFacade facade;
    private final AvatarTextureCache avatarTextureCache;
    private final long thisGameAppId;
    private final FriendSidebarWidget.RowClickListener rowClickListener;
    private final WorldsPanel worldsPanel;
    private final ServersPanel serversPanel;
    private final IconTextureCache iconCache = new IconTextureCache(LazuliMod.LOGGER::warn);
    private final String greeting;
    private final de.lazuli.features.mainmenu.config.MainMenuJoinHistoryConfig joinHistoryConfig;

    /**
     * @param worldsPanel  already-constructed owner-screen instance, reused
     *                     for Recent-section world data/play action (batch-2-fixes
     *                     Decision 3) -- no separate {@code FriendServerPresenceReader}
     *                     parameter is needed since {@code serversPanel} already
     *                     owns one internally and this panel calls its exposed
     *                     {@code renderFriendAvatars} directly.
     * @param serversPanel already-constructed owner-screen instance, reused
     *                     for Recent-section server data/connect action/friend avatars
     * @param joinHistoryConfig loaded once at composition-root time
     *                          (batch-3-fixes BF4, FR-BF4.4)
     */
    public HomePanel(FriendsSidebarFacade facade, AvatarTextureCache avatarTextureCache,
                      FriendSidebarWidget.RowClickListener rowClickListener,
                      WorldsPanel worldsPanel, ServersPanel serversPanel,
                      de.lazuli.features.mainmenu.config.MainMenuJoinHistoryConfig joinHistoryConfig) {
        this.facade = facade;
        this.avatarTextureCache = avatarTextureCache;
        this.rowClickListener = rowClickListener;
        this.thisGameAppId = SteamAppIdResolver.resolve(System::getProperty);
        this.worldsPanel = worldsPanel;
        this.serversPanel = serversPanel;
        this.joinHistoryConfig = joinHistoryConfig;
        this.greeting = pickGreeting();
    }

    private String pickGreeting() {
        String template = GREETINGS.get(new Random().nextInt(GREETINGS.size()));
        String playerName = MinecraftClient.getInstance().getSession() != null
                ? MinecraftClient.getInstance().getSession().getUsername() : "";
        return template.replace("{Playername}", playerName);
    }

    private List<FriendSummary> friendsPlayingThisGame() {
        List<FriendSummary> matches = facade.friends().stream()
                .filter(f -> f.inGame() && f.gameAppId() == thisGameAppId)
                .collect(Collectors.toList());
        return facade.stateMachine().sortForDisplay(matches);
    }

    private record RecentEntry(boolean isWorld, String name, String subtitle, Identifier icon,
                                WorldListWidget.WorldEntry worldEntry, ServerInfo serverInfo, long timestampEpochMillis) { }

    private List<RecentEntry> recentEntries() {
        List<RecentEntry> result = new ArrayList<>();
        List<WorldListWidget.WorldEntry> worlds = new ArrayList<>(worldsPanel.recentEntries());
        worlds.sort(Comparator.comparingLong((WorldListWidget.WorldEntry e) -> e.getLevel().getLastPlayed()).reversed());
        for (WorldListWidget.WorldEntry entry : worlds) {
            LevelSummary summary = entry.getLevel();
            Identifier icon = iconCache.forWorld(summary.getName(), summary.getIconPath());
            String subtitle = summary.getGameMode().getTranslatableName().getString()
                    + " · " + WorldsPanel.relativeTime(summary.getLastPlayed());
            result.add(new RecentEntry(true, entry.getLevelDisplayName(), subtitle, icon, entry, null, summary.getLastPlayed()));
        }
        // batch-3-fixes BF4/FR-BF4.4: real last-joined timestamp from the
        // persisted join-history record, looked up by server address; a
        // saved server never found in the record falls back to a sentinel so
        // it still appears (just sorted last), rather than being dropped.
        for (ServerInfo server : serversPanel.recentServers()) {
            Identifier icon = iconCache.forServer("home:" + server.address, server.getFavicon());
            long timestamp = Long.MIN_VALUE;
            String subtitle = "Saved server";
            for (var entry : joinHistoryConfig.servers()) {
                if (entry.ip().equals(server.address)) {
                    timestamp = entry.lastJoinedEpochMillis();
                    subtitle = WorldsPanel.relativeTime(timestamp);
                    break;
                }
            }
            result.add(new RecentEntry(false, server.name, subtitle, icon, null, server, timestamp));
        }
        result.sort((a, b) -> Long.compare(b.timestampEpochMillis(), a.timestampEpochMillis()));
        if (result.size() > RECENT_MAX_ENTRIES) {
            return result.subList(0, RECENT_MAX_ENTRIES);
        }
        return result;
    }

    public void render(DrawContext context, TextRenderer font, int x, int y, int width, int height, int mouseX, int mouseY) {
        int leftX = x + CONTENT_LEFT_PAD;

        // BF7: greeting rendered bold and visibly larger than the "Recent"/
        // "Activity" headers below, which are otherwise untouched.
        context.getMatrices().pushMatrix();
        context.getMatrices().scale(GREETING_SCALE, GREETING_SCALE);
        context.drawText(font, Text.literal(greeting).setStyle(Style.EMPTY.withBold(true)),
                (int) (leftX / GREETING_SCALE), (int) (y / GREETING_SCALE), 0xFFEAE8E1, false);
        context.getMatrices().popMatrix();

        int rowY = y + 16;
        context.drawText(font, Text.literal("Recent"), leftX, rowY, 0xFFC9A227, false);
        rowY += 12;

        List<RecentEntry> recent = recentEntries();
        if (recent.isEmpty()) {
            context.drawText(font, Text.literal("No recently played worlds or servers yet."), leftX, rowY, 0xFF908C7F, false);
            rowY += ROW_HEIGHT;
        } else {
            for (RecentEntry entry : recent) {
                if (rowY + RECENT_ROW_HEIGHT > y + height) {
                    break;
                }
                boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + RECENT_ROW_HEIGHT;
                context.fill(x, rowY, x + width, rowY + RECENT_ROW_HEIGHT, hovered ? 0xFF2A2820 : 0xFF201E17);
                context.drawTexture(RenderPipelines.GUI_TEXTURED, entry.icon(), leftX + 3, rowY + 4, 0f, 0f,
                        RECENT_ICON_SIZE, RECENT_ICON_SIZE, ICON_TEX_SIZE, ICON_TEX_SIZE);
                int textX = leftX + 3 + RECENT_ICON_SIZE + 6;
                context.drawText(font, Text.literal(entry.name()), textX, rowY + 4, 0xFFEAE8E1, false);
                context.drawText(font, Text.literal(entry.subtitle()), textX, rowY + 15, 0xFF908C7F, false);
                if (!entry.isWorld()) {
                    serversPanel.renderFriendAvatars(context, entry.serverInfo().address, x + width, rowY + 4);
                }
                rowY += RECENT_ROW_HEIGHT + 4;
            }
        }

        rowY += 10;
        context.drawText(font, Text.literal("Activity"), leftX, rowY, 0xFFC9A227, false);
        rowY += 12;

        if (!facade.isSteamAvailable()) {
            context.drawText(font, Text.literal(facade.steamUnavailableMessage()), leftX, rowY, 0xFFB54848, false);
            return;
        }
        List<FriendSummary> friends = friendsPlayingThisGame();
        if (friends.isEmpty()) {
            context.drawText(font, Text.literal("No friends are playing right now."), leftX, rowY, 0xFF908C7F, false);
            return;
        }
        for (FriendSummary friend : friends) {
            if (rowY + ROW_HEIGHT > y + height) {
                break;
            }
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT;
            context.fill(x, rowY, x + width, rowY + ROW_HEIGHT, hovered ? 0xFF2A2820 : 0xFF201E17);

            Identifier avatarTexture = avatarTextureCache.getOrUpload(friend.steamId64(),
                    facade.avatarRgba(friend.steamId64()).orElse(null));
            int avatarX = leftX + ROW_PADDING;
            int avatarY = rowY + (ROW_HEIGHT - AVATAR_SIZE) / 2;
            if (avatarTexture != null) {
                int size = AvatarTextureCache.SIZE;
                context.drawTexture(RenderPipelines.GUI_TEXTURED, avatarTexture, avatarX, avatarY, 0f, 0f,
                        AVATAR_SIZE, AVATAR_SIZE, size, size, size, size);
            } else {
                context.fill(avatarX, avatarY, avatarX + AVATAR_SIZE, avatarY + AVATAR_SIZE, 0xFF528A54);
            }

            int textX = avatarX + AVATAR_SIZE + 6;
            context.drawText(font, Text.literal(friend.personaName()), textX, rowY + 3, 0xFFEAE8E1, false);
            String status = facade.richPresenceStatus(friend.steamId64())
                    .orElseGet(() -> facade.stateMachine().statusLabel(friend.personaState(), friend.inGame()));
            context.drawText(font, Text.literal(status), textX, rowY + 14, 0xFF908C7F, false);

            rowY += ROW_HEIGHT + 2;
        }
    }

    /** @return true if this click was consumed by a row (Recent card or friend row) in this panel. */
    public boolean mouseClicked(int x, int y, int width, int height, double mouseX, double mouseY, int button) {
        int rowY = y + 16 + 12;
        List<RecentEntry> recent = recentEntries();
        if (recent.isEmpty()) {
            rowY += ROW_HEIGHT;
        } else {
            for (RecentEntry entry : recent) {
                if (rowY + RECENT_ROW_HEIGHT > y + height) {
                    break;
                }
                if (mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + RECENT_ROW_HEIGHT) {
                    MainMenuScreen.playClickSound();
                    if (entry.isWorld()) {
                        worldsPanel.playWorld(entry.worldEntry());
                    } else {
                        serversPanel.connect(entry.serverInfo());
                    }
                    return true;
                }
                rowY += RECENT_ROW_HEIGHT + 4;
            }
        }

        rowY += 10 + 12;
        if (!facade.isSteamAvailable()) {
            return false;
        }
        List<FriendSummary> friends = friendsPlayingThisGame();
        for (FriendSummary friend : friends) {
            if (rowY + ROW_HEIGHT > y + height) {
                break;
            }
            if (mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT) {
                MainMenuScreen.playClickSound();
                rowClickListener.onRowClicked(friend, (int) mouseX, (int) mouseY, button, false);
                return true;
            }
            rowY += ROW_HEIGHT + 2;
        }
        return false;
    }
}
