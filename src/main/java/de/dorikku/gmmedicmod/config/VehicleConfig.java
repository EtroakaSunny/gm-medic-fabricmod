package de.dorikku.gmmedicmod.config;

import de.dorikku.gmmedicmod.GMMedic;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public class VehicleConfig {
   public static final int MIN_EXIT_DELAY_TICKS = 1;
   public static final int MAX_EXIT_DELAY_TICKS = 200;
   private static VehicleConfig INSTANCE;
   private boolean loaded = false;
   private boolean motorEnabled = true;
   private boolean motorAlways = false;
   private boolean gearEnabled = true;
   private boolean gearAlways = false;
   private boolean sirenEnabled = true;
   private boolean sirenAlways = false;
   private int exitDelayTicks = 20;

   private VehicleConfig() {
   }

   public static VehicleConfig getInstance() {
      if (INSTANCE == null) {
         INSTANCE = new VehicleConfig();
      }

      if (!INSTANCE.loaded) {
         INSTANCE.load();
      }

      return INSTANCE;
   }

   private static Path getConfigPath() {
      return FabricLoader.getInstance().getConfigDir().resolve("gm-medic-vehicle.cfg");
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
                  int eq = line.indexOf(61);
                  if (eq >= 0) {
                     String key = line.substring(0, eq).trim();
                     String value = line.substring(eq + 1).trim();
                     switch (key) {
                        case "motorEnabled":
                           this.motorEnabled = Boolean.parseBoolean(value);
                           break;
                        case "motorAlways":
                           this.motorAlways = Boolean.parseBoolean(value);
                           break;
                        case "gearEnabled":
                           this.gearEnabled = Boolean.parseBoolean(value);
                           break;
                        case "gearAlways":
                           this.gearAlways = Boolean.parseBoolean(value);
                           break;
                        case "sirenEnabled":
                           this.sirenEnabled = Boolean.parseBoolean(value);
                           break;
                        case "sirenAlways":
                           this.sirenAlways = Boolean.parseBoolean(value);
                           break;
                        case "exitDelayTicks":
                           try {
                              this.exitDelayTicks = clampDelay(Integer.parseInt(value));
                           } catch (NumberFormatException var13) {
                           }
                     }
                  }
               }
            }

            GMMedic.LOGGER
               .info(
                  "[VehicleConfig] Loaded: motor={}/{}, gear={}/{}, siren={}/{}, exitDelay={}",
                  new Object[]{
                     this.motorEnabled, this.motorAlways, this.gearEnabled, this.gearAlways, this.sirenEnabled, this.sirenAlways, this.exitDelayTicks
                  }
               );
         } catch (Exception var14) {
            GMMedic.LOGGER.warn("[VehicleConfig] Failed to load config, using defaults", var14);
         }
      } else {
         GMMedic.LOGGER.info("[VehicleConfig] No config file found, using defaults");
      }
   }

   public void save() {
      try {
         Path configPath = getConfigPath();
         Files.createDirectories(configPath.getParent());
         String content = "# GM-Medic Vehicle Automation Configuration\n# Each behaviour runs only while on duty unless its *Always flag is true.\n# Auto-start the motor (/vehicles motor) when entering a vehicle\nmotorEnabled="
            + this.motorEnabled
            + "\nmotorAlways="
            + this.motorAlways
            + "\n# Auto-drop the in-hand item to set the gear shift after sitting down\ngearEnabled="
            + this.gearEnabled
            + "\ngearAlways="
            + this.gearAlways
            + "\n# Auto-toggle the siren (/vehicles sirene) while you have an active call\nsirenEnabled="
            + this.sirenEnabled
            + "\nsirenAlways="
            + this.sirenAlways
            + "\n# Ticks to delay a sneak dismount so the siren turns off first (20 ticks = 1s)\nexitDelayTicks="
            + this.exitDelayTicks
            + "\n";
         Files.writeString(configPath, content);
         GMMedic.LOGGER.info("[VehicleConfig] Saved config");
      } catch (Exception var3) {
         GMMedic.LOGGER.warn("[VehicleConfig] Failed to save config", var3);
      }
   }

   private static int clampDelay(int ticks) {
      return Math.max(1, Math.min(200, ticks));
   }

   public boolean isMotorEnabled() {
      return this.motorEnabled;
   }

   public void setMotorEnabled(boolean v) {
      this.motorEnabled = v;
      this.save();
   }

   public boolean isMotorAlways() {
      return this.motorAlways;
   }

   public void setMotorAlways(boolean v) {
      this.motorAlways = v;
      this.save();
   }

   public boolean isGearEnabled() {
      return this.gearEnabled;
   }

   public void setGearEnabled(boolean v) {
      this.gearEnabled = v;
      this.save();
   }

   public boolean isGearAlways() {
      return this.gearAlways;
   }

   public void setGearAlways(boolean v) {
      this.gearAlways = v;
      this.save();
   }

   public boolean isSirenEnabled() {
      return this.sirenEnabled;
   }

   public void setSirenEnabled(boolean v) {
      this.sirenEnabled = v;
      this.save();
   }

   public boolean isSirenAlways() {
      return this.sirenAlways;
   }

   public void setSirenAlways(boolean v) {
      this.sirenAlways = v;
      this.save();
   }

   public int getExitDelayTicks() {
      return this.exitDelayTicks;
   }

   public void setExitDelayTicks(int ticks) {
      this.exitDelayTicks = clampDelay(ticks);
      this.save();
   }
}
