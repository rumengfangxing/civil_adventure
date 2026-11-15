package civil_adventure.client;

import civil_adventure.config.AdventureConfig;
import civil_adventure.network.AdventureTransitionPayload;
import civil.civilization.ZoneTransitionHud;
import civil.civilization.ZoneTransitionPayload;

/**
 * 客户端处理器：收到生态领地切换通知 → 调用文礼 ZoneTransitionHud。
 * 使用 CAUTION (2) 表示非文明显性区，WILDERNESS (1) 表示离开或荒野，支持 label 覆写。
 */
public class ClientPayloadHandler {

    public static void handleTransition(AdventureTransitionPayload payload, net.minecraftforge.network.NetworkEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            if (!AdventureConfig.ADVENTURE_HUD_ENABLED.get()) return;

            int stateId = payload.entering() ? 2 : 1; // CAUTION / WILDERNESS
            String rawLabel = payload.label();
            String displayLabel = rawLabel;
            if (rawLabel != null && !rawLabel.isEmpty()) {
                if (net.minecraft.client.resources.language.I18n.exists(rawLabel)) {
                    displayLabel = net.minecraft.client.resources.language.I18n.get(rawLabel);
                }
            }
            var civilPayload = new ZoneTransitionPayload(payload.epoch(), stateId, displayLabel);
            ZoneTransitionHud.onPayload(civilPayload);
        });
        ctx.setPacketHandled(true);
    }

    public static void handle(AdventureTransitionPayload payload, net.minecraftforge.network.NetworkEvent.Context ctx) {
        handleTransition(payload, ctx);
    }

    public static void handleClusterSync(civil_adventure.network.AdventureClusterSyncPayload payload, net.minecraftforge.network.NetworkEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            ClientClusterCache.applySync(payload);
        });
        ctx.setPacketHandled(true);
    }
}
