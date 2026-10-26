package net.kuching.cattail.mixin;

import net.kuching.cattail.Redirector;
import java.util.Optional;
import net.minecraft.client.multiplayer.resolver.AddressCheck;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * When the system resolver finds nothing, a host that is ours still resolves to its route target
 * (TXT {@code target=} or a forced host). Runs on the caller's thread: the connector thread, a
 * server pinger thread, or NeoForge's LAN detection thread; never the render thread.
 */
@Mixin(ServerNameResolver.class)
abstract class ServerNameResolverMixin {

    @Shadow
    @Final
    private AddressCheck addressCheck;

    @Inject(method = "resolveAddress(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;",
            at = @At("RETURN"), cancellable = true)
    private void cattail$fallback(ServerAddress address, CallbackInfoReturnable<Optional<ResolvedServerAddress>> cir) {
        if (cir.getReturnValue().isPresent() || !addressCheck.isAllowed(address)) {
            return; // resolved normally, or blocked by Mojang's server blocklist: leave it alone
        }
        Redirector.get().resolveFallback(address)
                .filter(addressCheck::isAllowed)
                .ifPresent(r -> cir.setReturnValue(Optional.of(r)));
    }
}
