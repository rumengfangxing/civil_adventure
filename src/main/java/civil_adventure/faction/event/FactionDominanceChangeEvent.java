package civil_adventure.faction.event;

import civil_adventure.faction.FactionDefinition;
import civil_adventure.faction.FactionRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.level.LevelEvent;

/**
 * 当区块内的显性统治派系（谁是老大）发生改变时触发。
 * 供模组内系统与 KubeJS 脚本监听。
 */
public class FactionDominanceChangeEvent extends LevelEvent {

    private final ChunkPos chunkPos;
    private final byte oldFactionIndex;
    private final byte newFactionIndex;
    private final float dominantScore;
    private final float margin;

    public FactionDominanceChangeEvent(ServerLevel level, ChunkPos chunkPos,
                                        byte oldFactionIndex, byte newFactionIndex,
                                        float dominantScore, float margin) {
        super(level);
        this.chunkPos = chunkPos;
        this.oldFactionIndex = oldFactionIndex;
        this.newFactionIndex = newFactionIndex;
        this.dominantScore = dominantScore;
        this.margin = margin;
    }

    public ServerLevel getServerLevel() {
        return (ServerLevel) getLevel();
    }

    public ChunkPos getChunkPos() {
        return chunkPos;
    }

    public byte getOldFactionIndex() {
        return oldFactionIndex;
    }

    public byte getNewFactionIndex() {
        return newFactionIndex;
    }

    public FactionDefinition getOldFaction() {
        return FactionRegistry.getByIndex(oldFactionIndex);
    }

    public FactionDefinition getNewFaction() {
        return FactionRegistry.getByIndex(newFactionIndex);
    }

    public float getDominantScore() {
        return dominantScore;
    }

    public float getMargin() {
        return margin;
    }
}
