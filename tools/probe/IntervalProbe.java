package org.yinyun.smtcraft.media;

import org.yinyun.smtcraft.SmtcConfig;

/**
 * 每个 tick 一次 {@code setConfig} 不许重启适配器；拖滑条式的连续变间隔只该重启一次。
 *
 * <p>判定看日志里 {@code bridge adapter started} 出现了几次：稳态那段应为 1 次，连续拖 30 格
 * 之后只该再多 1 次。多于这个数就是退避/静置逻辑被写坏了（AGENTS.md 规则 4g）。</p>
 *
 * <p>编译与运行同 {@link BridgeProbe}；工作目录是仓库根目录，探针的临时配置放在
 * {@code build/probe/config}（构建产物目录，不入库）。</p>
 */
public final class IntervalProbe {

    public static void main(String[] args) throws Exception {
        java.io.File dir = new java.io.File("build/probe/config");
        SmtcConfig cfg = SmtcConfig.load(new java.io.File(dir, "smtcraft.json"));
        cfg.pollIntervalMs = 150;
        MediaBridge bridge = MediaBridges.create(cfg, dir);
        bridge.start();
        long end = System.currentTimeMillis() + 2500L;
        while (System.currentTimeMillis() < end) {
            bridge.setConfig(cfg);
            Thread.sleep(50L);
        }
        System.out.println("steady samples=" + bridge.sampleCount());
        // 模拟拖动滑条：30 次连续变间隔，间隔每次 +5 ms
        for (int i = 0; i < 30; i++) {
            cfg.pollIntervalMs = 200 + i * 5;
            bridge.setConfig(cfg);
            Thread.sleep(20L);
        }
        Thread.sleep(1200L);
        System.out.println("after drag samples=" + bridge.sampleCount());
        bridge.setConfig(cfg);
        Thread.sleep(300L);
        bridge.close(4000L);
        System.out.println("done");
    }
}
