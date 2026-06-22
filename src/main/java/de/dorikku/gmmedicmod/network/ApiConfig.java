package de.dorikku.gmmedicmod.network;

import de.dorikku.gmmedicmod.GMMedic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;

public class ApiConfig {
   private static ApiConfig INSTANCE;
   private boolean loaded = false;
   private String serverUrl = "";
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

            GMMedic.LOGGER.info("[ApiConfig] Loaded: serverUrl={}", this.serverUrl.isBlank() ? "(none)" : this.serverUrl);
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
         String content = "# GM-Medic API Configuration\n# Leave serverUrl blank to disable WebSocket connectivity\nserverUrl="
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
      return !this.serverUrl.isBlank();
   }
}
