#!/usr/bin/env python3
# ============================================================
# SurvivalAdvisor - 生存策略顾问
# ============================================================
# 职责：
#   1. 分析当前游戏状态（血量、饥饿、时间、天气、周围实体）
#   2. 生成生存建议（优先级排序）
#   3. 检测危险区域（基于失败经验库）
#   4. 推荐下一步行动
#   5. 构建增强的 LLM 上下文
# ============================================================

import logging
import time
from typing import Optional

logger = logging.getLogger("EasyAI/SurvivalAdvisor")


class SurvivalAdvisor:
    """生存策略分析与建议"""

    # 优先级常量
    PRIORITY_CRITICAL = 0  # 生死攸关
    PRIORITY_HIGH = 1      # 紧急
    PRIORITY_MEDIUM = 2    # 重要
    PRIORITY_LOW = 3       # 建议

    def __init__(self, failure_repo=None):
        self.failure_repo = failure_repo

    # ============================================================
    # 分析当前状态，生成建议列表
    # ============================================================
    def analyze(self, state: dict) -> list[dict]:
        """
        分析游戏状态，返回按优先级排序的建议列表

        Args:
            state: Mod 上报的游戏状态

        Returns:
            建议列表，每条包含 priority, category, suggestion, action
        """
        suggestions = []

        health = state.get("health", 20)
        hunger = state.get("hunger", 20)
        x = state.get("x", 0)
        y = state.get("y", 64)
        z = state.get("z", 0)
        dimension = state.get("dimension", "overworld")
        target = state.get("target_entity", "空")

        # ---- 1. 血量检测 ----
        if health <= 0:
            suggestions.append({
                "priority": self.PRIORITY_CRITICAL,
                "category": "health",
                "suggestion": "AI 已死亡！需要重生并返回死亡地点拾取物品",
                "action": "respawn",
            })
        elif health < 8:
            suggestions.append({
                "priority": self.PRIORITY_CRITICAL,
                "category": "health",
                "suggestion": "血量极低，立即逃离并进食",
                "action": "escape_and_eat",
            })
        elif health < 14:
            suggestions.append({
                "priority": self.PRIORITY_HIGH,
                "category": "health",
                "suggestion": "血量偏低，建议进食恢复",
                "action": "eat",
            })

        # ---- 2. 饥饿检测 ----
        if hunger < 6:
            suggestions.append({
                "priority": self.PRIORITY_CRITICAL,
                "category": "hunger",
                "suggestion": "饱食度极低，立即进食",
                "action": "eat",
            })
        elif hunger < 12:
            suggestions.append({
                "priority": self.PRIORITY_HIGH,
                "category": "hunger",
                "suggestion": "饱食度偏低，建议进食",
                "action": "eat",
            })

        # ---- 3. 维度安全检测 ----
        if dimension == "minecraft:the_nether":
            suggestions.append({
                "priority": self.PRIORITY_HIGH,
                "category": "dimension",
                "suggestion": "处于下界，注意岩浆和恶魂",
                "action": "caution",
            })
        elif dimension == "minecraft:the_end":
            suggestions.append({
                "priority": self.PRIORITY_HIGH,
                "category": "dimension",
                "suggestion": "处于末地，注意末影龙和虚空",
                "action": "caution",
            })

        # ---- 4. 高度检测 ----
        if y < 10 and dimension == "minecraft:overworld":
            suggestions.append({
                "priority": self.PRIORITY_MEDIUM,
                "category": "height",
                "suggestion": "处于深层地下，注意岩浆和怪物",
                "action": "caution",
            })
        elif y > 200:
            suggestions.append({
                "priority": self.PRIORITY_LOW,
                "category": "height",
                "suggestion": "处于高空，注意坠落风险",
                "action": "caution",
            })

        # ---- 5. 敌对实体检测 ----
        if target != "空":
            suggestions.append({
                "priority": self.PRIORITY_HIGH,
                "category": "combat",
                "suggestion": f"附近有敌对实体: {target}，准备战斗",
                "action": "combat",
            })

        # ---- 6. 失败经验检测 ----
        if self.failure_repo:
            failures = self.failure_repo.get_nearby_failures(x, z, radius=20)
            if failures:
                suggestions.append({
                    "priority": self.PRIORITY_HIGH,
                    "category": "experience",
                    "suggestion": f"附近有 {len(failures)} 条死亡记录，建议避开此区域",
                    "action": "avoid",
                    "details": [f["cause"] for f in failures],
                })

        # 按优先级排序
        suggestions.sort(key=lambda s: s["priority"])
        return suggestions

    # ============================================================
    # 获取最高优先级建议
    # ============================================================
    def get_top_suggestion(self, state: dict) -> Optional[dict]:
        """获取最高优先级的生存建议"""
        suggestions = self.analyze(state)
        return suggestions[0] if suggestions else None

    # ============================================================
    # 构建增强的 LLM 上下文
    # ============================================================
    def build_enhanced_context(self, state: dict, sender: str, msg: str) -> str:
        """
        构建包含生存分析的增强 LLM Prompt

        Args:
            state: 当前游戏状态
            sender: 发送者
            msg: 消息内容

        Returns:
            增强后的系统提示
        """
        suggestions = self.analyze(state)
        suggestion_text = ""
        if suggestions:
            top = suggestions[0]
            suggestion_text = f"\n生存警告: {top['suggestion']} (优先级: {top['priority']})"

        # 失败区域信息
        avoid_info = ""
        if self.failure_repo:
            failures = self.failure_repo.get_nearby_failures(
                state.get("x", 0), state.get("z", 0), radius=20
            )
            if failures:
                avoid_info = f"\n注意: 附近有 {len(failures)} 条死亡记录，请避开危险区域。"

        x = state.get("x", 0)
        y = state.get("y", 64)
        z = state.get("z", 0)
        health = state.get("health", 20)
        hunger = state.get("hunger", 20)
        dimension = state.get("dimension", "overworld")
        target = state.get("target_entity", "空")

        prompt = f"""当前状态：坐标(X:{x}, Y:{y}, Z:{z}), 血量:{health}, 饥饿:{hunger}, 维度:{dimension}, 目标:{target}。{suggestion_text}{avoid_info}
玩家[{sender}]说："{msg}"。请判断意图并输出JSON。
输出格式：{{"type":"chat", "reply":"回复内容"}} 或 {{"type":"plan", "tasks":[{{"action":"goto","x":0,"z":0}}]}}"""
        return prompt

    # ============================================================
    # 获取状态摘要
    # ============================================================
    def get_status_summary(self, state: dict) -> str:
        """生成可读的状态摘要"""
        suggestions = self.analyze(state)
        summary_parts = [
            f"坐标:({state.get('x',0)}, {state.get('y',0)}, {state.get('z',0)})",
            f"血量:{state.get('health',0)}",
            f"饥饿:{state.get('hunger',0)}",
            f"维度:{state.get('dimension','?')}",
        ]

        if suggestions:
            top = suggestions[0]
            summary_parts.append(f"警告:{top['suggestion']}")

        return " | ".join(summary_parts)
