package net.kuching.cattail.mixin;

import net.kuching.cattail.Redirector;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Early helper start when the server list opens. {@code init()} runs on the render thread, so it only
 * parses the saved addresses (no DNS) and hands them to a daemon worker thread.
 */
@Mixin(JoinMultiplayerScreen.class)
abstract class JoinMultiplayerScreenMixin {

    @Shadow
    private ServerList servers;

    @Inject(method = "init()V", at = @At("TAIL"))
    private void cattail$earlyStart(CallbackInfo ci) {
        if (servers == null) {
            return;
        }
        List<ServerAddress> addresses = new ArrayList<>(servers.size());
        for (int i = 0; i < servers.size(); i++) {
            addresses.add(ServerAddress.parseString(servers.get(i).ip));
        }
        Redirector.get().earlyStartAsync(addresses);
    }
}
