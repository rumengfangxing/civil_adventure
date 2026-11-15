package civil_adventure.override;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 维度级已肃清与受损结构基础分持久化存储（NBT）。
 * 1. 记录被压制彻底清零的文礼/派系冒险区结构位置（cleansedStructureKeys）。
 * 2. 记录部分压制但尚未清零的结构剩余分（damagedStructureScores），保证重启后不复活满血。
 */
public class CleansedStructureStorage extends SavedData {

    private static final String DATA_NAME = "civil_adventure_cleansed_structures";
    // [修复 M-05] 并发安全集合，防止异步地图采样或多线程结构判定导致 ConcurrentModificationException
    private final Set<Long> cleansedStructureKeys = ConcurrentHashMap.newKeySet();
    private final Map<Long, Double> damagedStructureScores = new ConcurrentHashMap<>();

    public static CleansedStructureStorage get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                CleansedStructureStorage::load,
                CleansedStructureStorage::new,
                DATA_NAME
        );
    }

    public CleansedStructureStorage() {}

    public static CleansedStructureStorage load(CompoundTag tag) {
        CleansedStructureStorage storage = new CleansedStructureStorage();
        if (tag.contains("CleansedKeys", Tag.TAG_LIST)) {
            ListTag list = tag.getList("CleansedKeys", Tag.TAG_LONG);
            for (int i = 0; i < list.size(); i++) {
                storage.cleansedStructureKeys.add(((LongTag) list.get(i)).getAsLong());
            }
        }
        if (tag.contains("DamagedScores", Tag.TAG_LIST)) {
            ListTag list = tag.getList("DamagedScores", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                storage.damagedStructureScores.put(entry.getLong("Key"), entry.getDouble("Score"));
            }
        }
        return storage;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Long key : cleansedStructureKeys) {
            list.add(LongTag.valueOf(key));
        }
        tag.put("CleansedKeys", list);

        ListTag damagedList = new ListTag();
        for (Map.Entry<Long, Double> e : damagedStructureScores.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Key", e.getKey());
            entry.putDouble("Score", e.getValue());
            damagedList.add(entry);
        }
        tag.put("DamagedScores", damagedList);

        return tag;
    }

    public boolean isCleansed(long key) {
        return cleansedStructureKeys.contains(key);
    }

    public int getCleansedCount() {
        return cleansedStructureKeys.size();
    }

    public void markCleansed(long key) {
        damagedStructureScores.remove(key);
        if (cleansedStructureKeys.add(key)) {
            setDirty();
        }
    }

    public void unmarkCleansed(long key) {
        if (cleansedStructureKeys.remove(key)) {
            setDirty();
        }
    }

    public Double getRemainingScore(long key) {
        return damagedStructureScores.get(key);
    }

    public void setRemainingScore(long key, double score) {
        damagedStructureScores.put(key, score);
        setDirty();
    }

    public void removeRemainingScore(long key) {
        if (damagedStructureScores.remove(key) != null) {
            setDirty();
        }
    }

    public void clear() {
        cleansedStructureKeys.clear();
        damagedStructureScores.clear();
        setDirty();
    }
}
