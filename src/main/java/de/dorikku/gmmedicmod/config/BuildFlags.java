package de.dorikku.gmmedicmod.config;

import de.dorikku.gmmedicmod.GMMedic;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Compile-time flags baked in by the Gradle build (see build.gradle's filtering of
 * gmmedicmod-buildflags.properties via the {@code require_verification} project property),
 * so one source tree produces both the gated release jar and an unrestricted jar for
 * offline testing without any code changes.
 */
public final class BuildFlags {

    public static final boolean REQUIRE_SERVER_VERIFICATION = load();

    private BuildFlags() {}

    private static boolean load() {
        try (InputStream in = BuildFlags.class.getResourceAsStream("/gmmedicmod-buildflags.properties")) {
            if (in == null) return true; // fail closed if the resource is missing
            Properties props = new Properties();
            props.load(in);
            return Boolean.parseBoolean(props.getProperty("requireVerification", "true"));
        } catch (IOException e) {
            GMMedic.LOGGER.warn("[BuildFlags] Failed to load build flags — defaulting to verification required", e);
            return true;
        }
    }
}
