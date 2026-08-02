package com.easyai.interaction;

// ============================================================
// BuildingManager - 建造管理器
// ============================================================
// 职责：
//   1. 执行建筑蓝图（从 JSON 加载的方块放置序列）
//   2. 支持预设模式：墙壁、地板、圆圈、螺旋塔
//   3. 自动切换到合适的方块类型
//   4. 分层建造（从下往上逐层放置）
//   5. 建造进度上报
// ============================================================

import com.easyai.EasyAIMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class BuildingManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("EasyAI/Build");

    // 建造任务队列
    private final List<BuildTask> buildQueue = new ArrayList<>();
    private int currentTaskIndex = 0;

    // 建造状态
    private boolean isBuilding = false;
    private String currentPattern = "none";
    private int buildProgress = 0;
    private int buildTotal = 0;

    // ============================================================
    // 预设模式
    // ============================================================
    public static final String PATTERN_WALL = "wall";
    public static final String PATTERN_FLOOR = "floor";
    public static final String PATTERN_TOWER = "tower";
    public static final String PATTERN_HOUSE = "house";
    public static final String PATTERN_CIRCLE = "circle";

    // ============================================================
    // 内部任务类
    // ============================================================
    private static class BuildTask {
        final int x, y, z;
        final Item blockItem;

        BuildTask(int x, int y, int z, Item blockItem) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.blockItem = blockItem;
        }
    }

    // ============================================================
    // 执行预设模式
    // ============================================================
    public void executePattern(String pattern, int startX, int startY, int startZ) {
        LOGGER.info("[EasyAI/Build] 执行建筑模式: {} 起点 ({}, {}, {})",
                pattern, startX, startY, startZ);

        buildQueue.clear();
        currentPattern = pattern;

        switch (pattern.toLowerCase()) {
            case PATTERN_WALL -> generateWall(startX, startY, startZ);
            case PATTERN_FLOOR -> generateFloor(startX, startY, startZ);
            case PATTERN_TOWER -> generateTower(startX, startY, startZ);
            case PATTERN_HOUSE -> generateHouse(startX, startY, startZ);
            case PATTERN_CIRCLE -> generateCircle(startX, startY, startZ);
            default -> {
                LOGGER.warn("[EasyAI/Build] 未知建筑模式: {}", pattern);
                return;
            }
        }

        buildTotal = buildQueue.size();
        buildProgress = 0;
        isBuilding = true;
        currentTaskIndex = 0;

        LOGGER.info("[EasyAI/Build] 建造计划已生成，共 {} 个方块", buildTotal);
    }

    // ============================================================
    // 生成墙壁（5x4）
    // ============================================================
    private void generateWall(int x, int y, int z) {
        for (int dx = 0; dx < 5; dx++) {
            for (int dy = 0; dy < 4; dy++) {
                buildQueue.add(new BuildTask(x + dx, y + dy, z, Items.STONE));
            }
        }
    }

    // ============================================================
    // 生成地板（5x5）
    // ============================================================
    private void generateFloor(int x, int y, int z) {
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                buildQueue.add(new BuildTask(x + dx, y, z + dz, Items.OAK_PLANKS));
            }
        }
    }

    // ============================================================
    // 生成塔（3x3x8）
    // ============================================================
    private void generateTower(int x, int y, int z) {
        for (int dy = 0; dy < 8; dy++) {
            for (int dx = 0; dx < 3; dx++) {
                for (int dz = 0; dz < 3; dz++) {
                    // 空心塔（中间留空）
                    if (dx == 1 && dz == 1) continue;
                    buildQueue.add(new BuildTask(x + dx, y + dy, z + dz, Items.STONE_BRICKS));
                }
            }
        }
    }

    // ============================================================
    // 生成简易房屋（7x5x7）
    // ============================================================
    private void generateHouse(int x, int y, int z) {
        // 地板
        for (int dx = 0; dx < 7; dx++) {
            for (int dz = 0; dz < 7; dz++) {
                buildQueue.add(new BuildTask(x + dx, y, z + dz, Items.OAK_PLANKS));
            }
        }
        // 墙壁（4面）
        for (int dy = 1; dy <= 4; dy++) {
            for (int dx = 0; dx < 7; dx++) {
                // 前墙（留门）
                if (dy <= 2 && dx == 3) continue;
                buildQueue.add(new BuildTask(x + dx, y + dy, z, Items.OAK_PLANKS));
                // 后墙
                buildQueue.add(new BuildTask(x + dx, y + dy, z + 6, Items.OAK_PLANKS));
            }
            for (int dz = 1; dz < 6; dz++) {
                // 左墙（留窗）
                if (dy == 2 && dz == 3) continue;
                buildQueue.add(new BuildTask(x, y + dy, z + dz, Items.OAK_PLANKS));
                // 右墙（留窗）
                if (dy == 2 && dz == 3) continue;
                buildQueue.add(new BuildTask(x + 6, y + dy, z + dz, Items.OAK_PLANKS));
            }
        }
        // 屋顶
        for (int dx = 0; dx < 7; dx++) {
            for (int dz = 0; dz < 7; dz++) {
                buildQueue.add(new BuildTask(x + dx, y + 5, z + dz, Items.OAK_SLAB));
            }
        }
        LOGGER.info("[EasyAI/Build] 房屋蓝图: 7x5x7, 含门窗");
    }

    // ============================================================
    // 生成圆圈（半径5）
    // ============================================================
    private void generateCircle(int x, int y, int z) {
        int radius = 5;
        for (int angle = 0; angle < 360; angle += 10) {
            double rad = Math.toRadians(angle);
            int dx = (int) Math.round(Math.cos(rad) * radius);
            int dz = (int) Math.round(Math.sin(rad) * radius);
            buildQueue.add(new BuildTask(x + dx, y, z + dz, Items.STONE));
        }
    }

    // ============================================================
    // 每 Tick 执行建造（由 ActionQueue 调度）
    // ============================================================
    public void tick(ClientPlayerEntity player, MinecraftClient client) {
        if (!isBuilding || currentTaskIndex >= buildQueue.size()) {
            if (isBuilding) {
                LOGGER.info("[EasyAI/Build] 建造完成！共放置 {} 个方块", buildProgress);
                isBuilding = false;
                currentPattern = "none";
            }
            return;
        }

        BuildTask task = buildQueue.get(currentTaskIndex);
        BlockPos pos = new BlockPos(task.x, task.y, task.z);

        // 检查目标位置是否为空气
        if (!client.world.getBlockState(pos).isAir()) {
            // 已有方块，跳过
            currentTaskIndex++;
            return;
        }

        // 切换到对应方块
        if (!switchToBlock(player, task.blockItem)) {
            LOGGER.warn("[EasyAI/Build] 背包缺少方块: {}, 建造暂停",
                    task.blockItem.getName().getString());
            isBuilding = false;
            return;
        }

        // 放置方块
        var keys = EasyAIMod.getInstance().getKeySimulator();
        keys.placeBlock(task.x, task.y, task.z);

        buildProgress++;
        currentTaskIndex++;

        // 每 10 个方块打印一次进度
        if (buildProgress % 10 == 0) {
            LOGGER.info("[EasyAI/Build] 进度: {}/{} ({}%)",
                    buildProgress, buildTotal,
                    Math.round(buildProgress * 100.0 / buildTotal));
        }
    }

    // ============================================================
    // 切换到指定方块
    // ============================================================
    private boolean switchToBlock(ClientPlayerEntity player, Item blockItem) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).getItem() == blockItem) {
                player.getInventory().selectedSlot = i;
                return true;
            }
        }
        // 尝试任意可放置方块
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.getItem() == Items.STONE ||
                stack.getItem() == Items.DIRT ||
                stack.getItem() == Items.COBBLESTONE ||
                stack.getItem() == Items.OAK_PLANKS) {
                player.getInventory().selectedSlot = i;
                return true;
            }
        }
        return false;
    }

    // ============================================================
    // 取消建造
    // ============================================================
    public void cancel() {
        isBuilding = false;
        buildQueue.clear();
        currentTaskIndex = 0;
        LOGGER.info("[EasyAI/Build] 建造已取消");
    }

    // ============================================================
    // 状态查询
    // ============================================================
    public boolean isBuilding() { return isBuilding; }
    public String getCurrentPattern() { return currentPattern; }
    public int getBuildProgress() { return buildProgress; }
    public int getBuildTotal() { return buildTotal; }
    public double getProgressPercent() {
        return buildTotal > 0 ? Math.round(buildProgress * 100.0 / buildTotal) : 0;
    }
}
