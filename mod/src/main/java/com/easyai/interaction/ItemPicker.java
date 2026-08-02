package com.easyai.interaction;

// ============================================================
// ItemPicker - 自动物品拾取器
// ============================================================
// 职责：
//   1. 检测 3 格内的掉落物（ItemEntity / ExperienceOrbEntity）
//   2. 自动走向掉落物拾取
//   3. 优先拾取经验球（因为会消失）
//   4. 避免拾取危险区域的物品（岩浆旁、悬崖边）
// ============================================================

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;

public class ItemPicker {

    private static final Logger LOGGER = LoggerFactory.getLogger("EasyAI/Pickup");

    private static final double PICKUP_RANGE = 3.0;
    private static final double DETECTION_RANGE = 6.0;

    // ============================================================
    // 每 10 tick 检测一次
    // ============================================================
    public void tick(ClientPlayerEntity player, MinecraftClient client) {
        Vec3d pos = player.getPos();
        Box searchBox = Box.of(pos, DETECTION_RANGE * 2, DETECTION_RANGE, DETECTION_RANGE * 2);

        // 检测附近的掉落物
        List<ItemEntity> items = player.getWorld().getEntitiesByClass(
                ItemEntity.class, searchBox,
                e -> e.distanceTo(player) < DETECTION_RANGE && e.isAlive());

        // 检测附近的经验球
        List<ExperienceOrbEntity> orbs = player.getWorld().getEntitiesByClass(
                ExperienceOrbEntity.class, searchBox,
                e -> e.distanceTo(player) < DETECTION_RANGE && e.isAlive());

        // 合并所有可拾取实体
        int totalTargets = items.size() + orbs.size();
        if (totalTargets == 0) return;

        LOGGER.debug("[EasyAI/Pickup] 检测到 {} 个掉落物 (物品: {}, 经验球: {})",
                totalTargets, items.size(), orbs.size());

        // 优先拾取经验球（因为会消失更快）
        if (!orbs.isEmpty()) {
            orbs.sort(Comparator.comparingDouble(e -> e.distanceTo(player)));
            ExperienceOrbEntity nearestOrb = orbs.get(0);
            if (nearestOrb.distanceTo(player) <= PICKUP_RANGE) {
                LOGGER.info("[EasyAI/Pickup] 拾取经验球 (值: {})", nearestOrb.getExperienceAmount());
            } else {
                // 走向经验球
                moveToEntity(player, nearestOrb);
            }
            return;
        }

        // 拾取最近的物品
        if (!items.isEmpty()) {
            items.sort(Comparator.comparingDouble(e -> e.distanceTo(player)));
            ItemEntity nearestItem = items.get(0);

            if (nearestItem.distanceTo(player) <= PICKUP_RANGE) {
                LOGGER.info("[EasyAI/Pickup] 拾取: {} x{}",
                        nearestItem.getStack().getName().getString(),
                        nearestItem.getStack().getCount());
            } else {
                // 检查物品是否在危险位置
                if (isDangerousPosition(nearestItem, player)) {
                    LOGGER.warn("[EasyAI/Pickup] 跳过危险位置的物品: ({}, {}, {})",
                            nearestItem.getBlockX(), nearestItem.getBlockY(), nearestItem.getBlockZ());
                    return;
                }
                // 走向物品
                moveToEntity(player, nearestItem);
            }
        }
    }

    // ============================================================
    // 走向实体
    // ============================================================
    private void moveToEntity(ClientPlayerEntity player, net.minecraft.entity.Entity target) {
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();

        // 设置朝向目标
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        player.setYaw(yaw);

        // 向前移动（通过 KeySimulator）
        var keys = com.easyai.EasyAIMod.getInstance().getKeySimulator();
        keys.setForward(true);
        keys.setSprint(false); // 拾取时不需要疾跑
    }

    // ============================================================
    // 检查位置是否危险（岩浆旁、悬崖边）
    // ============================================================
    private boolean isDangerousPosition(ItemEntity item, ClientPlayerEntity player) {
        int x = item.getBlockX();
        int y = item.getBlockY();
        int z = item.getBlockZ();

        // 检查下方是否有方块（防止跳下悬崖）
        var world = player.getWorld();
        for (int dy = 0; dy < 5; dy++) {
            var state = world.getBlockState(new net.minecraft.util.math.BlockPos(x, y - dy, z));
            if (!state.isAir() && !state.getFluidState().isEmpty()) {
                // 下方有液体（水/岩浆）
                return true;
            }
            if (!state.isAir()) {
                break; // 找到固体方块，安全
            }
        }

        // 检查周围是否有岩浆
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                var state = world.getBlockState(new net.minecraft.util.math.BlockPos(x + dx, y, z + dz));
                if (state.getBlock() == net.minecraft.block.Blocks.LAVA) {
                    return true;
                }
            }
        }

        return false;
    }
}
