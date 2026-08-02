#!/usr/bin/env python3
# ============================================================
# CommandParser - 游戏内 @ 指令解析器
# ============================================================
# 职责：
#   1. 解析游戏内聊天中的 @{AI_NAME} 前缀指令
#   2. 使用正则 + 映射表实现硬解析（延迟 < 200ms）
#   3. 白名单分层：白名单玩家立即执行，非白名单仅异步回复
#   4. 复杂指令（如"建房子"）标记为 LLM 处理
#
# 解析流程：
#   1. 检测消息是否包含 @{AI_NAME}
#   2. 提取指令内容
#   3. 正则匹配已知指令模式
#   4. 未匹配的归为 LLM 处理
# ============================================================

import re
import logging
from typing import Optional

logger = logging.getLogger("EasyAI/Parser")


class CommandParser:
    """游戏内 @ 指令硬解析器"""

    def __init__(self, ai_name: str, whitelist: list):
        self.ai_name = ai_name
        self.whitelist = list(whitelist)

        # 构建匹配 @{AI_NAME} 的正则
        # 支持中英文 @ 符号
        self._at_pattern = re.compile(
            rf"[@＠]\s*{re.escape(ai_name)}\s+(.+)",
            re.IGNORECASE,
        )

        # ============================================================
        # 指令映射表
        # ============================================================
        # 每条规则：(正则模式, cmd_type, 默认回复)
        # ============================================================
        self._command_rules = [
            # ---- 跟随类 ----
            (re.compile(r"^(来|过来|过来这|过来这里|过来找我|跟着我|跟我)$", re.IGNORECASE),
             "follow", "来了，等我一下"),

            (re.compile(r"^(跟我来|跟我走|跟着)$", re.IGNORECASE),
             "follow", "好的，跟着你"),

            # ---- TPA 类 ----
            (re.compile(r"^(tpa我|tp我|tp|传送我|传我)$", re.IGNORECASE),
             "tpa", "已发送TPA请求"),

            # ---- 停止类 ----
            (re.compile(r"^(停止|停下|stop|halt|别动|站住)$", re.IGNORECASE),
             "stop", "已停止当前动作"),

            # ---- 攻击类 ----
            (re.compile(r"^(打他|打它|攻击|attack|杀他|干掉他|干掉它)$", re.IGNORECASE),
             "attack", "交给我"),

            # ---- 进食类 ----
            (re.compile(r"^(食物|饿|饿了|吃|吃东西|进食|eat)$", re.IGNORECASE),
             "eat", "好的，补充能量"),

            # ---- 前往坐标 ----
            # 匹配 "去 100 64 200" 或 "前往 100 64 200" 或 "goto 100 64 200"
            (re.compile(r"^(?:去|前往|goto|move)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)$", re.IGNORECASE),
             "goto", "正在前往"),

            # 匹配 "去 100 200"（只有 XZ，Y 自动）
            (re.compile(r"^(?:去|前往|goto|move)\s+(-?\d+)\s+(-?\d+)$", re.IGNORECASE),
             "goto_xz", "正在前往"),
        ]

        # 复杂指令关键词（触发 LLM）
        self._llm_keywords = [
            "建", "造", "盖", "挖", "采集", "收集", "找",
            "红石", "电路", "农场", "房子", "基地",
            "末影", "龙", "下界", "末地", "探险", "探索",
        ]

        # 非白名单触发关键词
        self._stranger_keywords = ["help", "bot", "你好", "你是", "what"]

    # ============================================================
    # 主解析入口
    # ============================================================
    def parse(self, sender: str, msg: str) -> Optional[dict]:
        """
        解析聊天消息

        Args:
            sender: 发送者玩家名
            msg: 消息内容（已去除前缀）

        Returns:
            解析结果 dict，或 None（非 AI 相关消息）
        """
        # 检查是否包含 @AI_NAME
        match = self._at_pattern.search(msg)
        if not match:
            # 非 @ 指令，检查是否非白名单玩家触发关键词
            if sender not in self.whitelist:
                msg_lower = msg.lower()
                mention_count = msg.lower().count(self.ai_name.lower())
                if mention_count >= 3:
                    return {"action": "async_reply",
                            "reply": "您好，我是AI助手，已记录您的发言"}
                for kw in self._stranger_keywords:
                    if kw in msg_lower:
                        return {"action": "async_reply",
                                "reply": "您好，我是AI助手，已记录您的发言"}
            return None

        # 提取指令内容
        command_text = match.group(1).strip()
        logger.info(f"解析指令: sender={sender}, cmd='{command_text}'")

        # ============================================================
        # 白名单检查
        # ============================================================
        if sender not in self.whitelist:
            # 非白名单玩家：绝不执行任何动作，仅记录
            logger.warning(f"非白名单玩家 [{sender}] 尝试操控 AI，已忽略")
            return {"action": "ignore"}

        # ============================================================
        # 白名单玩家：尝试硬解析
        # ============================================================
        for pattern, cmd_type, default_reply in self._command_rules:
            m = pattern.match(command_text)
            if m:
                result = {
                    "action": "command",
                    "cmd_type": cmd_type,
                    "reply": default_reply,
                    "sender": sender,
                    "raw_command": command_text,
                }

                # 提取坐标参数
                if cmd_type == "goto":
                    result["x"] = int(m.group(1))
                    result["y"] = int(m.group(2))
                    result["z"] = int(m.group(3))
                elif cmd_type == "goto_xz":
                    result["x"] = int(m.group(1))
                    result["y"] = -1  # Y 自动
                    result["z"] = int(m.group(2))
                    result["cmd_type"] = "goto"

                logger.info(f"硬解析结果: {cmd_type}")
                return result

        # ============================================================
        # 未匹配已知指令 → LLM 处理
        # ============================================================
        # 检查是否包含复杂指令关键词
        is_complex = any(kw in command_text for kw in self._llm_keywords)

        if is_complex:
            logger.info(f"复杂指令，转 LLM: '{command_text}'")
            return {
                "action": "llm",
                "sender": sender,
                "raw_command": command_text,
                "msg": msg,  # 完整原始消息
            }

        # 默认也走 LLM（对话类）
        logger.info(f"未知指令，转 LLM: '{command_text}'")
        return {
            "action": "llm",
            "sender": sender,
            "raw_command": command_text,
            "msg": msg,
        }

    # ============================================================
    # 动态更新白名单
    # ============================================================
    def add_to_whitelist(self, name: str):
        if name not in self.whitelist:
            self.whitelist.append(name)

    def remove_from_whitelist(self, name: str):
        if name in self.whitelist:
            self.whitelist.remove(name)
