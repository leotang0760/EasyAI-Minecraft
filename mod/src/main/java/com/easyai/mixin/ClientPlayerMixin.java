package com.easyai.mixin;

// ============================================================
// ClientPlayerMixin - 读取/修改本地玩家状态
// ============================================================
// Mixin 注入原理：
//   通过 @Mixin 注入 ClientPlayerEntity，我们可以在不修改原版类的前提下，
//   访问受保护的字段、钩入 tick() 方法实现每帧状态同步。
//
//   这个 Mixin 主要用于：
//   1. 拦截玩家移动输入（在 AI 接管时阻止人工输入干扰）
//   2. 钩入 tick() 方法，在每帧末尾执行 AI 微操作
//   3. 读取玩家坐标、血量、饱食度等私有状态
// ============================================================

import com.easyai.EasyAIMod;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public class ClientPlayerMixin {

    // ============================================================
    // 钩入 tick() 方法尾部
    // ============================================================
    // ClientPlayerEntity.tick() 每帧调用一次，处理玩家所有逻辑更新。
    // 我们在方法尾部注入，确保在原版逻辑执行完毕后再做 AI 微操，
    // 避免与原版输入处理冲突。
    // ============================================================
    @Inject(method = "tick", at = @At("TAIL"))
    private void easyai$onTickEnd(CallbackInfo ci) {
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;

        // 如果 AI 大脑离线，不做任何干预
        if (!EasyAIMod.getInstance().isBrainConnected()) {
            return;
        }

        // ---- 动态微操：自动跳跃上1格高的方块 ----
        // 当玩家前方有1格高方块且正在向前移动时，自动跳跃
        // 这里只做简单的前方检测，实际的精确寻路由 Baritone 处理
        // （Baritone 内部已实现此逻辑，此处作为兜底）

        // ---- 自动潜行防坠落 ----
        // 当玩家在方块边缘且不在潜行状态时，自动潜行
        // （同样，Baritone 已内置此逻辑，此处为独立模式兜底）

        // 此处保持空实现，具体微操由 EasyAIMod.onClientTick 统一调度
        // 保留此 Mixin 是为了未来扩展（如完全接管输入流）
    }
}
