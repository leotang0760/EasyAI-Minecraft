package baritone.api;

// ============================================================
// IBaritoneProvider - Baritone 提供者接口（桩代码）
// ============================================================
// 用于获取当前世界的主要 Baritone 实例
// ============================================================

public interface IBaritoneProvider {

    /**
     * 获取当前世界的首要 Baritone 实例
     * @return Baritone 实例，若不可用则返回 null
     */
    IBaritone getPrimaryBaritone();
}
