#!/usr/bin/env python3
# ============================================================
# AutoLoginManager - 自动注册/登录管理 (Python 端)
# ============================================================
# 职责：
#   1. 接收 Mod 端的自动登录请求
#   2. 按 server_ip 存储/查询密码
#   3. 生成 16 位强密码（大小写+数字+符号）
#   4. 写入 config/passwords.json
# ============================================================

import json
import secrets
import string
import logging
import asyncio
from pathlib import Path
from typing import Optional

logger = logging.getLogger("EasyAI/AutoLogin")

# 项目根目录
PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
PASSWORDS_FILE = PROJECT_ROOT / "config" / "passwords.json"


class AutoLoginManager:
    """自动注册/登录密码管理器"""

    # 密码字符集
    PASSWORD_CHARS = string.ascii_letters + string.digits + "!@#$%^&*"

    def __init__(self):
        self._passwords = self._load_passwords()
        logger.info(f"自动登录管理器已加载，已存储 {len(self._passwords)} 个服务器密码")

    def _load_passwords(self) -> dict:
        """从 JSON 文件加载密码本"""
        if PASSWORDS_FILE.exists():
            with open(PASSWORDS_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        return {}

    def _save_passwords(self):
        """保存密码本到 JSON 文件"""
        PASSWORDS_FILE.parent.mkdir(parents=True, exist_ok=True)
        with open(PASSWORDS_FILE, "w", encoding="utf-8") as f:
            json.dump(self._passwords, f, ensure_ascii=False, indent=2)

    def generate_password(self, length: int = 16) -> str:
        """
        生成强密码（使用 secrets 库，密码学安全）

        Args:
            length: 密码长度（默认 16）

        Returns:
            生成的密码字符串
        """
        # 确保包含每种字符类型
        password = [
            secrets.choice(string.ascii_uppercase),
            secrets.choice(string.ascii_lowercase),
            secrets.choice(string.digits),
            secrets.choice("!@#$%^&*"),
        ]
        # 填充剩余长度
        password += [
            secrets.choice(self.PASSWORD_CHARS) for _ in range(length - 4)
        ]
        # 打乱顺序
        result = list(password)
        for i in range(len(result) - 1, 0, -1):
            j = secrets.randbelow(i + 1)
            result[i], result[j] = result[j], result[i]
        return "".join(result)

    def get_or_create_password(self, server_ip: str) -> str:
        """
        获取服务器密码，如果不存在则生成新密码并存储

        Args:
            server_ip: 服务器 IP 地址

        Returns:
            密码字符串
        """
        # 标准化 IP（去除端口前缀等）
        server_key = self._normalize_ip(server_ip)

        if server_key in self._passwords:
            password = self._passwords[server_key]
            logger.info(f"服务器 {server_key} 已有密码记录")
            return password

        # 生成新密码
        password = self.generate_password()
        self._passwords[server_key] = password
        self._save_passwords()
        logger.info(f"为服务器 {server_key} 生成新密码: {password}")
        logger.info(f"密码已写入 {PASSWORDS_FILE}")
        return password

    def get_password(self, server_ip: str) -> Optional[str]:
        """查询已存储的密码"""
        server_key = self._normalize_ip(server_ip)
        return self._passwords.get(server_key)

    def _normalize_ip(self, ip: str) -> str:
        """标准化服务器 IP"""
        # 去除 / 前缀
        ip = ip.lstrip("/")
        # 去除端口号
        if ":" in ip:
            ip = ip.split(":")[0]
        return ip

    # ============================================================
    # 处理 Mod 端的自动登录消息
    # ============================================================
    async def handle_login_event(self, data: dict, send_to_mod):
        """
        处理来自 Mod 的自动登录事件

        Args:
            data: Mod 发送的 JSON 消息
            send_to_mod: 向 Mod 发送消息的回调函数
        """
        action = data.get("action")
        server = data.get("server", "unknown")

        if action == "register":
            password = data.get("password", "")
            if password:
                # Mod 端已生成密码，存储到本地
                server_key = self._normalize_ip(server)
                self._passwords[server_key] = password
                self._save_passwords()
                logger.info(f"已存储服务器 {server_key} 的密码: {password}")
                logger.info(f"密码已写入 config/passwords.json")

        elif action == "login_query":
            # Mod 请求查询密码
            password = self.get_password(server)
            if password:
                # 下发登录命令
                send_to_mod(json.dumps({
                    "type": "exec",
                    "cmd": "send_chat",
                    "msg": f"/login {password}",
                }))
                logger.info(f"已向 Mod 发送服务器 {server} 的登录密码")
            else:
                logger.warning(f"服务器 {server} 无密码记录，需要先注册")
                # 生成新密码并下发注册命令
                new_password = self.generate_password()
                server_key = self._normalize_ip(server)
                self._passwords[server_key] = new_password
                self._save_passwords()
                logger.info(f"为服务器 {server_key} 生成新密码: {new_password}")

                send_to_mod(json.dumps({
                    "type": "exec",
                    "cmd": "send_chat",
                    "msg": f"/register {new_password} {new_password}",
                }))

                # 200ms 后发送登录命令
                await asyncio.sleep(0.2)
                send_to_mod(json.dumps({
                    "type": "exec",
                    "cmd": "send_chat",
                    "msg": f"/login {new_password}",
                }))
