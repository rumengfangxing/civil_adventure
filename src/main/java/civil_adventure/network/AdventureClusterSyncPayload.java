package civil_adventure.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端→客户端：同步维度宏观领地显性统治区块分布及派系基础展示信息。
 * 供客户端 Xaero 地图雷达染色及 Tooltip 呈现，杜绝跨端内存竞态与联机失效。
 */
public record AdventureClusterSyncPayload(
        ResourceLocation dimension,
        List<FactionInfo> factions,
        Map<Long, Byte> chunkDominance
) {

    public record FactionInfo(byte index, String nameKey, int colorRgb, boolean isCivilization) {
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(index);
            buf.writeUtf(nameKey != null ? nameKey : "");
            buf.writeInt(colorRgb);
            buf.writeBoolean(isCivilization);
        }

        public static FactionInfo read(FriendlyByteBuf buf) {
            return new FactionInfo(buf.readByte(), buf.readUtf(), buf.readInt(), buf.readBoolean());
        }
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeVarInt(factions.size());
        for (FactionInfo f : factions) {
            f.write(buf);
        }
        buf.writeVarInt(chunkDominance.size());
        for (Map.Entry<Long, Byte> entry : chunkDominance.entrySet()) {
            buf.writeLong(entry.getKey());
            buf.writeByte(entry.getValue());
        }
    }

    public static AdventureClusterSyncPayload read(FriendlyByteBuf buf) {
        ResourceLocation dim = buf.readResourceLocation();
        int fCount = buf.readVarInt();
        List<FactionInfo> factions = new ArrayList<>(fCount);
        for (int i = 0; i < fCount; i++) {
            factions.add(FactionInfo.read(buf));
        }
        int cCount = buf.readVarInt();
        Map<Long, Byte> dominance = new HashMap<>(cCount);
        for (int i = 0; i < cCount; i++) {
            dominance.put(buf.readLong(), buf.readByte());
        }
        return new AdventureClusterSyncPayload(dim, factions, dominance);
    }
}
