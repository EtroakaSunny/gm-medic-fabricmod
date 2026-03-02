package de.dorikku.gmmedicmod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.dorikku.gmmedicmod.api.ApiClient;
import de.dorikku.gmmedicmod.config.ApiConfig;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.text.Text;

/**
 * Commands for managing the API sync connection.
 * Available to all players (not dev-only).
 */
public class ApiCommands {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
        dispatcher.register(ClientCommandManager.literal("gmapi")

                // /gmapi status — show connection status & config
                .then(ClientCommandManager.literal("status")
                        .executes(ctx -> {
                            ApiConfig config = ApiConfig.getInstance();
                            ApiClient client = ApiClient.getInstance();
                            ApiClient.ConnectionState state = client.getState();

                            ctx.getSource().sendFeedback(Text.literal("§6§l--- GM-Medic API Status ---"));
                            ctx.getSource().sendFeedback(Text.literal(
                                    "§7Enabled: " + (config.isEnabled() ? "§a✔ Ja" : "§c✘ Nein")
                            ));
                            ctx.getSource().sendFeedback(Text.literal(
                                    "§7URL: §f" + config.getApiUrl()
                            ));
                            ctx.getSource().sendFeedback(Text.literal(
                                    "§7API Key: §f" + (config.getApiKey().isEmpty() ? "§c(nicht gesetzt)" : "§a****" + config.getApiKey().substring(Math.max(0, config.getApiKey().length() - 4)))
                            ));
                            ctx.getSource().sendFeedback(Text.literal(
                                    "§7Poll Interval: §f" + config.getPollIntervalSeconds() + "s"
                            ));
                            ctx.getSource().sendFeedback(Text.literal(
                                    "§7Client ID: §f" + config.getClientId()
                            ));

                            String stateColor = switch (state) {
                                case CONNECTED -> "§a";
                                case CONNECTING -> "§e";
                                case ERROR -> "§c";
                                case DISCONNECTED -> "§7";
                            };
                            String stateLabel = switch (state) {
                                case CONNECTED -> "Verbunden";
                                case CONNECTING -> "Verbinden...";
                                case ERROR -> "Fehler";
                                case DISCONNECTED -> "Getrennt";
                            };
                            ctx.getSource().sendFeedback(Text.literal(
                                    "§7Verbindung: " + stateColor + stateLabel
                            ));

                            if (client.getLastError() != null) {
                                ctx.getSource().sendFeedback(Text.literal(
                                        "§cFehler: §f" + client.getLastError()
                                ));
                            }
                            return 1;
                        })
                )

                // /gmapi enable — enable API sync
                .then(ClientCommandManager.literal("enable")
                        .executes(ctx -> {
                            ApiConfig.getInstance().setEnabled(true);
                            ctx.getSource().sendFeedback(Text.literal("§a✔ API Sync aktiviert."));
                            ctx.getSource().sendFeedback(Text.literal("§7Vergiss nicht, URL und API-Key zu setzen!"));
                            return 1;
                        })
                )

                // /gmapi disable — disable API sync
                .then(ClientCommandManager.literal("disable")
                        .executes(ctx -> {
                            ApiConfig.getInstance().setEnabled(false);
                            ApiClient.getInstance().disconnect();
                            ctx.getSource().sendFeedback(Text.literal("§c✘ API Sync deaktiviert."));
                            return 1;
                        })
                )

                // /gmapi url <url> — set the API URL
                .then(ClientCommandManager.literal("url")
                        .then(ClientCommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String url = StringArgumentType.getString(ctx, "url");
                                    // Remove trailing slash
                                    if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
                                    ApiConfig.getInstance().setApiUrl(url);
                                    ctx.getSource().sendFeedback(Text.literal("§a✔ API URL gesetzt: §f" + url));
                                    return 1;
                                })
                        )
                )

                // /gmapi key <key> — set the API key
                .then(ClientCommandManager.literal("key")
                        .then(ClientCommandManager.argument("key", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String key = StringArgumentType.getString(ctx, "key");
                                    ApiConfig.getInstance().setApiKey(key);
                                    ctx.getSource().sendFeedback(Text.literal("§a✔ API Key gesetzt."));
                                    return 1;
                                })
                        )
                )

                // /gmapi interval <seconds> — set polling interval
                .then(ClientCommandManager.literal("interval")
                        .then(ClientCommandManager.argument("seconds", IntegerArgumentType.integer(1, 30))
                                .executes(ctx -> {
                                    int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
                                    ApiConfig.getInstance().setPollIntervalSeconds(seconds);
                                    ctx.getSource().sendFeedback(Text.literal("§a✔ Poll-Intervall: §f" + seconds + "s"));
                                    ctx.getSource().sendFeedback(Text.literal("§7Wird beim nächsten Dienstbeginn wirksam."));
                                    return 1;
                                })
                        )
                )

                // /gmapi reconnect — force reconnect
                .then(ClientCommandManager.literal("reconnect")
                        .executes(ctx -> {
                            ApiClient api = ApiClient.getInstance();
                            api.disconnect();
                            String playerName = ctx.getSource().getPlayer().getNameForScoreboard();
                            api.connect(playerName);
                            ctx.getSource().sendFeedback(Text.literal("§e⟳ Verbindung wird neu aufgebaut..."));
                            return 1;
                        })
                )

                // /gmapi (no argument) — show help
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(Text.literal("§6Verfügbare API-Befehle:"));
                    ctx.getSource().sendFeedback(Text.literal("§a  /gmapi status §r- Verbindungsstatus anzeigen"));
                    ctx.getSource().sendFeedback(Text.literal("§a  /gmapi enable §r- API Sync aktivieren"));
                    ctx.getSource().sendFeedback(Text.literal("§a  /gmapi disable §r- API Sync deaktivieren"));
                    ctx.getSource().sendFeedback(Text.literal("§a  /gmapi url <url> §r- Server-URL setzen"));
                    ctx.getSource().sendFeedback(Text.literal("§a  /gmapi key <key> §r- API-Key setzen"));
                    ctx.getSource().sendFeedback(Text.literal("§a  /gmapi interval <s> §r- Poll-Intervall setzen (1-30s)"));
                    ctx.getSource().sendFeedback(Text.literal("§a  /gmapi reconnect §r- Verbindung neu aufbauen"));
                    return 1;
                })
        );
    }
}

