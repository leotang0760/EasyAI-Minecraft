package baritone.api.behavior;

import net.minecraft.client.network.ClientPlayerEntity;

// ============================================================
// IPlayerContext - 玩家上下文接口（桩代码）
// ============================================================
// 提供当前玩家实体的访问，供 Baritone 读取位置/状态。
// ============================================================

public interface IPlayerContext {

    /**
     * 获取当前客户端玩家实体
     */
    ClientPlayerEntity player();
}
