package baritone.api.pathing.goals;

// ============================================================
// GoalRunAway - 远离指定位置的寻路目标（桩代码）
// ============================================================
// Baritone 会计算一条路径，使玩家远离危险源。
// 通常在血量过低、被敌对生物追击时触发。
// ============================================================

public class GoalRunAway implements Goal {

    /**
     * @param distance  逃离距离
     * @param positions 逃离起点坐标 (x, z 交替)
     */
    public GoalRunAway(int distance, int... positions) {}

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return false;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return 0;
    }
}
