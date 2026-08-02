package com.easyai.mixin;

// ============================================================
// ChatHudMixin - 拦截游戏内聊天消息 (MC 1.21.1)
// ============================================================
// Mixin 注入原理：
//   @Inject 注解在 ChatHud.addMessage() 方法头部织入我们的代码。
//   当游戏收到任何聊天消息时，我们能在它显示到屏幕之前先截获，
//   解析是否包含 @{AI_NAME} 关键字，若匹配则转发给 Python 大脑处理。
//
// 1.21.x 签名变化：
//   1.20.x: addMessage(Text, MessageSignatureData, int, MessageIndicator, boolean)
//   1.21.x: addMessage(Text, MessageSignature, MessageIndicator)
//   MessageSignatureData → MessageSignature（包路径变更）
//
//   为保证兼容性，我们注入 addMessage(Text) 简单重载，
//   该方法在 1.20.x / 1.21.x 中均稳定存在。
// ============================================================

import com.easyai.EasyAIMod;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

@Mixin(ChatHud.class)
public class ChatHudMixin {

    private static final Gson GSON = new Gson();

    // ============================================================
    // 拦截 addMessage(Text) —— 每条聊天消息都会经过这里
    // ============================================================
    // 使用简单重载签名 addMessage(Text) 保证跨版本兼容性。
    // 该方法是所有 addMessage 重载的最终入口，无论 1.20 还是 1.21
    // 都会调用此方法将消息添加到聊天 HUD。
    // ============================================================
    @Inject(method = "addMessage(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void easyai$onChatMessage(Text message, CallbackInfo ci) {

        // 获取纯文本内容
        String rawText = message.getString();
        if (rawText == null || rawText.isEmpty()) return;

        EasyAIMod.LOGGER.debug("[EasyAI] 聊天拦截: {}", rawText);

        // 解析发送者和消息内容
        // MC 聊天格式通常为 "<PlayerName> 消息内容" 或 "PlayerName: 消息内容"
        String sender = "Unknown";
        String msg = rawText;

        // 尝试匹配 "<Player> message" 格式
        if (rawText.startsWith("<") && rawText.contains(">")) {
            int end = rawText.indexOf('>');
            sender = rawText.substring(1, end).trim();
            msg = rawText.substring(end + 1).trim();
        }
        // 尝试匹配 "Player: message" 格式
        else {
            int colonIdx = rawText.indexOf(':');
            if (colonIdx > 0 && colonIdx < 20) {
                sender = rawText.substring(0, colonIdx).trim();
                msg = rawText.substring(colonIdx + 1).trim();
            }
        }

        // 构造聊天事件 JSON，发送给 Python
        JsonObject chatEvent = new JsonObject();
        chatEvent.addProperty("type", "chat");
        chatEvent.addProperty("sender", sender);
        chatEvent.addProperty("msg", msg);
        chatEvent.addProperty("raw", rawText);
        chatEvent.addProperty("timestamp", System.currentTimeMillis() / 1000);

        // 通过 WebSocket 发送给 Python
        EasyAIMod.getInstance().getWsClient().sendMessage(GSON.toJson(chatEvent));

        // 同时转发给 AutoLoginManager（本地快速响应注册/登录提示）
        if (EasyAIMod.getInstance().getAutoLoginManager() != null) {
            EasyAIMod.getInstance().getAutoLoginManager().onChatMessage(rawText);
        }
    }
}
