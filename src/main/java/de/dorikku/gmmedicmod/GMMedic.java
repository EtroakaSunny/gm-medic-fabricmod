package de.dorikku.gmmedicmod;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class GMMedic implements ModInitializer {
    public static final String MOD_ID = "gm-medic";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    @Override
    public void onInitialize() {
        LOGGER.info("GM Medic initialized!");
    }
}
