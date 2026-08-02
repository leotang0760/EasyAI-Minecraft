#!/usr/bin/env python3
# ============================================================
# WSServer - WebSocket 服务器（接收 Mod 连接）
# ============================================================
# 职责：
#   1. 监听本地端口 (默认 8765)，等待 Java Mod 连接
#   2. 接收 Mod 上行消息（state 状态 / chat 聊天 / handshake 握手）
#   3. 将消息分发给回调函数处理
#   4. 提供向 Mod 下发指令的接口
#
# 使用库：websockets (asyncio 原生 WebSocket 实现)
# ============================================================

import asyncio
import json
import logging
from typing import Callable, Optional

import websockets
from websockets.exceptions import ConnectionClosed

logger = logging.getLogger("EasyAI/WS")


class WSServer:
    """WebSocket 服务器，桥接 Java Mod 与 Python 大脑"""

    def __init__(
        self,
        port: int = 8765,
        on_state_callback: Optional[Callable] = None,
        on_chat_callback: Optional[Callable] = None,
        on_other_callback: Optional[Callable] = None,
    ):
        self.port = port
        self.on_state_callback = on_state_callback
        self.on_chat_callback = on_chat_callback
        self.on_other_callback = on_other_callback

        # 当前连接的 Mod 客户端
        self._mod_client = None
        self._server = None

    # ============================================================
    # 启动 WebSocket 服务器
    # ============================================================
    async def start(self):
        self._server = await websockets.serve(
            self._handle_connection,
            "127.0.0.1",
            self.port,
            ping_interval=10,
            ping_timeout=5,
        )
        logger.info(f"WebSocket 服务器已启动，监听 127.0.0.1:{self.port}")

        # 保持服务器运行
        await self._server.wait_closed()

    async def stop(self):
        if self._server:
            self._server.close()
            await self._server.wait_closed()
            logger.info("WebSocket 服务器已关闭")

    # ============================================================
    # 处理 Mod 连接
    # ============================================================
    async def _handle_connection(self, websocket, path=None):
        """每个 Mod 连接的回调协程"""
        client_addr = websocket.remote_address
        logger.info(f"Mod 客户端已连接: {client_addr}")
        self._mod_client = websocket

        try:
            async for raw_message in websocket:
                await self._process_message(raw_message)
        except ConnectionClosed:
            logger.warning(f"Mod 客户端断开: {client_addr}")
        except Exception as e:
            logger.error(f"连接异常: {e}")
        finally:
            self._mod_client = None

    # ============================================================
    # 处理上行消息
    # ============================================================
    async def _process_message(self, raw: str):
        """解析 JSON 消息并分发"""
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            logger.error(f"JSON 解析失败: {raw[:100]}")
            return

        msg_type = data.get("type")

        if msg_type == "handshake":
            logger.info(
                f"握手成功: client={data.get('client')}, "
                f"version={data.get('version')}"
            )

        elif msg_type == "state":
            # 游戏状态上报（每秒一次）
            if self.on_state_callback:
                self.on_state_callback(data)

        elif msg_type == "chat":
            # 聊天事件
            if self.on_chat_callback:
                self.on_chat_callback(data)

        elif msg_type == "auto_login":
            # 自动登录事件
            if self.on_other_callback:
                self.on_other_callback(data)

        else:
            logger.warning(f"未知消息类型: {msg_type}")

    # ============================================================
    # 向 Mod 下发指令
    # ============================================================
    def send_to_mod(self, json_str: str):
        """向当前连接的 Mod 发送 JSON 指令"""
        if self._mod_client is None:
            logger.warning("无 Mod 连接，无法发送指令")
            return False

        # 使用 asyncio.run_coroutine_threadsafe 确保线程安全
        try:
            loop = asyncio.get_event_loop()
            if loop.is_running():
                asyncio.run_coroutine_threadsafe(
                    self._mod_client.send(json_str), loop
                )
            else:
                asyncio.run(self._mod_client.send(json_str))
            return True
        except Exception as e:
            logger.error(f"发送指令失败: {e}")
            return False

    def has_client(self) -> bool:
        """检查是否有 Mod 客户端连接"""
        return self._mod_client is not None
