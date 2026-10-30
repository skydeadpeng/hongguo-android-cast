package io.github.jackotom.hongguo.android;

/** Compatibility name for callers of the former self-hosted lifecycle suite. */
public final class AccountSyncLifecycleTest {
    public static void main(String[] args)throws Exception {
        OfficialAccountFlowTest.main(args);
        OfficialAccountCoordinatorTest.main(args);
    }
}
