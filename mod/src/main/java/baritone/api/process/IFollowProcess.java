package baritone.api.process;

import net.minecraft.entity.Entity;

// ============================================================
// IFollowProcess - 实体跟随处理器（桩代码）
// ============================================================
// 持续跟随指定实体，保持可配置的跟随距离。
// 当目标实体消失或超出范围时自动停止。
// ============================================================

public interface IFollowProcess {

    /**
     * 开始跟随指定实体
     * @param entity 要跟随的实体
     */
    void follow(Entity entity);

    /**
     * 失去控制权时调用（停止跟随）
     */
    void onLostControl();
}
