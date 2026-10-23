package de.dorikku.gmmedicmod.vehicle;

import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.config.VehicleConfig;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.mixin.BossBarHudAccessor;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import de.dorikku.gmmedicmod.network.ApiConnection;
import java.util.Locale;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Automates the repetitive vehicle steps an on-duty medic performs:
 * <ul>
 *   <li><b>Motor</b> — runs {@code /vehicles motor} when entering a vehicle.</li>
 *   <li><b>Gear</b> — drops the item the server places in hand after sitting, which sets the gear shift.</li>
 *   <li><b>Siren</b> — set through the car's Auto-Menü ({@link SirenMenu}), which also tells its
 *       real state: on when entering with an accepted call or when one is accepted while seated,
 *       off when entering without one.</li>
 *   <li><b>Exit</b> — leaving via sneak without an accepted call turns the siren off first and
 *       holds the dismount until that is done; with a call it stays on and the exit is immediate.</li>
 *   <li><b>Camera</b> — switches to third person while seated and back to the previous view on exit.</li>
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
    /** A sneak exit never waits longer than this for the siren to be switched off. */
    private static final long EXIT_SIREN_MAX_WAIT_MS = 4_000L;

    private static boolean wasRiding = false;
    private static boolean motorStarted = false;
    private static boolean gearHandled = false;
    private static boolean motorBarSeen = false;
    private static boolean isHelicopter = false;
    /** The siren's state as last read from the Auto-Menü; null while unknown. */
    private static Boolean sirenState = null;
    /** Whether the siren has been set once for this ride (after the motor/gear phase). */
    private static boolean sirenSynced = false;
    /** Accepted-call state on the previous tick, to act on a call being accepted while seated. */
    private static boolean hadCall = false;
    /** During a sneak exit: still waiting for the Auto-Menü to switch the siren off. */
    private static boolean exitWaitingForSiren = false;
    private static boolean exitSirenRequested = false;
    private static long exitStartMs = 0L;
    private static boolean exitInProgress = false;
    private static boolean suppressSneak = false;
    private static boolean forceSneak = false;
    private static int dismountDelayTicks = 0;
    private static long mountTimeMs = 0L;
    /** View the player had before the mod switched to third person; null when the mod changed nothing. */
    private static CameraType cameraBeforeMount = null;

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
    public static boolean isDrivingCar(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || !player.isPassenger()) return false;
        Inventory inv = player.getInventory();
        boolean hasCarItem = false;
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String name = stripColorCodes(stack.getHoverName().getString());
            if (name.equalsIgnoreCase(HELICOPTER_ITEM_NAME)) return false;
            if (name.equals(GEAR_ITEM_NAME)) hasCarItem = true;
        }
        return hasCarItem;
    }

    public static void tick(Minecraft client) {
        if (client.player == null || !ApiConnection.getInstance().isFeatureUnlocked()) {
            restoreCamera(client);
            reset();
            return;
        }
        LocalPlayer player = client.player;
        boolean riding = player.isPassenger();

        if (riding && !wasRiding) {
            onMount(client, player);
        } else if (!riding && wasRiding) {
            restoreCamera(client);
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
        handleSneakExit(client, cfg, inDuty);

        // Only manage the siren once the helicopter check has resolved, and never mid-exit.
        if (gearHandled && !exitInProgress) {
            manageSiren(client, cfg, inDuty);
        }
    }

    private static void onMount(Minecraft client, LocalPlayer player) {
        motorStarted = false;
        gearHandled = false;
        motorBarSeen = false;
        isHelicopter = false;
        sirenState = null;
        sirenSynced = false;
        hadCall = false;
        exitWaitingForSiren = false;
        exitSirenRequested = false;
        exitInProgress = false;
        suppressSneak = false;
        forceSneak = false;
        dismountDelayTicks = 0;
        mountTimeMs = System.currentTimeMillis();

        // Only leave first person; a player already in a third-person view keeps theirs.
        if (VehicleConfig.getInstance().isThirdPersonEnabled() && client.options.getCameraType().isFirstPerson()) {
            cameraBeforeMount = client.options.getCameraType();
            client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            GMMedic.LOGGER.info("[GM-Medic] Entered vehicle — switched to third person");
        }
    }

    /**
     * Puts back the view the player had before mounting. Skipped if they changed the view
     * themselves while seated (e.g. F5 to the front view), so a deliberate choice is kept.
     */
    private static void restoreCamera(Minecraft client) {
        if (cameraBeforeMount == null) return;
        if (client.options.getCameraType() == CameraType.THIRD_PERSON_BACK) {
            client.options.setCameraType(cameraBeforeMount);
            GMMedic.LOGGER.info("[GM-Medic] Left vehicle — restored previous camera view");
        }
        cameraBeforeMount = null;
    }

    /**
     * Sends the motor-start command and (re)opens the gear-detection window, so a motor started
     * late (e.g. "always" enabled while already seated) still gets its gear set once the boss bar
     * clears.
     */
    private static void startMotor(Minecraft client) {
        sendCommand(client, "vehicles motor");
        motorStarted = true;
        gearHandled = false;
        motorBarSeen = false;
        mountTimeMs = System.currentTimeMillis();
        GMMedic.LOGGER.info("[GM-Medic] Motor start command sent");
    }

    private static void onDismount() {
        // Once out of the vehicle the Auto-Menü is gone; just clear local state.
        suppressSneak = false;
        forceSneak = false;
        exitInProgress = false;
        dismountDelayTicks = 0;
        motorStarted = false;
        gearHandled = false;
        motorBarSeen = false;
        isHelicopter = false;
        sirenState = null;
        sirenSynced = false;
        hadCall = false;
        exitWaitingForSiren = false;
        exitSirenRequested = false;
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
    private static void detectVehicleItem(Minecraft client, LocalPlayer player, VehicleConfig cfg, boolean inDuty) {
        long elapsed = System.currentTimeMillis() - mountTimeMs;

        // Helicopter: identified by the in-hand menu item — never drop a gear, skip the siren.
        ItemStack held = player.getInventory().getSelectedItem();
        if (!held.isEmpty() && stripColorCodes(held.getHoverName().getString()).equalsIgnoreCase(HELICOPTER_ITEM_NAME)) {
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
    private static void dropGearItem(Minecraft client, LocalPlayer player) {
        Inventory inv = player.getInventory();

        if (isGearItem(inv.getSelectedItem())) {
            player.drop(false);
            GMMedic.LOGGER.info("[GM-Medic] Gear item '{}' dropped to set gear shift", GEAR_ITEM_NAME);
            return;
        }

        int slot = findGearHotbarSlot(inv);
        if (slot < 0) {
            GMMedic.LOGGER.info("[GM-Medic] No '{}' in hand or hotbar — nothing dropped", GEAR_ITEM_NAME);
            return;
        }

        inv.setSelectedSlot(slot);
        if (player.connection != null) {
            player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        }
        player.drop(false);
        GMMedic.LOGGER.info("[GM-Medic] Selected '{}' from hotbar slot {} and dropped it to set gear shift",
                GEAR_ITEM_NAME, slot);
    }

    private static int findGearHotbarSlot(Inventory inv) {
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (isGearItem(inv.getItem(i))) return i;
        }
        return -1;
    }

    private static boolean isGearItem(ItemStack stack) {
        return !stack.isEmpty() && stripColorCodes(stack.getHoverName().getString()).equals(GEAR_ITEM_NAME);
    }

    /** True while a boss bar whose text contains {@code Motor startet} is on screen. */
    private static boolean isMotorBossBarPresent(Minecraft client) {
        if (client.gui == null) return false;
        BossBarHudAccessor hud = (BossBarHudAccessor) client.gui.getBossOverlay();
        for (LerpingBossEvent bar : hud.gmmedic$getBossBars().values()) {
            String name = stripColorCodes(bar.getName().getString()).toLowerCase(Locale.ROOT);
            if (name.contains(MOTOR_BOSS_BAR_TEXT)) return true;
        }
        return false;
    }

    private static void handleSneakExit(Minecraft client, VehicleConfig cfg, boolean inDuty) {
        forceSneak = false;

        if (exitInProgress) {
            // The exit is latched: once the player asked to leave we always complete it, even if they
            // released sneak. Hold the dismount back until the Auto-Menü has switched the siren off,
            // then for the configured delay, then inject a sneak so the player actually dismounts.
            suppressSneak = true;
            if (exitWaitingForSiren) {
                boolean timedOut = System.currentTimeMillis() - exitStartMs > EXIT_SIREN_MAX_WAIT_MS;
                if (SirenMenu.isBusy() && !timedOut) return;
                // A session that was already running (e.g. the one after entering) may have left the
                // siren on; ask for "off" once ourselves.
                if (!exitSirenRequested && !timedOut && !Boolean.FALSE.equals(sirenState)) {
                    exitSirenRequested = true;
                    SirenMenu.request(client, false, result -> sirenState = result);
                    return;
                }
                exitWaitingForSiren = false;
                dismountDelayTicks = Math.max(1, cfg.getExitDelayTicks());
                return;
            }
            if (dismountDelayTicks > 0) {
                dismountDelayTicks--;
            } else {
                suppressSneak = false;
                forceSneak = true;
                exitInProgress = false;
                GMMedic.LOGGER.info("[GM-Medic] Sneak exit — siren settled, forcing dismount");
            }
            return;
        }

        // Only hold an exit back when the siren must go off first: no accepted call and not known
        // to be off already. With a call it stays on and the player leaves right away.
        boolean rawSneak = client.options.keyShift.isDown();
        if (rawSneak && isSirenManaged(cfg, inDuty) && !hasCallAcceptedByMe(client)
                && !Boolean.FALSE.equals(sirenState)) {
            exitInProgress = true;
            exitWaitingForSiren = true;
            exitSirenRequested = false;
            exitStartMs = System.currentTimeMillis();
            suppressSneak = true;
            if (!SirenMenu.isBusy()) {
                exitSirenRequested = true;
                SirenMenu.request(client, false, result -> sirenState = result);
            }
            GMMedic.LOGGER.info("[GM-Medic] Sneak exit without a call — switching the siren off first");
        }
    }

    private static boolean isSirenManaged(VehicleConfig cfg, boolean inDuty) {
        return !isHelicopter && cfg.isSirenEnabled() && (inDuty || cfg.isSirenAlways());
    }

    /**
     * Sets the siren at the two moments it should change while seated: once after entering (on
     * with an accepted call, off without), and when a call is accepted during the ride (on, left
     * as it is if already on). Each moment tries the Auto-Menü once; a call ending mid-ride
     * leaves the siren on until the player gets out.
     */
    private static void manageSiren(Minecraft client, VehicleConfig cfg, boolean inDuty) {
        boolean hasCall = hasCallAcceptedByMe(client);
        boolean callAccepted = hasCall && !hadCall;
        hadCall = hasCall;
        if (!isSirenManaged(cfg, inDuty) || SirenMenu.isBusy()) return;

        if (!sirenSynced) {
            sirenSynced = true;
            GMMedic.LOGGER.info("[GM-Medic] Entered vehicle {} an accepted call — siren {}",
                    hasCall ? "with" : "without", hasCall ? "on" : "off");
            SirenMenu.request(client, hasCall, result -> sirenState = result);
        } else if (callAccepted && !Boolean.TRUE.equals(sirenState)) {
            GMMedic.LOGGER.info("[GM-Medic] Call accepted while seated — siren on");
            SirenMenu.request(client, true, result -> sirenState = result);
        }
    }

    /**
     * True when an active call is currently accepted by the local player. The siren must not react
     * to a call merely being created, nor to one accepted by another medic on duty.
     */
    private static boolean hasCallAcceptedByMe(Minecraft client) {
        if (client.player == null) return false;
        String me = EmergencyCallManager.normalizeCallerName(client.player.getScoreboardName());
        if (me == null) return false;
        for (EmergencyCall call : EmergencyCallManager.getInstance().getActiveCalls()) {
            if (call.isPending() || call.isResolved() || call.isRejected()) continue;
            String medic = EmergencyCallManager.normalizeCallerName(call.getAssignedMedic());
            if (medic != null && medic.equalsIgnoreCase(me)) return true;
        }
        return false;
    }

    private static void sendCommand(Minecraft client, String command) {
        if (client.player != null && client.player.connection != null) {
            client.player.connection.sendCommand(command);
        }
    }

    /** Removes legacy {@code §x} formatting codes; display names may carry a colour. */
    private static String stripColorCodes(String name) {
        return name == null ? "" : name.replaceAll("§.", "").trim();
    }

    private static void reset() {
        wasRiding = false;
        if (SirenMenu.isBusy()) SirenMenu.reset();
        onDismount();
    }
}
