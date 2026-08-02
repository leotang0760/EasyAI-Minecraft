package com.easyai.control;

// ============================================================
// KeySimulator - 键盘/鼠标模拟器（防反作弊）
// ============================================================
// 职责：
//   1. 模拟按键按下/释放（前进、后退、左、右、跳跃、潜行、疾跑）
//   2. 模拟鼠标左键（攻击/破坏）和右键（使用/放置）
//   3. 提供持续按住模式（用于进食、疾跑）
//   4. 释放所有按键（用于紧急停止）
//
// 防反作弊原理：
//   使用 MC 原版的 Input 类和 KeyBinding 来模拟输入，
//   而非直接发送网络包。这样服务端看到的是"正常的人类输入"，
//   因为按键状态通过 MC 原版的 PlayerMoveC2SPacket 自然发送。
//
//   反作弊插件通常检测的是：
//   - 瞬间到达坐标（传送检测）→ 我们用寻路逐步移动
//   - 非人类点击频率 → 我们加了攻击冷却（1.6秒）
//   - 无视角变化的攻击 → 我们先 faceEntity 再攻击
// ============================================================

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KeySimulator {

    private static final Logger LOGGER = LoggerFactory.getLogger("EasyAI/Keys");

    // 按键持续状态
    private boolean forward = false;
    private boolean backward = false;
    private boolean left = false;
    private boolean right = false;
    private boolean jump = false;
    private boolean sneak = false;
    private boolean sprint = false;
    private boolean rightClickHeld = false;

    // 攻击冷却（1.6 秒 = 32 tick）
    private long lastAttackTime = 0;
    private static final long ATTACK_COOLDOWN_MS = 1600;

    // ============================================================
    // 设置按键状态（持续模式）
    // ============================================================
    public void setForward(boolean v) { forward = v; applyKeys(); }
    public void setBackward(boolean v) { backward = v; applyKeys(); }
    public void setLeft(boolean v) { left = v; applyKeys(); }
    public void setRight(boolean v) { right = v; applyKeys(); }
    public void setJump(boolean v) { jump = v; applyKeys(); }
    public void setSneak(boolean v) { sneak = v; applyKeys(); }
    public void setSprint(boolean v) { sprint = v; applyKeys(); }
    public void setRightClickHeld(boolean v) { rightClickHeld = v; }

    // ============================================================
    // 将按键状态同步到 MC 的 KeyBinding
    // ============================================================
    // KeyBinding.setKeyState() 直接修改按键的 pressed 状态，
    // MC 的 PlayerEntity.tickMovement() 会读取这些状态来决定移动。
    // 这是"最低层级"的按键模拟，与真实物理按键完全等效。
    // ============================================================
    private void applyKeys() {
        MinecraftClient mc = MinecraftClient.getInstance();
        GameOptions opts = mc.options;

        // 直接设置 KeyBinding 的按下状态
        // 1.21 中 KeyBinding.setKeyState(KeyBinding, boolean) 已被移除，
        // 改用实例方法 setPressed(boolean)
        opts.forwardKey.setPressed(forward);
        opts.backKey.setPressed(backward);
        opts.leftKey.setPressed(left);
        opts.rightKey.setPressed(right);
        opts.jumpKey.setPressed(jump);
        opts.sneakKey.setPressed(sneak);
        opts.sprintKey.setPressed(sprint);

        // 疾跑需要额外处理：MC 原版要求先按前进键再双击疾跑
        // 这里直接通过 player.setSprinting() 设置
        if (mc.player != null && sprint && forward) {
            mc.player.setSprinting(true);
        }
    }

    // ============================================================
    // 模拟左键点击（攻击/破坏）
    // ============================================================
    public void leftClick() {
        // 攻击冷却检查
        long now = System.currentTimeMillis();
        if (now - lastAttackTime < ATTACK_COOLDOWN_MS) {
            return; // 冷却中，忽略
        }
        lastAttackTime = now;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.interactionManager == null) return;

        // 如果准星对准实体，执行实体攻击
        if (mc.crosshairTarget != null) {
            switch (mc.crosshairTarget.getType()) {
                case ENTITY -> {
                    // 攻击实体（左键）
                    mc.interactionManager.attackEntity(
                            mc.player,
                            ((net.minecraft.util.hit.EntityHitResult) mc.crosshairTarget).getEntity());
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
                case BLOCK -> {
                    // 破坏方块（左键长按）
                    mc.interactionManager.updateBlockBreakingProgress(
                            ((BlockHitResult) mc.crosshairTarget).getBlockPos(),
                            ((BlockHitResult) mc.crosshairTarget).getSide());
                }
            }
        }

        LOGGER.debug("[EasyAI/Keys] 左键点击（攻击/破坏）");
    }

    // ============================================================
    // 模拟右键点击（使用/放置）
    // ============================================================
    public void rightClick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.interactionManager == null) return;

        // 使用物品（右键）
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        LOGGER.debug("[EasyAI/Keys] 右键点击（使用物品）");
    }

    // ============================================================
    // 破坏指定坐标的方块
    // ============================================================
    public void breakBlock(int x, int y, int z) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.interactionManager == null || mc.world == null) return;

        BlockPos pos = new BlockPos(x, y, z);

        // 验证方块存在
        if (mc.world.getBlockState(pos).isAir()) {
            LOGGER.warn("[EasyAI/Keys] 目标方块为空气: ({}, {}, {})", x, y, z);
            return;
        }

        // 模拟破坏方块（开始挖掘）
        mc.interactionManager.attackBlock(pos, Direction.UP);
        mc.player.swingHand(Hand.MAIN_HAND);

        LOGGER.info("[EasyAI/Keys] 破坏方块: ({}, {}, {})", x, y, z);
    }

    // ============================================================
    // 放置方块到指定坐标
    // ============================================================
    public void placeBlock(int x, int y, int z) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.interactionManager == null) return;

        BlockPos pos = new BlockPos(x, y, z);
        // 计算放置位置（在目标方块的面上）
        Vec3d hitVec = new Vec3d(x + 0.5, y + 0.5, z + 0.5);

        // 模拟右键放置方块
        BlockHitResult hitResult = new BlockHitResult(
                hitVec, Direction.UP, pos.down(), false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);

        LOGGER.info("[EasyAI/Keys] 放置方块: ({}, {}, {})", x, y, z);
    }

    // ============================================================
    // 释放所有按键（紧急停止）
    // ============================================================
    public void releaseAll() {
        forward = backward = left = right = false;
        jump = sneak = sprint = false;
        rightClickHeld = false;
        applyKeys();

        LOGGER.info("[EasyAI/Keys] 释放所有按键");
    }

    // ============================================================
    // 检查右键是否处于持续按住状态
    // ============================================================
    public boolean isRightClickHeld() {
        return rightClickHeld;
    }

    // 持续右键的 Tick 处理（在主循环中调用）
    public void tickRightClick() {
        if (rightClickHeld) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) {
                mc.options.useKey.setPressed(true);
            }
        } else {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.options != null) {
                mc.options.useKey.setPressed(false);
            }
        }
    }
}
