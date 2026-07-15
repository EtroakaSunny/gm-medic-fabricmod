package de.dorikku.gmmedicmod.vehicle;

import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.config.VehicleConfig;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.mixin.BossBarHudAccessor;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import de.dorikku.gmmedicmod.network.ApiConnection;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;

import java.util.Locale;

/**
 * Automates the repetitive vehicle steps an on-duty medic performs:
 * <ul>
 *   <li><b>Motor</b> — runs {@code /vehicles motor} when entering a vehicle.</li>
 *   <li><b>Gear</b> — drops the item the server places in hand after sitting, which sets the gear shift.</li>
 *   <li><b>Siren</b> — toggles {@code /vehicles sirene} so it is on while an active call exists.</li>
 *   <li><b>Exit</b> — when leaving via sneak, turns the siren off first and delays the dismount.</li>
 * </ul>
 *
 * <p>Driven by {@code ClientTickEvents.START_CLIENT_TICK} so the sneak-suppression flag is set
 * <em>before</em> the player entity ticks and sends its {@code PlayerInputC2SPacket}. The flag is
 * consumed by {@code KeyboardInputMixin}.</p>
 *
 * <p><b>Helicopter exception:</b> if the in-hand item is named {@code Helikopter-Menü} the gear is
 * not dropped and the siren is never engaged.</p>
 */
public final class VehicleAutomation {

    private static final String HELICOPTER_ITEM_NAME = "Helikopter-Menü";
    private static final String GEAR_ITEM_NAME = "Gangwahlhebel";
    /** Boss bar the server shows while the motor is starting; gear may be set once it clears. */
    private static final String MOTOR_BOSS_BAR_TEXT = "motor startet";
    /** Give up waiting for the motor boss bar to appear after this long (e.g. motor not started). */
    private static final long MOTOR_BAR_WAIT_MS = 4_000L;
    /** Overall safety cap on the whole gear-detection window. */
    private static final long GEAR_DETECT_WINDOW_MS = 12_000L;

    private static boolean wasRiding = false;
    private static boolean motorStarted = false;
    private static boolean gearHandled = false;
    private static boolean motorBarSeen = false;
    private static boolean isHelicopter = false;
    private static boolean sirenOnByMod = false;
    private static boolean exitInProgress = false;
    private static boolean suppressSneak = false;
    private static boolean forceSneak = false;
    private static int dismountDelayTicks = 0;
    private static long mountTimeMs = 0L;

    private VehicleAutomation() {}

    /** Read by {@code KeyboardInputMixin} to decide whether to strip the sneak input this tick. */
    public static boolean shouldSuppressSneak() {
        return suppressSneak;
    }

    /**
     * Read by {@code KeyboardInputMixin} to decide whether to inject a sneak this tick. Used on the
     * final tick of a delayed exit so the dismount fires even if the player already released sneak.
     */
    public static boolean shouldForceSneak() {
        return forceSneak;
    }

    /**
     * True while the player sits in a car: riding an entity with the vehicle control item
     * ({@code Gangwahlhebel}) in the hotbar. Helicopters carry a {@code Helikopter-Menü}
     * instead and are excluded — their flight paths are not streets. Used to tag
     * location updates so the server only learns the street network from car traces.
     */
    public static boolean isDrivingCar(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || !player.hasVehicle()) return false;
        PlayerInventory inv = player.getInventory();
        boolean hasCarItem = false;
        for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String name = stripColorCodes(stack.getName().getString());
            if (name.equalsIgnoreCase(HELICOPTER_ITEM_NAME)) return false;
            if (name.equals(GEAR_ITEM_NAME)) hasCarItem = true;
        }
        return hasCarItem;
    }

    public static void tick(MinecraftClient client) {
        if (client.player == null || !ApiConnection.getInstance().isFeatureUnlocked()) {
            reset();
            return;
        }
        ClientPlayerEntity player = client.player;
        boolean riding = player.hasVehicle();

        if (riding && !wasRiding) {
            onMount(client, player);
        } else if (!riding && wasRiding) {
            onDismount();
        }
        wasRiding = riding;
        if (!riding) return;

        VehicleConfig cfg = VehicleConfig.getInstance();
        boolean inDuty = EmergencyCallManager.getInstance().isInDuty();

        // Re-checked every tick (not just on mount) so enabling the feature, setting "always" or
        // going on duty while already seated still starts the motor.
        if (!motorStarted && !exitInProgress && cfg.isMotorEnabled() && (inDuty || cfg.isMotorAlways())) {
            startMotor(client);
        }

        if (!gearHandled) {
            detectVehicleItem(client, player, cfg, inDuty);
        }

        // Must run on START tick: sets suppressSneak before the player tick sends its input packet.
        handleSneakExit(client, cfg);

        // Only manage the siren once the helicopter check has resolved, and never mid-exit.
        if (gearHandled && !exitInProgress) {
            manageSiren(client, cfg, inDuty);
        }
    }

    private static void onMount(MinecraftClient client, ClientPlayerEntity player) {
        motorStarted = false;
        gearHandled = false;
        motorBarSeen = false;
        isHelicopter = false;
        sirenOnByMod = false;
        exitInProgress = false;
        suppressSneak = false;
        forceSneak = false;
        dismountDelayTicks = 0;
        mountTimeMs = System.currentTimeMillis();
    }

    /**
     * Sends the motor-start command and (re)opens the gear-detection window, so a motor started
     * late (e.g. "always" enabled while already seated) still gets its gear set once the boss bar
     * clears.
     */
    private static void startMotor(MinecraftClient client) {
        sendCommand(client, "vehicles motor");
        motorStarted = true;
        gearHandled = false;
        motorBarSeen = false;
        mountTimeMs = System.currentTimeMillis();
        GMMedic.LOGGER.info("[GM-Medic] Motor start command sent");
    }

    private static void onDismount() {
        // Once out of the vehicle we can no longer toggle the siren; just clear local state.
        suppressSneak = false;
        forceSneak = false;
        exitInProgress = false;
        dismountDelayTicks = 0;
        motorStarted = false;
        gearHandled = false;
        motorBarSeen = false;
        isHelicopter = false;
        sirenOnByMod = false;
    }

    /**
     * Decides when to set the gear. The server shows a {@code Motor startet} boss bar while the motor
     * spins up; once that bar clears (or changes to something else) the motor is ready and the gear
     * may be selected by dropping the {@code Gangwahlhebel}. Helicopters (in-hand {@code Helikopter-Menü})
     * never drop a gear and suppress the siren.
     *
     * <p>The gear is only ever set by dropping a {@code Gangwahlhebel}: if it is in hand it is dropped,
     * if it is elsewhere in the hotbar it is selected first and then dropped, and if it is nowhere to be
     * found nothing is dropped — a different item is never thrown away.</p>
     */
    private static void detectVehicleItem(MinecraftClient client, ClientPlayerEntity player, VehicleConfig cfg, boolean inDuty) {
        long elapsed = System.currentTimeMillis() - mountTimeMs;

        // Helicopter: identified by the in-hand menu item — never drop a gear, skip the siren.
        ItemStack held = player.getInventory().getSelectedStack();
        if (!held.isEmpty() && stripColorCodes(held.getName().getString()).equalsIgnoreCase(HELICOPTER_ITEM_NAME)) {
            isHelicopter = true;
            gearHandled = true;
            GMMedic.LOGGER.info("[GM-Medic] Helicopter detected — skipping gear drop and siren");
            return;
        }

        if (isMotorBossBarPresent(client)) {
            motorBarSeen = true;
            return; // motor still starting — wait for the bar to clear
        }

        if (!motorBarSeen) {
            // Bar hasn't shown yet; keep waiting unless the motor clearly never started.
            if (elapsed > MOTOR_BAR_WAIT_MS) {
                gearHandled = true;
                GMMedic.LOGGER.info("[GM-Medic] Motor boss bar never appeared — skipping gear");
            }
            return;
        }

        if (elapsed > GEAR_DETECT_WINDOW_MS) {
            gearHandled = true; // overall safety net
            return;
        }

        // Boss bar appeared and is now gone/changed → the motor is ready, set the gear.
        gearHandled = true;
        if (cfg.isGearEnabled() && (inDuty || cfg.isGearAlways())) {
            dropGearItem(client, player);
        }
    }

    /** Drops the {@code Gangwahlhebel} to set the gear, selecting it from the hotbar first if needed. */
    private static void dropGearItem(MinecraftClient client, ClientPlayerEntity player) {
        PlayerInventory inv = player.getInventory();

        if (isGearItem(inv.getSelectedStack())) {
            player.dropSelectedItem(false);
            GMMedic.LOGGER.info("[GM-Medic] Gear item '{}' dropped to set gear shift", GEAR_ITEM_NAME);
            return;
        }

        int slot = findGearHotbarSlot(inv);
        if (slot < 0) {
            GMMedic.LOGGER.info("[GM-Medic] No '{}' in hand or hotbar — nothing dropped", GEAR_ITEM_NAME);
            return;
        }

        inv.setSelectedSlot(slot);
        if (player.networkHandler != null) {
            player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
        player.dropSelectedItem(false);
        GMMedic.LOGGER.info("[GM-Medic] Selected '{}' from hotbar slot {} and dropped it to set gear shift",
                GEAR_ITEM_NAME, slot);
    }

    private static int findGearHotbarSlot(PlayerInventory inv) {
        for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
            if (isGearItem(inv.getStack(i))) return i;
        }
        return -1;
    }

    private static boolean isGearItem(ItemStack stack) {
        return !stack.isEmpty() && stripColorCodes(stack.getName().getString()).equals(GEAR_ITEM_NAME);
    }

    /** True while a boss bar whose text contains {@code Motor startet} is on screen. */
    private static boolean isMotorBossBarPresent(MinecraftClient client) {
        if (client.inGameHud == null) return false;
        BossBarHudAccessor hud = (BossBarHudAccessor) client.inGameHud.getBossBarHud();
        for (ClientBossBar bar : hud.gmmedic$getBossBars().values()) {
            String name = stripColorCodes(bar.getName().getString()).toLowerCase(Locale.ROOT);
            if (name.contains(MOTOR_BOSS_BAR_TEXT)) return true;
        }
        return false;
    }

    private static void handleSneakExit(MinecraftClient client, VehicleConfig cfg) {
        forceSneak = false;

        if (exitInProgress) {
            // The exit is latched: once the player asked to leave we always complete it, even if they
            // released sneak. Hold the dismount back while the siren-off command settles, then inject a
            // sneak on the final tick so the player actually dismounts.
            if (dismountDelayTicks > 0) {
                dismountDelayTicks--;
                suppressSneak = true;
            } else {
                suppressSneak = false;
                forceSneak = true;
                exitInProgress = false;
                GMMedic.LOGGER.info("[GM-Medic] Sneak exit — siren settled, forcing dismount");
            }
            return;
        }

        // Only delay an exit when the mod's siren is on and must be turned off first.
        boolean rawSneak = client.options.sneakKey.isPressed();
        if (rawSneak && sirenOnByMod && !isHelicopter) {
            exitInProgress = true;
            dismountDelayTicks = Math.max(1, cfg.getExitDelayTicks());
            suppressSneak = true;
            sendCommand(client, "vehicles sirene");
            sirenOnByMod = false;
            GMMedic.LOGGER.info("[GM-Medic] Sneak exit — siren off, delaying dismount {} ticks", dismountDelayTicks);
        }
    }

    private static void manageSiren(MinecraftClient client, VehicleConfig cfg, boolean inDuty) {
        if (isHelicopter) return;
        if (!cfg.isSirenEnabled() || !(inDuty || cfg.isSirenAlways())) return;

        boolean wantSiren = hasCallAcceptedByMe(client);
        if (wantSiren && !sirenOnByMod) {
            sendCommand(client, "vehicles sirene");
            sirenOnByMod = true;
            GMMedic.LOGGER.info("[GM-Medic] Accepted call — siren on");
        } else if (!wantSiren && sirenOnByMod) {
            sendCommand(client, "vehicles sirene");
            sirenOnByMod = false;
            GMMedic.LOGGER.info("[GM-Medic] No accepted call — siren off");
        }
    }

    /**
     * True when an active call is currently accepted by the local player. The siren must not react
     * to a call merely being created, nor to one accepted by another medic on duty.
     */
    private static boolean hasCallAcceptedByMe(MinecraftClient client) {
        if (client.player == null) return false;
        String me = EmergencyCallManager.normalizeCallerName(client.player.getNameForScoreboard());
        if (me == null) return false;
        for (EmergencyCall call : EmergencyCallManager.getInstance().getActiveCalls()) {
            if (call.isPending() || call.isResolved() || call.isRejected()) continue;
            String medic = EmergencyCallManager.normalizeCallerName(call.getAssignedMedic());
            if (medic != null && medic.equalsIgnoreCase(me)) return true;
        }
        return false;
    }

    private static void sendCommand(MinecraftClient client, String command) {
        if (client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendChatCommand(command);
        }
    }

    /** Removes legacy {@code §x} formatting codes; display names may carry a colour. */
    private static String stripColorCodes(String name) {
        return name == null ? "" : name.replaceAll("§.", "").trim();
    }

    private static void reset() {
        wasRiding = false;
        onDismount();
    }
}
