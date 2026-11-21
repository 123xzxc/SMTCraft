package org.yinyun.smtcraft;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import org.yinyun.smtcraft.gui.SettingsScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * Puts the settings behind the gear icon on this mod's page in Mod Menu, so the
 * options live where every other mod keeps them. Only loaded when Mod Menu is
 * installed - the entrypoint key is ignored otherwise.
 */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new SettingsScreen(SmtcCraftClient.get(), parent);
    }
}
