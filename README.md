# EasyAI-Minecraft - Minecraft 具身智能 AI 系统

> **v2.0.0** | **MC 1.21.1** | **Java 21** | **Python 3.10+**
>
> "云端大脑（Python）+ 本地躯体（Java Mod）"架构的 Minecraft AI 玩家助手

AI 不仅能聊天，更能像真人玩家一样操控游戏角色进行移动、战斗、交互和建造。所有物理动作均在客户端本地执行，绝不依赖服务端插件。

---

## v2.0 新功能

- **升级到 Minecraft 1.21.1** (Java 21, Yarn 1.21.1+build.3)
- **自动注册/登录**：正则匹配服务器提示，自动生成 16 位强密码，按 server_ip 存储
- **生存直觉系统**：雷暴寻遮挡、夜间自动睡觉、着火放水桶、中毒喝牛奶
- **高级战斗 AI**：目标优先级排序、骷髅 Z 字走位、苦力怕保持距离、箭矢闪避
- **自动物品拾取**：3 格内掉落物自动捡取，优先经验球，危险位置跳过
- **蓝图建造系统**：预设模式（墙壁/地板/塔/房屋/圆圈），分层建造
- **任务链引擎**：JSON 脚本驱动，步骤执行+失败回滚+LLM 替代方案
- **生存策略顾问**：实时分析血量/饥饿/维度/高度，生成优先级建议
- **增强 LLM 上下文**：注入生存警告+avoid_zone 标记

---

## 目录

- [架构概览](#架构概览)
- [核心功能](#核心功能)
- [项目结构](#项目结构)
- [环境要求](#环境要求)
- [安装步骤](#安装步骤)
- [配置说明](#配置说明)
- [使用方法](#使用方法)
- [通信协议](#通信协议)
- [FAQ](#faq)

---

## 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                    Minecraft 1.21.1 客户端                   │
│  ┌────────────────────────────────────────────────────────┐ │
│  │              Fabric Mod (Java 21)                      │ │
│  │  ┌─────────┐ ┌──────────┐ ┌──────────┐ ┌───────────┐ │ │
│  │  │ Mixin   │ │ Baritone │ │ CombatAI │ │ Survival  │ │ │
│  │  │ 注入    │ │ 寻路引擎 │ │ 战斗AI   │ │ Instinct  │ │ │
│  │  └─────────┘ └──────────┘ └──────────┘ └───────────┘ │ │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────┐ │ │
│  │  │ ActionQ  │ │ KeySim   │ │ ItemPick │ │ Building  │ │ │
│  │  │ +AutoLogin│ │ 防反作弊 │ │ 自动拾取 │ │ Manager  │ │ │
│  │  └──────────┘ └──────────┘ └──────────┘ └───────────┘ │ │
│  │           ┌──────────────────────┐                      │ │
│  │           │  WebSocket Client    │                      │ │
│  │           └──────────┬───────────┘                      │ │
│  └──────────────────────┼──────────────────────────────────┘ │
└─────────────────────────┼────────────────────────────────────┘
                          │ WebSocket ws://127.0.0.1:8765
┌─────────────────────────┼────────────────────────────────────┐
│              Python 后端 (大脑)                               │
│  ┌──────────────────────┴─────────────────────────────────┐  │
│  │              WebSocket Server                           │  │
│  │  ┌──────────┐ ┌─────────────┐ ┌──────────────────────┐ │  │
│  │  │LLM Router│ │CommandParser│ │ SurvivalAdvisor      │ │  │
│  │  │(Ollama/  │ │(@正则+白名单)│ │ (血量/饥饿/维度分析) │ │  │
│  │  │ OpenAI)  │ │             │ │                      │ │  │
│  │  └──────────┘ └─────────────┘ └──────────────────────┘ │  │
│  │  ┌──────────────┐ ┌──────────┐ ┌────────────────────┐  │  │
│  │  │ QuestEngine  │ │AutoLogin │ │ FailureRepo        │  │  │
│  │  │ (任务链引擎) │ │ (密码本) │ │ (SQLite 经验库)    │  │  │
│  │  └──────────────┘ └──────────┘ └────────────────────┘  │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

### 分层决策架构

| 层级 | 位置 | 频率 | 职责 |
|------|------|------|------|
| 底层（微循环） | Java Mod | 每 Tick (50ms) | 安全看门狗、自动进食、战斗AI、物品拾取、生存直觉、自动登录 |
| 中层（战术） | Mod+Python | 事件驱动 | @指令硬解析、任务链脚本、失败回滚 |
| 高层（战略） | Python LLM | 按需唤醒 | 未知任务规划、社交对话、替代方案生成 |

---

## 核心功能

### 1. 智能移动与导航
- Baritone 寻路集成（跨维度，自动避水/岩浆/悬崖）
- `goto(x,y,z)` / `followEntity(UUID)` / `escape()`
- 动态微操：自动跳跃、潜行防坠落、疾跑

### 2. 生存管理
- 自动进食（饱食度<6，优先级：熟牛排>面包>其他）
- 工具自动切换（镐→石头、斧→木头、剑→怪物）
- 物品自动拾取（3 格内，优先经验球，危险位置跳过）

### 3. 高级战斗 AI
- 目标优先级排序（苦力怕>骷髅>僵尸）
- 近战连击（1.6s 冷却，面向目标）
- 骷髅 Z 字形走位接近
- 苦力怕保持距离+弓箭攻击
- 箭矢闪避（检测来袭箭矢自动侧移）

### 4. 生存直觉系统
- 雷暴天气 → 自动寻找遮挡物
- 夜间 → 自动寻找床睡觉
- 着火 → 自动放置水桶
- 中毒/凋零 → 自动喝牛奶
- 血量<8 → 强制逃离

### 5. 自动注册/登录
- 正则匹配 `(?i)(register|login|密码|/reg)`
- 自动生成 16 位强密码（大小写+数字+符号）
- 按 server_ip 存储到 `config/passwords.json`
- 执行序列：检测→200ms→/register→200ms→/login

### 6. 蓝图建造系统
- 预设模式：`wall`(墙壁) / `floor`(地板) / `tower`(塔) / `house`(房屋) / `circle`(圆圈)
- 分层建造，自动切换方块
- 进度上报

### 7. 任务链引擎
- JSON 脚本驱动（`tasks/*.json`）
- 步骤执行+条件检查+失败回滚
- 失败时调用 LLM 生成替代方案

### 8. 双重触发
- **游戏内 @**：`@EasyAI 来` / `@EasyAI 去 100 64 200` / `@EasyAI 建个房子`
- **管理员终端**：`move` / `attack` / `build` / `quest` / `status` / `password`

---

## 项目结构

```
EasyAI_AI/
├── mod/                                    # Fabric Mod (Java 21, MC 1.21.1)
│   ├── src/main/java/com/easyai/
│   │   ├── EasyAIMod.java                  # 主入口 v2.0
│   │   ├── mixin/
│   │   │   ├── ChatHudMixin.java           # 聊天拦截 (1.21签名)
│   │   │   └── ClientPlayerMixin.java      # 玩家 Tick 钩子
│   │   ├── navigation/
│   │   │   └── BaritoneIntegration.java    # Baritone 寻路
│   │   ├── control/
│   │   │   ├── ActionQueue.java            # 优先级任务队列
│   │   │   └── KeySimulator.java           # 键盘/鼠标模拟
│   │   ├── network/
│   │   │   └── EasyAIWebSocketClient.java  # WebSocket 客户端
│   │   ├── survival/                       # ★ 新增
│   │   │   ├── AutoLoginManager.java       # 自动注册/登录
│   │   │   └── SurvivalInstinct.java       # 生存直觉
│   │   ├── combat/                         # ★ 新增
│   │   │   └── CombatAI.java               # 高级战斗AI
│   │   └── interaction/                    # ★ 新增
│   │       ├── ItemPicker.java             # 物品拾取
│   │       └── BuildingManager.java        # 蓝图建造
│   │   └── baritone/api/                   # Baritone API 桩代码（编译期）
│   ├── src/main/resources/
│   │   ├── fabric.mod.json                 # Mod 元数据 (1.21.1)
│   │   └── easyai.mixins.json              # Mixin 配置 (JAVA_21)
│   ├── gradle/wrapper/                     # Gradle Wrapper
│   ├── gradlew / gradlew.bat               # 一键构建脚本
│   ├── build.gradle                        # Fabric Loom 1.7
│   ├── settings.gradle
│   └── gradle.properties                   # MC 1.21.1 / Java 21
├── python/                                 # Python 后端
│   ├── main.py                             # 启动入口 v2.0
│   ├── server/ws_endpoint.py               # WebSocket 服务器
│   ├── brain/
│   │   ├── llm_router.py                   # LLM 统一接口
│   │   ├── command_parser.py               # @指令解析
│   │   ├── task_builder.py                 # 任务流构建
│   │   ├── auto_login.py                   # ★ 密码管理
│   │   ├── quest_engine.py                 # ★ 任务链引擎
│   │   └── survival_advisor.py             # ★ 生存策略顾问
│   ├── memory/failure_repo.py              # SQLite 经验库
│   └── ui/
│       ├── log_console.py                  # rich 日志
│       └── cmd_console.py                  # 命令输入
├── config/
│   ├── settings.json                       # 全局配置
│   ├── whitelist.json                      # 白名单
│   └── passwords.json                      # 密码本
├── tasks/
│   └── end_quest.json                      # 末地任务链
├── logs/
│   ├── debug.log                           # 调试日志
│   └── failures.db                         # SQLite 经验库
├── DEV.md                                  # ★ 开发文档
├── requirements.txt                        # Python 依赖
├── run.bat / run.sh                        # 一键启动
└── README.md                               # 本文件
```

★ = v2.0 新增

---

## 环境要求

### Java Mod
- **Minecraft** 1.21.1
- **Fabric Loader** ≥ 0.16.0
- **Fabric API** (匹配 1.21.1)
- **Baritone** (1.21.1 兼容构建，可选但强烈推荐 — 项目已内置 API 桩代码，编译不依赖外部仓库)
- **Java** 21+ (JDK 21)
- **Gradle** 8.x (由 wrapper 管理)

### Python 后端
- **Python** 3.10+
- 依赖：`websockets`, `aiohttp`, `rich`, `prompt-toolkit`

### LLM 后端（二选一）
- **离线模式**（默认）：[Ollama](https://ollama.ai/) + llama3.1
- **在线模式**：OpenAI API Key

---

## 安装步骤

### 1. 克隆项目
```bash
git clone https://github.com/your-repo/project-easyai.git
cd project-easyai
```

### 2. 安装 Python 依赖
```bash
pip install -r requirements.txt
```

### 3. 构建 Fabric Mod
```bash
cd mod
./gradlew build
# 产物: build/libs/easyai-2.0.0.jar
```

### 4. 安装 Mod
将 `easyai-2.0.0.jar` 和 Baritone 放入 `mods/` 目录。

### 5. 配置 Ollama（离线模式）
```bash
curl -fsSL https://ollama.ai/install.sh | sh
ollama pull llama3.1
ollama serve
```

---

## 配置说明

### config/settings.json

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `ai_name` | `"EasyAI"` | AI 名称，用于 @ 匹配 |
| `ws_port` | `8765` | WebSocket 端口 |
| `llm_mode` | `"offline"` | `"offline"` (Ollama) 或 `"online"` (OpenAI) |
| `ollama_model` | `"llama3.1"` | Ollama 模型名 |
| `ollama_host` | `http://127.0.0.1:11434` | Ollama 地址 |
| `openai_api_key` | `""` | OpenAI API Key |
| `openai_model` | `"gpt-4o-mini"` | OpenAI 模型 |
| `auto_eat_threshold` | `6` | 自动进食阈值 |
| `escape_health` | `8.0` | 逃离血量阈值 |
| `stuck_timeout_seconds` | `10` | 卡死检测超时 |
| `attack_cooldown_ms` | `1600` | 攻击冷却 |
| `combat_range` | `5.0` | 敌对实体检测范围 |
| `melee_range` | `3.0` | 近战攻击范围 |
| `auto_login` | `true` | 自动注册/登录 |
| `auto_register` | `true` | 自动注册 |

### config/whitelist.json
```json
["Steve", "123", "Alex"]
```

### config/passwords.json
```json
{"mc.hypixel.net": "P@ssw0rd!", "localhost": "test123456"}
```

---

## 使用方法

### 启动
1. 双击 `run.bat` 或运行 `./run.sh`
2. Python 后端启动，显示 `WebSocket 服务器将在 127.0.0.1:8765 监听`
3. 启动 Minecraft 1.21.1（已安装 EasyAI Mod + Baritone）
4. 日志显示 `[系统] 本地端口8765握手成功，AI实体已注入`

### 游戏内指令

| 输入 | 动作 |
|------|------|
| `@EasyAI 来` | 跟随发送者 |
| `@EasyAI tpa我` | 发送 TPA |
| `@EasyAI 停止` | 停止所有动作 |
| `@EasyAI 打他` | 攻击目标 |
| `@EasyAI 去 100 64 200` | 寻路至坐标 |
| `@EasyAI 食物` | 强制进食 |
| `@EasyAI 建个房子` | LLM 规划建筑 |

### 管理员终端命令

| 命令 | 效果 |
|------|------|
| `move 100 64 200` | 强制寻路 |
| `attack` | 攻击实体 |
| `inventory` | 打印背包 |
| `say <内容>` | 发送聊天 |
| `build house [x y z]` | 建造房屋 |
| `build wall` | 建造墙壁 |
| `quest end_quest` | 启动末地任务链 |
| `quest status` | 查看任务进度 |
| `sleep` | 尝试睡觉 |
| `password <server>` | 查看/生成密码 |
| `whitelist add/remove <name>` | 白名单管理 |
| `status` | 查看 AI 状态 |
| `stop` | 停止所有动作 |
| `exit` | 退出 |

---

## 通信协议

### 上行 (Mod → Python)

**状态** (每秒): `{"type":"state","name":"EasyAI","x":100,"y":64,"z":200,"health":20,"hunger":18,"dimension":"minecraft:overworld","target_entity":"空"}`

**聊天** (实时): `{"type":"chat","sender":"Steve","msg":"@EasyAI 来","timestamp":1700000000}`

**自动登录** (事件): `{"type":"auto_login","action":"register","server":"ip","password":"xxx"}`

### 下行 (Python → Mod)

```json
{"type":"exec","cmd":"goto","x":100,"y":64,"z":200}
{"type":"exec","cmd":"attack"}
{"type":"exec","cmd":"build_pattern","pattern":"house","start_x":0,"start_y":64,"start_z":0}
{"type":"exec","cmd":"send_chat","msg":"/tell Steve 来了"}
```

---

## FAQ

### Q: 没有 Baritone 能用吗？
A: 可以，寻路退化为简化 A*（直线移动+遇障跳跃）。强烈建议安装 Baritone。

### Q: 离线模式需要付费吗？
A: 不需要。Ollama 本地运行，完全免费。在线模式使用 OpenAI API 需付费。

### Q: 会被反作弊检测吗？
A: 使用 MC 原版 `KeyBinding.setKeyState()` 模拟按键，非网络包注入。但严格反作弊服务器可能检测异常行为模式。

### Q: 支持其他 MC 版本吗？
A: 当前仅 1.21.1。其他版本需调整 Yarn 映射和 Mixin 签名。参见 [DEV.md](DEV.md#版本迁移)。

### Q: 如何添加新的建造模式？
A: 参见 [DEV.md](DEV.md#添加新的建造模式)。

---

## License

MIT License
