package org.example.attacks;

import org.eclipse.mosaic.lib.geo.GeoCircle;
import org.eclipse.mosaic.lib.objects.trafficlight.TrafficLight;
import org.eclipse.mosaic.lib.objects.trafficlight.TrafficLightProgramPhase;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.eclipse.mosaic.lib.util.scheduling.Event;
import org.eclipse.mosaic.rti.TIME;
import org.example.AttackLogger;
import org.example.CsvOutputDirectory;
import org.example.intersection.MapData;
import org.example.intersection.MapDataMessage;
import org.example.RsuSpatApp;
import org.example.intersection.IntersectionGeometry;
import org.example.intersection.enums.IntersectionStatus;
import org.example.spat.*;
import org.example.spat.enums.TrafficLightState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

public class DosSpatApp extends RsuSpatApp {


    private boolean pauseMessagesFlag = false;

    private long PAUSE_MESSAGES_INTERVAL = TIME.SECOND * 20;

    @Override
    protected void broadcastMapMessage() {
        if (pauseMessagesFlag) {
            return;
        }

        long currentTime = getOs().getSimulationTime();

        MapData mapData = new MapData(currentTime, geometryList);
        // Send
        MessageRouting routing = getOs().getAdHocModule().createMessageRouting()
                .broadcast()
                .geographical(new GeoCircle(getOs().getPosition(), 300))
                .singlehop()
                .build();
        getOs().getAdHocModule().sendV2xMessage(new MapDataMessage(routing, mapData));
        for (IntersectionGeometry geo : mapData.getIntersectionGeometryList()) {
            getLog().info("Transmitting MAP Data for the intersection {} at {}", geo.getId(), geo.getRefPoint());
        }
        getOs().getEventManager().addEvent(new Event(
                getOs().getSimulationTime() + MAP_SEND_INTERVAL, this, "MAP"
        ));
    }

    protected void broadcastSpatMessage() {
        if (pauseMessagesFlag) {
            return;
        }

        Collection<TrafficLight> lights = getOs().getAllTrafficLights();
        TrafficLightProgramPhase phase = getOs().getCurrentPhase();
        long currentTime = getOs().getSimulationTime();
        long nextSwitch = phase.getRemainingDuration();

        // Map to Standard MovementState list (One entry per signal)
        List<IntersectionMovement> movements = new ArrayList<>();
        // temporary cheat to skip duplicate light states instead of outputting each lane for now
        HashSet<String> dedup = new HashSet<>();
        lights.forEach(light -> {
            if (!dedup.contains(light.getIncomingLane())) {
                getLog().info("Current states for lane {} on light {}: {}", light.getIncomingLane(), getOs().getTrafficLightGroup().getGroupId(), light.getCurrentState());
                dedup.add(light.getIncomingLane());
            }

            movements.add(new IntersectionMovement(getOs().getTrafficLightGroup().getGroupId() + "_" + light.getIndex(), TrafficLightState.convertToState(light.getCurrentState()), TimeChangeDetails.convert(phase, getOs().getSimulationTime())));
        });

        // Create Custom Content and Message
        List<IntersectionState> stateList = new ArrayList<>();
        stateList.add(new IntersectionState(getOs().getTrafficLightGroup().getGroupId(), IntersectionStatus.FIXED_TIME_OPERATION, movements));
        FullSpatContent content = new FullSpatContent(currentTime, stateList);
        MessageRouting routing = getOs().getAdHocModule().createMessageRouting()
                .broadcast()
                .geographical(new GeoCircle(getOs().getPosition(), 300))
                .singlehop()
                .build();

        FullSpatm message = new FullSpatm(routing, content);

        // Send
        getOs().getAdHocModule().sendV2xMessage(message);

        getOs().getEventManager().addEvent(new Event(
                getOs().getSimulationTime() + SPAT_SEND_INTERVAL, this, "SPAT"
        ));
    }

    protected void resumeMessages() {
        // flip the flag
        pauseMessagesFlag = !pauseMessagesFlag;

        if (pauseMessagesFlag) {
            AttackLogger.getInstance().stopAttack("DOS", getOs().getSimulationTime());
        } else {
            AttackLogger.getInstance().init(CsvOutputDirectory.forConfiguration(
                    getOs().getConfigurationPath().toPath()));
            AttackLogger.getInstance().startAttack("DOS", getOs().getSimulationTime());
        }
        // run timeout
        getOs().getEventManager().addEvent(new Event(
                getOs().getSimulationTime() + PAUSE_MESSAGES_INTERVAL, this, "RESUME"
        ));

        // in this function resume the map and spat messages
        broadcastMapMessage();
        broadcastSpatMessage();
    }

    @Override
    public void processEvent (Event event){
        Object resource = event.getResource();

        // Check if it matches your expected identifier type
        if (resource instanceof String uniqueId) {
            getLog().info("Processing event with ID: {}", uniqueId);

            // Use the ID to determine logic
            if ("SPAT".equals(uniqueId)) {
                broadcastSpatMessage();
            } else if ("MAP".equals(uniqueId)) {
                broadcastMapMessage();
            } else if ("RESUME".equals(uniqueId)) {
                resumeMessages();
            }
        }
    }

    @Override
    public void onShutdown() {
        super.onShutdown();
        AttackLogger.getInstance().stopAttack("DOS", getOs().getSimulationTime());
    }
}
