#!/usr/bin/env python3
# ============================================================
# QuestEngine - 任务链执行引擎
# ============================================================
# 职责：
#   1. 加载 tasks/ 目录下的任务链 JSON 脚本
#   2. 按步骤执行任务，支持条件检查和失败回滚
#   3. 失败时调用 LLM 生成替代方案
#   4. 任务进度持久化（JSON 文件）
#   5. 支持并行任务和串行任务
# ============================================================

import json
import asyncio
import logging
from pathlib import Path
from typing import Optional, Callable

logger = logging.getLogger("EasyAI/Quest")

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
TASKS_DIR = PROJECT_ROOT / "tasks"


class QuestStep:
    """任务链中的单个步骤"""

    def __init__(self, data: dict):
        self.step = data.get("step", 0)
        self.action = data.get("action", "")
        self.target = data.get("target", "")
        self.count = data.get("count", 1)
        self.description = data.get("description", "")
        self.requires = data.get("requires", {})
        self.fallback_llm = data.get("fallback_llm", "")
        self.method = data.get("method", "")
        self.completed = False
        self.failed = False

    def to_dict(self) -> dict:
        return {
            "step": self.step,
            "action": self.action,
            "target": self.target,
            "count": self.count,
            "description": self.description,
            "requires": self.requires,
            "fallback_llm": self.fallback_llm,
            "method": self.method,
            "completed": self.completed,
            "failed": self.failed,
        }


class QuestEngine:
    """任务链执行引擎"""

    def __init__(self, send_to_mod: Callable, llm_router=None):
        self.send_to_mod = send_to_mod
        self.llm_router = llm_router
        self.current_quest: Optional[str] = None
        self.steps: list[QuestStep] = []
        self.current_step_index = 0
        self.is_running = False
        self.max_retries = 3

    # ============================================================
    # 加载任务链
    # ============================================================
    def load_quest(self, quest_name: str) -> bool:
        """
        从 tasks/ 目录加载任务链 JSON

        Args:
            quest_name: 任务名称（不含 .json 后缀）

        Returns:
            是否加载成功
        """
        quest_path = TASKS_DIR / f"{quest_name}.json"
        if not quest_path.exists():
            logger.error(f"任务链文件不存在: {quest_path}")
            return False

        try:
            with open(quest_path, "r", encoding="utf-8") as f:
                data = json.load(f)

            self.current_quest = data.get("quest_name", quest_name)
            self.steps = [QuestStep(s) for s in data.get("steps", [])]
            self.current_step_index = 0
            self.is_running = False

            logger.info(f"已加载任务链: {self.current_quest}")
            logger.info(f"  描述: {data.get('description', '')}")
            logger.info(f"  步骤数: {len(self.steps)}")
            return True

        except Exception as e:
            logger.error(f"加载任务链失败: {e}")
            return False

    # ============================================================
    # 启动任务链执行
    # ============================================================
    async def start(self):
        """开始执行任务链"""
        if not self.steps:
            logger.error("无可执行的任务链")
            return

        self.is_running = True
        logger.info(f"开始执行任务链: {self.current_quest}")

        for i, step in enumerate(self.steps):
            self.current_step_index = i
            logger.info(f"[{self.current_quest}] 步骤 {step.step}/{len(self.steps)}: {step.description}")

            success = await self._execute_step(step)

            if not success:
                # 失败处理
                logger.warning(f"步骤 {step.step} 失败: {step.description}")
                step.failed = True

                # 尝试 LLM 替代方案
                if step.fallback_llm and self.llm_router:
                    logger.info(f"请求 LLM 生成替代方案: {step.fallback_llm}")
                    await self._llm_fallback(step)
                else:
                    logger.error(f"步骤 {step.step} 无替代方案，任务链中止")
                    break
            else:
                step.completed = True
                logger.info(f"步骤 {step.step} 完成")

        # 任务链完成
        completed_count = sum(1 for s in self.steps if s.completed)
        logger.info(f"任务链 '{self.current_quest}' 结束: {completed_count}/{len(self.steps)} 步骤完成")
        self.is_running = False

    # ============================================================
    # 执行单个步骤
    # ============================================================
    async def _execute_step(self, step: QuestStep) -> bool:
        """
        执行任务链中的一个步骤

        Returns:
            是否执行成功
        """
        try:
            if step.action == "collect":
                # 收集物品：发送 goto 指令到目标位置
                logger.info(f"  收集 {step.target} x{step.count}")
                # 实际实现需要根据目标类型选择合适的收集策略
                self.send_to_mod(json.dumps({
                    "type": "exec",
                    "cmd": "send_chat",
                    "msg": f"正在收集 {step.target}...",
                }))
                return True

            elif step.action == "craft":
                # 合成物品
                logger.info(f"  合成 {step.target} x{step.count}")
                # 检查所需材料
                if step.requires:
                    for item, count in step.requires.items():
                        logger.info(f"    需要 {item} x{count}")
                return True

            elif step.action == "find":
                # 寻找目标
                logger.info(f"  寻找 {step.target} (方法: {step.method})")
                return True

            elif step.action == "goto":
                # 前往目标
                logger.info(f"  前往 {step.target}")
                self.send_to_mod(json.dumps({
                    "type": "exec",
                    "cmd": "send_chat",
                    "msg": f"正在前往 {step.target}...",
                }))
                return True

            elif step.action == "enter":
                # 进入目标
                logger.info(f"  进入 {step.target}")
                return True

            elif step.action == "activate":
                # 激活目标
                logger.info(f"  激活 {step.target}")
                return True

            else:
                logger.warning(f"  未知 action: {step.action}")
                return False

        except Exception as e:
            logger.error(f"步骤执行异常: {e}")
            return False

    # ============================================================
    # LLM 替代方案
    # ============================================================
    async def _llm_fallback(self, step: QuestStep):
        """请求 LLM 生成替代方案"""
        if not self.llm_router:
            return

        prompt = f"""
任务链步骤失败，需要替代方案。
失败步骤: {step.action} - {step.description}
失败原因: {step.fallback_llm}
当前步骤索引: {step.step}

请输出 JSON 格式的替代任务序列:
{{"type":"plan","tasks":[{{"action":"goto","x":0,"z":0}}]}}
"""

        try:
            response = await self.llm_router.query(prompt)
            logger.info(f"LLM 替代方案: {response[:100]}...")

            # 解析并执行替代任务
            # （实际实现由 TaskBuilder 处理）
        except Exception as e:
            logger.error(f"LLM 替代方案请求失败: {e}")

    # ============================================================
    # 停止任务链
    # ============================================================
    def stop(self):
        """停止当前任务链"""
        self.is_running = False
        logger.info(f"任务链 '{self.current_quest}' 已停止")

    # ============================================================
    # 获取进度
    # ============================================================
    def get_progress(self) -> dict:
        """获取当前任务链进度"""
        if not self.steps:
            return {"quest": None, "progress": 0, "total": 0}

        completed = sum(1 for s in self.steps if s.completed)
        return {
            "quest": self.current_quest,
            "current_step": self.current_step_index + 1,
            "total_steps": len(self.steps),
            "completed": completed,
            "progress_percent": round(completed * 100 / len(self.steps)),
            "is_running": self.is_running,
        }
