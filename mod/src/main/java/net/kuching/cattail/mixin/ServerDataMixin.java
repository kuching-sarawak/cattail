package net.kuching.cattail.mixin;

import java.util.Optional;
import net.kuching.cattail.serverlist.CattailStatusCarrier;
import net.kuching.cattail.serverlist.ServerListInfo;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Server list: each row's {@link ServerData} carries the {@link ServerListInfo} of its last status
 * response, which {@code ServerStatusPingerListenerMixin} sets on the ping connection's network
 * thread and the row's render reads on the render thread (hence {@code volatile}; the info is
 * immutable).
 */
@Mixin(ServerData.class)
abstract class ServerDataMixin implements CattailStatusCarrier {

    @Unique
    private volatile Optional<ServerListInfo> cattail$info = Optional.empty();

    @Override
    public Optional<ServerListInfo> cattail$info() {
        return cattail$info;
    }

    @Override
    public void cattail$setInfo(Optional<ServerListInfo> info) {
        cattail$info = info;
    }
}
