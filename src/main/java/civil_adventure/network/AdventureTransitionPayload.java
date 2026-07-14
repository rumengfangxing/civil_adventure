package civil_adventure.network;

import net.minecraft.network.FriendlyByteBuf;

/** 服务端→客户端：冒险区状态切换通知 */
public record AdventureTransitionPayload(boolean entering, long epoch) {

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(entering);
        buf.writeLong(epoch);
    }

    public static AdventureTransitionPayload read(FriendlyByteBuf buf) {
        return new AdventureTransitionPayload(buf.readBoolean(), buf.readLong());
    }
}
