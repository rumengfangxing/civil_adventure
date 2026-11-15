package civil_adventure.core;

import net.minecraft.core.BlockPos;

/**
 * 高性能基本类型 long 区块坐标压缩工具。
 * 彻底消除 String 对象分配（"dim:cx,cz,0"）和 GC 压力。
 */
public final class ChunkCoordPacker {

    private ChunkCoordPacker() {}

    /** 将 (cx, cz) 压缩为一个 64 位 long */
    public static long pack(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
    }

    /** 从 BlockPos 压缩其所在区块坐标 */
    public static long pack(BlockPos pos) {
        return pack(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** 从压缩的 long 解压出 cx */
    public static int unpackCx(long packed) {
        return (int) (packed >> 32);
    }

    /** 从压缩的 long 解压出 cz */
    public static int unpackCz(long packed) {
        return (int) packed;
    }
}
