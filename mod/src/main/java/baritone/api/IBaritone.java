package baritone.api;

import baritone.api.behavior.IPlayerContext;
import baritone.api.pathing.calc.IPathingBehavior;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.IFollowProcess;

// ============================================================
// IBaritone - Baritone 核心接口（桩代码）
// ============================================================
// 定义了 Baritone 的主要功能入口：
//   - 自定义寻路（CustomGoalProcess）
//   - 实体跟随（FollowProcess）
//   - 路径计算状态（PathingBehavior）
//   - 玩家上下文（PlayerContext）
// ============================================================

public interface IBaritone {

    /**
     * 获取自定义寻路目标处理器
     */
    ICustomGoalProcess getCustomGoalProcess();

    /**
     * 获取实体跟随处理器
     */
    IFollowProcess getFollowProcess();

    /**
     * 获取路径计算行为
     */
    IPathingBehavior getPathingBehavior();

    /**
     * 获取玩家上下文
     */
    IPlayerContext getPlayerContext();
}
