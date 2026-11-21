package org.yinyun.smtcraft.font;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.yinyun.smtcraft.SmtcConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yinyun.smtcraft.util.Async;

/**
 * Turns the per-language font files chosen in the settings screen into a real
 * directory resource pack at {@code <gamedir>/resourcepacks/smtcraft_fonts/}.
 *
 * <p>Why a pack on disk: a glyph provider has to be loaded by the resource
 * manager, and Fabric's builtin-pack API can only ship resources that live in the
 * mod jar. Writing the pack at runtime and selecting it lets the user pick any
 * font installed on their machine and still keep a stable font id,
 * {@code smtcraft:systemfont}, that the HUD can reference.</p>
 */
public final class FontPack {
    private static final Logger LOG = LoggerFactory.getLogger("smtcraft");

    public static final String PACK_NAME = "smtcraft_fonts";
    public static final String FONT_ID = "smtcraft:systemfont";
    private static final String[] SLOTS = {"latin", "cjk", "japanese", "other"};

    /** Written on the background lane, read from the client thread. */
    private static volatile String appliedKey = "";
    private static volatile String problem = "";
    /** One pack write at a time: the client retries the apply every few seconds. */
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    private FontPack() {
    }

    /** Last failure, empty when the pack was written and selected cleanly. */
    public static String problem() {
        return problem;
    }

    /** Font id of one slot's own file, e.g. {@code smtcraft:latin}. */
    public static String slotFontId(String slot) {
        return "smtcraft:" + slot;
    }

    /** True when that slot has a generated font json in the pack on disk. */
    public static boolean hasSlot(String slot) {
        if (slot == null || slot.isEmpty()) return false;
        Path fontDir = FabricLoader.getInstance().getGameDir()
                .resolve("resourcepacks").resolve(PACK_NAME)
                .resolve("assets").resolve("smtcraft").resolve("font");
        return Files.isReadable(fontDir.resolve(slot + ".json"));
    }

    public static boolean isApplied(SmtcConfig cfg) {
        return cfg != null && cfg.hasSystemFonts() && cfg.fontKey().equals(appliedKey);
    }

    public static boolean needsApply(SmtcConfig cfg) {
        return cfg != null && cfg.hasSystemFonts() && !cfg.fontKey().equals(appliedKey);
    }

    /**
     * Writes the pack for the current config and selects it.
     *
     * <p>A slot copy is a real font file, hundreds of kilobytes to a few megabytes,
     * so the write runs on the background lane. The repository reload and
     * {@code reloadResourcePacks()} are still client-thread calls and are posted
     * back once the files are on disk. Returns {@code false} when a previous write
     * is still running, which is what makes the retry loop in the client harmless.</p>
     */
    public static boolean apply(Minecraft mc, SmtcConfig cfg) {
        if (mc == null || cfg == null) return false;
        if (!BUSY.compareAndSet(false, true)) return false;
        final String key = cfg.fontKey();
        final Path root = FabricLoader.getInstance().getGameDir()
                .resolve("resourcepacks").resolve(PACK_NAME);
        Async.run("font pack", () -> {
            try {
                writePack(root, cfg);
            } catch (Throwable t) {
                problem = "write: " + t;
                BUSY.set(false);
                return;
            }
            Async.onClient(() -> {
                try {
                    select(mc, key);
                } finally {
                    BUSY.set(false);
                }
            });
        });
        return true;
    }

    /** True while a pack write is in flight, so the settings tab can show "applying". */
    public static boolean busy() {
        return BUSY.get();
    }

    /** Repository half of {@link #apply}: client thread only. */
    private static boolean select(Minecraft mc, String key) {
        PackRepository repo = mc.getResourcePackRepository();
        if (repo == null) {
            problem = "no pack repository yet";
            return false;
        }
        try {
            repo.reload();
            List<String> selected = new ArrayList<>(repo.getSelectedIds());
            String id = "file/" + PACK_NAME;
            if (!selected.contains(id) && repo.isAvailable(id)) selected.add(id);
            if (!selected.contains(id)) {
                problem = "pack not picked up by the repository";
                return false;
            }
            if (key.equals(appliedKey) && repo.getSelectedIds().contains(id)) {
                // Nothing to do. A resource reload also rebuilds the sound engine,
                // which is audible as a stutter, so it is never spent on a no-op.
                problem = "";
                return true;
            }
            repo.setSelected(selected);
            LOG.info("reloading resource packs for the font selection ({})", key);
            mc.reloadResourcePacks();
            appliedKey = key;
            problem = "";
            return true;
        } catch (Throwable t) {
            problem = "select: " + t;
            return false;
        }
    }

    /** Forget the applied selection, so the next tick rewrites the pack. */
    public static void invalidate() {
        appliedKey = "";
    }

    private static void writePack(Path root, SmtcConfig cfg) throws IOException {
        Path fontDir = root.resolve("assets").resolve("smtcraft").resolve("font");
        Files.createDirectories(fontDir);

        JsonObject pack = new JsonObject();
        JsonObject meta = new JsonObject();
        meta.addProperty("description", "SMTCraft system fonts");
        // the client's own resource pack format: never hardcode a number here
        meta.addProperty("pack_format", SharedConstants.RESOURCE_PACK_FORMAT_MAJOR);
        pack.add("pack", meta);
        Files.writeString(root.resolve("pack.mcmeta"),
                new GsonBuilder().setPrettyPrinting().create().toJson(pack) + "\n",
                StandardCharsets.UTF_8);

        JsonArray providers = new JsonArray();
        String[] sources = {cfg.fontLatin, cfg.fontCjk, cfg.fontJapanese, cfg.fontOther};
        for (int i = 0; i < SLOTS.length; i++) {
            String source = sources[i] == null ? "" : sources[i].trim();
            Path single = fontDir.resolve(SLOTS[i] + ".json");
            if (source.isEmpty()) {
                // a slot that was cleared must not leave its preview font behind
                Files.deleteIfExists(single);
                continue;
            }
            Path file = Path.of(source);
            if (!Files.isReadable(file)) {
                Files.deleteIfExists(single);
                continue;
            }
            String name = SLOTS[i] + extensionOf(file);
            Files.copy(file, fontDir.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            JsonObject ttf = new JsonObject();
            ttf.addProperty("type", "ttf");
            // ttf "file" ids resolve under assets/<namespace>/font/
            ttf.addProperty("file", "smtcraft:" + name);
            ttf.addProperty("size", 11.0f);
            ttf.addProperty("oversample", 2.0f);
            JsonArray shift = new JsonArray();
            shift.add(0.0f);
            shift.add(0.0f);
            ttf.add("shift", shift);
            ttf.addProperty("skip", "");
            providers.add(ttf);
            // one json per slot so the settings screen can preview each font on its
            // own: smtcraft:latin / smtcraft:cjk / smtcraft:japanese / smtcraft:other
            writeFontJson(fontDir.resolve(SLOTS[i] + ".json"), ttf);
        }

        // anything the chosen fonts do not cover falls through to the vanilla font
        JsonObject fallback = new JsonObject();
        fallback.addProperty("type", "reference");
        fallback.addProperty("id", "minecraft:default");
        providers.add(fallback);

        JsonObject font = new JsonObject();
        font.add("providers", providers);
        Files.writeString(fontDir.resolve("systemfont.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(font) + "\n",
                StandardCharsets.UTF_8);
    }

    private static void writeFontJson(Path target, JsonObject ttf) throws IOException {
        JsonArray providers = new JsonArray();
        providers.add(ttf);
        JsonObject fallback = new JsonObject();
        fallback.addProperty("type", "reference");
        fallback.addProperty("id", "minecraft:default");
        providers.add(fallback);
        JsonObject font = new JsonObject();
        font.add("providers", providers);
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(font);
        Files.writeString(target, json + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case ".otf", ".otc", ".ttc", ".ttf" -> ext;
            default -> ".ttf";
        };
    }
}
