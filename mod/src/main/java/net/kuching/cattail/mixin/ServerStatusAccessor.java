package net.kuching.cattail.mixin;

import com.mojang.serialization.Codec;
import net.minecraft.network.protocol.status.ServerStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read and write access to {@link ServerStatus#CODEC} at run time, for
 * {@code StatusCodec.ensureWrapped()}: the field is replaced by more than one mod's static
 * initialiser injection (Better Compatibility Checker assigns a new codec outright), in an order
 * no mod controls, so Cattail re-wraps whatever codec is there just before the status is used.
 */
@Mixin(ServerStatus.class)
public interface ServerStatusAccessor {

    @Accessor("CODEC")
    static Codec<ServerStatus> cattail$codec() {
        throw new AssertionError("replaced by Mixin");
    }

    @Accessor("CODEC")
    static void cattail$setCodec(Codec<ServerStatus> codec) {
        throw new AssertionError("replaced by Mixin");
    }
}
