package com.easyai.survival;

// ============================================================
// SurvivalInstinct - 生存直觉系统
// ============================================================
// 职责：
//   1. 雷暴天气检测 → 自动寻找遮挡物
//   2. 夜间检测 → 日落前自动向安全高度移动或寻找室内
//   3. 自动睡觉（跳过夜晚）
//   4. 紧急情况处理（被火烧、掉入岩浆等）
//   5. 优先级高于一切常规任务
// ============================================================

import com.easyai.EasyAIMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SurvivalInstinct {

    private static final Logger LOGGER = LoggerFactory.getLogger("EasyAI/Survival");

    // 夜间安全检测阈值
    private static final long NIGHT_THRESHOLD = 13000; // MC 时间 > 13000 为夜晚
    private static final long DANGER_THRESHOLD = 12000; // 日落前 1000 tick 预警

    // 状态标记
    private boolean isSheltering = false;
    private boolean isSleeping = false;
    private int shelterSearchCooldown = 0;

    // ============================================================
    // 每 Tick 检测
    // ============================================================
    public void tick(ClientPlayerEntity player, MinecraftClient client, int tickCounter) {
        // 每 100 tick（5秒）检测一次环境
        if (tickCounter % 100 != 0) return;

        ClientWorld world = (ClientWorld) player.getWorld();
        if (world == null) return;

        // ---- 1. 检测燃烧/岩浆等紧急状态 ----
        if (player.isOnFire() || player.isInLava()) {
            LOGGER.warn("[EasyAI/Survival] 检测到危险状态！着火={}, 岩浆={}",
                    player.isOnFire(), player.isInLava());
            // 放置水桶自救（如果有）
            tryPlaceWater(player, client);
        }

        // ---- 2. 雷暴天气检测 ----
        boolean isThundering = world.isThundering();
        if (isThundering && !isSheltering) {
            LOGGER.warn("[EasyAI/Survival] 雷暴天气！寻找遮挡物...");
            findShelter(player, world);
            isSheltering = true;
        } else if (!isThundering && isSheltering) {
            isSheltering = false;
            LOGGER.info("[EasyAI/Survival] 天气恢复，解除遮挡");
        }

        // ---- 3. 夜间检测 ----
        long time = world.getTimeOfDay() % 24000;
        boolean isNight = time >= NIGHT_THRESHOLD || time < 0;
        if (isNight && !isSleeping && !isSheltering) {
            // 尝试自动睡觉
            if (shelterSearchCooldown <= 0) {
                LOGGER.info("[EasyAI/Survival] 夜晚降临 (时间={})，尝试睡觉...", time);
                trySleep(client);
                shelterSearchCooldown = 200; // 10 秒冷却
            }
        } else if (!isNight && isSleeping) {
            isSleeping = false;
        }

        // ---- 4. 检测中毒/凋零效果 ----
        if (player.hasStatusEffect(StatusEffects.POISON) ||
            player.hasStatusEffect(StatusEffects.WITHER)) {
            LOGGER.warn("[EasyAI/Survival] 检测到负面效果！中毒={}, 凋零={}",
                    player.hasStatusEffect(StatusEffects.POISON),
                    player.hasStatusEffect(StatusEffects.WITHER));
            // 喝牛奶解毒（如果有）
            tryConsumeMilk(player);
        }

        shelterSearchCooldown--;
    }

    // ============================================================
    // 寻找遮挡物
    // ============================================================
    private void findShelter(ClientPlayerEntity player, ClientWorld world) {
        BlockPos playerPos = player.getBlockPos();

        // 向上搜索非透明方块（遮挡物）
        for (int dy = 0; dy < 10; dy++) {
            BlockPos checkPos = playerPos.up(dy);
            Block block = world.getBlockState(checkPos).getBlock();
            if (block != Blocks.AIR && block != Blocks.GLASS && block != Blocks.TINTED_GLASS) {
                LOGGER.info("[EasyAI/Survival] 找到遮挡物: {} 在 Y={}",
                        block.getName().getString(), checkPos.getY());
                // 向遮挡物方向移动
                return;
            }
        }

        // 如果头顶没有遮挡，寻找附近洞穴或建造简易掩体
        LOGGER.info("[EasyAI/Survival] 头顶无遮挡，向安全高度移动");
        EasyAIMod.getInstance().getNavigation().goTo(
                player.getX(), 70, player.getZ()); // Y=70 安全高度
    }

    // ============================================================
    // 尝试睡觉
    // ============================================================
    public void trySleep(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        ClientWorld world = (ClientWorld) player.getWorld();
        BlockPos playerPos = player.getBlockPos();

        // 搜索附近 5 格内的床
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    BlockPos pos = playerPos.add(dx, dy, dz);
                    Block block = world.getBlockState(pos).getBlock();
                    if (block.getName().getString().contains("Bed") ||
                        block.getName().getString().contains("bed")) {
                        LOGGER.info("[EasyAI/Survival] 找到床在 ({}, {}, {})，尝试睡觉",
                                pos.getX(), pos.getY(), pos.getZ());
                        // 右键使用床
                        client.interactionManager.interactBlock(
                                player, net.minecraft.util.Hand.MAIN_HAND,
                                new net.minecraft.util.hit.BlockHitResult(
                                        net.minecraft.util.math.Vec3d.ofCenter(pos),
                                        Direction.UP, pos, false));
                        isSleeping = true;
                        return;
                    }
                }
            }
        }

        LOGGER.info("[EasyAI/Survival] 附近无床，保持警戒");
    }

    // ============================================================
    // 放置水桶自救
    // ============================================================
    private void tryPlaceWater(ClientPlayerEntity player, MinecraftClient client) {
        // 在热键栏查找水桶
        for (int i = 0; i < 9; i++) {
            var stack = player.getInventory().getStack(i);
            if (stack.getItem() == net.minecraft.item.Items.WATER_BUCKET) {
                player.getInventory().selectedSlot = i;
                client.interactionManager.interactItem(player, net.minecraft.util.Hand.MAIN_HAND);
                LOGGER.info("[EasyAI/Survival] 已放置水桶灭火");
                return;
            }
        }
        LOGGER.warn("[EasyAI/Survival] 背包无水桶，无法灭火");
    }

    // ============================================================
    // 喝牛奶解毒
    // ============================================================
    private void tryConsumeMilk(ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) {
            var stack = player.getInventory().getStack(i);
            if (stack.getItem() == net.minecraft.item.Items.MILK_BUCKET) {
                player.getInventory().selectedSlot = i;
                EasyAIMod.getInstance().getKeySimulator().setRightClickHeld(true);
                LOGGER.info("[EasyAI/Survival] 正在喝牛奶解毒");
                // 500ms 后释放右键
                new Thread(() -> {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    EasyAIMod.getInstance().getKeySimulator().setRightClickHeld(false);
                }, "EasyAI-Milk").start();
                return;
            }
        }
        LOGGER.warn("[EasyAI/Survival] 背包无牛奶，无法解毒");
    }

    // ============================================================
    // 紧急情况处理
    // ============================================================
    public void onEmergency(ClientPlayerEntity player, MinecraftClient client) {
        LOGGER.warn("[EasyAI/Survival] 紧急模式激活！");
        // 如果着火，放水
        if (player.isOnFire()) {
            tryPlaceWater(player, client);
        }
    }
}
