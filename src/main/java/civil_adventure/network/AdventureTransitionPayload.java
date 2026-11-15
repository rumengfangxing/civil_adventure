package civil_adventure.network;

import net.minecraft.network.FriendlyByteBuf;

/** 服务端→客户端：生态领地状态切换与 HUD 标签通知 */
public record AdventureTransitionPayload(boolean entering, long epoch, String label) {

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(entering);
        buf.writeLong(epoch);
        buf.writeUtf(label != null ? label : "");
    }

    public static AdventureTransitionPayload read(FriendlyByteBuf buf) {
        return new AdventureTransitionPayload(buf.readBoolean(), buf.readLong(), buf.readUtf());
    }
}
