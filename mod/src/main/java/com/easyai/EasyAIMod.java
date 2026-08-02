package com.easyai;

// ============================================================
// Project EasyAI - Fabric Mod 主入口 (MC 1.21.1)
// ============================================================
// 职责：
//   1. 注册客户端生命周期事件（初始化 / Tick 循环）
//   2. 初始化 WebSocket 客户端（连接 Python 大脑）
//   3. 启动底层微循环（每 Tick 检测饱食度/血量/周围实体）
//   4. 持有 ActionQueue 全局单例，供各子系统写入动作
//   5. 集成自动登录、生存直觉、高级战斗、物品拾取、建造系统
//
// Mixin 原理说明：
//   Fabric Mixin 在类加载期将我们的代码注入到 MC 原版类中，
//   不修改任何 .class 文件，仅通过字节码织入实现"钩子"。
//   因此完全兼容其他 Mod，且不触碰服务端。
// ============================================================

import com.easyai.control.ActionQueue;
import com.easyai.control.KeySimulator;
import com.easyai.navigation.BaritoneIntegration;
import com.easyai.network.EasyAIWebSocketClient;
import com.easyai.survival.AutoLoginManager;
import com.easyai.survival.SurvivalInstinct;
import com.easyai.combat.CombatAI;
import com.easyai.interaction.ItemPicker;
import com.easyai.interaction.BuildingManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class EasyAIMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("EasyAI");
    public static final String MOD_ID = "easyai";
    public static final String MC_VERSION = "1.21.1";

    // ===== 全局单例 =====
    private static EasyAIMod instance;

    // WebSocket 客户端——连接 Python 后端
    private EasyAIWebSocketClient wsClient;

    // 动作队列——支持优先级抢占
    private ActionQueue actionQueue;

    // 寻路引擎封装
    private BaritoneIntegration navigation;

    // 键盘/鼠标模拟器
    private KeySimulator keySim;

    // ===== 新功能模块 =====
    private AutoLoginManager autoLoginManager;
    private SurvivalInstinct survivalInstinct;
    private CombatAI combatAI;
    private ItemPicker itemPicker;
    private BuildingManager buildingManager;

    // Gson 实例（复用，避免频繁创建）
    private final Gson gson = new Gson();

    // ===== 状态追踪 =====
    private boolean brainConnected = false;
    private boolean autoEatTriggered = false;
    private int tickCounter = 0;
    private int stuckTicks = 0;
    private double lastX = 0, lastZ = 0;

    // 安全看门狗阈值
    private static final float ESCAPE_HEALTH = 8.0f;
    private static final int FOOD_THRESHOLD = 6;
    private static final int STUCK_TICKS_THRESHOLD = 200; // 10秒

    @Override
    public void onInitializeClient() {
        instance = this;
        LOGGER.info("[EasyAI] 正在初始化 Project EasyAI AI v2.0 (MC {})...", MC_VERSION);

        // ---- 初始化核心子系统 ----
        this.actionQueue = new ActionQueue();
        this.keySim = new KeySimulator();
        this.navigation = new BaritoneIntegration();

        // ---- 初始化新功能模块 ----
        this.autoLoginManager = new AutoLoginManager();
        this.survivalInstinct = new SurvivalInstinct();
        this.combatAI = new CombatAI();
        this.itemPicker = new ItemPicker();
        this.buildingManager = new BuildingManager();

        // ---- 初始化 WebSocket 连接 ----
        int wsPort = 8765;
        String wsUrl = "ws://127.0.0.1:" + wsPort;
        this.wsClient = new EasyAIWebSocketClient(wsUrl, this);
        this.wsClient.connect();

        // ---- 注册 Tick 事件 ----
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        LOGGER.info("[EasyAI] 初始化完成（含自动登录/生存直觉/战斗AI/拾取/建造），等待 Python 大脑连接...");
    }

    // ============================================================
    // 底层微循环 —— 每 Tick 执行
    // ============================================================
    private void onClientTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        tickCounter++;

        // ---- 0. 自动登录检测（最高优先级，检测服务器消息） ----
        autoLoginManager.tick(player, client);

        // ---- 1. 安全看门狗：血量过低强制逃离 ----
        if (player.getHealth() < ESCAPE_HEALTH) {
            LOGGER.warn("[EasyAI] 血量过低 ({})，触发紧急逃离！", player.getHealth());
            actionQueue.clearAll();
            navigation.escape();
            survivalInstinct.onEmergency(player, client);
            return;
        }

        // ---- 2. 大脑离线检测 ----
        if (!brainConnected) {
            if (tickCounter % 100 == 0) {
                player.sendMessage(Text.literal("[AI] 大脑离线，停止行动")
                        .formatted(net.minecraft.util.Formatting.RED), true);
            }
            keySim.releaseAll();
            return;
        }

        // ---- 3. 生存直觉（天气/夜间/危险检测） ----
        survivalInstinct.tick(player, client, tickCounter);

        // ---- 4. 卡死检测 ----
        double deltaX = Math.abs(player.getX() - lastX);
        double deltaZ = Math.abs(player.getZ() - lastZ);
        if (deltaX < 0.1 && deltaZ < 0.1) {
            stuckTicks++;
            if (stuckTicks >= STUCK_TICKS_THRESHOLD) {
                LOGGER.warn("[EasyAI] 检测到卡死，尝试脱困...");
                tryUnstuck(player);
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
        }
        lastX = player.getX();
        lastZ = player.getZ();

        // ---- 5. 自动进食 ----
        if (tickCounter % 20 == 0) {
            checkAutoEat(player);
        }
        if (autoEatTriggered) return;

        // ---- 6. 物品拾取（自动捡起3格内掉落物） ----
        if (tickCounter % 10 == 0) {
            itemPicker.tick(player, client);
        }

        // ---- 7. 高级战斗AI ----
        if (tickCounter % 10 == 0) {
            combatAI.tick(player, client, keySim, navigation);
        }

        // ---- 8. 执行 ActionQueue ----
        actionQueue.tick(client, navigation, keySim);

        // ---- 9. 上报状态给 Python ----
        if (tickCounter % 20 == 0) {
            reportState(player);
        }
    }

    // ============================================================
    // 自动进食逻辑
    // ============================================================
    private void checkAutoEat(ClientPlayerEntity player) {
        int foodLevel = player.getHungerManager().getFoodLevel();
        if (foodLevel >= FOOD_THRESHOLD && !autoEatTriggered) return;

        int foodSlot = findBestFood(player);
        if (foodSlot == -1) {
            LOGGER.warn("[EasyAI] 饱食度过低但背包无食物！");
            return;
        }

        if (!autoEatTriggered) {
            LOGGER.info("[EasyAI] 饱食度 {} < {}，开始自动进食（槽位 {}）", foodLevel, FOOD_THRESHOLD, foodSlot);
            autoEatTriggered = true;
        }

        player.getInventory().selectedSlot = foodSlot;
        keySim.setRightClickHeld(true);

        if (player.getHungerManager().getFoodLevel() >= 20) {
            keySim.setRightClickHeld(false);
            autoEatTriggered = false;
            LOGGER.info("[EasyAI] 进食完成，饱食度已恢复");
        }
    }

    private int findBestFood(ClientPlayerEntity player) {
        for (int priority = 0; priority < 3; priority++) {
            for (int i = 0; i < 36; i++) {
                ItemStack stack = player.getInventory().getStack(i);
                Item target = switch (priority) {
                    case 0 -> Items.COOKED_BEEF;
                    case 1 -> Items.BREAD;
                    default -> null;
                };
                if (target != null && stack.getItem() == target) return i;
            }
        }
        for (int i = 0; i < 36; i++) {
            if (player.getInventory().getStack(i).contains(net.minecraft.component.DataComponentTypes.FOOD)) return i;
        }
        return -1;
    }

    // ============================================================
    // 卡死脱困
    // ============================================================
    private void tryUnstuck(ClientPlayerEntity player) {
        LOGGER.info("[EasyAI] 执行脱困动作: 跳 + 潜行 + 左转");
        keySim.setJump(true);
        keySim.setSneak(true);
        player.setYaw(player.getYaw() - 30f);
    }

    // ============================================================
    // 上报状态给 Python 大脑
    // ============================================================
    private void reportState(ClientPlayerEntity player) {
        if (wsClient == null || !wsClient.isOpen()) return;

        JsonObject state = new JsonObject();
        state.addProperty("type", "state");
        state.addProperty("name", "EasyAI");
        state.addProperty("x", Math.round(player.getX() * 100.0) / 100.0);
        state.addProperty("y", Math.round(player.getY() * 100.0) / 100.0);
        state.addProperty("z", Math.round(player.getZ() * 100.0) / 100.0);
        state.addProperty("health", player.getHealth());
        state.addProperty("hunger", player.getHungerManager().getFoodLevel());
        state.addProperty("dimension", player.getWorld().getRegistryKey().getValue().toString());
        state.addProperty("on_ground", player.isOnGround());

        // 检测最近敌对实体
        String targetName = "空";
        Vec3d pos = player.getPos();
        Box searchBox = Box.of(pos, 20, 20, 20);
        List<HostileEntity> hostiles = player.getWorld().getEntitiesByClass(
                HostileEntity.class, searchBox, e -> e.distanceTo(player) < 10.0);
        if (!hostiles.isEmpty()) {
            hostiles.sort(Comparator.comparingDouble(e -> e.distanceTo(player)));
            targetName = hostiles.get(0).getName().getString();
        }
        state.addProperty("target_entity", targetName);

        wsClient.sendMessage(gson.toJson(state));
    }

    // ============================================================
    // 接收 Python 下发的指令
    // ============================================================
    public void handleCommand(String jsonStr) {
        JsonObject cmd = gson.fromJson(jsonStr, JsonObject.class);
        String type = cmd.get("type").getAsString();

        switch (type) {
            case "exec" -> {
                String action = cmd.get("cmd").getAsString();
                LOGGER.info("[EasyAI] 收到 Python 指令: {}", action);
                switch (action) {
                    case "goto" -> {
                        double x = cmd.get("x").getAsDouble();
                        double y = cmd.has("y") ? cmd.get("y").getAsDouble() : -1;
                        double z = cmd.get("z").getAsDouble();
                        navigation.goTo(x, y, z);
                    }
                    case "follow" -> {
                        String uuid = cmd.get("uuid").getAsString();
                        double distance = cmd.has("distance") ? cmd.get("distance").getAsDouble() : 3.0;
                        navigation.followEntity(uuid, distance);
                    }
                    case "stop" -> {
                        navigation.stop();
                        actionQueue.clearAll();
                        keySim.releaseAll();
                    }
                    case "attack" -> {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc.crosshairTarget != null
                                && mc.crosshairTarget.getType() == HitResult.Type.ENTITY) {
                            Entity target = ((EntityHitResult) mc.crosshairTarget).getEntity();
                            mc.interactionManager.attackEntity(mc.player, target);
                            mc.player.swingHand(Hand.MAIN_HAND);
                        } else {
                            keySim.leftClick();
                        }
                    }
                    case "use_item" -> keySim.rightClick();
                    case "send_chat" -> {
                        String msg = cmd.get("msg").getAsString();
                        if (MinecraftClient.getInstance().player != null) {
                            MinecraftClient.getInstance().player.networkHandler.sendChatMessage(msg);
                        }
                    }
                    case "break_block" -> {
                        int x = cmd.get("x").getAsInt();
                        int y = cmd.get("y").getAsInt();
                        int z = cmd.get("z").getAsInt();
                        keySim.breakBlock(x, y, z);
                    }
                    case "place_block" -> {
                        int x = cmd.get("x").getAsInt();
                        int y = cmd.get("y").getAsInt();
                        int z = cmd.get("z").getAsInt();
                        keySim.placeBlock(x, y, z);
                    }
                    case "build_pattern" -> {
                        // 建造蓝图模式
                        String pattern = cmd.get("pattern").getAsString();
                        int startX = cmd.get("start_x").getAsInt();
                        int startY = cmd.get("start_y").getAsInt();
                        int startZ = cmd.get("start_z").getAsInt();
                        buildingManager.executePattern(pattern, startX, startY, startZ);
                    }
                    case "inventory" -> printInventory();
                    case "escape" -> navigation.escape();
                    case "sleep" -> survivalInstinct.trySleep(MinecraftClient.getInstance());
                    default -> LOGGER.warn("[EasyAI] 未知指令: {}", action);
                }
            }
            case "chat_reply" -> {
                String msg = cmd.get("msg").getAsString();
                if (MinecraftClient.getInstance().player != null) {
                    MinecraftClient.getInstance().player.networkHandler.sendChatMessage(msg);
                }
            }
            default -> LOGGER.warn("[EasyAI] 未知消息类型: {}", type);
        }
    }

    private void printInventory() {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return;
        LOGGER.info("[EasyAI] ===== 背包内容 =====");
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                String slotType = i < 9 ? "热键栏" : "背包";
                LOGGER.info("[EasyAI] {}[{}]: {} x{}", slotType, i % 9,
                        stack.getName().getString(), stack.getCount());
            }
        }
        LOGGER.info("[EasyAI] =====================");
    }

    // ============================================================
    // Getters
    // ============================================================
    public static EasyAIMod getInstance() { return instance; }
    public ActionQueue getActionQueue() { return actionQueue; }
    public BaritoneIntegration getNavigation() { return navigation; }
    public KeySimulator getKeySimulator() { return keySim; }
    public EasyAIWebSocketClient getWsClient() { return wsClient; }
    public SurvivalInstinct getSurvivalInstinct() { return survivalInstinct; }
    public CombatAI getCombatAI() { return combatAI; }
    public AutoLoginManager getAutoLoginManager() { return autoLoginManager; }
    public BuildingManager getBuildingManager() { return buildingManager; }

    public void setBrainConnected(boolean connected) {
        if (connected != this.brainConnected) {
            LOGGER.info("[EasyAI] 大脑连接状态: {} -> {}", this.brainConnected, connected);
            if (connected) {
                LOGGER.info("[EasyAI] 本地端口握手成功，AI 实体已注入");
            }
        }
        this.brainConnected = connected;
    }
    public boolean isBrainConnected() { return brainConnected; }
}
