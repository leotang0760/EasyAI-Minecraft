package com.easyai.combat;

// ============================================================
// CombatAI - 高级战斗 AI
// ============================================================
// 职责：
//   1. 目标优先级排序（按距离+威胁度）
//   2. 近战连击（面向目标 + 左键 + 1.6s 冷却）
//   3. 远程防御（骷髅举弓时 Z 字形走位）
//   4. 武器自动切换（剑 > 斧）
//   5. 盾牌格挡（检测到远程攻击时举盾）
//   6. 攻击闪避（检测到箭矢时侧移）
// ============================================================

import com.easyai.EasyAIMod;
import com.easyai.control.KeySimulator;
import com.easyai.navigation.BaritoneIntegration;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;

public class CombatAI {

    private static final Logger LOGGER = LoggerFactory.getLogger("EasyAI/Combat");

    // 战斗参数
    private static final double DETECTION_RANGE = 12.0;
    private static final double MELEE_RANGE = 3.5;
    private static final long ATTACK_COOLDOWN_MS = 1600;
    private static final double ARROW_DODGE_RANGE = 15.0;

    private long lastAttackTime = 0;
    private int strafeDirection = 1; // Z 字形走位方向
    private int strafeCounter = 0;
    private boolean shieldRaised = false;

    // ============================================================
    // 每 Tick 战斗检测（由 EasyAIMod 每 10 tick 调用）
    // ============================================================
    public void tick(ClientPlayerEntity player, MinecraftClient client,
                     KeySimulator keys, BaritoneIntegration nav) {

        Vec3d pos = player.getPos();
        Box searchBox = Box.of(pos, DETECTION_RANGE * 2, DETECTION_RANGE, DETECTION_RANGE * 2);

        // 获取附近所有敌对实体
        List<HostileEntity> hostiles = player.getWorld().getEntitiesByClass(
                HostileEntity.class, searchBox,
                e -> e.distanceTo(player) < DETECTION_RANGE && e.isAlive());

        if (hostiles.isEmpty()) {
            // 无敌人，释放战斗按键
            keys.setLeft(false);
            keys.setRight(false);
            shieldRaised = false;
            return;
        }

        // 按威胁度+距离排序
        hostiles.sort(Comparator.comparingDouble(e -> {
            double dist = e.distanceTo(player);
            double threat = getThreatLevel(e);
            return dist - threat * 2; // 威胁高的优先
        }));

        HostileEntity target = hostiles.get(0);
        double dist = target.distanceTo(player);

        LOGGER.debug("[EasyAI/Combat] 目标: {} 距离: {} 威胁: {}",
                target.getName().getString(), dist, getThreatLevel(target));

        // ---- 检测来袭箭矢 ----
        if (checkIncomingArrows(player, client)) {
            // 侧移闪避
            dodgeArrows(player, keys);
            return;
        }

        // ---- 面向目标 ----
        faceEntity(player, target);

        // ---- 根据目标类型选择策略 ----
        if (target instanceof SkeletonEntity) {
            // 骷髅：Z 字形走位接近
            handleSkeleton(player, (SkeletonEntity) target, dist, keys);
        } else if (target instanceof CreeperEntity) {
            // 苦力怕：保持距离，远程攻击
            handleCreeper(player, (CreeperEntity) target, dist, keys);
        } else if (target instanceof ZombieEntity) {
            // 僵尸：近战连击
            handleMelee(player, target, dist, keys);
        } else {
            // 默认近战
            handleMelee(player, target, dist, keys);
        }
    }

    // ============================================================
    // 威胁等级评估
    // ============================================================
    private double getThreatLevel(Entity entity) {
        if (entity instanceof CreeperEntity) {
            // 苦力怕正在膨胀时威胁最高
            CreeperEntity creeper = (CreeperEntity) entity;
            return creeper.getClientFuseTime(0) > 0 ? 10.0 : 5.0;
        }
        if (entity instanceof SkeletonEntity) return 6.0; // 远程威胁
        if (entity instanceof ZombieEntity) return 3.0;   // 近战中等
        return 2.0; // 默认
    }

    // ============================================================
    // 近战处理
    // ============================================================
    private void handleMelee(ClientPlayerEntity player, HostileEntity target,
                             double dist, KeySimulator keys) {
        // 切换到剑
        switchToWeapon(player);

        if (dist <= MELEE_RANGE) {
            // 在攻击范围内：执行攻击
            long now = System.currentTimeMillis();
            if (now - lastAttackTime >= ATTACK_COOLDOWN_MS) {
                keys.leftClick();
                lastAttackTime = now;
                LOGGER.info("[EasyAI/Combat] 近战攻击: {} (距离: {})",
                        target.getName().getString(), dist);
            }
            // 停止移动
            keys.setForward(false);
        } else {
            // 不在范围内：向前推进
            keys.setForward(true);
            keys.setSprint(dist > 5.0); // 远距离疾跑
        }
    }

    // ============================================================
    // 骷髅处理（Z 字形走位）
    // ============================================================
    private void handleSkeleton(ClientPlayerEntity player, SkeletonEntity skeleton,
                                double dist, KeySimulator keys) {
        // 切换到盾牌+剑
        switchToWeapon(player);

        if (dist > MELEE_RANGE) {
            // Z 字形走位接近
            strafeCounter++;
            if (strafeCounter >= 10) { // 每 0.5 秒切换方向
                strafeDirection *= -1;
                strafeCounter = 0;
            }
            keys.setLeft(strafeDirection > 0);
            keys.setRight(strafeDirection < 0);
            keys.setForward(true); // 同时前进
            LOGGER.debug("[EasyAI/Combat] Z字形走位接近骷髅 (方向: {})",
                    strafeDirection > 0 ? "左" : "右");
        } else {
            // 进入近战范围：攻击
            keys.setLeft(false);
            keys.setRight(false);
            long now = System.currentTimeMillis();
            if (now - lastAttackTime >= ATTACK_COOLDOWN_MS) {
                keys.leftClick();
                lastAttackTime = now;
                LOGGER.info("[EasyAI/Combat] 近战攻击骷髅");
            }
        }
    }

    // ============================================================
    // 苦力怕处理（保持距离）
    // ============================================================
    private void handleCreeper(ClientPlayerEntity player, CreeperEntity creeper,
                               double dist, KeySimulator keys) {
        if (creeper.getClientFuseTime(0) > 0 || dist < 4.0) {
            // 苦力怕即将爆炸或太近：后退
            keys.setBackward(true);
            keys.setForward(false);
            LOGGER.warn("[EasyAI/Combat] 苦力怕即将爆炸！后退！");
        } else if (dist > 6.0) {
            // 远距离：用弓箭攻击
            switchToBow(player);
            keys.setForward(true);
        } else {
            // 中距离：用剑攻击后立即后退
            switchToWeapon(player);
            long now = System.currentTimeMillis();
            if (now - lastAttackTime >= ATTACK_COOLDOWN_MS) {
                keys.leftClick();
                lastAttackTime = now;
                LOGGER.info("[EasyAI/Combat] 攻击苦力怕");
            }
            keys.setBackward(true);
        }
    }

    // ============================================================
    // 检测来袭箭矢
    // ============================================================
    private boolean checkIncomingArrows(ClientPlayerEntity player, MinecraftClient client) {
        Vec3d pos = player.getPos();
        Box searchBox = Box.of(pos, ARROW_DODGE_RANGE * 2, ARROW_DODGE_RANGE, ARROW_DODGE_RANGE * 2);

        List<ArrowEntity> arrows = player.getWorld().getEntitiesByClass(
                ArrowEntity.class, searchBox,
                a -> a.distanceTo(player) < ARROW_DODGE_RANGE && a.getOwner() != player);

        for (ArrowEntity arrow : arrows) {
            Vec3d arrowVel = arrow.getVelocity();
            Vec3d toPlayer = pos.subtract(arrow.getPos()).normalize();
            double dot = arrowVel.normalize().dotProduct(toPlayer);
            if (dot > 0.8) {
                LOGGER.warn("[EasyAI/Combat] 检测到来袭箭矢！距离: {}", arrow.distanceTo(player));
                return true;
            }
        }
        return false;
    }

    // ============================================================
    // 箭矢闪避
    // ============================================================
    private void dodgeArrows(ClientPlayerEntity player, KeySimulator keys) {
        // 随机左右闪避
        strafeDirection *= -1;
        keys.setLeft(strafeDirection > 0);
        keys.setRight(strafeDirection < 0);
        keys.setForward(false);
    }

    // ============================================================
    // 面向目标
    // ============================================================
    private void faceEntity(ClientPlayerEntity player, Entity target) {
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double dy = (target.getY() + target.getStandingEyeHeight() / 2.0)
                   - (player.getY() + player.getStandingEyeHeight());

        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));

        player.setYaw(yaw);
        player.setPitch(pitch);
    }

    // ============================================================
    // 武器切换
    // ============================================================
    private void switchToWeapon(ClientPlayerEntity player) {
        // 优先级：下界合金剑 > 钻石剑 > 铁剑 > 石剑 > 木剑
        for (Item weapon : new Item[]{
                Items.NETHERITE_SWORD, Items.DIAMOND_SWORD,
                Items.IRON_SWORD, Items.STONE_SWORD, Items.WOODEN_SWORD
        }) {
            for (int i = 0; i < 9; i++) {
                if (player.getInventory().getStack(i).getItem() == weapon) {
                    player.getInventory().selectedSlot = i;
                    return;
                }
            }
        }
    }

    private void switchToBow(ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).getItem() == Items.BOW) {
                player.getInventory().selectedSlot = i;
                return;
            }
        }
        // 没有弓则切换到剑
        switchToWeapon(player);
    }
}
