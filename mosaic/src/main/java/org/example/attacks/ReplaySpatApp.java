package org.example.attacks;


import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.ReceivedAcknowledgement;
import org.eclipse.mosaic.fed.application.app.AbstractApplication;
import org.eclipse.mosaic.fed.application.app.api.CommunicationApplication;
import org.eclipse.mosaic.fed.application.app.api.os.RoadSideUnitOperatingSystem;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.ReceivedV2xMessage;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.CamBuilder;
import org.eclipse.mosaic.interactions.communication.V2xMessageTransmission;
import org.eclipse.mosaic.lib.geo.GeoCircle;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.eclipse.mosaic.lib.util.scheduling.Event;
import org.example.AttackLogger;
import org.example.CsvOutputDirectory;
import org.example.spat.FullSpatContent;
import org.example.spat.FullSpatm;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * REPLAY ATTACK
 * -------------
 * Mapped to a malicious RSU/vehicle unit sitting within range of the legitimate
 * traffic-light broadcasts. It passively CAPTURES real SPaT messages as they
 * arrive, holds them in a short buffer, then -- after a delay -- REBROADCASTS
 * an old, stale message verbatim, regardless of what the signal's true current
 * phase actually is at replay time.
 *
 * Unlike the tampering app, this attacker never fabricates new field values;
 * the danger here is entirely from staleness / lack of freshness validation
 * (e.g. replaying a captured "green, 20s remaining" message well after the
 * signal has actually turned red).
 */
public class ReplaySpatApp extends AbstractApplication<RoadSideUnitOperatingSystem>
        implements CommunicationApplication {

    // Replay a captured message this long after it was originally seen.
    private static final long REPLAY_DELAY_MS = org.eclipse.mosaic.rti.TIME.SECOND * 30;
    private final Deque<CapturedMessage> captureBuffer = new ArrayDeque<>();

    @Override
    public void onStartup() {
        getOs().getAdHocModule().enable();
        getLog().infoSimTime(this, "Replay attacker app started on {}", getOs().getId());
        AttackLogger.getInstance().init(CsvOutputDirectory.forConfiguration(
                getOs().getConfigurationPath().toPath()));
        AttackLogger.getInstance().startAttack("REPLAY", getOs().getSimulationTime());
    }

    @Override
    public void onMessageReceived(ReceivedV2xMessage receivedV2xMessage) {
        getLog().infoSimTime(this, "Received a V2X message {}", receivedV2xMessage.getMessage().getId());
        if (!(receivedV2xMessage.getMessage() instanceof FullSpatm)) {
            return; // only interested in SPaT-like traffic
        }

        FullSpatm spatMessage = (FullSpatm) receivedV2xMessage.getMessage();
        long captureTime = getOs().getSimulationTime();

        // Capture the legitimate message as-is (no field modification --
        // that's what distinguishes this from the tampering attack), and
        // attach it directly to its OWN delayed event as the event's
        // "resource". This guarantees the message replayed at t+DELAY is
        // exactly the one captured at t -- no shared queue to desync from
        // firing order, no assumption that events fire 1:1 in lockstep with
        // arrivals. Each captured message owns its own future replay event.
        CapturedMessage captured = new CapturedMessage(spatMessage.getContent(), captureTime);
        getOs().getEventManager().addEvent(
                new Event(captureTime + REPLAY_DELAY_MS, this, captured)
        );
    }

    @Override
    public void onAcknowledgementReceived(ReceivedAcknowledgement receivedAcknowledgement) {

    }

    @Override
    public void processEvent(Event event) {
        if (!(event.getResource() instanceof CapturedMessage stale)) {
            return; // not one of our scheduled replay events
        }
        rebroadcastStaleMessage(stale);
    }

    private void rebroadcastStaleMessage(CapturedMessage stale) {
        MessageRouting routing = getOs().getAdHocModule().createMessageRouting()
                .broadcast()
                .geographical(new GeoCircle(getOs().getPosition(), 300))
                .singlehop()
                .build();

        // Note: payload fields are copied UNCHANGED from the original capture --
        // only the transmission time is new. This is the "replay" signature your
        // misbehavior-detection model should be able to pick up on if it
        // cross-checks message timestamp/sequence fields against arrival time.
        FullSpatContent originalPayload = stale.payload;
        FullSpatm replayed = new FullSpatm(routing, originalPayload);

        getOs().getAdHocModule().sendV2xMessage(replayed);
        getLog().infoSimTime(this,
                "Replayed STALE SPaT captured at t={}ns (now t={}ms): phase={}, originalRemaining={}ns",
                stale.captureTimeMs, getOs().getSimulationTime(),
                originalPayload.getIntersectionStateList().getFirst().getMovementList().getFirst().getState(),
                originalPayload.getIntersectionStateList().getFirst().getMovementList().getFirst().getTime().getMinEndTime());
    }

    @Override
    public void onCamBuilding(CamBuilder camBuilder) {
        // no-op
    }

    @Override
    public void onMessageTransmitted(V2xMessageTransmission v2xMessageTransmission) {

    }

    @Override
    public void onShutdown() {
        getLog().infoSimTime(this, "Replay attacker app shutting down on {}", getOs().getId());
        AttackLogger.getInstance().stopAttack("REPLAY", getOs().getSimulationTime());
    }

    @Override
    public RoadSideUnitOperatingSystem getOs() {
        return super.getOs();
    }

    private record CapturedMessage(FullSpatContent payload, long captureTimeMs) {
    }
}