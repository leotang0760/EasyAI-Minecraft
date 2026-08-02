#!/usr/bin/env python3
# ============================================================
# TaskBuilder - 将 LLM 输出转为 JSON 任务流
# ============================================================
# 职责：
#   1. 解析 LLM 返回的 JSON 文本
#   2. 将 plan 类型的任务序列展开为逐条 Mod 指令
#   3. 查询失败经验库，为寻路任务添加 avoid_zone 标记
#   4. 处理 LLM 输出格式异常的兜底逻辑
# ============================================================

import json
import re
import logging
from typing import List

logger = logging.getLogger("EasyAI/TaskBuilder")


class TaskBuilder:
    """LLM 输出 → Mod 可执行任务流转换器"""

    def __init__(self, failure_repo=None):
        self.failure_repo = failure_repo

    # ============================================================
    # 解析 LLM 响应
    # ============================================================
    def parse(self, llm_response: str) -> List[dict]:
        """
        解析 LLM 的文本响应，提取任务列表

        Args:
            llm_response: LLM 返回的原始文本

        Returns:
            任务列表（每个元素是一条 Mod 指令 JSON），或空列表（纯聊天）
        """
        # 尝试提取 JSON（LLM 可能输出额外文本）
        json_str = self._extract_json(llm_response)
        if not json_str:
            # 无法解析为 JSON，视为纯文本回复
            logger.warning(f"LLM 输出非 JSON，视为纯文本: {llm_response[:80]}...")
            return []

        try:
            data = json.loads(json_str)
        except json.JSONDecodeError as e:
            logger.error(f"JSON 解析失败: {e}")
            return []

        msg_type = data.get("type")

        if msg_type == "chat":
            # 纯聊天回复，返回空列表（由调用方处理文本）
            logger.info(f"LLM 回复类型: chat")
            return []

        elif msg_type == "plan":
            # 任务计划，展开为指令列表
            tasks = data.get("tasks", [])
            logger.info(f"LLM 回复类型: plan, 共 {len(tasks)} 个任务")
            return self._build_tasks(tasks)

        else:
            logger.warning(f"未知 LLM 响应类型: {msg_type}")
            return []

    # ============================================================
    # 从 LLM 文本中提取 JSON
    # ============================================================
    def _extract_json(self, text: str) -> str:
        """
        LLM 可能输出额外说明文字，尝试提取其中的 JSON 对象

        策略：
        1. 如果整个文本就是有效 JSON，直接返回
        2. 否则查找第一个 { 到最后一个 } 的子串
        3. 如果有 ```json 代码块，提取代码块内容
        """
        text = text.strip()

        # 策略 1：直接尝试解析
        try:
            json.loads(text)
            return text
        except json.JSONDecodeError:
            pass

        # 策略 2：提取 ```json ... ``` 代码块
        code_block_match = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.DOTALL)
        if code_block_match:
            return code_block_match.group(1).strip()

        # 策略 3：提取第一个 { 到最后一个 }
        first_brace = text.find("{")
        last_brace = text.rfind("}")
        if first_brace != -1 and last_brace != -1 and last_brace > first_brace:
            candidate = text[first_brace:last_brace + 1]
            try:
                json.loads(candidate)
                return candidate
            except json.JSONDecodeError:
                pass

        return ""

    # ============================================================
    # 构建任务指令列表
    # ============================================================
    def _build_tasks(self, tasks: list) -> List[dict]:
        """
        将 LLM 的任务计划展开为 Mod 可执行的指令列表

        支持的 action 类型：
        - goto: 寻路到坐标
        - follow: 跟随实体
        - attack: 攻击
        - break_block: 破坏方块
        - place_block: 放置方块
        - use_item: 使用物品
        - send_chat: 发送聊天
        - wait: 等待（秒）
        """
        result = []

        for task in tasks:
            action = task.get("action")
            if not action:
                continue

            if action == "goto":
                x = task.get("x", 0)
                y = task.get("y", -1)
                z = task.get("z", 0)

                # 查询附近死亡记录
                avoid_zone = False
                if self.failure_repo:
                    failures = self.failure_repo.get_nearby_failures(x, z, radius=20)
                    if failures:
                        avoid_zone = True
                        logger.warning(
                            f"坐标 ({x}, {z}) 附近有 {len(failures)} 条死亡记录，"
                            f"已标记 avoid_zone"
                        )

                result.append({
                    "type": "exec",
                    "cmd": "goto",
                    "x": x,
                    "y": y,
                    "z": z,
                    "avoid_zone": avoid_zone,
                })

            elif action == "follow":
                result.append({
                    "type": "exec",
                    "cmd": "follow",
                    "uuid": task.get("uuid", ""),
                    "distance": task.get("distance", 3.0),
                })

            elif action == "attack":
                result.append({"type": "exec", "cmd": "attack"})

            elif action == "break_block":
                result.append({
                    "type": "exec",
                    "cmd": "break_block",
                    "x": task.get("x", 0),
                    "y": task.get("y", 0),
                    "z": task.get("z", 0),
                })

            elif action == "place_block":
                result.append({
                    "type": "exec",
                    "cmd": "place_block",
                    "x": task.get("x", 0),
                    "y": task.get("y", 0),
                    "z": task.get("z", 0),
                })

            elif action == "use_item":
                result.append({"type": "exec", "cmd": "use_item"})

            elif action == "send_chat":
                result.append({
                    "type": "exec",
                    "cmd": "send_chat",
                    "msg": task.get("msg", ""),
                })

            elif action == "wait":
                # wait 指令在 Python 端处理，不下发给 Mod
                # 实际实现：在 asyncio 中 sleep
                result.append({
                    "type": "wait",
                    "seconds": task.get("seconds", 1),
                })

            else:
                logger.warning(f"未知 action: {action}")

        return result
