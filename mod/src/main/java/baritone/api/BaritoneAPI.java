package baritone.api;

// ============================================================
// BaritoneAPI - Baritone 寻路引擎 API 入口（桩代码）
// ============================================================
// 这是编译期桩代码，仅提供接口签名。
// 运行时若用户安装了 Baritone mod，实际类由 Baritone 提供。
// 若未安装 Baritone，BaritoneIntegration 中的 try-catch 会捕获异常。
// ============================================================

public class BaritoneAPI {

    private static final IBaritoneProvider PROVIDER = new BaritoneProviderStub();

    /**
     * 获取 Baritone 提供者实例
     * 真实实现返回当前世界的 Baritone 提供者
     */
    public static IBaritoneProvider getProvider() {
        return PROVIDER;
    }

    /**
     * 桩实现——返回 null 表示 Baritone 未安装
     */
    private static class BaritoneProviderStub implements IBaritoneProvider {
        @Override
        public IBaritone getPrimaryBaritone() {
            return null; // 桩实现：无 Baritone 可用
        }
    }
}
