package net.kuching.cattail.mixin;

import net.kuching.cattail.ConnectAbortState;
import net.kuching.cattail.Redirector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Status line on the connect screen (render thread: reads a volatile field only, never blocks). */
@Mixin(ConnectScreen.class)
abstract class ConnectScreenMixin extends Screen implements ConnectAbortState {

    private static final int CATTAIL_STATUS_COLOR = 0xA0A0A0;

    @Shadow
    volatile boolean aborted;

    private ConnectScreenMixin(Component title) {
        super(title);
    }

    @Override
    public boolean cattail$isAborted() {
        return aborted;
    }

    /** A new connect attempt: forget the previous attempt's status. */
    @Inject(method = "startConnecting(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;ZLnet/minecraft/client/multiplayer/TransferState;)V",
            at = @At("HEAD"))
    private static void cattail$newAttempt(Screen parent, Minecraft minecraft, ServerAddress address, ServerData data,
            boolean quickPlay, TransferState transferState, CallbackInfo ci) {
        Redirector.get().clearStatus();
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("TAIL"))
    private void cattail$drawStatus(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        Redirector.get().lastStatus().filter(Redirector.Status::visible).ifPresent(s -> graphics.drawCenteredString(
                this.font, Component.translatable("cattail.status." + s.key(), s.arg()),
                this.width / 2, this.height / 2 - 50 + 2 * this.font.lineHeight, CATTAIL_STATUS_COLOR));
    }
}
