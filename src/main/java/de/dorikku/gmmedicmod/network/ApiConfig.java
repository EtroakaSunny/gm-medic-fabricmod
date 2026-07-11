package de.dorikku.gmmedicmod.network;

import de.dorikku.gmmedicmod.GMMedic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;

public class ApiConfig {
   /**
    * Default GM-Medic API endpoint. Used until overridden via {@code /gmapi url}. A blank
    * {@code serverUrl=} in the config file is migrated to this default on load; set it to
    * {@value #DISABLED_VALUE} to turn connectivity off.
    */
   public static final String DEFAULT_SERVER_URL = "wss://medic.dorikku.de/api";
   /** Sentinel {@code serverUrl} value that disables WebSocket connectivity. */
   public static final String DISABLED_VALUE = "off";
   private static ApiConfig INSTANCE;
   private boolean loaded = false;
   private String serverUrl = DEFAULT_SERVER_URL;
   private String authToken = "";

   private ApiConfig() {
   }

   public static ApiConfig getInstance() {
      if (INSTANCE == null) {
         INSTANCE = new ApiConfig();
      }

      if (!INSTANCE.loaded) {
         INSTANCE.load();
      }

      return INSTANCE;
   }

   private static Path getConfigPath() {
      return FabricLoader.getInstance().getConfigDir().resolve("gm-medic-api.cfg");
   }

   public void load() {
      this.loaded = true;
      Path configPath = getConfigPath();
      if (Files.exists(configPath)) {
         try {
            String content = Files.readString(configPath);

            for (String line : content.split("\n")) {
               line = line.trim();
               if (!line.isEmpty() && !line.startsWith("#")) {
                  if (line.startsWith("serverUrl=")) {
                     this.serverUrl = line.substring("serverUrl=".length()).trim();
                  } else if (line.startsWith("authToken=")) {
                     this.authToken = line.substring("authToken=".length()).trim();
                  }
               }
            }

            if (this.serverUrl.isBlank()) {
               // Configs written before a default endpoint existed have a blank serverUrl —
               // migrate them to the default (explicit opt-out is serverUrl=off).
               this.serverUrl = DEFAULT_SERVER_URL;
               this.save();
               GMMedic.LOGGER.info("[ApiConfig] Blank serverUrl migrated to default: {}", DEFAULT_SERVER_URL);
            }

            GMMedic.LOGGER.info("[ApiConfig] Loaded: serverUrl={}", this.serverUrl);
         } catch (Exception var7) {
            GMMedic.LOGGER.warn("[ApiConfig] Failed to load config, using defaults", var7);
         }
      } else {
         GMMedic.LOGGER.info("[ApiConfig] No config file found, using defaults");
      }
   }

   public void save() {
      try {
         Path configPath = getConfigPath();
         Files.createDirectories(configPath.getParent());
         String content = "# GM-Medic API Configuration\n# Default: "
            + DEFAULT_SERVER_URL
            + "\n# Set serverUrl=off to disable WebSocket connectivity (blank reverts to the default)\nserverUrl="
            + this.serverUrl
            + "\nauthToken="
            + this.authToken
            + "\n";
         Files.writeString(configPath, content);
         GMMedic.LOGGER.info("[ApiConfig] Saved config");
      } catch (Exception var3) {
         GMMedic.LOGGER.warn("[ApiConfig] Failed to save config", var3);
      }
   }

   public String getServerUrl() {
      return this.serverUrl;
   }

   public void setServerUrl(String url) {
      this.serverUrl = url == null ? "" : url.trim();
      this.save();
   }

   public String getAuthToken() {
      if (this.authToken == null || this.authToken.isBlank()) {
         this.authToken = UUID.randomUUID().toString();
         this.save();
      }

      return this.authToken;
   }

   public void resetAuthToken() {
      this.authToken = UUID.randomUUID().toString();
      this.save();
   }

   public boolean isConfigured() {
      return !this.serverUrl.isBlank() && !this.serverUrl.equalsIgnoreCase(DISABLED_VALUE);
   }
}
