#!/usr/bin/env python3
# ============================================================
# WebServer - WebUI HTTP + WebSocket 服务
# ============================================================
# 职责：
#   1. 提供 WebUI 静态文件服务 (HTTP :8080)
#   2. 提供 WebSocket 实时数据推送 (/ws)
#   3. 接收 WebUI 命令并转发给后端
#   4. 广播游戏状态、聊天、日志到所有 WebUI 客户端
# ============================================================

import asyncio
import json
import logging
from pathlib import Path
from typing import Callable, Optional, Set

from aiohttp import web

logger = logging.getLogger("EasyAI/WebUI")

STATIC_DIR = Path(__file__).parent / "static"


class WebServer:
    """WebUI HTTP + WebSocket 服务"""

    def __init__(self, port: int = 8080,
                 on_command: Optional[Callable] = None,
                 get_state: Optional[Callable] = None,
                 get_config: Optional[Callable] = None,
                 get_whitelist: Optional[Callable] = None,
                 get_passwords: Optional[Callable] = None,
                 get_quest_progress: Optional[Callable] = None,
                 on_whitelist_update: Optional[Callable] = None,
                 on_config_update: Optional[Callable] = None):
        self.port = port
        self.on_command = on_command
        self.get_state = get_state
        self.get_config = get_config
        self.get_whitelist = get_whitelist
        self.get_passwords = get_passwords
        self.get_quest_progress = get_quest_progress
        self.on_whitelist_update = on_whitelist_update
        self.on_config_update = on_config_update

        self._app = web.Application()
        self._runner: Optional[web.AppRunner] = None
        self._ws_clients: Set[web.WebSocketResponse] = set()

        self._setup_routes()

    def _setup_routes(self):
        self._app.router.add_get("/", self._index)
        self._app.router.add_get("/ws", self._websocket)
        self._app.router.add_get("/api/state", self._api_state)
        self._app.router.add_get("/api/config", self._api_config)
        self._app.router.add_post("/api/config", self._api_config_update)
        self._app.router.add_get("/api/whitelist", self._api_whitelist)
        self._app.router.add_post("/api/whitelist", self._api_whitelist_update)
        self._app.router.add_get("/api/passwords", self._api_passwords)
        self._app.router.add_get("/api/quest", self._api_quest)
        self._app.router.add_post("/api/command", self._api_command)
        self._app.router.add_static("/static", STATIC_DIR)

    # ============================================================
    # 页面
    # ============================================================
    async def _index(self, request):
        index_file = STATIC_DIR / "index.html"
        if index_file.exists():
            return web.FileResponse(index_file)
        return web.Response(text="WebUI not found", status=404)

    # ============================================================
    # WebSocket — 实时数据推送
    # ============================================================
    async def _websocket(self, request):
        ws = web.WebSocketResponse()
        await ws.prepare(request)
        self._ws_clients.add(ws)
        logger.info(f"WebUI 客户端连接 ({len(self._ws_clients)} 在线)")

        try:
            # 发送初始数据
            await self._send_json(ws, {
                "type": "init",
                "state": self.get_state() if self.get_state else {},
                "config": self.get_config() if self.get_config else {},
                "whitelist": self.get_whitelist() if self.get_whitelist else [],
                "quest": self.get_quest_progress() if self.get_quest_progress else {},
            })

            async for msg in ws:
                if msg.type == web.WSMsgType.TEXT:
                    try:
                        data = json.loads(msg.data)
                        await self._handle_ws_message(ws, data)
                    except json.JSONDecodeError:
                        pass
                elif msg.type == web.WSMsgType.ERROR:
                    break
        finally:
            self._ws_clients.discard(ws)
            logger.info(f"WebUI 客户端断开 ({len(self._ws_clients)} 在线)")

        return ws

    async def _handle_ws_message(self, ws, data):
        """处理 WebUI 发来的消息"""
        msg_type = data.get("type")
        if msg_type == "command" and self.on_command:
            cmd = data.get("cmd", "")
            self.on_command(cmd)
        elif msg_type == "whitelist_update" and self.on_whitelist_update:
            self.on_whitelist_update(data.get("whitelist", []))
        elif msg_type == "config_update" and self.on_config_update:
            self.on_config_update(data.get("config", {}))

    # ============================================================
    # REST API
    # ============================================================
    async def _api_state(self, request):
        state = self.get_state() if self.get_state else {}
        return web.json_response(state)

    async def _api_config(self, request):
        config = self.get_config() if self.get_config else {}
        return web.json_response(config)

    async def _api_config_update(self, request):
        data = await request.json()
        if self.on_config_update:
            self.on_config_update(data)
        return web.json_response({"ok": True})

    async def _api_whitelist(self, request):
        wl = self.get_whitelist() if self.get_whitelist else []
        return web.json_response({"whitelist": wl})

    async def _api_whitelist_update(self, request):
        data = await request.json()
        if self.on_whitelist_update:
            self.on_whitelist_update(data.get("whitelist", []))
        return web.json_response({"ok": True})

    async def _api_passwords(self, request):
        pw = self.get_passwords() if self.get_passwords else {}
        return web.json_response(pw)

    async def _api_quest(self, request):
        progress = self.get_quest_progress() if self.get_quest_progress else {}
        return web.json_response(progress)

    async def _api_command(self, request):
        data = await request.json()
        cmd = data.get("cmd", "")
        if self.on_command and cmd:
            self.on_command(cmd)
            return web.json_response({"ok": True, "cmd": cmd})
        return web.json_response({"ok": False, "error": "empty command"}, status=400)

    # ============================================================
    # 广播 — 推送实时数据到所有 WebUI 客户端
    # ============================================================
    async def broadcast(self, data: dict):
        """向所有连接的 WebUI 客户端广播消息"""
        if not self._ws_clients:
            return
        dead = set()
        for ws in self._ws_clients:
            try:
                await self._send_json(ws, data)
            except Exception:
                dead.add(ws)
        self._ws_clients -= dead

    async def _send_json(self, ws, data):
        await ws.send_str(json.dumps(data, ensure_ascii=False))

    # ============================================================
    # 便捷广播方法
    # ============================================================
    async def broadcast_state(self, state: dict):
        await self.broadcast({"type": "state", "data": state})

    async def broadcast_chat(self, sender: str, msg: str):
        await self.broadcast({"type": "chat", "sender": sender, "msg": msg})

    async def broadcast_log(self, level: str, msg: str):
        await self.broadcast({"type": "log", "level": level, "msg": msg})

    async def broadcast_action(self, msg: str):
        await self.broadcast({"type": "action", "msg": msg})

    # ============================================================
    # 启动 / 停止
    # ============================================================
    async def start(self):
        self._runner = web.AppRunner(self._app)
        await self._runner.setup()
        site = web.TCPSite(self._runner, "127.0.0.1", self.port)
        await site.start()
        logger.info(f"WebUI 已启动: http://127.0.0.1:{self.port}")

    async def stop(self):
        for ws in self._ws_clients:
            await ws.close()
        if self._runner:
            await self._runner.cleanup()
