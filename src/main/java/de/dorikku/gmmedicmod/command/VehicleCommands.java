package de.dorikku.gmmedicmod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import de.dorikku.gmmedicmod.config.VehicleConfig;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.text.Text;

public class VehicleCommands {
   public VehicleCommands() {
   }

   public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)ClientCommandManager.literal(
                              "gmvehicle"
                           )
                           .then(
                              toggle(
                                 "motor",
                                 VehicleConfig::isMotorEnabled,
                                 VehicleConfig::setMotorEnabled,
                                 VehicleConfig::isMotorAlways,
                                 VehicleConfig::setMotorAlways,
                                 "Motorstart"
                              )
                           ))
                        .then(
                           toggle(
                              "gear",
                              VehicleConfig::isGearEnabled,
                              VehicleConfig::setGearEnabled,
                              VehicleConfig::isGearAlways,
                              VehicleConfig::setGearAlways,
                              "Gangschaltung"
                           )
                        ))
                     .then(
                        toggle(
                           "siren",
                           VehicleConfig::isSirenEnabled,
                           VehicleConfig::setSirenEnabled,
                           VehicleConfig::isSirenAlways,
                           VehicleConfig::setSirenAlways,
                           "Sirene"
                        )
                     ))
                  .then(
                     ClientCommandManager.literal("exitdelay")
                        .then(
                           ClientCommandManager.argument("ticks", IntegerArgumentType.integer(1, 200))
                              .executes(
                                 ctx -> {
                                    int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
                                    VehicleConfig.getInstance().setExitDelayTicks(ticks);
                                    ((FabricClientCommandSource)ctx.getSource())
                                       .sendFeedback(
                                          Text.literal(
                                             "§a§l Ausstiegs-Verzögerung gesetzt: §r" + ticks + " Ticks (" + String.format("%.1f", (double)ticks / 20.0) + "s)"
                                          )
                                       );
                                    return 1;
                                 }
                              )
                        )
                  ))
               .then(ClientCommandManager.literal("status").executes(ctx -> {
                  sendStatus((FabricClientCommandSource)ctx.getSource());
                  return 1;
               })))
            .executes(ctx -> {
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§6Verfügbare Fahrzeug-Befehle:"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§a  /gmvehicle motor on|off §r- Motor automatisch starten"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§a  /gmvehicle gear on|off §r- Gangschaltung automatisch setzen"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§a  /gmvehicle siren on|off §r- Sirene bei Notruf automatisch"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§a  /gmvehicle <feature> always on|off §r- Auch außer Dienst aktiv"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§a  /gmvehicle exitdelay <ticks> §r- Verzögerung beim Aussteigen"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§a  /gmvehicle status §r- Aktuelle Einstellungen anzeigen"));
               return 1;
            })
      );
   }

   private static LiteralArgumentBuilder<FabricClientCommandSource> toggle(
      String name,
      Function<VehicleConfig, Boolean> getEnabled,
      BiConsumer<VehicleConfig, Boolean> setEnabled,
      Function<VehicleConfig, Boolean> getAlways,
      BiConsumer<VehicleConfig, Boolean> setAlways,
      String label
   ) {
      return (LiteralArgumentBuilder<FabricClientCommandSource>)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)ClientCommandManager.literal(
                     name
                  )
                  .then(ClientCommandManager.literal("on").executes(ctx -> {
                     setEnabled.accept(VehicleConfig.getInstance(), true);
                     ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§a§l " + label + " aktiviert"));
                     return 1;
                  })))
               .then(ClientCommandManager.literal("off").executes(ctx -> {
                  setEnabled.accept(VehicleConfig.getInstance(), false);
                  ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§c§l " + label + " deaktiviert"));
                  return 1;
               })))
            .then(((LiteralArgumentBuilder)ClientCommandManager.literal("always").then(ClientCommandManager.literal("on").executes(ctx -> {
               // "always on" must leave the feature usable — enable it too, or the flag is dead config.
               setEnabled.accept(VehicleConfig.getInstance(), true);
               setAlways.accept(VehicleConfig.getInstance(), true);
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§a§l " + label + ": §raktiviert, auch außer Dienst aktiv"));
               return 1;
            }))).then(ClientCommandManager.literal("off").executes(ctx -> {
               setAlways.accept(VehicleConfig.getInstance(), false);
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§e§l " + label + ": §rnur im Dienst aktiv"));
               return 1;
            }))))
         .executes(ctx -> {
            VehicleConfig cfg = VehicleConfig.getInstance();
            ((FabricClientCommandSource)ctx.getSource())
               .sendFeedback(Text.literal("§e" + label + ": " + describe(getEnabled.apply(cfg), getAlways.apply(cfg))));
            return 1;
         });
   }

   private static void sendStatus(FabricClientCommandSource source) {
      VehicleConfig cfg = VehicleConfig.getInstance();
      source.sendFeedback(Text.literal("§6GM-Medic Fahrzeug-Automatik:"));
      source.sendFeedback(Text.literal("§eMotor: " + describe(cfg.isMotorEnabled(), cfg.isMotorAlways())));
      source.sendFeedback(Text.literal("§eGangschaltung: " + describe(cfg.isGearEnabled(), cfg.isGearAlways())));
      source.sendFeedback(Text.literal("§eSirene: " + describe(cfg.isSirenEnabled(), cfg.isSirenAlways())));
      source.sendFeedback(
         Text.literal("§eAusstiegs-Verzögerung: " + cfg.getExitDelayTicks() + " Ticks (" + String.format("%.1f", (double)cfg.getExitDelayTicks() / 20.0) + "s)")
      );
   }

   private static String describe(boolean enabled, boolean always) {
      return !enabled ? "deaktiviert" : "aktiviert (" + (always ? "immer" : "nur im Dienst") + ")";
   }
}
