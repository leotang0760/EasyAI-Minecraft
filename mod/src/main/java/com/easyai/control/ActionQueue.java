package com.easyai.control;

// ============================================================
// ActionQueue - 任务队列（支持优先级抢占）
// ============================================================
// 职责：
//   1. 管理待执行的动作任务（原子动作或复合任务）
//   2. 支持优先级抢占：高优先级任务可以中断低优先级任务
//   3. 每个 Tick 从队列取出最高优先级任务执行
//   4. 支持 clearAll() 立即清空所有任务（用于 stop 指令）
//
// 设计原理：
//   使用 PriorityBlockingQueue 按优先级排序，
//   每次只执行队首任务。如果新任务优先级高于当前任务，
//   则暂停当前任务并执行新任务。
//
//   优先级层次（从高到低）：
//     CRITICAL (100) - 安全看门狗、紧急逃离
//     HIGH (75)      - 战斗、自动进食
//     NORMAL (50)    - 寻路、跟随
//     LOW (25)       - 拾取物品、社交回复
// ============================================================

import com.easyai.navigation.BaritoneIntegration;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class ActionQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger("EasyAI/ActionQueue");

    // ============================================================
    // 优先级常量
    // ============================================================
    public static final int PRIORITY_CRITICAL = 100;
    public static final int PRIORITY_HIGH = 75;
    public static final int PRIORITY_NORMAL = 50;
    public static final int PRIORITY_LOW = 25;

    // 任务队列（线程安全的优先级队列）
    // 使用 AtomicLong 作为序列号，保证相同优先级时 FIFO
    private final PriorityBlockingQueue<ActionTask> queue =
            new PriorityBlockingQueue<>();

    private final AtomicLong seqCounter = new AtomicLong(0);

    // 当前正在执行的任务
    private ActionTask currentTask = null;

    // ============================================================
    // ActionTask - 队列中的任务单元
    // ============================================================
    public static class ActionTask implements Comparable<ActionTask> {
        final int priority;          // 优先级（越大越高）
        final long sequence;         // 序列号（FIFO 保证）
        final String name;           // 任务名称（用于日志）
        final ActionExecutor executor; // 执行器
        final long createdAt;        // 创建时间戳

        // 任务执行器函数式接口
        @FunctionalInterface
        public interface ActionExecutor {
            void execute(MinecraftClient client,
                        BaritoneIntegration nav,
                        KeySimulator keys);
        }

        public ActionTask(int priority, String name, ActionExecutor executor) {
            this.priority = priority;
            this.name = name;
            this.executor = executor;
            this.sequence = 0; // 由 enqueue 设置
            this.createdAt = System.currentTimeMillis();
        }

        public ActionTask(int priority, long sequence, String name,
                          ActionExecutor executor) {
            this.priority = priority;
            this.sequence = sequence;
            this.name = name;
            this.executor = executor;
            this.createdAt = System.currentTimeMillis();
        }

        @Override
        public int compareTo(ActionTask other) {
            // 优先级高的排前面，相同优先级按序列号（先入先出）
            int cmp = Integer.compare(other.priority, this.priority);
            return cmp != 0 ? cmp : Long.compare(this.sequence, other.sequence);
        }
    }

    // ============================================================
    // 添加任务到队列
    // ============================================================
    public void enqueue(int priority, String name, ActionTask.ActionExecutor executor) {
        long seq = seqCounter.incrementAndGet();
        ActionTask task = new ActionTask(priority, seq, name, executor);
        queue.offer(task);
        LOGGER.debug("[EasyAI/Queue] 入队: {} (优先级: {})", name, priority);

        // 如果新任务优先级高于当前任务，中断当前任务
        if (currentTask != null && priority > currentTask.priority) {
            LOGGER.info("[EasyAI/Queue] 抢占: {} 被 {} 中断",
                    currentTask.name, name);
            currentTask = null; // 放弃当前任务
        }
    }

    // ============================================================
    // 每 Tick 执行
    // ============================================================
    public void tick(MinecraftClient client,
                     BaritoneIntegration nav,
                     KeySimulator keys) {
        // 如果当前没有任务，从队列取一个
        if (currentTask == null) {
            currentTask = queue.poll();
            if (currentTask != null) {
                LOGGER.debug("[EasyAI/Queue] 开始执行: {}", currentTask.name);
            }
        }

        // 执行当前任务
        if (currentTask != null) {
            try {
                currentTask.executor.execute(client, nav, keys);
            } catch (Exception e) {
                LOGGER.error("[EasyAI/Queue] 任务执行异常: {} - {}",
                        currentTask.name, e.getMessage());
                currentTask = null;
            }
        }
    }

    // 标记当前任务完成（由任务自身调用）
    public void completeCurrent() {
        if (currentTask != null) {
            LOGGER.debug("[EasyAI/Queue] 完成: {}", currentTask.name);
            currentTask = null;
        }
    }

    // ============================================================
    // 清空所有任务（stop 指令调用）
    // ============================================================
    public void clearAll() {
        int size = queue.size();
        queue.clear();
        currentTask = null;
        LOGGER.info("[EasyAI/Queue] 清空所有任务（共 {} 个）", size);
    }

    // 获取队列状态
    public int getPendingCount() { return queue.size(); }
    public String getCurrentTaskName() {
        return currentTask != null ? currentTask.name : "空闲";
    }
}
