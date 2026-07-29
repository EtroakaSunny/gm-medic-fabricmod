package de.dorikku.gmmedicmod;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class GMMedic implements ModInitializer {
    public static final String MOD_ID = "gm-medic";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * This jar's own version, e.g. {@code 0.1.0-Beta+26.2}. Reported in the AUTH frame so the
     * API server can tell the medic when a newer build exists. The comparison deliberately
     * lives on the server: a broken client-side one could only be fixed by the very update
     * it is meant to announce.
     */
    public static String modVersion() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
    @Override
    public void onInitialize() {
        LOGGER.info("GM Medic initialized!");
    }
}
