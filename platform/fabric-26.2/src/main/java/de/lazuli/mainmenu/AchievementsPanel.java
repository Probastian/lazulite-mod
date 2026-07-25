package de.lazuli.mainmenu;

import de.lazuli.api.mainmenu.AchievementSummary;
import de.lazuli.features.mainmenu.achievements.SpacewarAchievementMapping;
import de.lazuli.services.steamworks.SteamAchievementsGateway;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Achievements tab panel (batch-2 FR-BB3.4-3.6, finished for real in
 * batch-2-fixes Item F1, Branch A): a Mojang-mapped port of
 * {@code fabric-1.21.11}'s class of the same name -- see that class's own
 * Javadoc for the confirmed v1 data-scope reduction (no display name/
 * description/icon/unlock-time/progress binding in the resolved
 * {@code steamworks4j} fork jar).
 */
public final class AchievementsPanel {

    private enum Filter { ALL, UNLOCKED, LOCKED }

    private static final int PILL_HEIGHT = 18;
    private static final int PILL_GAP = 8;
    private static final int ROW_HEIGHT = 24;
    private static final int CONTENT_LEFT_PAD = 8;

    private final SteamAchievementsGateway gateway;
    private Filter filter = Filter.ALL;
    private List<AchievementSummary> cached;

    public AchievementsPanel(SteamAchievementsGateway gateway) {
        this.gateway = gateway;
    }

    private List<AchievementSummary> all() {
        if (cached == null) {
            cached = gateway.achievements();
        }
        return cached;
    }

    private List<AchievementSummary> filtered() {
        return switch (filter) {
            case ALL -> all();
            case UNLOCKED -> all().stream().filter(AchievementSummary::unlocked).collect(Collectors.toList());
            case LOCKED -> all().stream().filter(a -> !a.unlocked()).collect(Collectors.toList());
        };
    }

    public void render(GuiGraphicsExtractor guiGraphics, Font font, int x, int y, int width, int height, int mouseX, int mouseY) {
        int leftX = x + CONTENT_LEFT_PAD;
        guiGraphics.text(font, Component.literal("Achievements"), leftX, y, 0xFFEAE8E1);

        int pillY = y + 16;
        int pillX = leftX;
        for (Filter f : Filter.values()) {
            String label = filterLabel(f);
            int pillWidth = font.width(label) + 16;
            boolean active = f == filter;
            boolean hovered = mouseX >= pillX && mouseX <= pillX + pillWidth && mouseY >= pillY && mouseY <= pillY + PILL_HEIGHT;
            guiGraphics.fill(pillX, pillY, pillX + pillWidth, pillY + PILL_HEIGHT, active ? 0xFF528A54 : (hovered ? 0xFF2A2820 : 0xFF201E17));
            guiGraphics.centeredText(font, Component.literal(label), pillX + pillWidth / 2, pillY + 5, 0xFFEAE8E1);
            pillX += pillWidth + PILL_GAP;
        }

        int rowY = pillY + PILL_HEIGHT + 10;
        List<AchievementSummary> rows = filtered();
        if (all().isEmpty()) {
            guiGraphics.text(font, Component.literal("No achievement data available."), leftX, rowY, 0xFF908C7F);
            return;
        }
        if (rows.isEmpty()) {
            guiGraphics.text(font, Component.literal("No achievements match this filter."), leftX, rowY, 0xFF908C7F);
            return;
        }
        for (AchievementSummary a : rows) {
            if (rowY + ROW_HEIGHT > y + height) {
                break;
            }
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT;
            guiGraphics.fill(x, rowY, x + width, rowY + ROW_HEIGHT, hovered ? 0xFF2A2820 : 0xFF201E17);
            // BF5: mapped achievements show a friendlier display name +
            // description; unmapped ones keep the existing raw-apiName-only
            // rendering, byte-for-byte.
            SpacewarAchievementMapping.AchievementMetadata meta = SpacewarAchievementMapping.MAPPING.get(a.apiName());
            int textX = leftX + 8;
            if (meta != null && meta.iconAssetPath() != null) {
                Identifier iconId = resolveIconId(meta.iconAssetPath());
                if (iconId != null && Minecraft.getInstance().getResourceManager().getResource(iconId).isPresent()) {
                    int iconSize = ROW_HEIGHT - 8;
                    int iconY = rowY + (ROW_HEIGHT - iconSize) / 2;
                    guiGraphics.blit(RenderPipelines.GUI_TEXTURED, iconId, textX, iconY, 0f, 0f,
                            iconSize, iconSize, iconSize, iconSize, iconSize, iconSize);
                    textX += iconSize + 6;
                }
            }
            if (meta != null) {
                guiGraphics.text(font, Component.literal(meta.displayName()), textX, rowY + 3, 0xFFEAE8E1);
                guiGraphics.text(font, Component.literal(meta.description()), textX, rowY + 13, 0xFF908C7F);
            } else {
                guiGraphics.text(font, Component.literal(a.apiName()), textX, rowY + 4, 0xFFEAE8E1);
            }
            String status = a.unlocked() ? "✓ Unlocked" : "🔒 Locked";
            int statusColor = a.unlocked() ? 0xFF528A54 : 0xFF908C7F;
            int statusWidth = font.width(status);
            guiGraphics.text(font, Component.literal(status), x + width - statusWidth - 8, rowY + 4, statusColor);
            rowY += ROW_HEIGHT + 2;
        }
    }

    /** @return true if this click was consumed by a filter pill in this panel. */
    public boolean mouseClicked(int x, int y, int width, int height, double mouseX, double mouseY) {
        int pillY = y + 16;
        int pillX = x + CONTENT_LEFT_PAD;
        Font font = Minecraft.getInstance().font;
        for (Filter f : Filter.values()) {
            String label = filterLabel(f);
            int pillWidth = font.width(label) + 16;
            if (mouseX >= pillX && mouseX <= pillX + pillWidth && mouseY >= pillY && mouseY <= pillY + PILL_HEIGHT) {
                MainMenuScreen.playClickSound();
                filter = f;
                return true;
            }
            pillX += pillWidth + PILL_GAP;
        }
        return false;
    }

    /**
     * BF-4-1: parses an {@code "lazuli:textures/achievements/<apiName>.png"}-
     * shaped {@code iconAssetPath()} string into an {@link Identifier}, or
     * {@code null} if it isn't in the expected {@code namespace:path} shape.
     */
    private static Identifier resolveIconId(String iconAssetPath) {
        int colon = iconAssetPath.indexOf(':');
        if (colon < 0) {
            return null;
        }
        return Identifier.fromNamespaceAndPath(iconAssetPath.substring(0, colon), iconAssetPath.substring(colon + 1));
    }

    private static String filterLabel(Filter f) {
        return switch (f) {
            case ALL -> "All";
            case UNLOCKED -> "Unlocked";
            case LOCKED -> "Locked";
        };
    }
}
