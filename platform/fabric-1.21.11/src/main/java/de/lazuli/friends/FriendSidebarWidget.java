package de.lazuli.friends;

import de.lazuli.api.friends.FriendSummary;
import de.lazuli.features.friendssidebar.services.FriendsSidebarFacade;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * The Friends Sidebar's own overlay widget on Minecraft 1.21.11 (Yarn-mapped,
 * obfuscated) -- a single custom {@link ClickableWidget} subclass added via
 * {@code Screens.getButtons(screen)} (implementation plan Decision 1/3,
 * Pattern 1, no mixin). Owns collapsed (avatar-only, FR2.3) vs. expanded
 * (avatar+name, FR2.4) rendering and forwards a row click (either mouse
 * button, FR2.5) to a supplied listener rather than handling the context
 * menu itself (owned by {@code FabricFriendsSidebarInjector}).
 *
 * <p>Usage example (from {@code FabricFriendsSidebarInjector}):
 * <pre>{@code
 * FriendSidebarWidget sidebar = new FriendSidebarWidget(
 *         6, 6, facade, avatarTextureCache, this::onRowClicked);
 * Screens.getButtons(screen).add(sidebar);
 * }</pre>
 */
public final class FriendSidebarWidget extends ClickableWidget {

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
        super(x, y, EXPANDED_WIDTH, ROW_HEIGHT * MAX_ROWS, Text.literal("Friends"));
        this.facade = facade;
        this.avatarTextureCache = avatarTextureCache;
        this.rowClickListener = rowClickListener;
    }

    private List<FriendSummary> visibleFriends() {
        List<FriendSummary> all = facade.friends();
        return all.size() > MAX_ROWS ? all.subList(0, MAX_ROWS) : all;
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        if (!facade.isEnabled()) {
            return;
        }
        List<FriendSummary> friends = visibleFriends();
        expanded = facade.stateMachine().isExpanded(mouseX, mouseY, getX(), getY(), COLLAPSED_WIDTH,
                Math.max(ROW_HEIGHT, friends.size() * ROW_HEIGHT));

        int width = expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH;
        int height = Math.max(ROW_HEIGHT, friends.size() * ROW_HEIGHT);
        context.fill(getX(), getY(), getX() + width, getY() + height, 0x99000000);

        int rowY = getY();
        for (FriendSummary friend : friends) {
            drawRow(context, friend, getX(), rowY, width);
            rowY += ROW_HEIGHT;
        }
    }

    private void drawRow(DrawContext context, FriendSummary friend, int x, int y, int width) {
        Identifier avatarTexture = avatarTextureCache.getOrUpload(friend.steamId64(),
                facade.avatarRgba(friend.steamId64()).orElse(null));
        int avatarSize = ROW_HEIGHT - 4;
        if (avatarTexture != null) {
            context.drawTexturedQuad(avatarTexture, x + 2, y + 2, x + 2 + avatarSize, y + 2 + avatarSize, 0f, 1f, 0f, 1f);
        } else {
            context.fill(x + 2, y + 2, x + 2 + avatarSize, y + 2 + avatarSize, personaColor(friend));
        }

        if (expanded) {
            context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, friend.personaName(),
                    x + avatarSize + 6, y + 6, 0xFFFFFF);
        }
    }

    private static int personaColor(FriendSummary friend) {
        return friend.personaState() == 0 ? 0xFF808080 : 0xFF33AA33;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (!facade.isEnabled() || !isMouseOver(click.x(), click.y())) {
            return false;
        }
        List<FriendSummary> friends = visibleFriends();
        int relativeY = (int) click.y() - getY();
        int index = relativeY / ROW_HEIGHT;
        if (index >= 0 && index < friends.size()) {
            rowClickListener.onRowClicked(friends.get(index), (int) click.x(), (int) click.y(), click.button());
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
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        builder.put(net.minecraft.client.gui.screen.narration.NarrationPart.TITLE, getMessage());
    }
}
