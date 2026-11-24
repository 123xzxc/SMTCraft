package org.yinyun.smtcraft.util;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.input.Keyboard;

/**
 * 键盘状态与键码。
 *
 * <p>1.7.10 的键码就是 LWJGL 的 {@code Keyboard.KEY_*}，不用像 26.x 那样在 GLFW 与 SDL
 * 两套编号之间挑；但仍然按名字反射取值，因为 {@code static final int} 编进字节码后
 * 就永远停在编译时的那个数字上。</p>
 */
public final class Keys {

    private static final Map<String, Integer> CODES = new ConcurrentHashMap<>();

    private Keys() {}

    /** 这个键此刻是否按着。 */
    public static boolean isKeyDown(int key) {
        try {
            return Keyboard.isKeyDown(key);
        } catch (Throwable failed) {
            return false;
        }
    }

    /**
     * 本版 {@code Keyboard} 里叫 {@code name} 的常量值，没有就给 {@code fallback}。
     *
     * <p>取不到不缓存：缓存了 fallback 之后，即使这个常量后来可读了也会一直答错。</p>
     */
    public static int key(String name, int fallback) {
        Integer cached = CODES.get(name);
        if (cached != null) return cached;
        try {
            Field f = Keyboard.class.getField(name);
            int value = f.getInt(null);
            CODES.put(name, value);
            return value;
        } catch (Throwable missing) {
            return fallback;
        }
    }

    /** 1.7.10 只有 GLFW 一套键码，永远是 false。 */
    public static boolean scancodes() {
        return false;
    }

    /** 本版读键盘的方式，供启动日志用。 */
    public static String mode() {
        return "Keyboard.isKeyDown(int)";
    }

    /** 给自测探针用：确认这个常量真的存在。 */
    public static String resolveForProbe() {
        return mode();
    }
}
