package org.yinyun.smtcraft.media;

import org.yinyun.smtcraft.SmtcConfig;
import org.yinyun.smtcraft.smtc.TrackInfo;

/**
 * 命令行探针：起一遍桥，逐秒打印快照，用于人工核对封面、字段与状态。
 *
 * <p>它不进模组运行时，只给改脚本之后做回归用（{@code assets/smtcraft/bridge/*.ps1} 或
 * {@code ProcessBridge} 里的内嵌脚本一改，就必须先跑这个，别靠进游戏试）。</p>
 *
 * <p>编译与运行（仓库根目录、JDK 8）：
 * <pre>
 * javac -encoding UTF-8 -cp "build/classes/main;build/resources/main;GSON_JAR" \
 *       -d build/probe tools/probe/BridgeProbe.java
 * java -cp "build/classes/main;build/resources/main;build/probe;GSON_JAR" \
 *      org.yinyun.smtcraft.media.BridgeProbe
 * </pre>
 * GSON_JAR 见 {@code toolchain/ghome/caches/modules-2/files-2.1/com.google.code.gson/gson/}。
 * 探针只读当前会话，不会写配置；要临时改配置就在 {@code config/smtcraft/smtcraft.json} 里改。</p>
 */
public final class BridgeProbe {

    public static void main(String[] args) throws Exception {
        SmtcConfig cfg = SmtcConfig.load(null);
        MediaBridge bridge = MediaBridges.create(cfg);
        System.out.println("platform=" + MediaBridges.platform() + " tool=" + MediaBridges.tool());
        bridge.setConfig(cfg);
        bridge.start();
        for (int i = 0; i < 8; i++) {
            Thread.sleep(1000L);
            TrackInfo t = bridge.latest();
            byte[] art = bridge.artwork();
            String line = (t == null ? "no track" : (t.title() + " - " + t.artist() + " (" + t.app()
                    + ") " + t.status() + " " + t.positionMs() + "/" + t.endMs() + " ms"))
                    + ", cover " + (art == null ? 0 : art.length) + " bytes"
                    + ", hash " + bridge.artworkHash();
            System.out.println("[" + i + "] running=" + bridge.running() + " samples=" + bridge.sampleCount()
                    + " " + line);
            String err = bridge.lastError();
            if (err != null && !err.isEmpty()) {
                System.out.println("    lastError: " + err);
            }
        }
        bridge.close(3000L);
        System.out.println("closed");
    }
}
