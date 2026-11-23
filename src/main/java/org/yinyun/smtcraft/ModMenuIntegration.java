package org.yinyun.smtcraft;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.terraformersmc.modmenu.api.UpdateChannel;
import com.terraformersmc.modmenu.api.UpdateChecker;
import com.terraformersmc.modmenu.api.UpdateInfo;
import org.yinyun.smtcraft.gui.SettingsScreen;
import org.yinyun.smtcraft.util.UpdateCheck;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Puts the settings behind the gear icon on this mod's page in Mod Menu, so the
 * options live where every other mod keeps them, and answers the update badge in
 * the same list. Only loaded when Mod Menu is installed - the entrypoint key is
 * ignored otherwise.
 *
 * <p>The website, source and issue links on that page come from the
 * {@code contact} block of {@code fabric.mod.json}, not from this class: Mod Menu
 * reads them from the metadata so they stay correct for every loader version.</p>
 */
public final class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new SettingsScreen(SmtcCraftClient.get(), parent);
    }

    /**
     * The badge Mod Menu draws next to the mod's name. It must answer in the same
     * frame the list is drawn, so it does not wait for the network: the look starts
     * (or continues) in the background and the last answer is reported as it stands.
     * The first tick of the game usually has already started that look.
     */
    @Override
    public UpdateChecker getUpdateChecker() {
        SmtcCraftClient client = SmtcCraftClient.instance();
        SmtcConfig config = client == null ? null : client.config();
        if (config != null && !config.checkUpdates) {
            // no badge at all, rather than one that says "checking" for the whole session:
            // Mod Menu hides the row for a null checker (AUDIT-47-1)
            return null;
        }
        return () -> {
            String current = SmtcCraftClient.version();
            UpdateCheck.request(current);
            // Mod Menu asks once and keeps the answer, so the badge waits out the first
            // reply instead of answering "still looking" for the rest of the session.
            UpdateCheck.awaitAnswer(2500L);
            return new MenuUpdate(UpdateCheck.state());
        };
    }

    /** What the badge shows: available, or the last answer's reason in the tooltip. */
    private record MenuUpdate(UpdateCheck.State state) implements UpdateInfo {

        @Override
        public boolean isUpdateAvailable() {
            return state.available();
        }

        /**
         * Six answers, not two: before the first reply arrives there is nothing to
         * say about the version, after a failed look "up to date" would be a claim the
         * code cannot make (the badge repeats the reason instead), and a switch that is
         * off is not a look in flight. Which
         * one applies is decided by {@link UpdateCheck#badgeKey}, so the choice is
         * covered by the self-test even though this class needs Mod Menu to load.
         */
        @Override
        public Component getUpdateMessage() {
            String key = UpdateCheck.badgeKey(state, UpdateCheck.running());
            if (UpdateCheck.BADGE_AVAILABLE.equals(key)) {
                return Component.translatable(key, state.latest());
            }
            if (UpdateCheck.BADGE_FAILED.equals(key)) {
                // the raw note names each route and its status; a player reads one short line
                return Component.translatable(key,
                        Component.translatable(UpdateCheck.reasonKey(state)));
            }
            if (UpdateCheck.BADGE_CURRENT.equals(key)) {
                return Component.translatable(key, SmtcCraftClient.version());
            }
            if (UpdateCheck.BADGE_UNKNOWN.equals(key)) {
                return Component.translatable(key);
            }
            return Component.translatable(key);
        }

        @Override
        public String getDownloadLink() {
            return state.url();
        }

        @Override
        public UpdateChannel getUpdateChannel() {
            return UpdateChannel.RELEASE;
        }
    }
}
