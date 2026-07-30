package de.dorikku.gmmedicmod.manager;

import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.network.ApiConnection;
import de.dorikku.gmmedicmod.network.OutboundMessages;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * Tracks the bank alarm announced in the D-Funk ("Der Alarm der &lt;Bank&gt; wurde
 * ausgelöst"). The client that reads the line reports it to the API, which fans it
 * out to every connected client that is NOT on duty. The alarm stays active until
 * "Der Bankraub wurde beendet." arrives (with a timeout as safety net in case the
 * end message is missed).
 */
public class AlarmManager {

    private static final AlarmManager INSTANCE = new AlarmManager();

    /** Safety net: never keep an alarm active longer than this. */
    private static final long MAX_ALARM_DURATION_MS = 30 * 60 * 1000L;

    public static AlarmManager getInstance() {
        return INSTANCE;
    }

    private volatile String alarmName;
    private volatile long triggeredAtMs;

    private AlarmManager() {}

    /** Alarm line read from the local D-Funk — report to the API and activate. */
    public void triggerFromChat(String name) {
        ApiConnection.getInstance().send(OutboundMessages.alarmTriggered(getUsername(), name));
        trigger(name, System.currentTimeMillis());
    }

    /** Alarm pushed by the API server (reported by another client). */
    public void triggerFromRemote(String name, long triggeredAt) {
        trigger(name, triggeredAt > 0 ? triggeredAt : System.currentTimeMillis());
    }

    private void trigger(String name, long atMs) {
        if (isActive() && alarmName.equals(name)) return; // duplicate report
        alarmName = name;
        triggeredAtMs = Math.min(atMs, System.currentTimeMillis());
        GMMedic.LOGGER.info("[GM-Medic] ALARM active: {}", name);
        announce(name);
    }

    /** End line read from the local D-Funk — report to the API and clear. */
    public void endFromChat() {
        ApiConnection.getInstance().send(OutboundMessages.alarmEnded(getUsername()));
        clear();
    }

    /** Alarm end pushed by the API server. */
    public void endFromRemote() {
        clear();
    }

    public void clear() {
        if (alarmName != null) GMMedic.LOGGER.info("[GM-Medic] ALARM cleared: {}", alarmName);
        alarmName = null;
        triggeredAtMs = 0L;
    }

    public boolean isActive() {
        return alarmName != null && System.currentTimeMillis() - triggeredAtMs < MAX_ALARM_DURATION_MS;
    }

    public String getAlarmName() { return alarmName; }
    public long getTriggeredAtMs() { return triggeredAtMs; }

    /**
     * Title, chat line and alarm horn for off-duty players. On-duty medics read the
     * D-Funk themselves, so they get no extra effects (the banner is also off-duty
     * only, see {@link de.dorikku.gmmedicmod.hud.AlarmHud}).
     */
    private void announce(String name) {
        if (EmergencyCallManager.getInstance().isInDuty()) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return;
        client.gui.setTimes(5, 70, 20);
        client.gui.setTitle(Component.literal("⚠ ALARM ⚠").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        client.gui.setSubtitle(Component.literal("Der Alarm der " + name + " wurde ausgelöst!").withStyle(ChatFormatting.YELLOW));
        client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.RAID_HORN, 1.0F));
        client.player.sendSystemMessage(
                Component.literal("[GM-Medic] ⚠ ALARM: Der Alarm der " + name + " wurde ausgelöst!")
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
        );
    }

    private static String getUsername() {
        return Minecraft.getInstance().getUser().getName();
    }
}
