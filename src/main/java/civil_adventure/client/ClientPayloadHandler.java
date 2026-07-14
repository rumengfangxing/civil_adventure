package civil_adventure.client;

import civil_adventure.config.AdventureConfig;
import civil_adventure.network.AdventureTransitionPayload;
import civil.civilization.ZoneTransitionHud;
import civil.civilization.ZoneTransitionPayload;

/**
 * Client‑side handler: 收到冒险区切换通知 → 调用文礼 ZoneTransitionHud。
 * 使用 CAUTION（橙色）表示冒险区，WILDERNESS（蓝色）表示离开。
 */
public class ClientPayloadHandler {

    public static void handle(AdventureTransitionPayload payload, net.minecraftforge.network.NetworkEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            if (!AdventureConfig.ADVENTURE_HUD_ENABLED.get()) return;

            int stateId = payload.entering() ? 2 : 1; // CAUTION / WILDERNESS
            var civilPayload = new ZoneTransitionPayload(payload.epoch(), stateId);
            ZoneTransitionHud.onPayload(civilPayload);
        });
        ctx.setPacketHandled(true);
    }
}
