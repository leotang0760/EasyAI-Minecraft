#!/usr/bin/env python3
# ============================================================
# LLMRouter - 大语言模型统一接口
# ============================================================
# 职责：
#   1. 统一封装 OpenAI API 和 Ollama 本地模型的调用
#   2. 根据 config/settings.json 的 llm_mode 自动选择后端
#   3. 提供 async query() 接口，支持异步调用
#   4. 支持离线模式（Ollama）和在线模式（OpenAI）
#
# 设计说明：
#   - 离线模式使用 Ollama 本地部署的模型（如 llama3.1），
#     不依赖任何付费 API，零成本运行。
#   - 在线模式使用 OpenAI API，需要配置 API Key。
#   - 两种模式返回统一格式的文本，上层无需关心底层差异。
# ============================================================

import json
import logging
from typing import Optional

logger = logging.getLogger("EasyAI/LLM")


class LLMRouter:
    """LLM 统一路由器"""

    def __init__(self, config: dict):
        self.mode = config.get("llm_mode", "offline")  # "offline" or "online"

        # Ollama 配置
        self.ollama_host = config.get("ollama_host", "http://127.0.0.1:11434")
        self.ollama_model = config.get("ollama_model", "llama3.1")

        # OpenAI 配置
        self.openai_api_key = config.get("openai_api_key", "")
        self.openai_model = config.get("openai_model", "gpt-4o-mini")

        # 系统提示前缀（固定注入）
        self.system_prefix = (
            "你是一个 Minecraft 游戏中的 AI 玩家助手，名叫 EasyAI。"
            "你能操控游戏角色进行移动、战斗、交互和建造。"
            "请根据当前游戏状态和玩家指令，输出 JSON 格式的决策。"
            "不要输出 JSON 以外的内容。"
        )

        logger.info(
            f"LLM 路由器初始化: mode={self.mode}, "
            f"model={self.ollama_model if self.mode == 'offline' else self.openai_model}"
        )

    # ============================================================
    # 查询 LLM（统一入口）
    # ============================================================
    async def query(self, prompt: str) -> str:
        """
        向 LLM 发送查询并返回文本响应

        Args:
            prompt: 完整的提示词（已包含上下文）

        Returns:
            LLM 的文本响应
        """
        if self.mode == "offline":
            return await self._query_ollama(prompt)
        elif self.mode == "online":
            return await self._query_openai(prompt)
        else:
            logger.warning(f"未知 LLM 模式: {self.mode}，回退到离线")
            return await self._query_ollama(prompt)

    # ============================================================
    # Ollama 本地模型调用
    # ============================================================
    async def _query_ollama(self, prompt: str) -> str:
        """
        通过 HTTP 调用 Ollama REST API
        端点: POST {host}/api/generate
        """
        import aiohttp

        url = f"{self.ollama_host}/api/generate"
        payload = {
            "model": self.ollama_model,
            "prompt": f"{self.system_prefix}\n\n{prompt}",
            "stream": False,
            "options": {
                "temperature": 0.7,
                "top_p": 0.9,
                "num_predict": 512,
            },
        }

        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(url, json=payload, timeout=aiohttp.ClientTimeout(total=30)) as resp:
                    if resp.status != 200:
                        error_text = await resp.text()
                        logger.error(f"Ollama 返回 {resp.status}: {error_text}")
                        return '{"type":"chat","reply":"抱歉，我的大脑暂时无法响应"}'

                    data = await resp.json()
                    response_text = data.get("response", "").strip()
                    logger.info(f"Ollama 响应: {response_text[:100]}...")
                    return response_text

        except aiohttp.ClientConnectorError:
            logger.error(f"无法连接 Ollama 服务: {self.ollama_host}")
            logger.error("请确保 Ollama 已启动并运行 (ollama serve)")
            return '{"type":"chat","reply":"大脑离线，请检查 Ollama 服务"}'
        except Exception as e:
            logger.error(f"Ollama 调用异常: {e}")
            return '{"type":"chat","reply":"内部错误"}'

    # ============================================================
    # OpenAI API 调用
    # ============================================================
    async def _query_openai(self, prompt: str) -> str:
        """
        通过 OpenAI Chat Completions API 调用
        """
        import aiohttp

        url = "https://api.openai.com/v1/chat/completions"
        headers = {
            "Authorization": f"Bearer {self.openai_api_key}",
            "Content-Type": "application/json",
        }
        payload = {
            "model": self.openai_model,
            "messages": [
                {"role": "system", "content": self.system_prefix},
                {"role": "user", "content": prompt},
            ],
            "temperature": 0.7,
            "max_tokens": 512,
        }

        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(url, json=payload, headers=headers,
                                        timeout=aiohttp.ClientTimeout(total=30)) as resp:
                    if resp.status != 200:
                        error_text = await resp.text()
                        logger.error(f"OpenAI 返回 {resp.status}: {error_text}")
                        return '{"type":"chat","reply":"API 调用失败"}'

                    data = await resp.json()
                    response_text = data["choices"][0]["message"]["content"].strip()
                    logger.info(f"OpenAI 响应: {response_text[:100]}...")
                    return response_text

        except Exception as e:
            logger.error(f"OpenAI 调用异常: {e}")
            return '{"type":"chat","reply":"内部错误"}'

    # ============================================================
    # 同步查询（用于非异步上下文）
    # ============================================================
    def query_sync(self, prompt: str) -> str:
        """同步版本的查询（阻塞调用）"""
        import asyncio
        return asyncio.run(self.query(prompt))
