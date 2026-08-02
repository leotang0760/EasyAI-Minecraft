#!/usr/bin/env python3
# ============================================================
# LogConsole - 日志渲染面板（rich 库美化输出）
# ============================================================
# 职责：
#   1. 使用 rich 库渲染彩色日志（状态、聊天、动作、错误）
#   2. 在终端上栏显示实时状态面板
#   3. 不同类型消息使用不同颜色和图标
# ============================================================

import logging
from datetime import datetime

try:
    from rich.console import Console
    from rich.panel import Panel
    from rich.table import Table
    from rich.text import Text
    from rich.logging import RichHandler
    RICH_AVAILABLE = True
except ImportError:
    RICH_AVAILABLE = False
    print("警告: rich 库未安装，将使用纯文本输出。请运行 pip install rich")


class LogConsole:
    """日志渲染面板"""

    def __init__(self):
        if RICH_AVAILABLE:
            self.console = Console()
            self._setup_logging()
        else:
            self.console = None

        # 最新状态缓存
        self._last_state = {}

    def _setup_logging(self):
        """配置 rich 日志处理器"""
        if not RICH_AVAILABLE:
            return

        # 配置全局 logging 使用 RichHandler
        logging.basicConfig(
            level=logging.DEBUG,
            format="%(message)s",
            datefmt="[%X]",
            handlers=[RichHandler(
                console=self.console,
                show_path=False,
                rich_tracebacks=True,
            )],
        )

    # ============================================================
    # 状态日志（蓝色）
    # ============================================================
    def log_state(self, state: dict):
        """渲染游戏状态面板"""
        self._last_state = state
        if not RICH_AVAILABLE:
            print(f"[状态] {state}")
            return

        # 创建状态表格
        table = Table(show_header=False, box=None, padding=(0, 1))
        table.add_column("key", style="cyan", width=10)
        table.add_column("value", style="white")

        table.add_row("坐标", f"({state.get('x', 0)}, {state.get('y', 0)}, {state.get('z', 0)})")
        table.add_row("血量", f"{state.get('health', 0)}/20")
        table.add_row("饥饿", f"{state.get('hunger', 0)}/20")
        table.add_row("维度", str(state.get('dimension', '?')))
        table.add_row("目标", str(state.get('target_entity', '空')))
        table.add_row("着地", str(state.get('on_ground', True)))

        panel = Panel(
            table,
            title="[bold blue]EasyAI AI 状态[/bold blue]",
            border_style="blue",
            width=40,
        )
        # 注意：在实际分屏 UI 中，这里会更新上栏面板
        # 当前简化版直接打印
        self.console.print(panel, overflow="ellipsis")

    # ============================================================
    # 聊天日志（绿色）
    # ============================================================
    def log_chat(self, sender: str, msg: str):
        """渲染聊天消息"""
        timestamp = datetime.now().strftime("%H:%M:%S")
        if not RICH_AVAILABLE:
            print(f"[{timestamp}] [聊天] <{sender}> {msg}")
            return

        self.console.print(
            f"[green][{timestamp}] [聊天][/green] "
            f"[bold green]<{sender}>[/bold green] {msg}"
        )

    # ============================================================
    # 动作日志（黄色）
    # ============================================================
    def log_action(self, action: str):
        """渲染 AI 执行的动作"""
        timestamp = datetime.now().strftime("%H:%M:%S")
        if not RICH_AVAILABLE:
            print(f"[{timestamp}] [动作] {action}")
            return

        self.console.print(
            f"[yellow][{timestamp}] [动作][/yellow] {action}"
        )

    # ============================================================
    # 系统日志（灰色）
    # ============================================================
    def log_system(self, msg: str):
        """渲染系统消息"""
        timestamp = datetime.now().strftime("%H:%M:%S")
        if not RICH_AVAILABLE:
            print(f"[{timestamp}] [系统] {msg}")
            return

        self.console.print(
            f"[dim][{timestamp}] [系统][/dim] {msg}"
        )

    # ============================================================
    # 错误日志（红色）
    # ============================================================
    def log_error(self, msg: str):
        """渲染错误消息"""
        timestamp = datetime.now().strftime("%H:%M:%S")
        if not RICH_AVAILABLE:
            print(f"[{timestamp}] [错误] {msg}")
            return

        self.console.print(
            f"[red][{timestamp}] [错误][/red] [bold red]{msg}[/bold red]"
        )

    # ============================================================
    # 战斗日志（红色加粗）
    # ============================================================
    def log_combat(self, msg: str):
        """渲染战斗事件"""
        timestamp = datetime.now().strftime("%H:%M:%S")
        if not RICH_AVAILABLE:
            print(f"[{timestamp}] [战斗] {msg}")
            return

        self.console.print(
            f"[red bold][{timestamp}] [战斗][/red bold] {msg}"
        )
