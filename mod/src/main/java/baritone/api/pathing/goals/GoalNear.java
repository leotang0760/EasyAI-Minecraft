package baritone.api.pathing.goals;

// ============================================================
// GoalNear - 到达指定坐标附近范围的寻路目标（桩代码）
// ============================================================
// 当玩家进入 (x, y, z) 周围 radius 范围内时视为到达。
// 适用于不需要精确站位的场景。
// ============================================================

public class GoalNear implements Goal {

    public GoalNear(int x, int y, int z, int radius) {}

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return false;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return 0;
    }
}
