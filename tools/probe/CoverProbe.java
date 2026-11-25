import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Base64;
import org.yinyun.smtcraft.SmtcConfig;
import org.yinyun.smtcraft.media.ProcessBridge;
import org.yinyun.smtcraft.smtc.TrackInfo;

/**
 * 封面归属逻辑的回归探针：反射调 {@code ProcessBridge#handleLine(String)} 喂四条快照，
 * 看封面字节在哪一步被保留、哪一步被丢掉。
 *
 * <p>四步的预期（改 {@code publishEmpty} 或那段 artKey 判断前后都跑一遍）：
 * <ol>
 *   <li>带封面的快照 → {@code artwork=8B}（有图）</li>
 *   <li>空窗 {@code {"ok":false,"reason":"no session"}} → 仍然 8B（旧代码在这一步把封面清掉，
 *       于是同一首歌恢复播放后脚本按 artHash 认为"没变"、永不重发，就是"暂停之后没封面"）</li>
 *   <li>同一首歌回来、脚本不带 art → 仍然 8B</li>
 *   <li>换了歌且还没有 art → 0B，且 {@code artKey} 清空（不能把上一首的封面串到下一首）</li>
 * </ol>
 * 编译与运行见 {@code tools/probe/README.md}；探针不启进程，只调纯解析路径。</p>
 */
public final class CoverProbe {
    private static ProcessBridge bridge;
    private static Method handleLine;

    public static void main(String[] args) throws Exception {
        SmtcConfig cfg = SmtcConfig.load(new java.io.File("build/probe/nonexistent.json"));
        cfg.swapGraceMs = 0;
        bridge = new ProcessBridge(cfg);
        handleLine = ProcessBridge.class.getDeclaredMethod("handleLine", String.class);
        handleLine.setAccessible(true);

        String art = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        String track = "{\"ok\":true,\"title\":\"Song A\",\"artist\":\"Singer\",\"album\":\"Album\","
                + "\"app\":\"Cloudy.exe\",\"playing\":true,\"positionMs\":1000,\"endMs\":200000,"
                + "\"artHash\":\"hash1\",\"art\":\"" + art + "\"}";
        feed(track, "1) first snapshot with cover");
        feed("{\"ok\":false,\"reason\":\"no session\"}", "2) empty window (no session)");
        feed("{\"ok\":true,\"title\":\"Song A\",\"artist\":\"Singer\",\"album\":\"Album\","
                + "\"app\":\"Cloudy.exe\",\"playing\":true,\"positionMs\":2000,\"endMs\":200000}",
                "3) same track back, script sends no art");
        feed("{\"ok\":true,\"title\":\"Song B\",\"artist\":\"Other\",\"album\":\"Album2\","
                + "\"app\":\"Cloudy.exe\",\"playing\":true,\"positionMs\":500,\"endMs\":100000}",
                "4) different track, no art yet");
    }

    private static void feed(String json, String label) throws Exception {
        handleLine.invoke(bridge, json);
        byte[] art = bridge.artwork();
        String hash = bridge.artworkHash();
        TrackInfo info = bridge.latest();
        System.out.println(label + " -> artwork=" + art.length + "B hash=" + hash
                + " hasTrack=" + info.hasTrack() + " artKey=" + str("artKey")
                + " heldKey=[" + str("heldKey") + "]");
    }

    private static String str(String name) throws Exception {
        Field f = ProcessBridge.class.getDeclaredField(name);
        f.setAccessible(true);
        String v = String.valueOf(f.get(bridge));
        return v.length() > 28 ? v.substring(0, 25) + "..." : v;
    }
}
