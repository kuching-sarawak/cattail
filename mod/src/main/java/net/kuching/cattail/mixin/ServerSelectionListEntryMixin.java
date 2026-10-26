package net.kuching.cattail.mixin;

import net.kuching.cattail.Redirector;
import net.kuching.cattail.dns.Route;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server list: the text {@code Tailscale Tunnel} on the NAME row of every server Cattail routes,
 * green while the helper is READY for that route, grey otherwise, with the tooltip
 * {@code Via Tailscale · <target ip>}. Render thread: answers only from the route cache
 * ({@link Redirector#cachedRoute}), which the server-list ping fills, so before the first ping
 * there is no label. No DNS, no TXT, no helper start here.
 *
 * <p>Placement (1.21.1 vanilla, {@code x}/{@code y} = the row's left/top, {@code w} = its width):
 * the ping bars sit at {@code x + w - 15 .. x + w - 5}, {@code y .. y + 8}; the player-count
 * text (e.g. {@code 3/20}) ends 5 px left of them on {@code y + 1}. The label's right edge sits
 * 4 px left of whatever is right-most there: the player-count text when there is one, else the
 * ping bars ({@code x + w - 15 - 4}). The brief's {@code x + w - 15 - 4 - width} alone would
 * draw over the player count, which vanilla puts in exactly that spot. A long server name may
 * run under the label; no re-layout (vanilla does not clip names either).
 *
 * <p>modflared (a Cloudflare-tunnel client mod some players use): its 1.21.1 build
 * ({@code ServerEntryMixin}, render TAIL) draws its 10x10 indicator at
 * {@code x + w - 15, y + 11}, i.e. on the SECOND row under the ping bars, and only for servers it
 * tunnels. This label stays on the first row, left of the ping bars, so the two never overlap
 * even if one server had both.
 */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
abstract class ServerSelectionListEntryMixin {

    @Unique
    private static final int CATTAIL_READY_COLOR = 0x55FF55;
    @Unique
    private static final int CATTAIL_IDLE_COLOR = 0xAAAAAA;
    /** Width of the vanilla ping bars plus their 5 px right margin. */
    @Unique
    private static final int CATTAIL_PING_BARS = 15;
    /** Vanilla's gap between the player-count text and the ping bars. */
    @Unique
    private static final int CATTAIL_STATUS_GAP = 5;
    @Unique
    private static final int CATTAIL_LABEL_GAP = 4;

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
    private void cattail$drawLabel(GuiGraphics graphics, int index, int y, int x, int entryWidth, int entryHeight,
            int mouseX, int mouseY, boolean hovered, float partialTick, CallbackInfo ci) {
        ServerAddress address = ServerAddress.parseString(serverData.ip);
        Redirector redirector = Redirector.get();
        Optional<Route> route = redirector.cachedRoute(address.getHost(), address.getPort());
        if (route.isEmpty()) {
            return;
        }
        Font font = minecraft.font;
        Component label = Component.translatable("cattail.label");
        int labelWidth = font.width(label);
        int pingBarsLeft = x + entryWidth - CATTAIL_PING_BARS;
        int statusWidth = font.width(statusText());
        int rightNeighbour = statusWidth > 0 ? pingBarsLeft - CATTAIL_STATUS_GAP - statusWidth : pingBarsLeft;
        int labelRight = rightNeighbour - CATTAIL_LABEL_GAP;
        int labelLeft = labelRight - labelWidth;
        int color = redirector.helperReadyFor(route.get()) ? CATTAIL_READY_COLOR : CATTAIL_IDLE_COLOR;
        graphics.drawString(font, label, labelLeft, y + 1, color, false);
        if (mouseX >= labelLeft && mouseX <= labelRight && mouseY >= y + 1 && mouseY <= y + font.lineHeight) {
            screen.setTooltipForNextRenderPass(
                    Component.translatable("cattail.label.tooltip", route.get().target().getHostAddress()));
        }
    }

    /** The text vanilla draws left of the ping bars on the name row (same choice as vanilla's render). */
    @Unique
    private Component statusText() {
        return serverData.state() == ServerData.State.INCOMPATIBLE
                ? serverData.version.copy().withStyle(ChatFormatting.RED)
                : serverData.status;
    }
}
