package civil_adventure.block;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * 压制方块物品：使用标准 2D 像素图标渲染背包/快捷栏/手持，
 * 避免 3D GeckoLib 模型在 GUI 格子中悬空、偏移与剪裁问题。
 * 放置到世界中后依然由 GeckoLib 接管完整的 3D 旋转动画。
 */
public class SuppressorBlockItem extends BlockItem {

    public SuppressorBlockItem(Block block, Item.Properties properties) {
        super(block, properties);
    }
}
