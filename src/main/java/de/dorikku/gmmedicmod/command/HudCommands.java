package de.dorikku.gmmedicmod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import de.dorikku.gmmedicmod.config.HudConfig;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;

public class HudCommands {
   public HudCommands() {
   }

   public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext registryAccess) {
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)ClientCommands.literal("gmhud")
                     .then(ClientCommands.literal("compact").executes(ctx -> {
                        HudConfig.getInstance().toggleCompactMode();
                        boolean enabled = HudConfig.getInstance().isCompactMode();
                        String message = enabled
                           ? "§a§l Kompakter HUD-Modus aktiviert §r(smaller display)"
                           : "§c§l Kompakter HUD-Modus deaktiviert §r(normal display)";
                        ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Component.literal(message));
                        return 1;
                     })))
                  .then(
                     ((LiteralArgumentBuilder)ClientCommands.literal("highlight")
                           .then(
                              ClientCommands.literal("range")
                                 .then(
                                    ClientCommands.argument("blocks", IntegerArgumentType.integer(8, 256))
                                       .executes(
                                          ctx -> {
                                             int blocks = IntegerArgumentType.getInteger(ctx, "blocks");
                                             HudConfig.getInstance().setHighlightRange((double)blocks);
                                             ((FabricClientCommandSource)ctx.getSource())
                                                .sendFeedback(
                                                   Component.literal(
                                                      "§a§l Highlight-Reichweite gesetzt: §r" + (int)HudConfig.getInstance().getHighlightRange() + " Blöcke"
                                                   )
                                                );
                                             return 1;
                                          }
                                       )
                                 )
                           ))
                        .executes(ctx -> {
                           HudConfig.getInstance().toggleHighlight();
                           boolean enabled = HudConfig.getInstance().isHighlightEnabled();
                           String message = enabled ? "§a§l Spieler-Highlight aktiviert §r(Notruf-Spieler markieren)" : "§c§l Spieler-Highlight deaktiviert";
                           ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Component.literal(message));
                           return 1;
                        })
                  ))
               .then(ClientCommands.literal("status").executes(ctx -> {
                  HudConfig cfg = HudConfig.getInstance();
                  String compact = cfg.isCompactMode() ? "aktiviert (compact)" : "deaktiviert (normal)";
                  String highlight = cfg.isHighlightEnabled() ? "aktiviert (" + (int)cfg.getHighlightRange() + " Blöcke)" : "deaktiviert";
                  ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Component.literal("§eHUD-Modus: " + compact));
                  ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Component.literal("§eHighlight: " + highlight));
                  return 1;
               })))
            .executes(ctx -> {
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Component.literal("§6Verfügbare HUD-Befehle:"));
               ((FabricClientCommandSource)ctx.getSource())
                  .sendFeedback(Component.literal("§a  /gmhud compact §r- Toggle compact display mode (for large GUI scales)"));
               ((FabricClientCommandSource)ctx.getSource())
                  .sendFeedback(Component.literal("§a  /gmhud highlight §r- Toggle highlighting of players with an open call"));
               ((FabricClientCommandSource)ctx.getSource())
                  .sendFeedback(Component.literal("§a  /gmhud highlight range <blocks> §r- Set the highlight max distance"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Component.literal("§a  /gmhud status §r- Show current HUD settings"));
               return 1;
            })
      );
   }
}
