package baritone.api.process;

import baritone.api.pathing.goals.Goal;

// ============================================================
// ICustomGoalProcess - 自定义寻路目标处理器（桩代码）
// ============================================================
// 设置寻路目标并开始计算路径。
// Baritone 会自动处理避障、跳跃、潜行等。
// ============================================================

public interface ICustomGoalProcess {

    /**
     * 设置寻路目标并开始路径计算
     * @param goal 目标（GoalBlock / GoalXZ / GoalRunAway 等）
     */
    void setGoalAndPath(Goal goal);
}
