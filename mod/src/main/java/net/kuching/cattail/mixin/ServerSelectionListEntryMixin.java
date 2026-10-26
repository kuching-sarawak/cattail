package net.kuching.cattail.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kuching.cattail.Redirector;
import net.kuching.cattail.config.CattailConfig;
import net.kuching.cattail.dns.Route;
import net.kuching.cattail.serverlist.CattailStatusCarrier;
import net.kuching.cattail.serverlist.ServerListTooltip;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server list: the 14x14 Cattail icon on the NAME row of a Cattail server, with a tooltip of the
 * server's Cattail version, the Tailscale link for a routed server and the owner's lines
 * ({@link ServerListTooltip} decides all of it). It shows when the row's last status response
 * carried a {@code cattail} object or Cattail routes the server; it is dim when the row is routed
 * and the helper is not READY for that route. Render thread: answers only from the row's
 * {@code ServerData} and the route cache ({@link Redirector#cachedRoute}), which the server-list
 * ping fills, so before the first ping there is no icon. No DNS, no TXT, no helper start here.
 *
 * <p>Placement ({@link ServerListTooltip#iconLeft}; 1.21.1 vanilla, {@code x}/{@code y} = the row's
 * left/top, {@code w} = its width): the ping bars sit at {@code x + w - 15 .. x + w - 5},
 * {@code y .. y + 8}; the player-count text (e.g. {@code 3/20}) ends 5 px left of them on
 * {@code y + 1}. The icon's right edge sits 4 px left of that text, or of the ping bars when there
 * is none, and its top is {@code y - 1}: vanilla draws nothing there, so the icon never covers the
 * count. The client config's {@code serverListIconOffsetX}/{@code Y} move it from there, which is
 * how a player makes room for another mod's row icon (No Chat Reports draws its 14x14 icon at
 * {@code x + w - 35, y - 1} plus its own offsets; Better Compatibility Checker draws 16x16 at
 * {@code x + w - 18, y + 10}). A long server name may run under the icon; no re-layout (vanilla
 * does not clip names either).
 *
 * <p>modflared (a Cloudflare-tunnel client mod some players use): its 1.21.1 build
 * ({@code ServerEntryMixin}, render TAIL) draws its 10x10 indicator at {@code x + w - 15, y + 11},
 * i.e. under the ping bars, and only for servers it tunnels. At the default offsets this icon stays
 * left of the ping-bar column, so the two never overlap.
 *
 * <p>The tooltip wraps at 250 px, as No Chat Reports' does.
 *
 * <p>The sprites have transparent and (the dim one) translucent pixels, and
 * {@code GuiGraphics.blitSprite} draws without blending (its shader only discards fully
 * transparent pixels), so the blit runs with blending on, as No Chat Reports does for its icon.
 */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
abstract class ServerSelectionListEntryMixin {

    @Unique
    private static final ResourceLocation CATTAIL_ICON = ResourceLocation.fromNamespaceAndPath("cattail", "server_list_icon");
    @Unique
    private static final ResourceLocation CATTAIL_ICON_DIM =
            ResourceLocation.fromNamespaceAndPath("cattail", "server_list_icon_dim");
    /** The tooltip's wrap width, in GUI pixels. */
    @Unique
    private static final int CATTAIL_TOOLTIP_WIDTH = 250;

    @Shadow
    @Final
    private JoinMultiplayerScreen screen;

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private ServerData serverData;

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIIIIIZF)V", at = @At("TAIL"))
    private void cattail$drawIcon(GuiGraphics graphics, int index, int y, int x, int entryWidth, int entryHeight,
            int mouseX, int mouseY, boolean hovered, float partialTick, CallbackInfo ci) {
        ServerAddress address = ServerAddress.parseString(serverData.ip);
        Redirector redirector = Redirector.get();
        Optional<Route> route = redirector.cachedRoute(address.getHost(), address.getPort());
        ServerListTooltip.View view = new ServerListTooltip.View(((CattailStatusCarrier) serverData).cattail$info(), route,
                route.map(redirector::helperReadyFor).orElse(false));
        if (!ServerListTooltip.shows(view)) {
            return;
        }
        int size = ServerListTooltip.ICON_SIZE;
        int left = ServerListTooltip.iconLeft(x + entryWidth, minecraft.font.width(cattail$statusText()),
                CattailConfig.SERVER_LIST_ICON_OFFSET_X.get());
        int top = y - 1 + CattailConfig.SERVER_LIST_ICON_OFFSET_Y.get();
        RenderSystem.enableBlend();
        graphics.blitSprite(ServerListTooltip.dim(view) ? CATTAIL_ICON_DIM : CATTAIL_ICON, left, top, size, size);
        RenderSystem.disableBlend();
        if (mouseX >= left && mouseX < left + size && mouseY >= top && mouseY < top + size) {
            List<FormattedCharSequence> wrapped = new ArrayList<>();
            for (Component line : ServerListTooltip.lines(view)) {
                wrapped.addAll(minecraft.font.split(line, CATTAIL_TOOLTIP_WIDTH));
            }
            screen.setTooltipForNextRenderPass(wrapped);
        }
    }

    /** The text vanilla draws left of the ping bars on the name row (same choice as vanilla's render). */
    @Unique
    private Component cattail$statusText() {
        return serverData.state() == ServerData.State.INCOMPATIBLE
                ? serverData.version.copy().withStyle(ChatFormatting.RED)
                : serverData.status;
    }
}
