package org.yinyun.smtcraft.gui;

import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.client.IModGuiFactory;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * 1.8.9 的"模组配置"入口。
 *
 * <p>26.x 版实现的是 Mod Menu 的 {@code ModMenuApi}；1.8.9 里 Forge 自己的模组列表
 * 会调用 {@code @Mod(guiFactory = ...)} 指定的工厂，并反射一个无参构造器来建页。
 * 所以 {@link SettingsScreen} 必须保留无参构造器，否则玩家点"Config"会看到
 * 一次 {@code InstantiationException}。</p>
 */
@SideOnly(Side.CLIENT)
public class ModConfigGuiFactory implements IModGuiFactory {

    @Override
    public void initialize(Minecraft minecraft) {
    }

    @Override
    public Class<? extends GuiScreen> mainConfigGuiClass() {
        return SettingsScreen.class;
    }

    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {
        return null;
    }

    @Override
    public RuntimeOptionGuiHandler getHandlerFor(RuntimeOptionCategoryElement element) {
        return null;
    }
}
