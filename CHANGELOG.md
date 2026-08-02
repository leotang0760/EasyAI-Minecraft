# Changelog

All notable changes to EasyAI-Minecraft will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/lang/zh-CN/).

---

## [2.0.0] - 2026-08-02

> 大版本升级：Minecraft 1.20.1 → 1.21.1，Java 17 → 21，新增 8 大功能模块

### 升级 (Changed)
- **Minecraft** 1.20.1 → **1.21.1**
- **Java** 17 → **21**
- **Fabric Loom** 1.4 → **1.7-SNAPSHOT**
- **Fabric Loader** 0.15.x → **0.16.0**
- **Fabric API** 0.91.x → **0.102.0+1.21.1**
- **Yarn Mappings** 1.20.1+build.10 → **1.21.1+build.3**
- Mixin `compatibilityLevel` JAVA_17 → JAVA_21
- `ChatHudMixin` 注入签名适配 1.21.x（使用 `addMessage(Text)` 简单重载保证跨版本兼容）
- `ItemStack.isFood()` → `stack.contains(DataComponentTypes.FOOD)`（1.21 DataComponent API）
- `KeyBinding.setKeyState(KB, bool)` → `keyBinding.setPressed(bool)`（1.21 API 变化）
- `Entity.isTouchingLava()` → `Entity.isInLava()`（1.21 方法重命名）
- `ClientWorld.getEntity(UUID)` → 遍历 `world.getEntities()`（1.21 方法移除）
- `AutoLoginManager` 从 HUD 标题检测改为聊天消息监听（1.21 HUD API 变化）
- `BaritoneIntegration.goto()` → `goTo()`（goto 是 Java 保留关键字）
- Baritone 依赖从外部 Maven 仓库改为内置 API 桩代码（编译独立，运行时自动检测）
- WebSocket 类 `HermesWebSocketClient` → `EasyAIWebSocketClient`（避免与父类重名）

### 新增 (Added)
- **自动注册/登录** (`AutoLoginManager.java` / `auto_login.py`)
  - 正则匹配服务器注册/登录提示（中英文兼容）
  - `SecureRandom` 生成 16 位强密码（大小写+数字+符号）
  - 按 server_ip 存储密码到 `passwords.json`
  - 200ms 间隔执行 `/register` → `/login` 命令序列
- **生存直觉系统** (`SurvivalInstinct.java` / `survival_advisor.py`)
  - 雷暴天气自动寻找遮挡物
  - 夜间自动搜索床铺并睡觉
  - 着火/岩浆自动放置水桶自救
  - 中毒/凋零自动喝牛奶解毒
  - 4 级优先级分析（CRITICAL/HIGH/MEDIUM/LOW）
- **高级战斗 AI** (`CombatAI.java`)
  - 目标威胁等级排序（苦力怕 > 骷髅 > 僵尸）
  - 骷髅对策：Z 字形走位接近
  - 苦力怕对策：保持安全距离，远程攻击
  - 箭矢检测与闪避（速度向量点积判断来袭方向）
  - 武器优先级自动切换
- **自动物品拾取** (`ItemPicker.java`)
  - 6 格检测范围，3 格拾取范围
  - 经验球优先拾取
  - 危险位置检测（岩浆/悬崖）自动跳过
- **蓝图建造系统** (`BuildingManager.java`)
  - 5 种预设模式：墙壁 / 地板 / 塔楼 / 房屋 / 圆圈
  - 分层建造，自动切换方块
  - 实时进度报告
- **任务链引擎** (`quest_engine.py`)
  - JSON 脚本驱动，从 `tasks/` 目录加载
  - 步骤化执行 + 失败回滚
  - LLM 替代方案生成（当脚本步骤失败时）
  - 示例任务：`tasks/end_quest.json`（末地任务链）
- **生存策略顾问** (`survival_advisor.py`)
  - 实时分析血量 / 饥饿 / 维度 / 高度 / 周围实体
  - 生成增强 LLM 上下文（注入生存警告 + avoid_zone 标记）
- **Python 管理命令**
  - `build` — 蓝图建造指令
  - `quest` — 任务链管理
  - `sleep` — 强制睡觉
  - `password` — 密码管理
- **Gradle Wrapper**（`gradlew` / `gradlew.bat`）一键构建
- **DEV.md** 完整开发文档（架构设计 / 构建指南 / API 参考 / 调试技巧 / 迁移表）
- **LICENSE** MIT 开源协议
- **.gitignore** 标准 Java + Python 排除规则

### 修复 (Fixed)
- 所有 11 个 Python 文件的 `//` 注释语法错误（Java 风格 → Python `#`）
- `WebSocketClient` 类名与父类冲突导致编译失败
- `send()` vs `sendMessage()` 线程安全调用
- `inputsProperty` → `inputs.property` Gradle API 名称修正

---

## [1.0.0] - 2026-08-02

> 首次发布：Minecraft 1.20.1，Java 17，基础架构搭建

### 新增 (Added)
- **核心架构**：云端大脑（Python）+ 本地躯体（Java Mod），WebSocket 通信
- **Mixin 注入**：`ChatHudMixin` 聊天拦截，`ClientPlayerMixin` 玩家 Tick 钩子
- **Baritone 寻路**：`BaritoneIntegration` 封装 goto / follow / escape
- **优先级任务队列**：`ActionQueue` 支持抢占式任务调度
- **键盘/鼠标模拟**：`KeySimulator` 防反作弊按键模拟
- **WebSocket 客户端**：自动重连（5秒间隔）
- **Python 后端**：
  - `ws_endpoint.py` WebSocket 服务器
  - `llm_router.py` Ollama / OpenAI 统一异步接口
  - `command_parser.py` @ 指令正则解析 + 白名单分层
  - `task_builder.py` LLM JSON → Mod 指令流
  - `failure_repo.py` SQLite 失败经验库（空间查询）
  - `log_console.py` rich 彩色日志
  - `cmd_console.py` prompt_toolkit 命令输入
- **Tick 微循环**（50ms）：安全看门狗（血量<8 逃离）、自动进食（饥饿<6）
- **配置文件**：`settings.json` / `whitelist.json` / `passwords.json`
- **启动脚本**：`run.bat` / `run.sh`
- **README.md** 项目文档

---

[2.0.0]: https://github.com/leotang0760/EasyAI-Minecraft/compare/v1.0.0...v2.0.0
[1.0.0]: https://github.com/leotang0760/EasyAI-Minecraft/releases/tag/v1.0.0
