package com.easyai.network;

// ============================================================
// EasyAIWebSocketClient - 连接 Python 大脑的 WebSocket 客户端
// ============================================================
// 职责：
//   1. 建立 WebSocket 连接到 Python 后端 (ws://127.0.0.1:8765)
//   2. 上行：发送游戏状态、聊天事件给 Python
//   3. 下行：接收 Python 下发的执行指令
//   4. 自动重连（断连后每 5 秒重试）
//   5. 连接状态通知 EasyAIMod（控制物理动作暂停/恢复）
//
// 使用库：Java-WebSocket (org.java_websocket)
//
// 注意：类名不能与父类 WebSocketClient 重名，因此命名为 EasyAIWebSocketClient
// ============================================================

import com.easyai.EasyAIMod;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class EasyAIWebSocketClient extends WebSocketClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("EasyAI/WS");

    private final EasyAIMod mod;
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    private final ScheduledExecutorService reconnectExecutor;

    // 重连间隔（毫秒）
    private static final long RECONNECT_INTERVAL_MS = 5000;

    public EasyAIWebSocketClient(String serverUri, EasyAIMod mod) {
        super(URI.create(serverUri));
        this.mod = mod;
        this.reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "EasyAI-Reconnect");
            t.setDaemon(true);
            return t;
        });

        // 配置连接超时
        this.setConnectionLostTimeout(5);
    }

    // ============================================================
    // 连接成功回调
    // ============================================================
    @Override
    public void onOpen(ServerHandshake handshake) {
        LOGGER.info("[EasyAI/WS] 连接成功: {} (HTTP {})", uri, handshake.getHttpStatus());
        mod.setBrainConnected(true);

        // 发送握手消息
        send("{\"type\":\"handshake\",\"client\":\"easyai-mod\",\"version\":\"2.0.0\"}");
    }

    // ============================================================
    // 收到消息回调
    // ============================================================
    @Override
    public void onMessage(String message) {
        LOGGER.debug("[EasyAI/WS] 收到: {}", message);
        try {
            // 将消息交给 EasyAIMod 处理
            mod.handleCommand(message);
        } catch (Exception e) {
            LOGGER.error("[EasyAI/WS] 消息处理异常: {}", e.getMessage(), e);
        }
    }

    // ============================================================
    // 连接关闭回调
    // ============================================================
    @Override
    public void onClose(int code, String reason, boolean remote) {
        LOGGER.warn("[EasyAI/WS] 连接关闭: code={} reason={} remote={}",
                code, reason, remote);
        mod.setBrainConnected(false);

        // 自动重连（每 5 秒尝试一次）
        if (shouldReconnect.get()) {
            reconnectExecutor.schedule(() -> {
                if (shouldReconnect.get() && !isOpen()) {
                    LOGGER.info("[EasyAI/WS] 尝试重连...");
                    try {
                        reconnect();
                    } catch (Exception e) {
                        LOGGER.error("[EasyAI/WS] 重连失败: {}", e.getMessage());
                    }
                }
            }, RECONNECT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    // ============================================================
    // 异常回调
    // ============================================================
    @Override
    public void onError(Exception ex) {
        LOGGER.error("[EasyAI/WS] 异常: {}", ex.getMessage());
        // 不在此处重连，onClose 会处理
    }

    // ============================================================
    // 安全关闭
    // ============================================================
    public void shutdown() {
        shouldReconnect.set(false);
        reconnectExecutor.shutdown();
        close();
    }

    // ============================================================
    // 发送消息（线程安全封装）
    // ============================================================
    public void sendMessage(String json) {
        if (isOpen()) {
            send(json);
        } else {
            LOGGER.warn("[EasyAI/WS] 连接未就绪，丢弃消息: {}", json);
        }
    }
}
