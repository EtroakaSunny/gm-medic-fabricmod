package de.dorikku.gmmedicmod.command;

import com.mojang.brigadier.CommandDispatcher;
import de.dorikku.gmmedicmod.config.HudConfig;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.text.Text;

/**
 * Commands for HUD configuration.
 */
public class HudCommands {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
        dispatcher.register(ClientCommandManager.literal("gmhud")

                // /gmhud compact - toggle compact mode
                .then(ClientCommandManager.literal("compact")
                        .executes(ctx -> {
                            HudConfig.getInstance().toggleCompactMode();
                            boolean enabled = HudConfig.getInstance().isCompactMode();
                            String message = enabled
                                ? "\u00a7a\u00a7l Kompakter HUD-Modus aktiviert \u00a7r(smaller display)"
                                : "\u00a7c\u00a7l Kompakter HUD-Modus deaktiviert \u00a7r(normal display)";
                            ctx.getSource().sendFeedback(Text.literal(message));
                            return 1;
                        })
                )

                // /gmhud status - show current config
                .then(ClientCommandManager.literal("status")
                        .executes(ctx -> {
                            boolean compact = HudConfig.getInstance().isCompactMode();
                            String status = compact ? "aktiviert (compact)" : "deaktiviert (normal)";
                            ctx.getSource().sendFeedback(Text.literal("\u00a7eHUD-Einstellungen: " + status));
                            return 1;
                        })
                )

                // /gmhud default display mode
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(Text.literal("\u00a76Verfügbare HUD-Befehle:"));
                    ctx.getSource().sendFeedback(Text.literal("\u00a7a  /gmhud compact \u00a7r- Toggle compact display mode (for large GUI scales)"));
                    ctx.getSource().sendFeedback(Text.literal("\u00a7a  /gmhud status \u00a7r- Show current HUD settings"));
                    return 1;
                })
        );
    }
}

