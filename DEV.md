# EasyAI-Minecraft - 开发文档 (DEV.md)

> **版本**: 2.0.0 | **MC**: 1.21.1 | **Java**: 21 | **Python**: 3.10+

---

## 目录

- [架构设计](#架构设计)
- [环境搭建](#环境搭建)
- [构建指南](#构建指南)
- [Java Mod 开发](#java-mod-开发)
- [Python 后端开发](#python-后端开发)
- [通信协议规范](#通信协议规范)
- [Mixin 注入原理](#mixin-注入原理)
- [Baritone API 参考](#baritone-api-参考)
- [扩展指南](#扩展指南)
- [调试技巧](#调试技巧)
- [版本迁移](#版本迁移)

---

## 架构设计

### 三层决策模型

```
┌─────────────────────────────────────────────┐
│  高层 (Python LLM) — 按需唤醒               │
│  • 未知任务规划 (建红石电路/复杂建筑)        │
│  • 社交对话 (非指令性聊天)                   │
│  • 失败回滚替代方案生成                      │
├─────────────────────────────────────────────┤
│  中层 (Mod+Python) — 事件驱动                │
│  • 任务链脚本执行 (tasks/*.json)             │
│  • @指令硬解析 (正则+映射表, <200ms)         │
│  • 失败回滚 → LLM 请求替代方案               │
├─────────────────────────────────────────────┤
│  底层 (Java Mod) — Tick 微循环 (50ms)        │
│  • 安全看门狗 (血量<8 → escape)              │
│  • 自动进食 (饥饿<6 → 最佳食物)              │
│  • 战斗AI (敌对实体检测 → 攻击/闪避)         │
│  • 物品拾取 (3格内 → 自动捡取)               │
│  • 生存直觉 (雷暴/夜间/中毒 → 自动应对)      │
│  • 自动登录 (正则匹配 → /register+/login)    │
└─────────────────────────────────────────────┘
```

### 模块依赖关系

```
EasyAIMod (主入口)
├── control/
│   ├── ActionQueue      ← 优先级任务队列
│   └── KeySimulator     ← 键盘/鼠标模拟
├── navigation/
│   └── BaritoneIntegration ← 寻路引擎封装
├── network/
│   └── EasyAIWebSocketClient ← WebSocket 通信
├── mixin/
│   ├── ChatHudMixin     ← 聊天拦截
│   └── ClientPlayerMixin ← 玩家 Tick 钩子
├── survival/
│   ├── AutoLoginManager ← 自动注册/登录
│   └── SurvivalInstinct ← 天气/夜间/危险检测
├── combat/
│   └── CombatAI         ← 高级战斗 (目标优先级/Z走位/箭矢闪避)
└── interaction/
    ├── ItemPicker       ← 物品拾取
    └── BuildingManager  ← 蓝图建造
```

---

## 环境搭建

### Java 开发环境

```bash
# 1. 安装 JDK 21 (推荐 Microsoft Build of OpenJDK)
# Windows: winget install Microsoft.OpenJDK.21
# macOS: brew install openjdk@21
# Linux: sudo apt install openjdk-21-jdk

# 2. 验证
java -version   # 应显示 21.x
javac -version  # 应显示 21.x

# 3. 设置 JAVA_HOME
export JAVA_HOME=/path/to/jdk-21
```

### Python 开发环境

```bash
# 1. 安装 Python 3.10+
python3 --version

# 2. 安装依赖
cd EasyAI_AI
pip install -r requirements.txt

# 3. (可选) 安装 Ollama 用于离线 LLM
curl -fsSL https://ollama.ai/install.sh | sh
ollama pull llama3.1
```

### IDE 推荐

- **Java**: IntelliJ IDEA + Minecraft Development 插件 + Fabric Loom
- **Python**: VS Code + Python 扩展 + Pylance

---

## 构建指南

### 构建 JAR (Java Mod)

```bash
cd mod

# 使用 Gradle Wrapper (推荐)
./gradlew build        # Linux/macOS
gradlew.bat build      # Windows

# 构建产物
# build/libs/EasyAI-2.0.0.jar        — 发布 JAR (含内嵌依赖)
# build/libs/EasyAI-2.0.0-sources.jar — 源码 JAR
```

### Baritone 依赖说明

项目不依赖外部 Baritone Maven 仓库。`src/main/java/baritone/api/` 内置了编译期桩代码，
打包时通过 `jar { exclude 'baritone/**' }` 排除。运行时若用户安装了 Baritone mod，
`BaritoneIntegration` 中的 `try-catch` 会自动检测并使用真实实现。

### 已知编译注意事项

- `goto` 是 Java 保留关键字，方法名为 `goTo()`
- 1.21 中 `ItemStack.isFood()` → `stack.contains(DataComponentTypes.FOOD)`
- 1.21 中 `KeyBinding.setKeyState(KB, bool)` → `keyBinding.setPressed(bool)`
- 1.21 中 `Entity.isTouchingLava()` → `Entity.isInLava()`
- 1.21 中 `ClientWorld.getEntity(UUID)` 不可用，改为遍历 `world.getEntities()`

### 验证 Python

```bash
cd python

# 语法检查
python3 -m py_compile main.py
python3 -m py_compile brain/*.py
python3 -m py_compile server/*.py
python3 -m py_compile memory/*.py
python3 -m py_compile ui/*.py

# 运行 (需要安装依赖)
python3 main.py
```

### 一键启动

```bash
# Windows
run.bat

# Linux/macOS
chmod +x run.sh
./run.sh
```

---

## Java Mod 开发

### 文件结构

```
mod/src/main/java/com/easyai/
├── EasyAIMod.java              // 主入口 @ClientModInitializer
├── mixin/
│   ├── ChatHudMixin.java       // @Mixin(ChatHud.class)
│   └── ClientPlayerMixin.java  // @Mixin(ClientPlayerEntity.class)
├── control/
│   ├── ActionQueue.java        // PriorityBlockingQueue<ActionTask>
│   └── KeySimulator.java       // KeyBinding.setKeyState()
├── navigation/
│   └── BaritoneIntegration.java // IBaritone API 封装
├── network/
│   └── EasyAIWebSocketClient.java // extends WebSocketClient
├── survival/
│   ├── AutoLoginManager.java   // SecureRandom + Pattern
│   └── SurvivalInstinct.java   // world.isThundering() + 时间检测
├── combat/
│   └── CombatAI.java           // 目标优先级 + Z走位 + 箭矢闪避
└── interaction/
    ├── ItemPicker.java         // ItemEntity / ExperienceOrbEntity 检测
    └── BuildingManager.java    // 蓝图模式 (wall/floor/tower/house/circle)
```

### 添加新的 Tick 行为

在 `EasyAIMod.onClientTick()` 中添加新的检测逻辑：

```java
private void onClientTick(MinecraftClient client) {
    ClientPlayerEntity player = client.player;
    if (player == null) return;

    tickCounter++;

    // 在此处添加新的检测逻辑
    // 使用 tickCounter % N 控制执行频率
    if (tickCounter % 20 == 0) {
        // 每 1 秒执行一次
        yourNewModule.tick(player, client);
    }
}
```

### 添加新的 Mixin

1. 创建 Mixin 类：
```java
@Mixin(TargetClass.class)
public class YourMixin {
    @Inject(method = "targetMethod", at = @At("HEAD"))
    private void easyai$onTarget(TargetParam param, CallbackInfo ci) {
        // 你的逻辑
    }
}
```

2. 注册到 `easyai.mixins.json`：
```json
{
  "client": [
    "ChatHudMixin",
    "ClientPlayerMixin",
    "YourMixin"    // 添加此处
  ]
}
```

### 添加新的 Python 指令处理

在 `EasyAIMod.handleCommand()` 中添加新的 case：

```java
case "your_new_command" -> {
    // 解析参数
    int param = cmd.get("param").getAsInt();
    // 执行逻辑
    yourModule.doSomething(param);
}
```

---

## Python 后端开发

### 文件结构

```
python/
├── main.py                     // EasyAIBackend 主控制器
├── server/
│   └── ws_endpoint.py          // WSServer (asyncio + websockets)
├── brain/
│   ├── llm_router.py           // Ollama/OpenAI 统一接口
│   ├── command_parser.py       // @指令正则解析
│   ├── task_builder.py         // LLM JSON → Mod 指令流
│   ├── auto_login.py           // 密码生成/存储
│   ├── quest_engine.py         // 任务链执行
│   └── survival_advisor.py     // 生存策略分析
├── memory/
│   └── failure_repo.py         // SQLite 失败经验库
└── ui/
    ├── log_console.py          // rich 彩色日志
    └── cmd_console.py          // prompt_toolkit 命令输入
```

### 添加新的 @指令

在 `command_parser.py` 的 `_command_rules` 列表中添加：

```python
self._command_rules.append(
    (re.compile(r"^(你的指令关键词)$", re.IGNORECASE),
     "your_cmd_type", "回复内容"),
)
```

然后在 `main.py` 的 `_handle_hardcoded_command()` 中添加处理：

```python
elif cmd_type == "your_cmd_type":
    self._send_to_mod({"type": "exec", "cmd": "your_command"})
    self._send_to_mod({"type": "exec", "cmd": "send_chat",
                       "msg": f"/tell {sender} {reply}"})
```

### 添加新的管理员命令

在 `main.py` 的 `_on_admin_command()` 中添加：

```python
elif cmd == "your_command" and len(parts) >= 2:
    param = parts[1]
    self._send_to_mod({"type": "exec", "cmd": "your_cmd", "param": param})
    self.log_console.log_action(f"执行: {param}")
```

### 添加新的任务链

在 `tasks/` 目录创建 JSON 文件：

```json
{
    "quest_name": "你的任务名",
    "description": "任务描述",
    "steps": [
        {
            "step": 1,
            "action": "goto",
            "target": "location_name",
            "description": "前往目标地点"
        },
        {
            "step": 2,
            "action": "collect",
            "target": "item_name",
            "count": 10,
            "fallback_llm": "如果无法获得，请寻找替代方案"
        }
    ],
    "on_failure": {
        "retry": 3,
        "fallback_llm": "任务链失败，请LLM分析原因"
    }
}
```

---

## 通信协议规范

### 上行：Mod → Python

| 类型 | 频率 | 格式 |
|------|------|------|
| `handshake` | 连接时 | `{"type":"handshake","client":"easyai-mod","version":"2.0.0"}` |
| `state` | 每秒 | `{"type":"state","name":"EasyAI","x":100,"y":64,"z":200,"health":20,"hunger":18,"dimension":"minecraft:overworld","on_ground":true,"target_entity":"空"}` |
| `chat` | 实时 | `{"type":"chat","sender":"Steve","msg":"@EasyAI 来","timestamp":1700000000}` |
| `auto_login` | 事件 | `{"type":"auto_login","action":"register","server":"ip","password":"xxx"}` |

### 下行：Python → Mod

| 指令 | 格式 |
|------|------|
| 寻路 | `{"type":"exec","cmd":"goto","x":100,"y":64,"z":200}` |
| 跟随 | `{"type":"exec","cmd":"follow","uuid":"xxx","distance":3.0}` |
| 攻击 | `{"type":"exec","cmd":"attack"}` |
| 使用物品 | `{"type":"exec","cmd":"use_item"}` |
| 发送聊天 | `{"type":"exec","cmd":"send_chat","msg":"/tell Steve 来了"}` |
| 破坏方块 | `{"type":"exec","cmd":"break_block","x":100,"y":64,"z":200}` |
| 放置方块 | `{"type":"exec","cmd":"place_block","x":100,"y":64,"z":200}` |
| 建造模式 | `{"type":"exec","cmd":"build_pattern","pattern":"house","start_x":0,"start_y":64,"start_z":0}` |
| 睡觉 | `{"type":"exec","cmd":"sleep"}` |
| 停止 | `{"type":"exec","cmd":"stop"}` |
| 逃离 | `{"type":"exec","cmd":"escape"}` |
| 背包 | `{"type":"exec","cmd":"inventory"}` |

---

## Mixin 注入原理

### 什么是 Mixin

Fabric Mixin 是一种字节码织入技术，在类加载期将自定义代码注入到 MC 原版类中，**不修改任何 .class 文件**。

### 注入类型

| 注解 | 说明 | 示例 |
|------|------|------|
| `@Inject` | 在目标方法头部/尾部插入代码 | 拦截聊天消息 |
| `@Redirect` | 替换目标方法调用 | 修改移动逻辑 |
| `@ModifyVariable` | 修改变量值 | 修改坐标 |
| `@ModifyArg` | 修改方法参数 | 修改攻击伤害 |

### 注入点 (At)

| 值 | 说明 |
|------|------|
| `@At("HEAD")` | 方法开头 |
| `@At("TAIL")` | 方法结尾 |
| `@At("RETURN")` | return 指令处 |
| `@At("INVOKE")` | 方法调用处 |

### 1.21.x Mixin 注意事项

- 兼容级别必须为 `JAVA_21`
- `MessageSignatureData` → `MessageSignature` (包路径变更)
- 使用方法描述符 `addMessage(Lnet/minecraft/text/Text;)V` 提高兼容性
- 部分类从 `net.minecraft.entity.player` 移动到 `net.minecraft.entity`

---

## Baritone API 参考

### 核心类

```java
// 获取 Baritone 实例
IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();

// 自定义目标进程
ICustomGoalProcess goalProcess = baritone.getCustomGoalProcess();

// 跟随进程
IFollowProcess followProcess = baritone.getFollowProcess();

// 寻路行为
IPathingBehavior pathing = baritone.getPathingBehavior();
```

### 目标类型

| 类 | 说明 |
|------|------|
| `GoalBlock(x,y,z)` | 精确到达坐标 |
| `GoalXZ(x,z)` | 只关心 XZ 坐标 |
| `GoalNear(x,y,z,radius)` | 到达坐标附近 |
| `GoalRunAway(distance,x,z)` | 远离坐标 |
| `GoalYLevel(y)` | 到达指定 Y 高度 |

### 常用操作

```java
// 寻路到坐标
goalProcess.setGoalAndPath(new GoalBlock(new BlockPos(x, y, z)));

// 跟随实体
followProcess.follow(targetEntity);

// 停止寻路
pathing.cancelEverything();
followProcess.onLostControl();

// 检查是否正在寻路
boolean isPathing = pathing.isPathing();
```

---

## 扩展指南

### 添加新的建造模式

在 `BuildingManager.java` 中添加：

```java
private void generateBridge(int x, int y, int z) {
    for (int dx = 0; dx < 20; dx++) {
        buildQueue.add(new BuildTask(x + dx, y, z, Items.OAK_PLANKS));
    }
}
```

然后在 `executePattern()` 的 switch 中添加 case。

### 添加新的战斗策略

在 `CombatAI.java` 中添加新的实体处理：

```java
else if (target instanceof WitchEntity) {
    // 女巫：保持距离，远程攻击
    handleWitch(player, (WitchEntity) target, dist, keys);
}
```

### 添加新的 LLM 后端

在 `llm_router.py` 中添加新的后端：

```python
async def _query_custom(self, prompt: str) -> str:
    # 你的自定义 LLM 调用逻辑
    pass

# 在 query() 方法中添加分支
elif self.mode == "custom":
    return await self._query_custom(prompt)
```

---

## 调试技巧

### 启用 DEBUG 日志

在 `settings.json` 中没有日志级别配置，但可以在代码中修改：

```java
// Java: 在 EasyAIMod.java 中
LOGGER.info("[EasyAI] ...");  // INFO 级别
LOGGER.debug("[EasyAI] ..."); // DEBUG 级别
```

```python
# Python: 在 main.py 中
logging.basicConfig(level=logging.DEBUG)
```

### WebSocket 通信调试

使用 `wscat` 工具手动测试 WebSocket 通信：

```bash
# 安装 wscat
npm install -g wscat

# 连接到 Python 服务器
wscat -c ws://127.0.0.1:8765

# 发送测试消息
> {"type":"handshake","client":"test","version":"1.0"}
```

### SQLite 调试

```bash
# 查看失败记录
sqlite3 logs/failures.db "SELECT * FROM failures ORDER BY timestamp DESC LIMIT 10;"

# 查看统计
sqlite3 logs/failures.db "SELECT cause, COUNT(*) FROM failures GROUP BY cause;"
```

---

## 版本迁移

### 1.20.1 → 1.21.1 迁移指南

| 变更项 | 1.20.1 | 1.21.1 |
|--------|--------|--------|
| Java 版本 | 17 | 21 |
| Yarn 映射 | 1.20.1+build.10 | 1.21.1+build.3 |
| Fabric Loader | 0.15.11 | 0.16.0 |
| Fabric API | 0.92.2+1.20.1 | 0.102.0+1.21.1 |
| Fabric Loom | 1.6-SNAPSHOT | 1.7-SNAPSHOT |
| MessageSignatureData | `net.minecraft.network.message.MessageSignatureData` | `net.minecraft.network.message.MessageSignature` |
| ChatHud.addMessage | `(Text, MessageSignatureData, int, MessageIndicator, boolean)` | `(Text, MessageSignature, MessageIndicator)` 或 `(Text)` |
| Mixin 兼容级别 | `JAVA_17` | `JAVA_21` |

### 迁移步骤

1. 更新 `gradle.properties` 中的版本号
2. 更新 `build.gradle` 中的 Loom 版本和 Java 版本
3. 更新 `fabric.mod.json` 中的 depends
4. 更新 `easyai.mixins.json` 中的 compatibilityLevel
5. 修复 Mixin 签名变化（ChatHudMixin）
6. 运行 `./gradlew build` 验证编译
