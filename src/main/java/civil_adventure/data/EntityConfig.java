package civil_adventure.data;

import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import java.util.List;

/** 单个实体 ID 的冒险区强化配置 */
public record EntityConfig(
    List<AttrMod> attributes,
    List<EffectDef> effects
) {
    public record AttrMod(String attributeId, double modifier, AttributeModifier.Operation operation) {
    }

    public record EffectDef(String effectId, int duration, int amplifier) {
    }
}
