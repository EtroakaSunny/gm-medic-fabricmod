package de.dorikku.gmmedicmod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import de.dorikku.gmmedicmod.config.VehicleConfig;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;

public class VehicleCommands {
   public VehicleCommands() {
   }

   public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext registryAccess) {
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)ClientCommands.literal(
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
                     ClientCommands.literal("exitdelay")
                        .then(
                           ClientCommands.argument("ticks", IntegerArgumentType.integer(1, 200))
                              .executes(
                                 ctx -> {
                                    int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
                                    VehicleConfig.getInstance().setExitDelayTicks(ticks);
                                    ((FabricClientCommandSource)ctx.getSource())
                                       .sendFeedback(
                                          Component.literal(
                                             "§a§l Ausstiegs-Verzögerung gesetzt: §r" + ticks + " Ticks (" + String.format("%.1f", (double)ticks / 20.0) + "s)"
                                          )
                                       );
                                    return 1;
                                 }
                              )
                        )
                  ))
               .then(ClientCommands.literal("status").executes(ctx -> {
                  sendStatus((FabricClientCommandSource)ctx.getSource());
                  return 1;
               })))
            .executes(ctx -> {
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Component.literal("§6Verfügbare Fahrzeug-Befehle:"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Component.literal("§a  /gmvehicle motor on|off §r- Motor automatisch starten"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Component.literal("§a  /gmvehicle gear on|off §r- Gangschaltung automatisch setzen"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Component.literal("§a  /gmvehicle siren on|off §r- Sirene bei Notruf automatisch"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Component.literal("§a  /gmvehicle <feature> always on|off §r- Auch außer Dienst aktiv"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Component.literal("§a  /gmvehicle exitdelay <ticks> §r- Verzögerung beim Aussteigen"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Component.literal("§a  /gmvehicle status §r- Aktuelle Einstellungen anzeigen"));
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
      return (LiteralArgumentBuilder<FabricClientCommandSource>)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)ClientCommands.literal(
                     name
                  )
                  .then(ClientCommands.literal("on").executes(ctx -> {
                     setEnabled.accept(VehicleConfig.getInstance(), true);
                     ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Component.literal("§a§l " + label + " aktiviert"));
                     return 1;
                  })))
               .then(ClientCommands.literal("off").executes(ctx -> {
                  setEnabled.accept(VehicleConfig.getInstance(), false);
                  ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Component.literal("§c§l " + label + " deaktiviert"));
                  return 1;
               })))
            .then(((LiteralArgumentBuilder)ClientCommands.literal("always").then(ClientCommands.literal("on").executes(ctx -> {
               // "always on" must leave the feature usable — enable it too, or the flag is dead config.
               setEnabled.accept(VehicleConfig.getInstance(), true);
               setAlways.accept(VehicleConfig.getInstance(), true);
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Component.literal("§a§l " + label + ": §raktiviert, auch außer Dienst aktiv"));
               return 1;
            }))).then(ClientCommands.literal("off").executes(ctx -> {
               setAlways.accept(VehicleConfig.getInstance(), false);
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Component.literal("§e§l " + label + ": §rnur im Dienst aktiv"));
               return 1;
            }))))
         .executes(ctx -> {
            VehicleConfig cfg = VehicleConfig.getInstance();
            ((FabricClientCommandSource)ctx.getSource())
               .sendFeedback(Component.literal("§e" + label + ": " + describe(getEnabled.apply(cfg), getAlways.apply(cfg))));
            return 1;
         });
   }

   private static void sendStatus(FabricClientCommandSource source) {
      VehicleConfig cfg = VehicleConfig.getInstance();
      source.sendFeedback(Component.literal("§6GM-Medic Fahrzeug-Automatik:"));
      source.sendFeedback(Component.literal("§eMotor: " + describe(cfg.isMotorEnabled(), cfg.isMotorAlways())));
      source.sendFeedback(Component.literal("§eGangschaltung: " + describe(cfg.isGearEnabled(), cfg.isGearAlways())));
      source.sendFeedback(Component.literal("§eSirene: " + describe(cfg.isSirenEnabled(), cfg.isSirenAlways())));
      source.sendFeedback(
         Component.literal("§eAusstiegs-Verzögerung: " + cfg.getExitDelayTicks() + " Ticks (" + String.format("%.1f", (double)cfg.getExitDelayTicks() / 20.0) + "s)")
      );
   }

   private static String describe(boolean enabled, boolean always) {
      return !enabled ? "deaktiviert" : "aktiviert (" + (always ? "immer" : "nur im Dienst") + ")";
   }
}
