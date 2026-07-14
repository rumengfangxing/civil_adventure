package civil_adventure;

import civil_adventure.client.ClientPayloadHandler;
import civil_adventure.config.AdventureConfig;
import civil_adventure.data.AdventureEntityLoader;
import civil_adventure.network.AdventureTransitionPayload;
import civil_adventure.score.AdventureBlockLoader;
import civil_adventure.score.AdventureScoreService;
import civil_adventure.score.AdventureZoneScanner;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod(CivilAdventureMod.MOD_ID)
public class CivilAdventureMod {
    public static final String MOD_ID = "civil_adventure";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(MOD_ID, "main"),
        () -> "1.0", s -> true, s -> true);

    private static volatile AdventureScoreService scoreService;
    private static volatile AdventureZoneScanner zoneScanner;

    // 玩家 → 上次是否在冒险区
    private final Map<UUID, Boolean> prevZoneState = new ConcurrentHashMap<>();
    private long epoch = 0;

    public CivilAdventureMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, AdventureConfig.SPEC);

        // 注册网络包
        int id = 0;
        CHANNEL.registerMessage(id++, AdventureTransitionPayload.class,
            AdventureTransitionPayload::write, AdventureTransitionPayload::read,
            (pkt, ctx) -> {
                if (FMLEnvironment.dist.isClient()) {
                    ClientPayloadHandler.handle(pkt, ctx.get());
                }
                ctx.get().setPacketHandled(true);
            });

        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::onLivingDeath);
        MinecraftForge.EVENT_BUS.addListener(this::onChunkUnload);
        MinecraftForge.EVENT_BUS.addListener(this::onChunkLoad);
    }

    private void onServerStarted(ServerStartedEvent event) {
        var server = event.getServer();
        scoreService = new AdventureScoreService();
        zoneScanner = new AdventureZoneScanner();
        AdventureBlockLoader.reload(server.getResourceManager());
        AdventureEntityLoader.reload(server.getResourceManager());
        prevZoneState.clear();

        LOGGER.info("冒险区系统初始化完成");
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (scoreService != null) { scoreService.shutdown(); scoreService = null; }
        zoneScanner = null;
        prevZoneState.clear();
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (zoneScanner == null || scoreService == null) return;

        for (ServerLevel level : event.getServer().getAllLevels()) {
            zoneScanner.tick(level);

            // 检测玩家进出冒险区 → 发送 HUD 通知
            for (ServerPlayer player : level.players()) {
                if (!player.isAlive()) continue;
                boolean inZone = scoreService.getNormalizedScoreAt(level, player.blockPosition())
                    >= AdventureConfig.ZONE_THRESHOLD.get();
                Boolean prev = prevZoneState.get(player.getUUID());

                if (prev != null && prev != inZone) {
                    epoch++;
                    CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new AdventureTransitionPayload(inZone, epoch));
                }
                prevZoneState.put(player.getUUID(), inZone);
            }
        }
    }

    private void onLivingDeath(LivingDeathEvent event) {
        if (zoneScanner != null) zoneScanner.onEntityRemoved(event.getEntity().getUUID());
    }

    private void onChunkUnload(ChunkEvent.Unload event) {
        if (scoreService == null) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var pos = event.getChunk().getPos();
        scoreService.onChunkUnload(level, pos.x, pos.z);
    }

    /** 区块加载时全高度扫描冒险区方块 */
    private void onChunkLoad(ChunkEvent.Load event) {
        if (scoreService == null) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var pos = event.getChunk().getPos();
        scoreService.recalculateFullChunk(level, pos.x, pos.z);
    }

    public static AdventureScoreService getScoreService() { return scoreService; }
    public static AdventureZoneScanner getZoneScanner() { return zoneScanner; }
}
