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
import org.eclipse.mosaic.rti.TIME;
import org.example.AttackLogger;
import org.example.CsvOutputDirectory;
import org.example.intersection.enums.IntersectionStatus;
import org.example.spat.*;
import org.example.spat.enums.TrafficLightState;

import java.util.*;

/**
 * IMPERSONATION ATTACK
 * -------------
 * CAPTURE A SIGNALS ID THROUGH BROADCASTED SPAT/MAP messages, then use that id to broadcast its own SPAT/MAP
 * Similar to Replay except manipulates data
 *
 */
public class ImpersonationSpatApp extends AbstractApplication<RoadSideUnitOperatingSystem>
        implements CommunicationApplication {

    // Captured legit intersection data
    private CapturedMessage templateMessage;

    @Override
    public void onStartup() {
        getOs().getAdHocModule().enable();
        getLog().infoSimTime(this, "Impersonation attacker app started on {}", getOs().getId());
        AttackLogger.getInstance().init(CsvOutputDirectory.forConfiguration(
                getOs().getConfigurationPath().toPath()));
        AttackLogger.getInstance().startAttack("IMPERSONATION", getOs().getSimulationTime());
    }

    @Override
    public void onMessageReceived(ReceivedV2xMessage receivedV2xMessage) {
        getLog().infoSimTime(this, "Received a V2X message {}", receivedV2xMessage.getMessage().getId());
        if (!(receivedV2xMessage.getMessage() instanceof FullSpatm spatMessage)) {
            return; // only interested in SPaT-like traffic
        }

        long captureTime = getOs().getSimulationTime();

        if (templateMessage == null) {
            templateMessage = new CapturedMessage(spatMessage.getContent(), captureTime);
            getOs().getEventManager().addEvent(
                    new Event(getOs().getSimulationTime() + TIME.SECOND, this, "IMPERSONATE")
            );
        }
    }

    @Override
    public void onAcknowledgementReceived(ReceivedAcknowledgement receivedAcknowledgement) {

    }

    @Override
    public void processEvent(Event event) {
        Object resource = event.getResource();

        // Check if it matches your expected identifier type
        if (resource instanceof String) {
            String uniqueId = (String) resource;
            getLog().info("Processing event with ID: {}", uniqueId);

            // Use the ID to determine logic
            if ("IMPERSONATE".equals(uniqueId)) {
                broadcastImpersonation();
            }
        }
    }

    private void broadcastImpersonation() {
        MessageRouting routing = getOs().getAdHocModule().createMessageRouting()
                .broadcast()
                .geographical(new GeoCircle(getOs().getPosition(), 300))
                .singlehop()
                .build();

        FullSpatContent originalPayload = templateMessage.payload;

        // Map to Standard MovementState list (One entry per signal)
        List<IntersectionMovement> movements = new ArrayList<>();
        // Create Custom Content and Message
        List<IntersectionState> stateList = new ArrayList<>();
        originalPayload.getIntersectionStateList().forEach(is -> {
            for (IntersectionMovement im : is.getMovementList()) {
                // TODO :- MANIPULATE DATA
                // Currently just flip traffic light states
                movements.add(new IntersectionMovement(im.getSignalId(),
                        im.getState() == TrafficLightState.RED ? TrafficLightState.GREEN : TrafficLightState.RED, im.getTime()));
            }
            stateList.add(new IntersectionState(is.getId(), is.getStatus(), movements));
        });


        FullSpatContent content = new FullSpatContent(getOs().getSimulationTime(), stateList);

        FullSpatm impersonation = new FullSpatm(routing, content);

        getOs().getAdHocModule().sendV2xMessage(impersonation);
        getLog().infoSimTime(this,
                "Transmitted fake SPaT based on message at t={}ns (now t={}ms): phase={}, originalRemaining={}ns",
                templateMessage.captureTimeMs, getOs().getSimulationTime(),
                originalPayload.getIntersectionStateList().getFirst().getMovementList().getFirst().getState(),
                originalPayload.getIntersectionStateList().getFirst().getMovementList().getFirst().getTime().getMinEndTime());



        // keep repeating
        getOs().getEventManager().addEvent(
                new Event(getOs().getSimulationTime() + TIME.SECOND, this, "IMPERSONATE")
        );
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
        AttackLogger.getInstance().stopAttack("IMPERSONATION", getOs().getSimulationTime());
    }

    @Override
    public RoadSideUnitOperatingSystem getOs() {
        return super.getOs();
    }

    private record CapturedMessage(FullSpatContent payload, long captureTimeMs) {
    }
}
