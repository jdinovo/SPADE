package org.example;

import org.eclipse.mosaic.fed.application.app.AbstractApplication;
import org.eclipse.mosaic.fed.application.app.api.os.TrafficLightOperatingSystem;

import org.eclipse.mosaic.lib.geo.GeoCircle;
import org.eclipse.mosaic.lib.objects.trafficlight.TrafficLight;
import org.eclipse.mosaic.lib.objects.trafficlight.TrafficLightProgramPhase;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.eclipse.mosaic.lib.util.scheduling.Event;
import org.example.intersection.*;
import org.example.intersection.enums.*;
import org.example.spat.*;
import org.example.spat.enums.TrafficLightState;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public class RsuSpatApp extends AbstractApplication<TrafficLightOperatingSystem> {

    protected static final long SPAT_SEND_INTERVAL = org.eclipse.mosaic.rti.TIME.SECOND;
    protected static final long MAP_SEND_INTERVAL = 5 * SPAT_SEND_INTERVAL;

    private BufferedWriter logWriter;

    protected List<IntersectionGeometry> geometryList;

    @Override
    public void onStartup () {
        getOs().getAdHocModule().enable();
        getLog().info("SpatBroadcastApp started on traffic light {}", getOs().getId());
        // create .csv for output
        try {
            Path outputDirectory = CsvOutputDirectory.forConfiguration(
                    getOs().getConfigurationPath().toPath());
            Path csvPath = outputDirectory.resolve(getOs().getId() + "_tl_output.csv").normalize();
            if (!csvPath.startsWith(outputDirectory)) {
                throw new IOException("RSU CSV path must remain inside " + outputDirectory);
            }
            logWriter = new BufferedWriter(new FileWriter(csvPath.toFile()));
            getLog().info("Writing RSU CSV to {}", csvPath);
            AttackLogger.getInstance().init(outputDirectory);
        } catch (IOException e) {
            getLog().error("Could not open log file", e);
        }

        geometryList = generateGeometryList();
        broadcastMapMessage();
        broadcastSpatMessage();
    }

    private List<IntersectionGeometry> generateGeometryList() {
        Collection<TrafficLight> lights = getOs().getAllTrafficLights();
        TrafficLightProgramPhase phase = getOs().getCurrentPhase();
        long nextSwitch = phase.getRemainingDuration();

        HashMap<String, GenericLane> laneSet = new HashMap<>();
        HashSet<String> dedup = new HashSet<>();
        final GenericLane[] lane = new GenericLane[1];
        lights.forEach(light -> {
            if (!dedup.contains(light.getIncomingLane())) {
                if (lane[0] != null) {
                    laneSet.put(lane[0].getLaneId(), new GenericLane(lane[0]));
                }
                lane[0] = new GenericLane(light.getIncomingLane(), new LaneAttributes(LaneDirection.INGRESS_PATH, LaneSharing.INDIVIDUAL_MOTORIZED_VEHICLE_TRAFFIC, LaneTypeAttribute.VEHICLE), new ArrayList<LaneConnection>());

                dedup.add(light.getIncomingLane());
            }
            lane[0].getConnectsTo().add(new LaneConnection(light.getOutgoingLane(), getOs().getTrafficLightGroup().getGroupId() + "_" + light.getIndex()));
        });
        laneSet.put(lane[0].getLaneId(), new GenericLane(lane[0]));

        List<IntersectionGeometry> geometryList = new ArrayList<>();
        geometryList.add(new IntersectionGeometry(
                getOs().getTrafficLightGroup().getGroupId(),
                getOs().getPosition(),
                laneSet
        ));
        // Create Map Content and Message
//        System.out.println(laneSet.size());
        return geometryList;
    }

    protected void broadcastMapMessage() {

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
        if (isAutoSchedulingEnabled()) {
            getOs().getEventManager().addEvent(new Event(
                    getOs().getSimulationTime() + MAP_SEND_INTERVAL, this, "MAP"
            ));
        }
    }

    protected void broadcastSpatMessage() {
        FullSpatContent content = createSpatContent();
        MessageRouting routing = getOs().getAdHocModule().createMessageRouting()
                .broadcast()
                .geographical(new GeoCircle(getOs().getPosition(), 300))
                .singlehop()
                .build();

        FullSpatm message = new FullSpatm(routing, content);

        // Send
        getOs().getAdHocModule().sendV2xMessage(message);
        onSpatMessageSent(message);

        if (isAutoSchedulingEnabled()) {
            getOs().getEventManager().addEvent(new Event(
                    getOs().getSimulationTime() + SPAT_SEND_INTERVAL, this, "SPAT"
            ));
        }
    }

    protected FullSpatContent createSpatContent() {
        Collection<TrafficLight> lights = getOs().getAllTrafficLights();
        TrafficLightProgramPhase phase = getOs().getCurrentPhase();
        long currentTime = getOs().getSimulationTime();

        List<IntersectionMovement> movements = new ArrayList<>();
        HashSet<String> dedup = new HashSet<>();
        lights.forEach(light -> {
            if (!dedup.contains(light.getIncomingLane())) {
                getLog().info("Current states for lane {} on light {}: {}",
                        light.getIncomingLane(), getOs().getTrafficLightGroup().getGroupId(), light.getCurrentState());
                dedup.add(light.getIncomingLane());
            }
            movements.add(new IntersectionMovement(
                    getOs().getTrafficLightGroup().getGroupId() + "_" + light.getIndex(),
                    TrafficLightState.convertToState(light.getCurrentState()),
                    TimeChangeDetails.convert(phase, currentTime)));
        });

        List<IntersectionState> stateList = new ArrayList<>();
        stateList.add(new IntersectionState(getOs().getTrafficLightGroup().getGroupId(),
                IntersectionStatus.FIXED_TIME_OPERATION, movements));
        return new FullSpatContent(currentTime, stateList);
    }

    protected void onSpatMessageSent(FullSpatm message) {
    }

    // Allow subclasses to opt-out of automatic SPAT/MAP rescheduling (RsuAttackApp disables it)
    protected boolean isAutoSchedulingEnabled() {
        return true;
    }

    @Override
    public void processEvent (Event event){
        Object resource = event.getResource();

        // Check if it matches your expected identifier type
        if (resource instanceof String) {
            String uniqueId = (String) resource;
            getLog().info("Processing event with ID: {}", uniqueId);

            // Use the ID to determine logic
            if ("SPAT".equals(uniqueId)) {
                broadcastSpatMessage();
            } else if ("MAP".equals(uniqueId)) {
                broadcastMapMessage();
            }
        }
    }

    @Override
    public void onShutdown () {
        getLog().info("SpatBroadcastApp shutting down");
    }


}
