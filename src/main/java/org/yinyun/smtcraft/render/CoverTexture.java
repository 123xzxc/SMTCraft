package org.yinyun.smtcraft.render;

import com.mojang.blaze3d.platform.NativeImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yinyun.smtcraft.util.Errors;

/**
 * Uploads a decoded cover image to the GPU on any supported game version.
 *
 * <p>Three APIs cover the range, and none shares a supertype with another, so the first one that
 * resolves is picked once and then driven by reflection:
 * <ul>
 *   <li>26.1 - 26.4-snapshot-2: {@code DynamicTexture(Supplier, NativeImage)} plus
 *       {@code TextureManager.register(Identifier, AbstractTexture)}.</li>
 *   <li>26.4-snapshot-3 - 26.4-snapshot-9: {@code TextureResources.from2dImage(Supplier, NativeImage)}
 *       plus {@code TextureManager.register(Identifier, TextureResources)} — this snapshot range
 *       dropped {@code DynamicTexture} but had not yet grown {@code ExternalTextureProvider}, so it
 *       is the only window where a ready handle is registered directly.</li>
 *   <li>26.4-snapshot-10: the same {@code from2dImage}, published with
 *       {@code TextureManager.registerProvider} through an {@code ExternalTextureProvider}.</li>
 * </ul>
 * The drawing side is untouched because {@code blit(Identifier, ...)} has the same descriptor
 * everywhere.
 */
public final class CoverTexture {

    private static final Logger LOG = LoggerFactory.getLogger("smtcraft-cover");

    private static final String TEXTURE_MANAGER = "net.minecraft.client.renderer.texture.TextureManager";
    private static final String PROVIDER = "net.minecraft.client.renderer.texture.TextureProvider";
    private static final String EXTERNAL = "net.minecraft.client.renderer.texture.ExternalTextureProvider";
    private static final String RESOURCES = "net.minecraft.client.renderer.texture.TextureResources";
    private static final String DYNAMIC = "net.minecraft.client.renderer.texture.DynamicTexture";
    private static final String ABSTRACT_TEXTURE = "net.minecraft.client.renderer.texture.AbstractTexture";

    /** One resolved way of publishing an image under an identifier. */
    private interface Plan {
        void register(Identifier id, NativeImage image, Supplier<String> label) throws Throwable;
    }

    private static boolean ready;
    private static Plan plan;
    private static String mode = "unresolved";
    /** Why the last {@link #register} failed: no API at all, or an upload that threw. */
    private static String lastFailure = "not attempted";

    private CoverTexture() {
    }

    /** True when this game build has an API the mod knows how to drive. */
    public static boolean available() {
        return plan() != null;
    }

    /** Human readable name of the API in use, for the log and for diagnostics. */
    public static String mode() {
        plan();
        return mode;
    }

    /**
     * Publishes the image under {@code id}. Takes ownership of {@code image} on success; on failure
     * the caller still owns it and is expected to close it.
     *
     * <p>False covers two different things — no API on this build, and an upload that threw — so
     * the reason travels in {@link #lastFailure()} instead of being guessed by the caller (R28-7).</p>
     */
    public static boolean register(Identifier id, NativeImage image, String label) {
        Plan chosen = plan();
        if (chosen == null) {
            lastFailure = "no cover texture API on " + mode;
            return false;
        }
        Supplier<String> supplier = () -> label;
        try {
            chosen.register(id, image, supplier);
            lastFailure = "not attempted";
            LOG.debug("cover texture {} registered through {}", id, mode);
            return true;
        } catch (Throwable failed) {
            lastFailure = mode + ": " + Errors.describe(failed);
            LOG.warn("cannot upload the cover texture {}: {}", id, lastFailure);
            return false;
        }
    }

    /** Why the last {@link #register} returned false, for the caller that has to report it. */
    public static String lastFailure() {
        return lastFailure;
    }

    private static synchronized Plan plan() {
        if (ready) return plan;
        ready = true;
        Throwable directMiss = null;
        Throwable providerMiss = null;
        try {
            plan = directPlan();
            mode = "texture-resources";
            return plan;
        } catch (Throwable missing) {
            directMiss = missing;
        }
        try {
            plan = providerPlan();
            mode = "texture-provider";
            return plan;
        } catch (Throwable missing) {
            providerMiss = missing;
        }
        try {
            plan = dynamicPlan();
            mode = "dynamic-texture";
            return plan;
        } catch (Throwable noDynamic) {
            mode = "unavailable";
            LOG.warn("no cover texture API on this version ({}, {}, {})",
                    Errors.describe(directMiss), Errors.describe(providerMiss),
                    Errors.describe(noDynamic));
            return null;
        }
    }

    private static Plan directPlan() throws Exception {
        Class<?> resources = Class.forName(RESOURCES);
        Method fromImage = resources.getMethod("from2dImage", Supplier.class, NativeImage.class);
        Method register = Class.forName(TEXTURE_MANAGER)
                .getMethod("register", Identifier.class, resources);
        return (id, image, label) -> register.invoke(textureManager(), id, fromImage.invoke(null, label, image));
    }

    private static Plan providerPlan() throws Exception {
        Class<?> resources = Class.forName(RESOURCES);
        Method fromImage = resources.getMethod("from2dImage", Supplier.class, NativeImage.class);
        Constructor<?> provider = Class.forName(EXTERNAL).getConstructor(resources);
        Method register = Class.forName(TEXTURE_MANAGER)
                .getMethod("registerProvider", Identifier.class, Class.forName(PROVIDER));
        return (id, image, label) -> {
            Object handle = fromImage.invoke(null, label, image);
            register.invoke(textureManager(), id, provider.newInstance(handle));
        };
    }

    private static Plan dynamicPlan() throws Exception {
        Class<?> dynamic = Class.forName(DYNAMIC);
        Constructor<?> texture = dynamic.getConstructor(Supplier.class, NativeImage.class);
        Method register = Class.forName(TEXTURE_MANAGER)
                .getMethod("register", Identifier.class, Class.forName(ABSTRACT_TEXTURE));
        return (id, image, label) -> register.invoke(textureManager(), id, texture.newInstance(label, image));
    }

    private static Object textureManager() {
        return Minecraft.getInstance().getTextureManager();
    }
}
