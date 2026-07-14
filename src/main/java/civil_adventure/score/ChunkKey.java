package civil_adventure.score;

import net.minecraft.core.BlockPos;

/**
 * 自包含的区块坐标键，避免强耦合文礼 VoxelChunkKey。
 * 使用区块坐标（cx, cz），Y 层固定为 0（单层扫描）。
 */
public record ChunkKey(int cx, int cz) {

    public static ChunkKey from(BlockPos pos) {
        return new ChunkKey(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static String key(String dim, int cx, int cz) {
        return dim + ":" + cx + "," + cz + ",0";
    }
}
