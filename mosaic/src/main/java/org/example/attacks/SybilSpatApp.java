package org.example.attacks;

import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.CamBuilder;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.ReceivedAcknowledgement;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.ReceivedV2xMessage;
import org.eclipse.mosaic.fed.application.app.AbstractApplication;
import org.eclipse.mosaic.fed.application.app.api.CommunicationApplication;
import org.eclipse.mosaic.fed.application.app.api.os.RoadSideUnitOperatingSystem;
import org.eclipse.mosaic.interactions.communication.V2xMessageTransmission;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.eclipse.mosaic.lib.objects.v2x.etsi.Cam;
import org.eclipse.mosaic.lib.util.scheduling.Event;
import org.eclipse.mosaic.rti.TIME;
import org.example.AttackLogger;
import org.example.CsvOutputDirectory;
import org.example.PerceivedTrafficLight;
import org.example.cpm.CoopPercepData;
import org.example.cpm.CoopPercepMessage;
import org.example.spat.FullSpatContent;
import org.example.spat.enums.TrafficLightState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class SybilSpatApp extends AbstractApplication<RoadSideUnitOperatingSystem>
        implements CommunicationApplication {

    // Captured legit data
    private CoopPercepMessage templateCoopMessage;
    private Cam templateCam;
    private List<FakeVehicle> fakeVehicles = new ArrayList<>();

    @Override
    public void onStartup() {
        getOs().getAdHocModule().enable();
        getLog().infoSimTime(this, "Sybil attacker app started on {}", getOs().getId());

        AttackLogger.getInstance().init(CsvOutputDirectory.forConfiguration(
                getOs().getConfigurationPath().toPath()));
        AttackLogger.getInstance().startAttack("SYBIL", getOs().getSimulationTime());
    }

    @Override
    public void onMessageReceived(ReceivedV2xMessage receivedV2xMessage) {
        getLog().infoSimTime(this, "Received a Cooperative perception message {}", receivedV2xMessage.getMessage().getId());
        if (!(receivedV2xMessage.getMessage() instanceof CoopPercepMessage || receivedV2xMessage.getMessage() instanceof Cam)) {
            return; // only interested in CAM/CPM traffic
        }

        long captureTime = getOs().getSimulationTime();

        if (receivedV2xMessage.getMessage() instanceof CoopPercepMessage coopPercepMessage && templateCoopMessage == null) {
            templateCoopMessage = coopPercepMessage;
            // generate an array of fake vehicles all to corroborate false traffic signal state perception
            getOs().getEventManager().addEvent(
                    new Event(getOs().getSimulationTime() + TIME.SECOND, this, "SWARM")
            );
        }
        if (receivedV2xMessage.getMessage() instanceof Cam camMsg && templateCam == null) {
            templateCam = camMsg;
            // generate an array of fake vehicles all to corroborate false traffic signal state perception
            getOs().getEventManager().addEvent(
                    new Event(getOs().getSimulationTime() + TIME.SECOND, this, "SWARM")
            );
        }


        // if we have both CAM and CPM then we can generate the list of fake vehicle messages from different vehicles
        generateVehicleMessages();
        // then we are ready to transmit on the timer
        broadcastSwarm();
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
            if ("SWARM".equals(uniqueId)) {
                broadcastSwarm();
            }
        }
    }

    private void generateVehicleMessages() {

        // generate
        for (int i = 0; i < 10; ++i) {
            String fakeId = "veh_" + i * getRandom().nextInt(20, 999);
            CamBuilder camBuilder = new CamBuilder();
            camBuilder.position(templateCam.getPosition());
            camBuilder.awarenessData(templateCam.getAwarenessData());

            Cam cam = new Cam(templateCam.getRouting(), camBuilder.create(getOs().getSimulationTime(), fakeId), templateCam.getPayload().getMinimalLength());
            CoopPercepMessage coopPercepMessage = new CoopPercepMessage(templateCoopMessage.getRouting(), new CoopPercepData(templateCoopMessage.getContent()));

            coopPercepMessage.getContent().setSourceVehicle(fakeId);

            // flip perceptions
            List<PerceivedTrafficLight> ptlist = new ArrayList<>();
            for (PerceivedTrafficLight ptl : coopPercepMessage.getContent().getPerceivedObjects()) {
                PerceivedTrafficLight newPtl = ptl.clone();
                switch (ptl.getSignalState()) {
                    case RED -> {
                        newPtl.setSignalState(TrafficLightState.GREEN);
                        newPtl.setOneHotState(new int[]{0, 0, 1});
                    }
                    case GREEN -> {
                        newPtl.setSignalState(TrafficLightState.RED);
                        newPtl.setOneHotState(new int[]{1, 0, 0});
                    }
                    default -> {}
                }
                ptlist.add(newPtl);
            }
            fakeVehicles.add(new FakeVehicle(cam, coopPercepMessage));
        }

    }

    private void broadcastSwarm() {

        MessageRouting routing = getOs().getAdHocModule().createMessageRouting()
                .broadcast()
                .topological()
                .singlehop()
                .build();

        for (FakeVehicle fv : fakeVehicles) {
            getOs().getAdHocModule().sendV2xMessage(fv.coopPercepMessage);
            getOs().getAdHocModule().sendV2xMessage(fv.cam);
        }


        // after each transmission the vehicles should be moved in some way

//        getOs().getAdHocModule().sendV2xMessage(impersonation);
//        getLog().infoSimTime(this,
//                "Transmitted fake SPaT based on message at t={}ns (now t={}ms): phase={}, originalRemaining={}ns",
//                templateCoopMessage.captureTimeMs, getOs().getSimulationTime(),
//                originalPayload.getIntersectionStateList().getFirst().getMovementList().getFirst().getState(),
//                originalPayload.getIntersectionStateList().getFirst().getMovementList().getFirst().getTime().getMinEndTime());

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
        AttackLogger.getInstance().stopAttack("SYBIL", getOs().getSimulationTime());
    }

    @Override
    public RoadSideUnitOperatingSystem getOs() {
        return super.getOs();
    }

    private record FakeVehicle(Cam cam, CoopPercepMessage coopPercepMessage) {
    }

}
