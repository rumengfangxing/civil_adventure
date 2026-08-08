package civil_adventure;

import civil_adventure.block.AdventureSuppressorBlock;
import civil_adventure.block.AdventureSuppressorBlockEntity;
import civil_adventure.client.ClientPayloadHandler;
import civil_adventure.config.AdventureConfig;
import civil_adventure.data.AdventureEntityLoader;
import civil_adventure.data.AdventureFuelLoader;
import civil_adventure.network.AdventureTransitionPayload;
import civil_adventure.score.AdventureBlockLoader;
import civil_adventure.score.AdventureScoreService;
import civil_adventure.score.AdventureZoneScanner;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.event.entity.player.PlayerEvent;
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
    public static final DeferredRegister<net.minecraft.sounds.SoundEvent> SOUND_EVENTS =
        DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MOD_ID);

    // 压制方块动画音效（占位，ogg 文件待美术提供）
    public static final RegistryObject<net.minecraft.sounds.SoundEvent> SOUND_IDLE =
        sound("adventure_suppressor_idle");
    public static final RegistryObject<net.minecraft.sounds.SoundEvent> SOUND_OVERLOAD =
        sound("adventure_suppressor_overload");
    public static final RegistryObject<net.minecraft.sounds.SoundEvent> SOUND_RUNNING =
        sound("adventure_suppressor_running");
    public static final RegistryObject<net.minecraft.sounds.SoundEvent> SOUND_START =
        sound("adventure_suppressor_start");
    public static final RegistryObject<net.minecraft.sounds.SoundEvent> SOUND_CLOSE =
        sound("adventure_suppressor_close");

    private static RegistryObject<net.minecraft.sounds.SoundEvent> sound(String name) {
        return SOUND_EVENTS.register(name,
            () -> net.minecraft.sounds.SoundEvent.createVariableRangeEvent(
                new ResourceLocation(MOD_ID, name)));
    }

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
    private static volatile AdventureZoneScanner zoneScanner;

    // 玩家进出冒险区状态追踪
    private final Map<UUID, Boolean> prevZoneState = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTransitionTick = new ConcurrentHashMap<>();
    private static final int TRANSITION_COOLDOWN = 20; // tick，至少间隔 1 秒
    private long epoch = 0;

    public CivilAdventureMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, AdventureConfig.SPEC);

        // 注册方块/物品/方块实体
        IEventBus modBus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        SOUND_EVENTS.register(modBus);
        modBus.addListener(this::onCreativeTab);

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
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLogout);
    }

    private void onServerStarted(ServerStartedEvent event) {
        var server = event.getServer();
        scoreService = new AdventureScoreService();
        zoneScanner = new AdventureZoneScanner();
        AdventureBlockLoader.reload(server.getResourceManager());
        AdventureEntityLoader.reload(server.getResourceManager());
        AdventureFuelLoader.reload(server.getResourceManager());
        prevZoneState.clear();
        lastTransitionTick.clear();

        LOGGER.info("冒险区系统初始化完成");
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (scoreService != null) { scoreService.shutdown(); scoreService = null; }
        zoneScanner = null;
        prevZoneState.clear();
        lastTransitionTick.clear();
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (zoneScanner == null || scoreService == null) return;

        long currentTick = event.getServer().getTickCount();

        for (ServerLevel level : event.getServer().getAllLevels()) {
            zoneScanner.tick(level);

            // 冷却到期批量重算脏区块
            scoreService.tickCooldown(level, currentTick);

            // 检测玩家进出冒险区 → 发送 HUD 通知
            for (ServerPlayer player : level.players()) {
                if (!player.isAlive()) continue;
                boolean inZone = scoreService.getNormalizedScoreAt(level, player.blockPosition())
                    >= AdventureConfig.ZONE_THRESHOLD.get();
                UUID puid = player.getUUID();
                Boolean prev = prevZoneState.get(puid);

                if (prev != null && prev != inZone) {
                    // 去抖：同玩家切换冷却
                    Long lastTick = lastTransitionTick.get(puid);
                    if (lastTick == null || currentTick - lastTick >= TRANSITION_COOLDOWN) {
                        epoch++;
                        CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new AdventureTransitionPayload(inZone, epoch));
                        lastTransitionTick.put(puid, currentTick);
                    }
                }
                prevZoneState.put(puid, inZone);
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

    private void onCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(SUPPRESSOR_ITEM.get());
        }
    }

    private void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        prevZoneState.remove(uuid);
        lastTransitionTick.remove(uuid);
    }

    public static AdventureScoreService getScoreService() { return scoreService; }
    public static AdventureZoneScanner getZoneScanner() { return zoneScanner; }
}
