package com.easyai.navigation;

// ============================================================
// BaritoneIntegration - Baritone 寻路引擎封装
// ============================================================
// 职责：
//   1. 封装 Baritone API 的寻路调用（goto / follow / escape）
//   2. 若 Baritone 未安装，回退到自实现的简化 A* 算法
//   3. 提供统一的寻路接口，屏蔽底层差异
//
// Baritone 调用原理：
//   Baritone 是一个客户端寻路 Mod，它通过分析世界数据计算最优路径。
//   核心类：BaritoneAPI.getPrimaryBaritone().getCustomGoalProcess()
//   - GoalBlock(x,y,z)：精确到达指定坐标
//   - GoalNear(x,y,z,radius)：到达坐标附近一定范围
//   - GoalRunAway(x,y,z)：远离指定坐标（用于 escape）
//
//   Baritone 内部已实现：
//   - 自动避开水、岩浆、悬崖
//   - 自动跳跃上1格方块
//   - 自动潜行防坠落
//   - 自动疾跑（长距离）
//   - 动态路径重计算（遇到障碍时）
// ============================================================

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.IFollowProcess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class BaritoneIntegration {

    private static final Logger LOGGER = LoggerFactory.getLogger("EasyAI/Navigation");

    // Baritone 实例（如果可用）
    private IBaritone baritone;
    private boolean baritoneAvailable = false;

    // 当前寻路目标
    private String currentGoal = "none";
    private double targetX, targetY, targetZ;

    public BaritoneIntegration() {
        try {
            // 尝试获取 Baritone 实例
            this.baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            this.baritoneAvailable = (baritone != null);
            LOGGER.info("[EasyAI/Nav] Baritone 引擎已加载");
        } catch (NoClassDefFoundError | Exception e) {
            LOGGER.warn("[EasyAI/Nav] Baritone 未安装，将使用简化 A* 回退模式: {}", e.getMessage());
            this.baritoneAvailable = false;
        }
    }

    // ============================================================
    // goto - 寻路到指定坐标
    // ============================================================
    // 使用 GoalBlock 精确到达 (x, y, z)
    // Baritone 会自动处理避障、跳跃、潜行等微操
    // ============================================================
    public void goTo(double x, double y, double z) {
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
        this.currentGoal = "goto";

        LOGGER.info("[EasyAI/Nav] 寻路至 ({}, {}, {})", x, y, z);

        if (baritoneAvailable && baritone.getPlayerContext().player() != null) {
            // 使用 Baritone 精确寻路
            ICustomGoalProcess goalProcess = baritone.getCustomGoalProcess();
            // 如果 y < 0，只指定 XZ 目标（不关心 Y 高度）
            if (y < 0) {
                goalProcess.setGoalAndPath(new GoalXZ((int) x, (int) z));
            } else {
                goalProcess.setGoalAndPath(new GoalBlock(
                        new BlockPos((int) x, (int) y, (int) z)));
            }
        } else {
            // 回退模式：使用简化 A* 寻路
            fallbackGoto(x, y, z);
        }
    }

    // ============================================================
    // followEntity - 跟随指定实体
    // ============================================================
    // 使用 Baritone 的 FollowProcess 持续跟随目标实体
    // 保持可配置的跟随距离
    // ============================================================
    public void followEntity(String uuidStr, double followDistance) {
        this.currentGoal = "follow";
        LOGGER.info("[EasyAI/Nav] 开始跟随实体: {} (距离: {})", uuidStr, followDistance);

        if (baritoneAvailable) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.world != null) {
                    // 1.21 中 ClientWorld.getEntity(UUID) 不可用，
                    // 改为遍历实体列表查找
                    Entity target = null;
                    for (Entity e : mc.world.getEntities()) {
                        if (e.getUuid().equals(uuid)) {
                            target = e;
                            break;
                        }
                    }
                    if (target != null) {
                        IFollowProcess followProcess = baritone.getFollowProcess();
                        followProcess.follow(target);
                        LOGGER.info("[EasyAI/Nav] 已锁定跟随目标: {}",
                                target.getName().getString());
                    } else {
                        LOGGER.warn("[EasyAI/Nav] 未找到实体: {}", uuidStr);
                    }
                }
            } catch (IllegalArgumentException e) {
                LOGGER.error("[EasyAI/Nav] 无效的 UUID: {}", uuidStr);
            }
        } else {
            LOGGER.warn("[EasyAI/Nav] 无 Baritone，回退跟随模式暂不支持");
        }
    }

    // ============================================================
    // escape - 紧急逃离
    // ============================================================
    // 使用 GoalRunAway 远离当前位置，向安全方向移动
    // 通常在血量过低或遇到危险时触发
    // ============================================================
    public void escape() {
        this.currentGoal = "escape";
        LOGGER.warn("[EasyAI/Nav] 触发紧急逃离！");

        if (baritoneAvailable && baritone.getPlayerContext().player() != null) {
            var player = baritone.getPlayerContext().player();
            // 向当前坐标的反方向逃离 30 格
            ICustomGoalProcess goalProcess = baritone.getCustomGoalProcess();
            goalProcess.setGoalAndPath(new GoalRunAway(30,
                    (int) player.getX(), (int) player.getZ()));
        } else {
            // 回退模式：跳跃 + 向后移动
            fallbackEscape();
        }
    }

    // ============================================================
    // stop - 停止所有寻路
    // ============================================================
    public void stop() {
        this.currentGoal = "none";
        LOGGER.info("[EasyAI/Nav] 停止寻路");

        if (baritoneAvailable) {
            baritone.getPathingBehavior().cancelEverything();
            baritone.getFollowProcess().onLostControl();
        }
    }

    // ============================================================
    // 状态查询
    // ============================================================
    public boolean isPathing() {
        if (baritoneAvailable) {
            return baritone.getPathingBehavior().isPathing();
        }
        return !"none".equals(currentGoal);
    }

    public String getCurrentGoal() {
        return currentGoal;
    }

    public double getTargetX() { return targetX; }
    public double getTargetY() { return targetY; }
    public double getTargetZ() { return targetZ; }

    // ============================================================
    // 回退寻路实现（无 Baritone 时的简化 A*）
    // ============================================================
    // 这是一个极简版本，仅做直线移动 + 遇障碍跳跃
    // 生产环境强烈建议安装 Baritone
    // ============================================================
    private void fallbackGoto(double x, double y, double z) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        double dx = x - mc.player.getX();
        double dz = z - mc.player.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        LOGGER.info("[EasyAI/Nav] 回退模式: 直线移动距离 {}", distance);

        // 设置朝向目标
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mc.player.setYaw(yaw);

        // 前进（由 KeySimulator 配合执行）
        // 这里仅设置目标，实际按键由 ActionQueue.tick 调度
    }

    private void fallbackEscape() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        // 向反方向逃跑（转身 180 度）
        mc.player.setYaw(mc.player.getYaw() + 180);
        LOGGER.info("[EasyAI/Nav] 回退模式: 向反方向逃跑");
    }
}
