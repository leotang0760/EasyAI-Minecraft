#!/usr/bin/env python3
# ============================================================
# CmdConsole - 命令输入控制台（prompt_toolkit）
# ============================================================
# 职责：
#   1. 提供管理员命令输入界面（终端下栏）
#   2. 支持命令历史记录（上下键浏览）
#   3. 支持自动补全
#   4. 将输入命令转发给回调函数处理
# ============================================================

from typing import Callable

try:
    from prompt_toolkit import PromptSession
    from prompt_toolkit.history import InMemoryHistory
    from prompt_toolkit.auto_suggest import AutoSuggestFromHistory
    from prompt_toolkit.completion import WordCompleter
    PROMPT_TOOLKIT_AVAILABLE = True
except ImportError:
    PROMPT_TOOLKIT_AVAILABLE = False
    print("警告: prompt_toolkit 未安装，将使用基础 input()。请运行 pip install prompt_toolkit")


def _create_session(completer):
    """安全创建 PromptSession，在不支持的终端回退为 None"""
    if not PROMPT_TOOLKIT_AVAILABLE:
        return None
    try:
        return PromptSession(
            history=InMemoryHistory(),
            auto_suggest=AutoSuggestFromHistory(),
            completer=completer,
        )
    except Exception:
        # NoConsoleScreenBufferError: Git Bash / IDE 终端等非 cmd 环境
        return None


class CmdConsole:
    """命令输入控制台"""

    # 支持的命令列表（用于自动补全）
    COMMANDS = [
        "move", "attack", "inventory", "say", "whitelist",
        "status", "stop", "exit", "quit", "build", "quest",
        "sleep", "password",
        "whitelist add", "whitelist remove", "whitelist list",
        "quest status", "quest stop",
        "build wall", "build floor", "build tower", "build house", "build circle",
    ]

    def __init__(self, on_command: Callable[[str], None]):
        self.on_command = on_command

        if PROMPT_TOOLKIT_AVAILABLE:
            completer = WordCompleter(
                self.COMMANDS,
                ignore_case=True,
                sentence=True,
            )
            self.session = _create_session(completer)
        else:
            self.session = None

    # ============================================================
    # 运行命令控制台（阻塞，在独立线程中调用）
    # ============================================================
    def run(self):
        """主循环：读取用户输入并转发"""
        print("\n" + "=" * 50)
        print("  Project EasyAI 管理员控制台")
        print("  输入 'help' 查看可用命令")
        print("=" * 50 + "\n")

        while True:
            try:
                if self.session is not None:
                    # 使用 prompt_toolkit（支持历史记录和自动补全）
                    user_input = self.session.prompt("easyai> ")
                else:
                    # 回退到基础 input()
                    user_input = input("easyai> ")

                user_input = user_input.strip()
                if not user_input:
                    continue

                # 特殊命令处理
                if user_input.lower() == "help":
                    self._print_help()
                    continue

                # 转发给回调函数
                self.on_command(user_input)

                # exit/quit 命令退出循环
                if user_input.lower() in ("exit", "quit"):
                    break

            except EOFError:
                # Ctrl+D
                print("\n退出控制台")
                break
            except KeyboardInterrupt:
                # Ctrl+C
                print("\n（输入 exit 退出）")
                continue
            except Exception as e:
                print(f"命令执行错误: {e}")

    # ============================================================
    # 打印帮助信息
    # ============================================================
    def _print_help(self):
        help_text = """
可用命令:
  move <x> <y> <z>        强制 AI 寻路至该坐标
  attack                  强制攻击屏幕中心最近的实体
  inventory               打印当前 AI 背包全部物品
  say <内容>              强制 AI 在游戏内发送聊天消息
  whitelist add <名字>    动态添加白名单
  whitelist remove <名字> 移除白名单
  whitelist list          查看白名单
  build <pattern> [x y z] 执行建造模式 (wall/floor/tower/house/circle)
  quest <name>            启动任务链 (如: quest end_quest)
  quest status            查看任务链进度
  quest stop              停止任务链
  sleep                   尝试睡觉（跳过夜晚）
  password <server_ip>    查看或生成服务器密码
  status                  打印 AI 当前坐标、血量、目标状态
  stop                    强制停止所有动作
  exit / quit             退出程序
        """
        print(help_text)
