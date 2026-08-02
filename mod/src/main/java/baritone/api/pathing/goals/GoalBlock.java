package baritone.api.pathing.goals;

import net.minecraft.util.math.BlockPos;

// ============================================================
// GoalBlock - 精确到达指定方块的寻路目标（桩代码）
// ============================================================
// Baritone 会计算一条路径，使玩家精确站在 (x, y, z) 上方。
// 这是最常用的寻路目标类型。
// ============================================================

public class GoalBlock implements Goal {

    public GoalBlock(BlockPos pos) {}
    public GoalBlock(int x, int y, int z) {}

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return false;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return 0;
    }
}
