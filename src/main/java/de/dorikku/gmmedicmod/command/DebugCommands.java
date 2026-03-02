package de.dorikku.gmmedicmod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.message.MessageHandler;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Debug commands for testing the emergency call system without being on a real server.
 * All commands start with /gm
 */
public class DebugCommands {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
        dispatcher.register(ClientCommandManager.literal("gm")

                // /gm duty
                .then(ClientCommandManager.literal("duty")
                        .executes(ctx -> {
                            EmergencyCallManager mgr = EmergencyCallManager.getInstance();
                            mgr.setInDuty(!mgr.isInDuty());
                            ctx.getSource().sendFeedback(Text.literal(
                                    mgr.isInDuty() ? "\u00a7a\u2714 Du bist nun im Dienst." : "\u00a7c\u2718 Du bist nicht mehr im Dienst."
                            ));
                            return 1;
                        })
                )

                // /gm call <name> <reason>
                .then(ClientCommandManager.literal("call")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .then(ClientCommandManager.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "name");
                                            String reason = StringArgumentType.getString(ctx, "reason");
                                            double x = ctx.getSource().getPlayer().getX();
                                            double y = ctx.getSource().getPlayer().getY();
                                            double z = ctx.getSource().getPlayer().getZ();

                                            EmergencyCallManager mgr = EmergencyCallManager.getInstance();
                                            if (!mgr.isInDuty()) {
                                                mgr.setInDuty(true);
                                                ctx.getSource().sendFeedback(Text.literal("\u00a7eDienst automatisch aktiviert."));
                                            }

                                            EmergencyCall call = new EmergencyCall(name, reason, x, y, z, "", EmergencyCall.CallType.ECALL);
                                            mgr.addCall(call);
                                            ctx.getSource().sendFeedback(Text.literal(
                                                    "\u00a7a\u2714 Notruf erstellt: \u00a7f" + name + " \u00a77- " + reason +
                                                    " \u00a77(X:" + (int) x + " Y:" + (int) y + " Z:" + (int) z + ")"
                                            ));
                                            return 1;
                                        })
                                )
                        )
                )

                // /gm death <name> <reason>
                .then(ClientCommandManager.literal("death")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .then(ClientCommandManager.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "name");
                                            String reason = StringArgumentType.getString(ctx, "reason");
                                            double x = ctx.getSource().getPlayer().getX();
                                            double y = ctx.getSource().getPlayer().getY();
                                            double z = ctx.getSource().getPlayer().getZ();

                                            EmergencyCallManager mgr = EmergencyCallManager.getInstance();
                                            if (!mgr.isInDuty()) {
                                                mgr.setInDuty(true);
                                                ctx.getSource().sendFeedback(Text.literal("\u00a7eDienst automatisch aktiviert."));
                                            }

                                            EmergencyCall call = new EmergencyCall(name, reason, x, y, z, "", EmergencyCall.CallType.DEATH);
                                            call.setRemainingSeconds(5 * 60);
                                            mgr.addCall(call);
                                            ctx.getSource().sendFeedback(Text.literal(
                                                    "\u00a7c\u2620 Todesmeldung erstellt: \u00a7f" + name + " \u00a77- " + reason
                                            ));
                                            return 1;
                                        })
                                )
                        )
                )

                // /gm accept <caller> <medic>
                .then(ClientCommandManager.literal("accept")
                        .then(ClientCommandManager.argument("caller", StringArgumentType.word())
                                .then(ClientCommandManager.argument("medic", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String caller = StringArgumentType.getString(ctx, "caller");
                                            String medic = StringArgumentType.getString(ctx, "medic");
                                            EmergencyCallManager.getInstance().assignMedic(caller, medic);
                                            ctx.getSource().sendFeedback(Text.literal(
                                                    "\u00a7a\u2714 " + medic + " \u00fcbernimmt den Notruf von " + caller
                                            ));
                                            return 1;
                                        })
                                )
                        )
                )

                // /gm remove <name>
                .then(ClientCommandManager.literal("remove")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    EmergencyCallManager.getInstance().removeCallByCallerName(name);
                                    ctx.getSource().sendFeedback(Text.literal(
                                            "\u00a7c\u2718 Notruf von " + name + " entfernt."
                                    ));
                                    return 1;
                                })
                        )
                )

                // /gm clear
                .then(ClientCommandManager.literal("clear")
                        .executes(ctx -> {
                            EmergencyCallManager mgr = EmergencyCallManager.getInstance();
                            mgr.setInDuty(false);
                            ctx.getSource().sendFeedback(Text.literal("\u00a7c\u2718 Alle Notrufe gel\u00f6scht, Dienst beendet."));
                            return 1;
                        })
                )

                // /gm status
                .then(ClientCommandManager.literal("status")
                        .executes(ctx -> {
                            EmergencyCallManager mgr = EmergencyCallManager.getInstance();
                            List<EmergencyCall> calls = mgr.getActiveCalls();

                            ctx.getSource().sendFeedback(Text.literal("\u00a76\u00a7l--- GM Medic Status ---"));
                            ctx.getSource().sendFeedback(Text.literal(
                                    "\u00a77Dienst: " + (mgr.isInDuty() ? "\u00a7a\u2714 Aktiv" : "\u00a7c\u2718 Inaktiv")
                            ));
                            ctx.getSource().sendFeedback(Text.literal(
                                    "\u00a77Aktive Notrufe: \u00a7f" + calls.size()
                            ));

                            for (int i = 0; i < calls.size(); i++) {
                                EmergencyCall call = calls.get(i);
                                String type = call.getType() == EmergencyCall.CallType.DEATH ? "\u00a7c\u2620 Tod" : "\u00a7e\ud83d\udd14 Notruf";
                                String pending = call.isPending() ? " \u00a7e\u23f3 ausstehend" : "";
                                String medic = call.isAccepted() ? " \u00a77\u2192 \u00a7b" + call.getAssignedMedic() : " \u00a78(offen)";
                                ctx.getSource().sendFeedback(Text.literal(
                                        "  \u00a77#" + (i + 1) + " " + type + " \u00a7f" + call.getCallerName() +
                                        " \u00a77- " + call.getReason() + pending + medic
                                ));
                            }
                            return 1;
                        })
                )

                // /gm simulate
                .then(ClientCommandManager.literal("simulate")
                        .executes(ctx -> {
                            EmergencyCallManager mgr = EmergencyCallManager.getInstance();
                            if (!mgr.isInDuty()) {
                                mgr.setInDuty(true);
                                ctx.getSource().sendFeedback(Text.literal("\u00a7eDienst automatisch aktiviert."));
                            }

                            double x = ctx.getSource().getPlayer().getX();
                            double y = ctx.getSource().getPlayer().getY();
                            double z = ctx.getSource().getPlayer().getZ();

                            mgr.addCall(new EmergencyCall("MaxMustermann", "Herzinfarkt", x + 100, y, z + 50, "Innenstadt", EmergencyCall.CallType.ECALL));
                            mgr.addCall(new EmergencyCall("LisaMueller", "Autounfall auf der A1", x - 200, y, z + 300, "Autobahn A1", EmergencyCall.CallType.ECALL));
                            EmergencyCall deathCall = new EmergencyCall("TomSchmidt", "Ertrunken", x + 50, y, z - 100, "Hafen", EmergencyCall.CallType.DEATH);
                            deathCall.setRemainingSeconds(5 * 60);
                            deathCall.setAssignedMedic("DrHouse");
                            mgr.addCall(deathCall);

                            ctx.getSource().sendFeedback(Text.literal("\u00a7a\u2714 3 Testnotrufe erstellt."));
                            return 1;
                        })
                )

                // /gm testdeath [name]
                .then(ClientCommandManager.literal("testdeath")
                        .executes(ctx -> simulateDeathTransmission(ctx.getSource(), "Toxic_padz"))
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .executes(ctx -> simulateDeathTransmission(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")
                                ))
                        )
                )

                // /gm testcall [name]
                .then(ClientCommandManager.literal("testcall")
                        .executes(ctx -> simulateCallTransmission(ctx.getSource(), "MaxMustermann"))
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .executes(ctx -> simulateCallTransmission(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")
                                ))
                        )
                )

                // /gm testduty
                .then(ClientCommandManager.literal("testduty")
                        .executes(ctx -> {
                            FabricClientCommandSource src = ctx.getSource();
                            src.sendFeedback(Text.literal("\u00a77Simuliere Dienst-Nachrichten durch den MessageHandler..."));
                            simulateChatMessage("\u00a7e\u2503 \u00a7620:58:12 \u00a78\u00bb \u00a7r\u00bb \u2714 Du bist nun im Dienst.");
                            src.sendFeedback(Text.literal("\u00a77\u2192 \u00a7aDienst betreten gesendet."));
                            return 1;
                        })
                )

                // /gm testoffduty
                .then(ClientCommandManager.literal("testoffduty")
                        .executes(ctx -> {
                            FabricClientCommandSource src = ctx.getSource();
                            simulateChatMessage("\u00a7e\u2503 \u00a7620:59:00 \u00a78\u00bb \u00a7r\u00bb \u2714 Du hast den Dienst verlassen.");
                            src.sendFeedback(Text.literal("\u00a77\u2192 \u00a7cDienst verlassen gesendet."));
                            return 1;
                        })
                )

                // /gm testfunk
                .then(ClientCommandManager.literal("testfunk")
                        .executes(ctx -> {
                            FabricClientCommandSource src = ctx.getSource();
                            String playerName = ctx.getSource().getPlayer().getNameForScoreboard();

                            src.sendFeedback(Text.literal("\u00a77Simuliere FUNK-Nachrichten..."));

                            simulateChatMessage("[FUNK] (Assistent) " + playerName + " \u00bb Ich bin wieder auf dem Server! *Roger*");
                            src.sendFeedback(Text.literal("\u00a7a\u2192 FUNK join gesendet"));

                            simulateChatMessage("[FUNK] (Facharzt) mmlp12345 \u00bb Ich bin nun auf dem Weg zu dem Notruf von F3lixus.");
                            src.sendFeedback(Text.literal("\u00a7e\u2192 Notruf-Annahme gesendet"));

                            simulateChatMessage("[FUNK] ZENTRALE \u00bb Der Spieler F3lixus hat seinen Notruf zur\u00fcckgezogen.");
                            src.sendFeedback(Text.literal("\u00a7c\u2192 Notruf-R\u00fccknahme gesendet"));

                            simulateChatMessage("[FUNK] (Sanit\u00e4ter) DrHouse \u00bb Ich habe Toxic_padz wiederbelebt!");
                            src.sendFeedback(Text.literal("\u00a7a\u2192 Wiederbelebung gesendet"));

                            simulateChatMessage("[FUNK] (Assistent) " + playerName + " \u00bb Ich bin nicht mehr im Dienst. Bis dann!");
                            src.sendFeedback(Text.literal("\u00a7c\u2192 FUNK leave gesendet"));

                            return 1;
                        })
                )

                // /gm testfull
                .then(ClientCommandManager.literal("testfull")
                        .executes(ctx -> {
                            FabricClientCommandSource src = ctx.getSource();
                            String playerName = ctx.getSource().getPlayer().getNameForScoreboard();

                            src.sendFeedback(Text.literal("\u00a76\u00a7l--- Volle GermanMiner-Simulation ---"));

                            // Step 1: Join duty
                            simulateChatMessage("\u00a7e\u2503 \u00a7620:58:12 \u00a78\u00bb \u00a7r\u00bb \u2714 Du bist nun im Dienst.");
                            simulateChatMessage("[FUNK] (Assistent) " + playerName + " \u00bb Ich bin wieder auf dem Server! *Roger*");

                            // Step 2: Death call comes in
                            simulateChatMessage("[FUNK] ZENTRALE \u00bb Wir haben eine neue Todesmeldung erhalten - ich schicke euch die Daten r\u00fcber!");
                            simulateChatMessage("\u00a7e\u2503 \u00a7620:58:30 \u00a78\u00bb \u00a7r  ----- DATEN\u00dcBERMITTLUNG VON ZENTRALE -----");
                            simulateChatMessage("\u00a7e\u2503 \u00a7620:58:30 \u00a78\u00bb \u00a7r - Betroffener: Toxic_padz");
                            simulateChatMessage("\u00a7e\u2503 \u00a7620:58:30 \u00a78\u00bb \u00a7r - Verbleibende Zeit: 4 Minuten, 58 Sekunden");
                            simulateChatMessage("\u00a7e\u2503 \u00a7620:58:30 \u00a78\u00bb \u00a7r - Todesursache: T\u00f6tungsdelikt");
                            simulateChatMessage("\u00a7e\u2503 \u00a7620:58:30 \u00a78\u00bb \u00a7r - Distanz: 2733 Meter");
                            simulateChatMessage("\u00a7e\u2503 \u00a7620:58:30 \u00a78\u00bb \u00a7r\u00a7e - Ortung: \u00a7f\u00a7fX: -1488 Y: 63 Z: -2095 (Offenbach Nord)");
                            simulateChatMessage("\u00a7e\u2503 \u00a7620:58:30 \u00a78\u00bb \u00a7r - Auf dem Weg: Niemand!");
                            simulateChatMessage("\u00a7e\u2503 \u00a7620:58:30 \u00a78\u00bb \u00a7r");
                            simulateChatMessage("\u00a7e\u2503 \u00a7620:58:30 \u00a78\u00bb \u00a7r  \u00a7aANNEHMEN      \u00a7eANRUFEN      \u00a7cMELDEN      \u00a74ZUR\u00dcCKWEISEN");

                            // Step 3: Normal Notruf comes in
                            simulateChatMessage("[FUNK] ZENTRALE \u00bb Wir haben einen neuen Notruf erhalten - ich schicke euch die Daten r\u00fcber!");
                            simulateChatMessage("  ----- DATEN\u00dcBERMITTLUNG VON ZENTRALE -----");
                            simulateChatMessage(" - Notruf von: F3lixus");
                            simulateChatMessage(" - Erstellt: 01.03.2026, 20:58");
                            simulateChatMessage(" - Grund: ich brauche heal");
                            simulateChatMessage(" - Distanz: 2310 Meter");
                            simulateChatMessage("\u00a7e - Ortung: \u00a7f\u00a7fX: -1332 Y: 82 Z: -550 (S\u00fcdlicher Gebirgszug)");
                            simulateChatMessage(" - Auf dem Weg: Niemand!");
                            simulateChatMessage("");
                            simulateChatMessage("  \u00a7aANNEHMEN      \u00a7eANRUFEN      \u00a7cMELDEN      \u00a74ZUR\u00dcCKWEISEN");

                            // Step 4: Medic accepts F3lixus call
                            simulateChatMessage("[FUNK] (Facharzt) mmlp12345 \u00bb Ich bin nun auf dem Weg zu dem Notruf von F3lixus.");

                            src.sendFeedback(Text.literal("\u00a7a\u2714 Volle Simulation abgeschlossen. HUD sollte 2 Notrufe zeigen."));
                            return 1;
                        })
                )


                // /gm help
                .then(ClientCommandManager.literal("help")
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(Text.literal("\u00a76\u00a7l--- GM Medic Debug Commands ---"));
                            ctx.getSource().sendFeedback(Text.literal("\u00a7e/gm duty \u00a77- Dienst an/aus"));
                            ctx.getSource().sendFeedback(Text.literal("\u00a7e/gm call <name> <grund> \u00a77- Notruf direkt hinzuf\u00fcgen"));
                            ctx.getSource().sendFeedback(Text.literal("\u00a7e/gm death <name> <grund> \u00a77- Todesmeldung direkt hinzuf\u00fcgen"));
                            ctx.getSource().sendFeedback(Text.literal("\u00a76\u00a7l--- Realistische Simulationen (via MessageHandler) ---"));
                            ctx.getSource().sendFeedback(Text.literal("\u00a7e/gm testfull \u00a77- Volle GermanMiner-Simulation ([FUNK] Format)"));
                            ctx.getSource().sendFeedback(Text.literal("\u00a7e/gm testdeath [name] \u00a77- DATEN\u00dcBERMITTLUNG (Tod) simulieren"));
                            ctx.getSource().sendFeedback(Text.literal("\u00a7e/gm testcall [name] \u00a77- DATEN\u00dcBERMITTLUNG (Notruf) simulieren"));
                            ctx.getSource().sendFeedback(Text.literal("\u00a7e/gm testduty \u00a77- Dienst betreten simulieren"));
                            ctx.getSource().sendFeedback(Text.literal("\u00a7e/gm testoffduty \u00a77- Dienst verlassen simulieren"));
                            ctx.getSource().sendFeedback(Text.literal("\u00a7e/gm testfunk \u00a77- FUNK-Nachrichten simulieren"));
                            ctx.getSource().sendFeedback(Text.literal("\u00a76\u00a7l--- Direkte Verwaltung ---"));
                            ctx.getSource().sendFeedback(Text.literal("\u00a7e/gm accept <anrufer> <medic> \u00a77- Notruf zuweisen"));
                            ctx.getSource().sendFeedback(Text.literal("\u00a7e/gm remove <name> \u00a77- Notruf entfernen"));
                            ctx.getSource().sendFeedback(Text.literal("\u00a7e/gm simulate \u00a77- 3 Testnotrufe direkt erstellen"));
                            ctx.getSource().sendFeedback(Text.literal("\u00a7e/gm clear \u00a77- Alles zur\u00fccksetzen"));
                            ctx.getSource().sendFeedback(Text.literal("\u00a7e/gm status \u00a77- Status anzeigen"));
                            return 1;
                        })
                )

                // /gm (no argument)
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(Text.literal("\u00a7e/gm help \u00a77f\u00fcr alle Befehle"));
                    return 1;
                })
        );
    }

    /**
     * Simulates the exact DATEN\u00dcBERMITTLUNG block for a death call.
     */
    private static int simulateDeathTransmission(FabricClientCommandSource src, String name) {
        EmergencyCallManager mgr = EmergencyCallManager.getInstance();
        if (!mgr.isInDuty()) {
            mgr.setInDuty(true);
            src.sendFeedback(Text.literal("\u00a7eDienst automatisch aktiviert."));
        }

        src.sendFeedback(Text.literal("\u00a77Simuliere DATEN\u00dcBERMITTLUNG (Tod) f\u00fcr: \u00a7f" + name));

        simulateChatMessage("\u00a7e\u2503 \u00a7616:24:08 \u00a78\u00bb \u00a7r[FUNK] ZENTRALE \u00bb Wir haben eine neue Todesmeldung erhalten - ich schicke euch die Daten r\u00fcber!");
        simulateChatMessage("\u00a7e\u2503 \u00a7616:24:08 \u00a78\u00bb \u00a7r  ----- DATEN\u00dcBERMITTLUNG VON ZENTRALE -----");
        simulateChatMessage("\u00a7e\u2503 \u00a7616:24:08 \u00a78\u00bb \u00a7r - Betroffener: " + name);
        simulateChatMessage("\u00a7e\u2503 \u00a7616:24:08 \u00a78\u00bb \u00a7r - Verbleibende Zeit: 4 Minuten, 58 Sekunden");
        simulateChatMessage("\u00a7e\u2503 \u00a7616:24:08 \u00a78\u00bb \u00a7r - Todesursache: T\u00f6tungsdelikt");
        simulateChatMessage("\u00a7e\u2503 \u00a7616:24:08 \u00a78\u00bb \u00a7r - Distanz: 2733 Meter");
        simulateChatMessage("\u00a7e\u2503 \u00a7616:24:08 \u00a78\u00bb \u00a7r\u00a7e - Ortung: \u00a7f\u00a7fX: -1488 Y: 63 Z: -2095 (Offenbach Nord)");
        simulateChatMessage("\u00a7e\u2503 \u00a7616:24:08 \u00a78\u00bb \u00a7r - Auf dem Weg: Niemand!");
        simulateChatMessage("\u00a7e\u2503 \u00a7616:24:08 \u00a78\u00bb \u00a7r");
        simulateChatMessage("\u00a7e\u2503 \u00a7616:24:08 \u00a78\u00bb \u00a7r  \u00a7aANNEHMEN      \u00a7eANRUFEN      \u00a7cMELDEN      \u00a74ZUR\u00dcCKWEISEN");

        src.sendFeedback(Text.literal("\u00a7a\u2714 Simulation abgeschlossen."));
        return 1;
    }

    /**
     * Simulates the exact DATEN\u00dcBERMITTLUNG block for a normal Notruf.
     */
    private static int simulateCallTransmission(FabricClientCommandSource src, String name) {
        EmergencyCallManager mgr = EmergencyCallManager.getInstance();
        if (!mgr.isInDuty()) {
            mgr.setInDuty(true);
            src.sendFeedback(Text.literal("\u00a7eDienst automatisch aktiviert."));
        }

        src.sendFeedback(Text.literal("\u00a77Simuliere DATEN\u00dcBERMITTLUNG (Notruf) f\u00fcr: \u00a7f" + name));

        simulateChatMessage("[FUNK] ZENTRALE \u00bb Wir haben einen neuen Notruf erhalten - ich schicke euch die Daten r\u00fcber!");
        simulateChatMessage("  ----- DATEN\u00dcBERMITTLUNG VON ZENTRALE -----");
        simulateChatMessage(" - Notruf von: " + name);
        simulateChatMessage(" - Erstellt: 01.03.2026, 20:58");
        simulateChatMessage(" - Grund: ich brauche heal");
        simulateChatMessage(" - Distanz: 2310 Meter");
        simulateChatMessage("\u00a7e - Ortung: \u00a7f\u00a7fX: -1332 Y: 82 Z: -550 (S\u00fcdlicher Gebirgszug)");
        simulateChatMessage(" - Auf dem Weg: Niemand!");
        simulateChatMessage("");
        simulateChatMessage("  \u00a7aANNEHMEN      \u00a7eANRUFEN      \u00a7cMELDEN      \u00a74ZUR\u00dcCKWEISEN");

        src.sendFeedback(Text.literal("\u00a7a\u2714 Simulation abgeschlossen."));
        return 1;
    }

    /**
     * Simulates a server system message by feeding it through the real MessageHandler.
     */
    private static void simulateChatMessage(String rawMessage) {
        Text text = Text.literal(rawMessage);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            MessageHandler handler = client.getMessageHandler();
            handler.onGameMessage(text, false);
        }
    }
}

