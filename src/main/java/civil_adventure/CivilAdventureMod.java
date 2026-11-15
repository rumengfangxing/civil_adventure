package civil_adventure;

import civil_adventure.block.AdventureSuppressorBlock;
import civil_adventure.block.AdventureSuppressorBlockEntity;
import civil_adventure.cluster.MacroCluster;
import civil_adventure.config.AdventureConfig;
import civil_adventure.data.AdventureEntityLoader;
import civil_adventure.data.AdventureFuelLoader;
import civil_adventure.entity.EntityFactionTagManager;
import civil_adventure.faction.FactionDefinition;
import civil_adventure.faction.FactionRegistry;
import civil_adventure.network.AdventureTransitionPayload;
import civil_adventure.score.AdventureBlockLoader;
import civil_adventure.score.AdventureScoreService;
import civil_adventure.override.CleansedStructureStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod(CivilAdventureMod.MOD_ID)
@EventBusSubscriber(modid = CivilAdventureMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class CivilAdventureMod {
    public static final String MOD_ID = "civil_adventure";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(MOD_ID, "main"),
        () -> "1.0", s -> true, s -> true);

    // ── 方块注册 ────────────────────────────────────────
    public static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MOD_ID);

    public static final RegistryObject<AdventureSuppressorBlock> SUPPRESSOR_BLOCK = BLOCKS.register(
        "adventure_suppressor",
        () -> new AdventureSuppressorBlock(BlockBehaviour.Properties.of()
            .strength(3.0F)
            .requiresCorrectToolForDrops()));

    public static final RegistryObject<Item> SUPPRESSOR_ITEM = ITEMS.register(
        "adventure_suppressor",
        () -> new civil_adventure.block.SuppressorBlockItem(SUPPRESSOR_BLOCK.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<AdventureSuppressorBlockEntity>> SUPPRESSOR_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("adventure_suppressor",
            () -> BlockEntityType.Builder.of(
                AdventureSuppressorBlockEntity::new, SUPPRESSOR_BLOCK.get()).build(null));

    private static volatile AdventureScoreService scoreService;
    private static volatile EntityFactionTagManager entityTagManager;

    // 玩家宏观领地状态追踪
    private final Map<UUID, Byte> prevPlayerFaction = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTransitionTick = new ConcurrentHashMap<>();
    private static final int TRANSITION_COOLDOWN = 20;
    private long epoch = 0;

    public CivilAdventureMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, AdventureConfig.SPEC);
        software.bernie.geckolib.GeckoLib.initialize();

        IEventBus modBus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(this::onCreativeTab);

        int id = 0;
        CHANNEL.registerMessage(id++, AdventureTransitionPayload.class,
            AdventureTransitionPayload::write, AdventureTransitionPayload::read,
            (pkt, ctx) -> {
                net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> civil_adventure.client.ClientPayloadHandler.handleTransition(pkt, ctx.get())
                );
                ctx.get().setPacketHandled(true);
            });

        CHANNEL.registerMessage(id++, civil_adventure.network.AdventureClusterSyncPayload.class,
            civil_adventure.network.AdventureClusterSyncPayload::write,
            civil_adventure.network.AdventureClusterSyncPayload::read,
            (pkt, ctx) -> {
                net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> civil_adventure.client.ClientPayloadHandler.handleClusterSync(pkt, ctx.get())
                );
                ctx.get().setPacketHandled(true);
            });

        MinecraftForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::onLivingDeath);
        MinecraftForge.EVENT_BUS.addListener(this::onChunkUnload);
        MinecraftForge.EVENT_BUS.addListener(this::onChunkLoad);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLogin);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLogout);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerChangeDimension);
    }

    private void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new SimplePreparableReloadListener<Void>() {
            @Override
            protected Void prepare(ResourceManager resourceManager, ProfilerFiller profilerFiller) {
                return null;
            }

            @Override
            protected void apply(Void unused, ResourceManager resourceManager, ProfilerFiller profilerFiller) {
                FactionRegistry.reload(resourceManager);
                AdventureBlockLoader.reload(resourceManager);
                AdventureEntityLoader.reload(resourceManager);
                AdventureFuelLoader.reload(resourceManager);

                net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                if (server != null && scoreService != null) {
                    scoreService.onReload(server.getAllLevels());
                }
            }
        });
    }

    private void onServerStarted(ServerStartedEvent event) {
        var server = event.getServer();
        scoreService = new AdventureScoreService();
        entityTagManager = new EntityFactionTagManager();

        FactionRegistry.reload(server.getResourceManager());
        AdventureBlockLoader.reload(server.getResourceManager());
        AdventureEntityLoader.reload(server.getResourceManager());
        AdventureFuelLoader.reload(server.getResourceManager());

        prevPlayerFaction.clear();
        lastTransitionTick.clear();
        LOGGER.info("[civil_adventure][Step 2/WorldInit] 2.0 宏观生态对抗与威胁场引擎初始化完成");

        for (ServerLevel level : server.getAllLevels()) {
            int cleansedCount = CleansedStructureStorage.get(level).getCleansedCount();
            LOGGER.info("[civil_adventure][Step 2/WorldInit] 维度 {} 威胁场就绪 (已载入 {} 个已肃清结构)",
                    level.dimension().location(), cleansedCount);
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (scoreService != null) { scoreService.shutdown(); scoreService = null; }
        if (entityTagManager != null) { entityTagManager.clear(); entityTagManager = null; }
        prevPlayerFaction.clear();
        lastTransitionTick.clear();
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (scoreService == null) return;

        long currentTick = event.getServer().getTickCount();

        for (ServerLevel level : event.getServer().getAllLevels()) {
            // 1. 冷却到期批量重算脏区块与宏观连通域聚类
            scoreService.tickCooldown(level, currentTick);

            // 2. 驱动生物【是 / 非】Tag 租约与防抖延迟剥离
            if (entityTagManager != null) {
                entityTagManager.tick(level);
            }

            // 3. 玩家跨领地 HUD 标签推送
            for (ServerPlayer player : level.players()) {
                if (!player.isAlive()) continue;

                MacroCluster cluster = scoreService.getClusterAt(level, player.blockPosition());
                byte currentFactionIdx = (cluster != null && cluster.hasDominance()) ? cluster.getDominantIndex() : (byte) -1;
                UUID puid = player.getUUID();
                Byte prev = prevPlayerFaction.get(puid);

                if (prev != null && prev != currentFactionIdx) {
                    Long lastTick = lastTransitionTick.get(puid);
                    if (lastTick == null || currentTick - lastTick >= TRANSITION_COOLDOWN) {
                        epoch++;
                        boolean enteringNonCiv = currentFactionIdx > 0;
                        FactionDefinition dom = (cluster != null) ? cluster.getDominantFaction() : null;
                        String label = (dom != null) ? dom.nameTranslationKey() : "";

                        LOGGER.debug("[civil_adventure][Step 5/ZoneDominance] 玩家 {} 跨越地缘边界进入 [{}] 领地 (坐标: {}, {}, {} | 聚类ID: #{} | 威胁总分: {})",
                                player.getName().getString(),
                                dom != null ? dom.id() : "荒野/无统治",
                                player.getBlockX(), player.getBlockY(), player.getBlockZ(),
                                cluster != null ? cluster.getId() : 0,
                                cluster != null ? String.format("%.1f", cluster.getDominantTotalScore()) : "0.0");

                        CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new AdventureTransitionPayload(enteringNonCiv, epoch, label));
                        lastTransitionTick.put(puid, currentTick);
                    }
                }
                prevPlayerFaction.put(puid, currentFactionIdx);
            }
        }
    }

    private void onLivingDeath(LivingDeathEvent event) {
        UUID uuid = event.getEntity().getUUID();
        if (entityTagManager != null) entityTagManager.onEntityRemoved(uuid);
    }

    private void onChunkUnload(ChunkEvent.Unload event) {
        if (scoreService == null) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var pos = event.getChunk().getPos();
        scoreService.onChunkUnload(level, pos.x, pos.z);
    }

    private void onChunkLoad(ChunkEvent.Load event) {
        if (scoreService == null) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var chunk = event.getChunk();
        var pos = chunk.getPos();
        scoreService.onChunkLoaded(level, chunk, pos.x, pos.z);
    }

    private void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (scoreService == null) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        int centerCx = pos.getX() >> 4;
        int centerCz = pos.getZ() >> 4;
        int radius = 10;
        int scannedCount = 0;

        LOGGER.info("[civil_adventure][Step 2/WorldInit] 玩家 {} 进入维度 {}，开始预热扫描周围区块 (半径: {})...",
                player.getName().getString(), level.dimension().location(), radius);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = centerCx + dx;
                int cz = centerCz + dz;
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk != null) {
                    scoreService.onChunkLoaded(level, chunk, cx, cz);
                    scannedCount++;
                }
            }
        }

        scoreService.rebuildClustersImmediately(level);
        scoreService.syncToPlayer(player);
        LOGGER.info("[civil_adventure][Step 2/WorldInit] 玩家初始区域预热完成，共预热扫描 {} 个已加载区块", scannedCount);
    }

    private void onPlayerChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (scoreService == null) return;
        if (event.getEntity() instanceof ServerPlayer player) {
            prevPlayerFaction.remove(player.getUUID());
            scoreService.rebuildClustersImmediately(player.serverLevel());
            scoreService.syncToPlayer(player);
        }
    }

    private void onCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(SUPPRESSOR_ITEM.get());
        }
    }

    private void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        prevPlayerFaction.remove(uuid);
        lastTransitionTick.remove(uuid);
    }

    public static AdventureScoreService getScoreService() { return scoreService; }
    public static EntityFactionTagManager getEntityTagManager() { return entityTagManager; }
}
