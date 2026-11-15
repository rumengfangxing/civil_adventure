package civil_adventure.faction.event;

import civil_adventure.faction.FactionDefinition;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingEvent;

import java.util.List;

/**
 * 当生物因为处于其派系的显性统治区域而被附加或移除 Tag 词缀时触发。
 */
public abstract class EntityFactionTagEvent extends LivingEvent {

    private final FactionDefinition faction;
    private final List<String> tags;

    public EntityFactionTagEvent(LivingEntity entity, FactionDefinition faction, List<String> tags) {
        super(entity);
        this.faction = faction;
        this.tags = tags;
    }

    public FactionDefinition getFaction() {
        return faction;
    }

    public List<String> getTags() {
        return tags;
    }

    /** 赋予显性 Tag 事件 */
    public static class Attached extends EntityFactionTagEvent {
        public Attached(LivingEntity entity, FactionDefinition faction, List<String> tags) {
            super(entity, faction, tags);
        }
    }

    /** 移除显性 Tag 事件（离开统治区或统治易主） */
    public static class Removed extends EntityFactionTagEvent {
        public Removed(LivingEntity entity, FactionDefinition faction, List<String> tags) {
            super(entity, faction, tags);
        }
    }
}
