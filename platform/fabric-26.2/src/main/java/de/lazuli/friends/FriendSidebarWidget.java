package de.lazuli.friends;

import de.lazuli.api.friends.FriendSummary;
import de.lazuli.features.friendssidebar.services.FriendsSidebarFacade;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.function.Consumer;

/**
 * The Friends Sidebar's own overlay widget on Minecraft 26.2 (Mojang-mapped,
 * unobfuscated) -- a single custom {@link AbstractWidget} subclass added via
 * {@code Screens.getWidgets(screen)} (implementation plan Decision 1/3, Pattern
 * 1, no mixin). Owns collapsed (avatar-only, FR2.3) vs. expanded
 * (avatar+name, FR2.4) rendering and forwards a row click (either mouse
 * button, FR2.5) to a supplied listener rather than handling the context menu
 * itself (owned by {@code FabricFriendsSidebarInjector}).
 *
 * <p>Usage example (from {@code FabricFriendsSidebarInjector}):
 * <pre>{@code
 * FriendSidebarWidget sidebar = new FriendSidebarWidget(
 *         6, 6, facade, avatarTextureCache, this::onRowClicked);
 * Screens.getWidgets(screen).add(sidebar);
 * }</pre>
 */
public final class FriendSidebarWidget extends AbstractWidget {

    private static final int COLLAPSED_WIDTH = 22;
    private static final int EXPANDED_WIDTH = 140;
    private static final int ROW_HEIGHT = 20;
    private static final int MAX_ROWS = 12;

    private final FriendsSidebarFacade facade;
    private final AvatarTextureCache avatarTextureCache;
    private final RowClickListener rowClickListener;

    private boolean expanded;

    public interface RowClickListener {
        void onRowClicked(FriendSummary friend, int mouseX, int mouseY, int button);
    }

    public FriendSidebarWidget(int x, int y, FriendsSidebarFacade facade, AvatarTextureCache avatarTextureCache,
            RowClickListener rowClickListener) {
        super(x, y, EXPANDED_WIDTH, ROW_HEIGHT * MAX_ROWS, Component.literal("Friends"));
        this.facade = facade;
        this.avatarTextureCache = avatarTextureCache;
        this.rowClickListener = rowClickListener;
    }

    private List<FriendSummary> visibleFriends() {
        List<FriendSummary> all = facade.friends();
        return all.size() > MAX_ROWS ? all.subList(0, MAX_ROWS) : all;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        if (!facade.isEnabled()) {
            return;
        }
        List<FriendSummary> friends = visibleFriends();
        expanded = facade.stateMachine().isExpanded(mouseX, mouseY, getX(), getY(), COLLAPSED_WIDTH,
                Math.max(ROW_HEIGHT, friends.size() * ROW_HEIGHT));

        int width = expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH;
        int height = Math.max(ROW_HEIGHT, friends.size() * ROW_HEIGHT);
        guiGraphics.fill(getX(), getY(), getX() + width, getY() + height, 0x99000000);

        int rowY = getY();
        for (FriendSummary friend : friends) {
            drawRow(guiGraphics, friend, getX(), rowY, width);
            rowY += ROW_HEIGHT;
        }
    }

    private void drawRow(GuiGraphicsExtractor guiGraphics, FriendSummary friend, int x, int y, int width) {
        Identifier avatarTexture = avatarTextureCache.getOrUpload(friend.steamId64(),
                facade.avatarRgba(friend.steamId64()).orElse(null));
        int avatarSize = ROW_HEIGHT - 4;
        if (avatarTexture != null) {
            guiGraphics.blit(avatarTexture, x + 2, y + 2, avatarSize, avatarSize, 0f, 0f, 1f, 1f);
        } else {
            guiGraphics.fill(x + 2, y + 2, x + 2 + avatarSize, y + 2 + avatarSize, personaColor(friend));
        }

        if (expanded) {
            guiGraphics.text(Minecraft.getInstance().font, friend.personaName(), x + avatarSize + 6, y + 6, 0xFFFFFF);
        }
    }

    private static int personaColor(FriendSummary friend) {
        // Online-ish states render brighter than offline/away -- a plain
        // flat-color placeholder until/unless a real avatar uploads (Decision 5).
        return friend.personaState() == 0 ? 0xFF808080 : 0xFF33AA33;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!facade.isEnabled() || !isMouseOver(event.x(), event.y())) {
            return false;
        }
        List<FriendSummary> friends = visibleFriends();
        int relativeY = (int) event.y() - getY();
        int index = relativeY / ROW_HEIGHT;
        if (index >= 0 && index < friends.size()) {
            rowClickListener.onRowClicked(friends.get(index), (int) event.x(), (int) event.y(), event.button());
            return true;
        }
        return true;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        List<FriendSummary> friends = visibleFriends();
        int height = Math.max(ROW_HEIGHT, friends.size() * ROW_HEIGHT);
        int width = expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH;
        return mouseX >= getX() && mouseX < getX() + width && mouseY >= getY() && mouseY < getY() + height;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        narrationElementOutput.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, getMessage());
    }
}
