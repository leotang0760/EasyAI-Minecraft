package com.easyai.survival;

// ============================================================
// AutoLoginManager - 自动注册/登录管理器
// ============================================================
// 职责：
//   1. 监听系统消息，正则匹配注册/登录提示
//   2. 自动生成 16 位强密码（含大小写+数字+符号）
//   3. 按 server_ip 存储密码到 passwords.json
//   4. 执行注册/登录命令序列（间隔 200ms）
//   5. 明文日志打印密码（仅本地）
// ============================================================

import com.easyai.EasyAIMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class AutoLoginManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("EasyAI/AutoLogin");

    // 注册/登录提示正则（中英文兼容）
    private static final Pattern REGISTER_PATTERN = Pattern.compile(
            "(?i)(register|/reg|注册|请注册|请输入密码|type.*password)"
    );
    private static final Pattern LOGIN_PATTERN = Pattern.compile(
            "(?i)(login|/login|登录|请登录|please.*login)"
    );

    // 密码字符集
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String SYMBOLS = "!@#$%^&*";
    private static final String ALL_CHARS = UPPER + LOWER + DIGITS + SYMBOLS;

    private final SecureRandom random = new SecureRandom();

    // 防止重复触发
    private long lastTriggerTime = 0;
    private static final long COOLDOWN_MS = 10000; // 10 秒冷却

    // 最近聊天消息缓存（由 ChatHudMixin 转发）
    private volatile String lastChatMessage = "";

    // ============================================================
    // 接收聊天消息（由 ChatHudMixin 调用）
    // ============================================================
    public void onChatMessage(String message) {
        this.lastChatMessage = message;
    }

    // ============================================================
    // 每 Tick 检测（由 EasyAIMod 调用）
    // ============================================================
    // 自动登录需要在 Mod 端本地快速响应（不依赖 Python）。
    // 通过分析最近收到的聊天消息来匹配注册/登录提示。
    // ============================================================
    public void tick(ClientPlayerEntity player, MinecraftClient client) {
        // 冷却检查
        long now = System.currentTimeMillis();
        if (now - lastTriggerTime < COOLDOWN_MS) return;

        // 检测最近的聊天消息
        String message = lastChatMessage;
        if (message == null || message.isEmpty()) return;

        if (REGISTER_PATTERN.matcher(message).find()) {
            LOGGER.info("[EasyAI/Login] 检测到注册提示: {}", message);
            executeRegister(player);
            lastTriggerTime = now;
            lastChatMessage = "";
        } else if (LOGIN_PATTERN.matcher(message).find()) {
            LOGGER.info("[EasyAI/Login] 检测到登录提示: {}", message);
            executeLogin(player);
            lastTriggerTime = now;
            lastChatMessage = "";
        }
    }

    // ============================================================
    // 执行注册
    // ============================================================
    private void executeRegister(ClientPlayerEntity player) {
        String password = generateStrongPassword();
        String serverIp = getServerIp(player);

        LOGGER.info("[EasyAI/Login] 生成密码: {} (服务器: {})", password, serverIp);
        LOGGER.info("[EasyAI/Login] 明文密码已打印到本地日志，请妥善保管");

        // 保存密码到本地配置（由 Python 端管理实际文件写入）
        // Mod 端发送通知给 Python
        EasyAIMod.getInstance().getWsClient().sendMessage(
                "{\"type\":\"auto_login\",\"action\":\"register\",\"server\":\"" +
                        serverIp + "\",\"password\":\"" + password + "\"}");

        // 发送注册命令
        player.networkHandler.sendChatMessage("/register " + password + " " + password);

        // 200ms 后发送登录命令
        new Thread(() -> {
            try {
                Thread.sleep(200);
                player.networkHandler.sendChatMessage("/login " + password);
                LOGGER.info("[EasyAI/Login] 自动登录命令已发送");
            } catch (InterruptedException e) {
                LOGGER.error("[EasyAI/Login] 登录延迟发送失败", e);
            }
        }, "EasyAI-Login").start();
    }

    // ============================================================
    // 执行登录
    // ============================================================
    private void executeLogin(ClientPlayerEntity player) {
        String serverIp = getServerIp(player);

        // 请求 Python 查询已存储的密码
        EasyAIMod.getInstance().getWsClient().sendMessage(
                "{\"type\":\"auto_login\",\"action\":\"login_query\",\"server\":\"" +
                        serverIp + "\"}");

        LOGGER.info("[EasyAI/Login] 已请求 Python 查询服务器 {} 的密码", serverIp);
    }

    // ============================================================
    // 生成 16 位强密码
    // ============================================================
    public String generateStrongPassword() {
        StringBuilder sb = new StringBuilder(16);
        // 确保至少包含每种字符
        sb.append(UPPER.charAt(random.nextInt(UPPER.length())));
        sb.append(LOWER.charAt(random.nextInt(LOWER.length())));
        sb.append(DIGITS.charAt(random.nextInt(DIGITS.length())));
        sb.append(SYMBOLS.charAt(random.nextInt(SYMBOLS.length())));
        // 剩余 12 位随机
        for (int i = 4; i < 16; i++) {
            sb.append(ALL_CHARS.charAt(random.nextInt(ALL_CHARS.length())));
        }
        // 打乱顺序
        char[] arr = sb.toString().toCharArray();
        for (int i = arr.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char tmp = arr[i];
            arr[i] = arr[j];
            arr[j] = tmp;
        }
        return new String(arr);
    }

    // ============================================================
    // 获取当前服务器 IP
    // ============================================================
    private String getServerIp(ClientPlayerEntity player) {
        try {
            if (player.networkHandler != null && player.networkHandler.getConnection() != null) {
                var addr = player.networkHandler.getConnection().getAddress();
                if (addr != null) {
                    return addr.toString();
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[EasyAI/Login] 获取服务器地址失败: {}", e.getMessage());
        }
        return "unknown";
    }
}
