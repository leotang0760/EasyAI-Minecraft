#!/usr/bin/env python3
# ============================================================
# Project EasyAI - Python 后端启动入口
# ============================================================
# 职责：
#   1. 加载配置文件 (config/settings.json)
#   2. 启动 WebSocket 服务器（接收 Mod 上行消息）
#   3. 启动命令控制台（管理员后台输入）
#   4. 启动日志渲染面板（rich 库美化输出）
#   5. 初始化 LLM 路由器、指令解析器、任务构建器
#
# 架构说明：
#   主线程运行 asyncio 事件循环，WebSocket 服务器在其上运行。
#   命令控制台在独立线程中运行，通过 asyncio.run_coroutine_threadsafe()
#   向主循环提交任务。
# ============================================================

import asyncio
import json
import os
import sys
import signal
import threading
import logging
from pathlib import Path

# 确保项目根目录在 sys.path 中
PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT / "python"))

from server.ws_endpoint import WSServer
from brain.llm_router import LLMRouter
from brain.command_parser import CommandParser
from brain.task_builder import TaskBuilder
from brain.auto_login import AutoLoginManager
from brain.quest_engine import QuestEngine
from brain.survival_advisor import SurvivalAdvisor
from memory.failure_repo import FailureRepo
from ui.log_console import LogConsole
from ui.cmd_console import CmdConsole
from ui.web_server import WebServer


class EasyAIBackend:
    """Project EasyAI Python 后端主控制器"""

    def __init__(self):
        # 加载配置
        self.config = self._load_config()
        self.whitelist = self._load_whitelist()
        self.passwords = self._load_passwords()

        # 初始化子系统
        self.failure_repo = FailureRepo(str(PROJECT_ROOT / "logs" / "failures.db"))
        self.llm_router = LLMRouter(self.config)
        self.command_parser = CommandParser(
            ai_name=self.config.get("ai_name", "EasyAI"),
            whitelist=self.whitelist,
        )
        self.task_builder = TaskBuilder(self.failure_repo)
        self.auto_login = AutoLoginManager()
        self.survival_advisor = SurvivalAdvisor(self.failure_repo)
        self.quest_engine = QuestEngine(self._send_to_mod, self.llm_router)

        # WebSocket 服务器
        self.ws_port = self.config.get("ws_port", 8765)
        self.ws_server = WSServer(
            port=self.ws_port,
            on_state_callback=self._on_state_update,
            on_chat_callback=self._on_chat_event,
            on_other_callback=self._on_other_event,
        )

        # UI
        self.log_console = LogConsole()
        self.cmd_console = CmdConsole(self._on_admin_command)

        # WebUI 服务
        self.web_port = self.config.get("web_port", 8080)
        self.web_server = WebServer(
            port=self.web_port,
            on_command=self._on_admin_command,
            get_state=lambda: self.current_state,
            get_config=lambda: self.config,
            get_whitelist=lambda: self.whitelist,
            get_passwords=lambda: self.passwords,
            get_quest_progress=lambda: self.quest_engine.get_progress(),
            on_whitelist_update=self._on_whitelist_update,
            on_config_update=self._on_config_update,
        )

        # 最新状态缓存
        self.current_state = {}

        # 运行标志
        self._running = False

    # ============================================================
    # 配置加载
    # ============================================================
    def _load_config(self) -> dict:
        config_path = PROJECT_ROOT / "config" / "settings.json"
        if config_path.exists():
            with open(config_path, "r", encoding="utf-8") as f:
                return json.load(f)
        # 默认配置
        return {
            "ai_name": "EasyAI",
            "ws_port": 8765,
            "llm_mode": "offline",
            "ollama_model": "llama3.1",
            "ollama_host": "http://127.0.0.1:11434",
            "openai_api_key": "",
            "openai_model": "gpt-4o-mini",
        }

    def _load_whitelist(self) -> list:
        wl_path = PROJECT_ROOT / "config" / "whitelist.json"
        if wl_path.exists():
            with open(wl_path, "r", encoding="utf-8") as f:
                return json.load(f)
        return []

    def _load_passwords(self) -> dict:
        pw_path = PROJECT_ROOT / "config" / "passwords.json"
        if pw_path.exists():
            with open(pw_path, "r", encoding="utf-8") as f:
                return json.load(f)
        return {}

    def _save_whitelist(self):
        wl_path = PROJECT_ROOT / "config" / "whitelist.json"
        with open(wl_path, "w", encoding="utf-8") as f:
            json.dump(self.whitelist, f, ensure_ascii=False, indent=2)

    def _save_passwords(self):
        pw_path = PROJECT_ROOT / "config" / "passwords.json"
        with open(pw_path, "w", encoding="utf-8") as f:
            json.dump(self.passwords, f, ensure_ascii=False, indent=2)

    # ============================================================
    # WebUI 回调
    # ============================================================
    def _on_whitelist_update(self, new_whitelist: list):
        """WebUI 更新白名单"""
        self.whitelist = new_whitelist
        self._save_whitelist()
        self.command_parser.whitelist = self.whitelist

    def _on_config_update(self, new_config: dict):
        """WebUI 更新配置"""
        self.config.update(new_config)
        config_path = PROJECT_ROOT / "config" / "settings.json"
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(self.config, f, ensure_ascii=False, indent=2)

    # ============================================================
    # WebSocket 回调
    # ============================================================
    def _on_state_update(self, state: dict):
        """Mod 上报的游戏状态（每秒一次）"""
        self.current_state = state
        self.log_console.log_state(state)

        # 广播到 WebUI
        asyncio.get_event_loop().create_task(
            self.web_server.broadcast_state(state))

        # 生存分析：检测危险状态
        suggestions = self.survival_advisor.analyze(state)
        for s in suggestions:
            if s["priority"] <= 1:  # CRITICAL 或 HIGH
                msg = f"生存警告: {s['suggestion']} (优先级: {s['priority']})"
                self.log_console.log_error(msg)
                asyncio.get_event_loop().create_task(
                    self.web_server.broadcast_log("error", msg))

    def _on_chat_event(self, chat: dict):
        """游戏内聊天事件"""
        sender = chat.get("sender", "Unknown")
        msg = chat.get("msg", "")
        self.log_console.log_chat(sender, msg)

        # 广播到 WebUI
        asyncio.get_event_loop().create_task(
            self.web_server.broadcast_chat(sender, msg))

        # 解析 @ 指令
        result = self.command_parser.parse(sender, msg)
        if result is None:
            return

        action = result.get("action")

        if action == "ignore":
            # 非白名单玩家，忽略
            self.log_console.log_system(f"非白名单玩家 [{sender}] 消息已忽略")
            return

        if action == "async_reply":
            # 非白名单玩家但触发了关键词，异步回复
            reply = result.get("reply", "您好，我是AI助手，已记录您的发言")
            self._send_to_mod({"type": "exec", "cmd": "send_chat",
                               "msg": f"/tell {sender} {reply}"})
            return

        # 白名单玩家指令处理
        if action == "command":
            # 硬解析指令（低延迟，不走 LLM）
            self._handle_hardcoded_command(sender, result)
        elif action == "llm":
            # 复杂指令，交给 LLM 处理
            asyncio.create_task(self._handle_llm_command(sender, msg))

    # ============================================================
    # 自动登录消息处理（来自 Mod 端）
    # ============================================================
    def _on_auto_login_event(self, data: dict):
        """处理 Mod 端发来的自动登录事件"""
        asyncio.create_task(self.auto_login.handle_login_event(data, self._send_to_mod))

    # ============================================================
    # 其他事件处理（自动登录等）
    # ============================================================
    def _on_other_event(self, data: dict):
        """处理其他类型的 Mod 事件"""
        msg_type = data.get("type")
        if msg_type == "auto_login":
            self._on_auto_login_event(data)
        else:
            self.log_console.log_system(f"收到其他事件: {msg_type}")

    # ============================================================
    # 硬编码指令处理（延迟 < 200ms）
    # ============================================================
    def _handle_hardcoded_command(self, sender: str, result: dict):
        cmd_type = result.get("cmd_type")
        reply = result.get("reply", "")

        if cmd_type == "follow":
            # 跟随发送者
            self._send_to_mod({"type": "exec", "cmd": "send_chat",
                               "msg": f"/tell {sender} {reply}"})
            self._send_to_mod({"type": "exec", "cmd": "follow",
                               "uuid": result.get("uuid", ""),
                               "distance": 3.0})
            self.log_console.log_action(f"跟随玩家: {sender}")

        elif cmd_type == "tpa":
            # 发送 TPA 请求
            self._send_to_mod({"type": "exec", "cmd": "send_chat",
                               "msg": f"/tpa {sender}"})
            self._send_to_mod({"type": "exec", "cmd": "send_chat",
                               "msg": f"/tell {sender} {reply}"})
            self.log_console.log_action(f"向 {sender} 发送 TPA 请求")

        elif cmd_type == "stop":
            # 停止所有动作
            self._send_to_mod({"type": "exec", "cmd": "stop"})
            self._send_to_mod({"type": "exec", "cmd": "send_chat",
                               "msg": f"/tell {sender} {reply}"})
            self.log_console.log_action("停止所有动作")

        elif cmd_type == "attack":
            # 攻击目标
            self._send_to_mod({"type": "exec", "cmd": "attack"})
            self._send_to_mod({"type": "exec", "cmd": "send_chat",
                               "msg": f"/tell {sender} {reply}"})
            self.log_console.log_action(f"攻击目标（由 {sender} 指令）")

        elif cmd_type == "goto":
            # 前往坐标
            x = result.get("x", 0)
            y = result.get("y", -1)
            z = result.get("z", 0)
            self._send_to_mod({"type": "exec", "cmd": "goto",
                               "x": x, "y": y, "z": z})
            self._send_to_mod({"type": "exec", "cmd": "send_chat",
                               "msg": f"/tell {sender} {reply}"})
            self.log_console.log_action(f"前往坐标 ({x}, {y}, {z})")

        elif cmd_type == "eat":
            # 强制进食
            self._send_to_mod({"type": "exec", "cmd": "use_item"})
            self._send_to_mod({"type": "exec", "cmd": "send_chat",
                               "msg": f"/tell {sender} {reply}"})
            self.log_console.log_action("强制进食")

    # ============================================================
    # LLM 指令处理（异步，用于复杂任务）
    # ============================================================
    async def _handle_llm_command(self, sender: str, msg: str):
        self.log_console.log_system(f"LLM 处理中: {sender} 说 '{msg}'")

        # 构建系统提示
        state = self.current_state
        system_prompt = self._build_system_prompt(state, sender, msg)

        try:
            # 调用 LLM
            llm_response = await self.llm_router.query(system_prompt)

            # 解析 LLM 输出
            tasks = self.task_builder.parse(llm_response)

            if tasks:
                for task in tasks:
                    self._send_to_mod(task)
                self.log_console.log_action(
                    f"LLM 生成了 {len(tasks)} 个任务")
            else:
                # 纯聊天回复
                self._send_to_mod({"type": "exec", "cmd": "send_chat",
                                   "msg": f"/tell {sender} {llm_response}"})
                self.log_console.log_action(f"LLM 回复: {llm_response[:50]}...")

        except Exception as e:
            self.log_console.log_error(f"LLM 处理失败: {e}")
            self._send_to_mod({"type": "exec", "cmd": "send_chat",
                               "msg": f"/tell {sender} 抱歉，我暂时无法处理这个请求"})

    def _build_system_prompt(self, state: dict, sender: str, msg: str) -> str:
        """构建注入给 LLM 的上下文 Prompt（含生存分析）"""
        # 使用 SurvivalAdvisor 构建增强上下文
        return self.survival_advisor.build_enhanced_context(state, sender, msg)

    # ============================================================
    # 管理员命令处理
    # ============================================================
    def _on_admin_command(self, cmd_line: str):
        """管理员在终端输入的命令"""
        parts = cmd_line.strip().split()
        if not parts:
            return

        cmd = parts[0].lower()

        if cmd == "move" and len(parts) >= 4:
            x, y, z = float(parts[1]), float(parts[2]), float(parts[3])
            self._send_to_mod({"type": "exec", "cmd": "goto",
                               "x": x, "y": y, "z": z})
            self.log_console.log_action(f"强制寻路至 ({x}, {y}, {z})")

        elif cmd == "attack":
            self._send_to_mod({"type": "exec", "cmd": "attack"})
            self.log_console.log_action("强制攻击最近实体")

        elif cmd == "inventory":
            self._send_to_mod({"type": "exec", "cmd": "inventory"})
            self.log_console.log_action("请求背包内容")

        elif cmd == "say" and len(parts) >= 2:
            msg = " ".join(parts[1:])
            self._send_to_mod({"type": "exec", "cmd": "send_chat", "msg": msg})
            self.log_console.log_action(f"发送聊天: {msg}")

        elif cmd == "whitelist":
            if len(parts) >= 3 and parts[1] == "add":
                name = parts[2]
                if name not in self.whitelist:
                    self.whitelist.append(name)
                    self._save_whitelist()
                    self.command_parser.whitelist = self.whitelist
                    self.log_console.log_system(f"已添加白名单: {name}")
                else:
                    self.log_console.log_system(f"{name} 已在白名单中")
            elif len(parts) >= 3 and parts[1] == "remove":
                name = parts[2]
                if name in self.whitelist:
                    self.whitelist.remove(name)
                    self._save_whitelist()
                    self.command_parser.whitelist = self.whitelist
                    self.log_console.log_system(f"已移除白名单: {name}")
            elif len(parts) >= 2 and parts[1] == "list":
                self.log_console.log_system(f"白名单: {', '.join(self.whitelist)}")

        elif cmd == "status":
            s = self.current_state
            self.log_console.log_system(
                f"AI状态 - 坐标:({s.get('x',0)}, {s.get('y',0)}, {s.get('z',0)}) "
                f"血量:{s.get('health',0)} 饥饿:{s.get('hunger',0)} "
                f"维度:{s.get('dimension','?')} 目标:{s.get('target_entity','空')}"
            )

        elif cmd == "stop":
            self._send_to_mod({"type": "exec", "cmd": "stop"})
            self.quest_engine.stop()
            self.log_console.log_action("强制停止所有动作和任务链")

        elif cmd == "build" and len(parts) >= 2:
            # 建造指令: build <pattern> [x y z]
            pattern = parts[1]
            if len(parts) >= 5:
                x, y, z = int(parts[2]), int(parts[3]), int(parts[4])
            else:
                s = self.current_state
                x, y, z = int(s.get("x", 0)), int(s.get("y", 0)), int(s.get("z", 0))
            self._send_to_mod({"type": "exec", "cmd": "build_pattern",
                               "pattern": pattern, "start_x": x,
                               "start_y": y, "start_z": z})
            self.log_console.log_action(f"建造模式: {pattern} 起点 ({x}, {y}, {z})")

        elif cmd == "quest" and len(parts) >= 2:
            # 任务链指令: quest <name> | quest status | quest stop
            sub = parts[1]
            if sub == "status":
                progress = self.quest_engine.get_progress()
                self.log_console.log_system(
                    f"任务链: {progress.get('quest', '无')} "
                    f"进度: {progress.get('completed', 0)}/"
                    f"{progress.get('total_steps', 0)} "
                    f"({progress.get('progress_percent', 0)}%)"
                )
            elif sub == "stop":
                self.quest_engine.stop()
                self.log_console.log_action("任务链已停止")
            else:
                if self.quest_engine.load_quest(sub):
                    asyncio.create_task(self.quest_engine.start())
                    self.log_console.log_action(f"启动任务链: {sub}")
                else:
                    self.log_console.log_error(f"任务链不存在: {sub}")

        elif cmd == "sleep":
            self._send_to_mod({"type": "exec", "cmd": "sleep"})
            self.log_console.log_action("尝试睡觉")

        elif cmd == "password" and len(parts) >= 2:
            # 密码管理: password <server_ip>
            server = parts[1]
            pw = self.auto_login.get_password(server)
            if pw:
                self.log_console.log_system(f"服务器 {server} 密码: {pw}")
            else:
                new_pw = self.auto_login.generate_password()
                self.log_console.log_system(f"服务器 {server} 无记录，生成新密码: {new_pw}")

        elif cmd == "exit" or cmd == "quit":
            self.log_console.log_system("正在关闭...")
            self._running = False

        else:
            self.log_console.log_error(f"未知命令: {cmd}")
            self.log_console.log_system(
                "可用命令: move <x> <y> <z> | attack | inventory | "
                "say <msg> | whitelist add/remove <name> | whitelist list | "
                "build <pattern> [x y z] | quest <name/status/stop> | "
                "sleep | password <server> | status | stop | exit"
            )

    # ============================================================
    # 向 Mod 发送指令
    # ============================================================
    def _send_to_mod(self, data: dict):
        """通过 WebSocket 向 Mod 下发指令"""
        if self.ws_server and self.ws_server.has_client():
            self.ws_server.send_to_mod(json.dumps(data, ensure_ascii=False))
        else:
            self.log_console.log_error("Mod 未连接，无法发送指令")

    # ============================================================
    # 启动
    # ============================================================
    async def run(self):
        self._running = True
        self.log_console.log_system("=" * 50)
        self.log_console.log_system("  Project EasyAI - AI 后端启动中...")
        self.log_console.log_system(f"  AI 名称: {self.config.get('ai_name', 'EasyAI')}")
        self.log_console.log_system(f"  LLM 模式: {self.config.get('llm_mode', 'offline')}")
        self.log_console.log_system(f"  WebSocket 端口: {self.ws_port}")
        self.log_console.log_system("=" * 50)

        # 启动 WebSocket 服务器
        ws_task = asyncio.create_task(self.ws_server.start())
        self.log_console.log_system(
            f"[系统] 本地端口 {self.ws_port} 监听中，等待 Mod 连接...")

        # 启动 WebUI 服务
        web_task = asyncio.create_task(self.web_server.start())
        self.log_console.log_system(
            f"[系统] WebUI 控制台: http://127.0.0.1:{self.web_port}")

        # 在独立线程中启动命令控制台
        cmd_thread = threading.Thread(
            target=self.cmd_console.run, daemon=True, name="CmdConsole"
        )
        cmd_thread.start()

        # 等待关闭信号
        try:
            while self._running:
                await asyncio.sleep(0.5)
        except KeyboardInterrupt:
            self.log_console.log_system("收到 Ctrl+C，正在关闭...")

        # 清理
        await self.ws_server.stop()
        await self.web_server.stop()
        self.failure_repo.close()
        self.log_console.log_system("Project EasyAI 已关闭")


# ============================================================
# 程序入口
# ============================================================
def main():
    backend = EasyAIBackend()
    try:
        asyncio.run(backend.run())
    except KeyboardInterrupt:
        print("\n已退出")


if __name__ == "__main__":
    main()
