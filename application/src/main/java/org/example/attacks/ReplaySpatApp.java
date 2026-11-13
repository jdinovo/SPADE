package org.example.attacks;

import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.ReceivedV2xMessage;
import org.eclipse.mosaic.lib.geo.GeoCircle;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.example.AttackLogger;
import org.example.spat.FullSpatContent;
import org.example.spat.FullSpatm;

import java.util.ArrayDeque;
import java.util.Deque;

public class ReplaySpatApp extends StandaloneRsuAttackApp {

    private static final long REPLAY_DELAY_NS = org.eclipse.mosaic.rti.TIME.SECOND * 30;
    private final Deque<CapturedMessage> captureBuffer = new ArrayDeque<>();

    @Override
    public void onMessageReceived(ReceivedV2xMessage receivedV2xMessage) {
        if (receivedV2xMessage.getMessage() instanceof FullSpatm spatMessage) {
            captureBuffer.addLast(new CapturedMessage(
                    spatMessage.getContent(), getOs().getSimulationTime()));
        }
    }

    @Override
    protected void attack() {
        long now = getOs().getSimulationTime();
        FullSpatContent stalePayload = null;
        while (!captureBuffer.isEmpty()
                && captureBuffer.peekFirst().captureTimeNs + REPLAY_DELAY_NS <= now) {
            stalePayload = captureBuffer.removeFirst().payload;
        }
        if (stalePayload == null) {
            return;
        }

        AttackLogger.getInstance().startAttack("REPLAY", now);
        MessageRouting routing = getOs().getAdHocModule().createMessageRouting()
                .broadcast()
                .geographical(new GeoCircle(getOs().getPosition(), 300))
                .singlehop()
                .build();
        FullSpatm replayed = new FullSpatm(routing, stalePayload);
        AttackLogger.getInstance().markMessageMalicious(String.valueOf(replayed.getId()), "REPLAY");
        getOs().getAdHocModule().sendV2xMessage(replayed);
    }

    @Override
    protected void attackEnd() {
        AttackLogger.getInstance().stopAttack("REPLAY", getOs().getSimulationTime());
    }

    private record CapturedMessage(FullSpatContent payload, long captureTimeNs) {
    }
}
