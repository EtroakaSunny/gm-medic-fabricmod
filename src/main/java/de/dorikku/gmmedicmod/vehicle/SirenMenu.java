package de.dorikku.gmmedicmod.vehicle;

import de.dorikku.gmmedicmod.GMMedic;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Reads and sets the siren through the car's own menu, unseen by the player. With the
 * {@code Gangwahlhebel} in hand, the swap-hands key (F) makes the server open a chest menu
 * titled {@code Auto-Menü}; a head in it is named {@code Sirene eingeschaltet} or
 * {@code Sirene abgeschaltet} and toggles the siren when clicked.
 *
 * <p>A session sends that same F packet, takes the menu when it arrives without ever showing
 * it ({@code ClientPacketListenerMixin}), reads the head, clicks it only if the siren is in the
 * wrong state, waits for the head to flip and closes the menu again. Unlike a blind
 * {@code /vehicles sirene} toggle this always ends in the wanted state, whatever it was before.</p>
 */
public final class SirenMenu {

    private static final String MENU_TITLE = "Auto-Menü";
    private static final String KNOB_ITEM_NAME = "Gangwahlhebel";
    private static final String SIREN_ON = "Sirene eingeschaltet";
    private static final String SIREN_OFF = "Sirene abgeschaltet";
    /** How long to wait for the server to open the menu after the F packet. */
    private static final long OPEN_TIMEOUT_MS = 3_000L;
    /** How long to wait for the head once the menu is open, and for it to flip after a click. */
    private static final long READ_TIMEOUT_MS = 2_000L;
    /** A menu arriving this late is still taken (and closed) so it never pops up on screen. */
    private static final long LATE_OPEN_GRACE_MS = 5_000L;

    private enum Phase { IDLE, WAITING_FOR_MENU, OPEN, CLICKED }

    private static Phase phase = Phase.IDLE;
    private static boolean wantOn;
    private static Consumer<Boolean> onDone;
    private static long phaseStartMs;
    private static int containerId = -1;
    private static AbstractContainerMenu menu;
    private static int restoreSlot = -1;
    /** Until this time a late "Auto-Menü" from an abandoned session is swallowed too. */
    private static long swallowLateUntilMs = 0L;

    private SirenMenu() {}

    public static boolean isBusy() {
        return phase != Phase.IDLE;
    }

    /**
     * Starts a session that ends with the siren on ({@code on = true}) or off. {@code done} gets
     * the siren's state afterwards, or {@code null} when it couldn't be read (no knob in the
     * hotbar, no menu, no head). Ignored while a session is already running.
     */
    public static void request(Minecraft client, boolean on, Consumer<Boolean> done) {
        LocalPlayer player = client.player;
        if (isBusy() || player == null || player.connection == null) return;

        Inventory inv = player.getInventory();
        int knob = findKnobSlot(inv);
        if (knob < 0) {
            GMMedic.LOGGER.info("[GM-Medic] No '{}' in the hotbar — can't open the Auto-Menü for the siren", KNOB_ITEM_NAME);
            done.accept(null);
            return;
        }
        wantOn = on;
        onDone = done;
        restoreSlot = -1;
        if (inv.getSelectedSlot() != knob) {
            restoreSlot = inv.getSelectedSlot();
            selectSlot(player, knob);
        }
        // Exactly what the swap-hands key sends; the server answers with the Auto-Menü.
        player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
        enter(Phase.WAITING_FOR_MENU);
    }

    /**
     * Called for every menu the server opens. Takes the Auto-Menü while a session waits for it
     * (or shortly after one gave up) and returns true, so the screen is never opened.
     */
    public static boolean interceptOpen(ClientboundOpenScreenPacket packet) {
        String title = packet.getTitle().getString().replaceAll("§.", "").trim();
        if (!title.equalsIgnoreCase(MENU_TITLE)) return false;
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) return false;

        if (phase != Phase.WAITING_FOR_MENU) {
            if (System.currentTimeMillis() > swallowLateUntilMs) return false;
            // Our own request, arriving after we stopped waiting: close it unseen.
            player.connection.send(new ServerboundContainerClosePacket(packet.getContainerId()));
            GMMedic.LOGGER.info("[GM-Medic] Closed a late Auto-Menü unseen");
            return true;
        }
        containerId = packet.getContainerId();
        menu = packet.getType().create(containerId, player.getInventory());
        // Content and slot packets for this id now land in this menu instead of a screen.
        player.containerMenu = menu;
        enter(Phase.OPEN);
        return true;
    }

    /** Called when the server closes a menu; returns true if it was ours (handled silently). */
    public static boolean interceptClose(int id) {
        if (menu == null || id != containerId) return false;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) player.containerMenu = player.inventoryMenu;
        // A click that made the server close the menu has done its job.
        finish(phase == Phase.CLICKED ? wantOn : null, false);
        return true;
    }

    public static void tick(Minecraft client) {
        if (phase == Phase.IDLE) return;
        LocalPlayer player = client.player;
        if (player == null) {
            reset();
            return;
        }
        long age = System.currentTimeMillis() - phaseStartMs;

        switch (phase) {
            case WAITING_FOR_MENU -> {
                if (age > OPEN_TIMEOUT_MS) {
                    GMMedic.LOGGER.info("[GM-Medic] Auto-Menü didn't open — siren left as it is");
                    swallowLateUntilMs = System.currentTimeMillis() + LATE_OPEN_GRACE_MS;
                    finish(null, false);
                }
            }
            case OPEN -> {
                Slot head = findSirenSlot();
                if (head == null) {
                    if (age > READ_TIMEOUT_MS) {
                        GMMedic.LOGGER.info("[GM-Medic] No siren head in the Auto-Menü");
                        finish(null, true);
                    }
                    return;
                }
                boolean on = isOn(head.getItem());
                if (on == wantOn) {
                    finish(on, true);
                } else if (client.gameMode != null) {
                    client.gameMode.handleContainerInput(containerId, head.index, 0, ContainerInput.PICKUP, player);
                    enter(Phase.CLICKED);
                }
            }
            case CLICKED -> {
                Slot head = findSirenSlot();
                if (head != null && isOn(head.getItem()) == wantOn) {
                    finish(wantOn, true);
                } else if (age > READ_TIMEOUT_MS) {
                    // No confirmation, but the click went out; the server most likely toggled it.
                    finish(wantOn, true);
                }
            }
            default -> {}
        }
    }

    /** Ends a session without notifying anyone, e.g. on disconnect. */
    public static void reset() {
        phase = Phase.IDLE;
        onDone = null;
        menu = null;
        containerId = -1;
        restoreSlot = -1;
    }

    private static void finish(Boolean result, boolean closeMenu) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player != null) {
            if (closeMenu && menu != null) {
                player.connection.send(new ServerboundContainerClosePacket(containerId));
                player.containerMenu = player.inventoryMenu;
            }
            if (restoreSlot >= 0) selectSlot(player, restoreSlot);
        }
        Consumer<Boolean> done = onDone;
        reset();
        GMMedic.LOGGER.info("[GM-Medic] Siren via Auto-Menü: {}", result == null ? "unknown" : (result ? "on" : "off"));
        if (done != null) done.accept(result);
    }

    private static void enter(Phase next) {
        phase = next;
        phaseStartMs = System.currentTimeMillis();
    }

    /** The head among the menu's own slots (not the player's inventory below it). */
    private static Slot findSirenSlot() {
        if (menu == null) return null;
        LocalPlayer player = Minecraft.getInstance().player;
        for (Slot slot : menu.slots) {
            if (player != null && slot.container == player.getInventory()) continue;
            String name = nameOf(slot.getItem());
            if (name.contains(SIREN_ON) || name.contains(SIREN_OFF)) return slot;
        }
        return null;
    }

    private static boolean isOn(ItemStack stack) {
        return nameOf(stack).contains(SIREN_ON);
    }

    private static String nameOf(ItemStack stack) {
        return stack.isEmpty() ? "" : stack.getHoverName().getString().replaceAll("§.", "").trim();
    }

    private static int findKnobSlot(Inventory inv) {
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (nameOf(inv.getItem(i)).equals(KNOB_ITEM_NAME)) return i;
        }
        return -1;
    }

    private static void selectSlot(LocalPlayer player, int slot) {
        player.getInventory().setSelectedSlot(slot);
        player.connection.send(new ServerboundSetCarriedItemPacket(slot));
    }
}
