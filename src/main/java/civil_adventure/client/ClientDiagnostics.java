package civil_adventure.client;

import civil_adventure.CivilAdventureMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import software.bernie.geckolib.cache.GeckoLibCache;

import java.util.Map;

/**
 * 运行时诊断（FORGE bus）：进入世界后打印一次「geo 文件是否被资源管理器列出」与「GeckoLib 缓存烘焙结果」。
 * 用于定位「Unable to find model」崩溃的环节：
 * - 文件未列出 → 资源包可见性问题
 * - 已列出未烘焙 → GeckoLib 侧问题
 * 定位完成后可删除本类。
 */
@Mod.EventBusSubscriber(modid = CivilAdventureMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ClientDiagnostics {

    private static boolean diagLogged = false;
    private static int tickCount = 0;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (diagLogged || event.phase != TickEvent.Phase.END) return;
        // 主菜单阶段（启动约 15 秒后）即可打印，无需进世界
        if (++tickCount < 300) return;
        diagLogged = true;

        var listed = Minecraft.getInstance().getResourceManager().listResources("geo",
            loc -> loc.getPath().endsWith(".json"));
        listed.keySet().stream()
            .filter(loc -> loc.getNamespace().equals(CivilAdventureMod.MOD_ID))
            .forEach(loc -> CivilAdventureMod.LOGGER.debug("[diag] geo listed: {}", loc));

        Map<ResourceLocation, ?> baked = GeckoLibCache.getBakedModels();
        long own = baked.keySet().stream()
            .filter(loc -> loc.getNamespace().equals(CivilAdventureMod.MOD_ID)).count();
        CivilAdventureMod.LOGGER.debug("[diag] geckolib total models: {}, civil_adventure models: {}", baked.size(), own);

        // 全部已烘焙模型的 namespace 统计（判断其它 mod 的 block 模型是否正常）
        java.util.Map<String, Long> byNs = baked.keySet().stream()
            .collect(java.util.stream.Collectors.groupingBy(ResourceLocation::getNamespace,
                java.util.stream.Collectors.counting()));
        CivilAdventureMod.LOGGER.debug("[diag] baked namespaces: {}", byNs);
        baked.keySet().stream()
            .filter(loc -> loc.getPath().contains("block/"))
            .forEach(loc -> CivilAdventureMod.LOGGER.debug("[diag] baked block model: {}", loc));
    }
}
