package org.yinyun.smtcraft;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * 服务端一侧的占位实现。
 *
 * <p>1.7.10 没有 {@code clientSideOnly} 这种"整个 mod 不加载到服务端"的开关（那是
 * 后来的版本才有的），所以入口类必须能在专用服务端被加载：凡是碰 {@code Minecraft}
 * 的代码都留在 {@link ClientProxy} 里，这个类只提供同名的空实现。整合包把它装在
 * 服务端不会崩，也不会做任何事。</p>
 */
public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
    }

    public void init(FMLInitializationEvent event) {
    }

    /** 服务端没有字体可换：什么都不做。（1.7.10 本身也没有运行期字体注入点。） */
    public void applyFonts() {
    }
}
