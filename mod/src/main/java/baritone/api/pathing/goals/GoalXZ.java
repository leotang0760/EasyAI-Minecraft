package baritone.api.pathing.goals;

// ============================================================
// GoalXZ - 仅指定 XZ 坐标的寻路目标（桩代码）
// ============================================================
// 不关心 Y 高度，只要求到达 (x, z) 坐标。
// Baritone 会自动选择最优 Y 层。
// ============================================================

public class GoalXZ implements Goal {

    public GoalXZ(int x, int z) {}

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return false;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return 0;
    }
}
