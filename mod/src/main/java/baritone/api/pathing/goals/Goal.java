package baritone.api.pathing.goals;

// ============================================================
// Goal - 寻路目标接口（桩代码）
// ============================================================
// 所有寻路目标的基接口。
// Baritone 内部使用 A* 算法计算从当前位置到目标的最短路径。
// ============================================================

public interface Goal {

    /**
     * 判断当前坐标是否已到达目标
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 是否到达
     */
    boolean isInGoal(int x, int y, int z);

    /**
     * 估算从指定坐标到目标的启发式代价
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 估算代价
     */
    double heuristic(int x, int y, int z);
}
