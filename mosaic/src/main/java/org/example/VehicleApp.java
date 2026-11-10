package org.example;

import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.CamBuilder;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.ReceivedAcknowledgement;
import org.eclipse.mosaic.fed.application.app.AbstractApplication;
import org.eclipse.mosaic.fed.application.app.api.CommunicationApplication;
import org.eclipse.mosaic.fed.application.app.api.VehicleApplication;
import org.eclipse.mosaic.fed.application.app.api.os.VehicleOperatingSystem;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.ReceivedV2xMessage;
import org.eclipse.mosaic.interactions.communication.V2xMessageTransmission;

import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.eclipse.mosaic.lib.objects.v2x.V2xMessage;
import org.eclipse.mosaic.lib.objects.v2x.etsi.Cam;
import org.eclipse.mosaic.lib.objects.v2x.etsi.cam.VehicleAwarenessData;
import org.eclipse.mosaic.lib.objects.vehicle.VehicleData;
import org.eclipse.mosaic.lib.perception.modifier.*;
import org.eclipse.mosaic.lib.perception.objects.TrafficLightObject;
import org.eclipse.mosaic.lib.perception.objects.VehicleObject;
import org.eclipse.mosaic.lib.util.scheduling.Event;

import org.eclipse.mosaic.lib.perception.PerceptionConfiguration;
import org.eclipse.mosaic.rti.TIME;
import org.example.cpm.CoopPercepData;
import org.example.cpm.CoopPercepMessage;
import org.example.intersection.*;
import org.example.spat.IntersectionMovement;
import org.example.spat.enums.TrafficLightState;
import org.example.spat.FullSpatContent;
import org.example.spat.FullSpatm;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public class VehicleApp extends AbstractApplication<VehicleOperatingSystem>
        implements VehicleApplication, CommunicationApplication {

    private BufferedWriter logWriter;

    private boolean percepOrSpatFlag;

    private static final double VIEWING_ANGLE = 160d; // degrees pointing forward
    private static final double VIEWING_RANGE = 100d; // meters
    private static final long CAM_INTERVAL_NS = TIME.SECOND;

    private static final double CONF_THRESHOLD = 0.6d;   // below this, vehicle doesn't trust the reading
    private static final double SAFE_FALLBACK_SPEED = 5.0d; // m/s, cautious speed when uncertain

    // TODO: load from application_config.json — placeholder constant schedule for now
    // could derived through weather channel information transmitted over the network
    // or through computer vision methods/sensors on vehicle
    private static final double DEFAULT_VISIBILITY_SCORE = 0.95;
    private double currentVisibilityScore = DEFAULT_VISIBILITY_SCORE;

    // Distance (m) from the stop line at which the app starts reacting to SPaT info.
    private static final double REACTION_DISTANCE_M = 100.0;
    private static final double LOGGING_RADIUS_M = 200.0;

    // Speed (m/s) the vehicle will accelerate to if it believes it has a clear green.
    private static final double PROCEED_SPEED_MS = 13.9; // ~50 km/h

    // How long the commanded speed change should be applied for.
    private static final long SPEED_COMMAND_INTERVAL = TIME.SECOND * 5;

    private final HashMap<String, IntersectionGeometry> intersectionGeometries = new HashMap<>();
    private PerceivedTrafficLight lastPerception;
    private IntersectionGeometry loggingIntersection;
    private boolean loggingWindowActive;
    private boolean enteredLoggingIntersection;
    private boolean passedLoggingIntersection;

    // Per-intersection approach state. Unlike a simple "passed forever" flag,
    // this can cycle: NOT_APPROACHING -> APPROACHING -> PASSED -> APPROACHING
    // again if the vehicle loops back around the block and re-enters an
    // approach edge for the same intersection later in the trip.
    private enum ApproachState { NOT_APPROACHING, APPROACHING, PASSED }

    private final Map<String, ApproachState> intersectionState = new HashMap<>();

    public VehicleApp(String lightFlag) {
        this(lightFlag, DEFAULT_VISIBILITY_SCORE);
    }

    public VehicleApp(String lightFlag, double currentVisibilityScore) {
        super(); // Call super constructor
        this.percepOrSpatFlag = false; // Default
        this.currentVisibilityScore = currentVisibilityScore;

        // 2. Parse the string passed from JSON
        if (lightFlag != null && !lightFlag.isEmpty()) {
            // pass true for camera perception, false for spat perception of traffic lights
            this.percepOrSpatFlag = Boolean.parseBoolean(lightFlag);
        }
    }

    @Override
    public void onStartup() {
        // CRITICAL: without this, SUMO's default safety layer will silently
        // override any speed command that would run a red light or violate
        // right-of-way -- regardless of what changeSpeedWithInterval() below
        // requests, and regardless of the vType jm* attributes, which govern
        // SUMO's OWN autonomous decisions, not its response to externally
        // commanded (TraCI/MOSAIC) speed changes. SUMO's own docs give the
        // working raw bitset for "run a red light even if the intersection
        // is occupied" as 39 -- i.e. disregard safe speed, right-of-way
        // (both approaching and within the intersection), while still
        // regarding acceleration/deceleration bounds. Exact enum/field names
        // below should be confirmed against your MOSAIC version's
        // VehicleParameters/SpeedMode javadoc -- if AGGRESSIVE doesn't
        // actually clear the "brake hard to avoid red light" bit, you may
        // need a custom SpeedMode construction instead of a preset.
//        getOs().requestVehicleParametersUpdate()
//                .changeSpeedMode(SpeedMode.AGGRESSIVE)
//                .apply();

        getOs().getAdHocModule().enable();
        getLog().info("VehicleApp started on {}", getOs().getId());

        PerceptionConfiguration cfg =
                new PerceptionConfiguration.Builder(VIEWING_ANGLE, VIEWING_RANGE)
                        .addModifiers(
                                new BoundingBoxOcclusion(),      // realistic occlusion between vehicles
                                new WallOcclusion()            // occlusion by buildings
                                //new DistanceFilter(getRandom(), 0.0),
                                //new PositionModifier(getRandom()),// GPS/detection noise
                                //new DimensionsModifier(getRandom()),
                                //new HeadingModifier(getRandom())
                        ).build();
        getOs().getPerceptionModule().enable(cfg);
        try {
           Path outputDirectory = CsvOutputDirectory.forConfiguration(
                   getOs().getConfigurationPath().toPath());
           Path csvPath = outputDirectory.resolve(getOs().getId() + "_veh_output.csv").normalize();
           if (!csvPath.startsWith(outputDirectory)) {
               throw new IOException("Vehicle CSV path must remain inside " + outputDirectory);
           }
           logWriter = new BufferedWriter(new FileWriter(csvPath.toFile()));
           getLog().info("Writing vehicle CSV to {}", csvPath);
//            logWriter.write("time,id,state,lane,distance\n");
        } catch (IOException e) {
            getLog().error("Could not open log file", e);
        }
    }

    private void processPerception() {

        // TODO :- NEED TO PERCEIVE OTHER VEHICLES AS WELL - SHOULD MAYBE USE VEHICLE PRECEPTION TO PREVENT COLLISIONS BUT COULD ALSO USE CAM INFO
        long now = getOs().getSimulationTime();

        List<VehicleObject> perceivedVehicles = getOs().getPerceptionModule().getPerceivedVehicles();
        List<TrafficLightObject> perceivedLights = getOs().getPerceptionModule().getPerceivedTrafficLights();
        TrafficLightObject light = getClosestRelevantLight(perceivedLights);

        // log the list of perceived traffic light IDs
        if (light != null) {
            // process and broadcast perception of traffic lights
            List<PerceivedTrafficLight> allProcessedPerceivedLights = new ArrayList<>();
            for (TrafficLightObject tl : perceivedLights) {
                allProcessedPerceivedLights.add(processTrafficLightPerception(tl));
            }
            broadcastCpm(new CoopPercepData(getOs().getSimulationTime(), getOs().getId(),
                    getOs().getPosition().getLatitude(), getOs().getPosition().getLongitude(), allProcessedPerceivedLights));
            lastPerception = processTrafficLightPerception(light);

                // --- log via Interaction ---
//                PerceivedTrafficLightInteraction interaction = new PerceivedTrafficLightInteraction(
//                        now, getOs().getId(), getOs().getId(), "tlId", // tl.getId()
//                        distance, angleOffCenter,
//                        oneHot[0], oneHot[1], oneHot[2],
//                        cameraConf, currentVisibilityScore, confVisWeighted
//                );
//                getOs().sendInteractionToRti(interaction);

                // --- ACTUALLY AFFECT THE SIMULATION ---
//                applyDrivingDecision(detectedColor, confVisWeighted);
//            }
//            for (TrafficLightObject light : perceivedLights) {
            getLog().infoSimTime(this,
                    "PERCEIVED_TL: id={}, lane={}, distance={}, angleOffCenter={}, oneHot={}, cameraConf={}, " +
                            "visibilityScore={}, confVisWeighted={}",
                    lastPerception.getTrafficLightId(),
                    lastPerception.getIncomingLane(),
                    lastPerception.getDistance(),
                    lastPerception.getAngleOffCenter(),
                    lastPerception.getOneHotState(),
                    lastPerception.getCameraConf(),
                    lastPerception.getCurVisScore(),
                    lastPerception.getConfVisWeighted()
            );
//            }
            if (shouldLogVehicleData()) {
                try {
                    logWriter.write(String.format("TL_PERCEPTION,%d,%s,%s,%f,%f,%d,%d,%d,%f,%f,%f%n",
                            getOs().getSimulationTime(),
                            lastPerception.getTrafficLightId(),
                            lastPerception.getIncomingLane(),
                            lastPerception.getDistance(),
                            lastPerception.getAngleOffCenter(),
                            lastPerception.getOneHotState()[0],
                            lastPerception.getOneHotState()[1],
                            lastPerception.getOneHotState()[2],
                            lastPerception.getCameraConf(),
                            lastPerception.getCurVisScore(),
                            lastPerception.getConfVisWeighted()));
                    logWriter.flush();
                } catch (IOException e) {
                    getLog().error("Failed writing Perception log entry", e);
                }
            }
        } else {
            // clear perception once no longer relevant
            lastPerception = null;
        }

//        for (TrafficLightObject tl : perceivedLights) {
//            PerceivedTrafficLightInteraction interaction = new PerceivedTrafficLightInteraction(
//                    now,
//                    getOs().getId(),
//                    tl.getId(),
//                    tl.getPosition().x,
//                    tl.getPosition().y,
//                    tl.getTrafficLightState().toString()
//            );
//            getOs().sendInteractionToRti(interaction);
//        }

        // Log the data to the application log file
        for (VehicleObject vehicleObject : perceivedVehicles) {
            double vehDistance = vehicleObject.getPosition().distanceTo(getOs().getPosition().toVector3d());
            getLog().infoSimTime(this,
                    "PERCEIVED_VEHICLE: id={}, position={}, speed={}, heading={}, lane={}, distance={}",
                    vehicleObject.getId(),
                    vehicleObject.getPosition().toGeo().getLatitude() + ":" +  vehicleObject.getPosition().toGeo().getLongitude(),
                    vehicleObject.getSpeed(),
                    vehicleObject.getHeading(),
                    vehicleObject.getEdgeId() + ":" + vehicleObject.getLaneIndex(),
                    vehDistance);
            if (shouldLogVehicleData()) {
                try {
                    logWriter.write(String.format("VEHICLE_PERCEPTION,%d,%s,%s,%f,%f,%f,%s%n",
                            getOs().getSimulationTime(),
                            vehicleObject.getId(),
                            vehicleObject.getPosition().toGeo().getLatitude() + ":" +  vehicleObject.getPosition().toGeo().getLongitude(),
                            vehDistance,
                            vehicleObject.getSpeed(),
                            vehicleObject.getHeading(),
                            vehicleObject.getEdgeId() + ":" + vehicleObject.getLaneIndex()));
                    logWriter.flush();
                } catch (IOException e) {
                    getLog().error("Failed writing Perception log entry", e);
                }

            }

            if (vehDistance < 1.5) {
                getLog().infoSimTime(this, "COLLISION DETECTED with vehicle: {}", vehicleObject.getId());
                if (shouldLogVehicleData()) {
                    try {
                        logWriter.write(String.format("COLLISION,%d,%s,%s,%s,%f%n",
                                getOs().getSimulationTime(),
                                vehicleObject.getId(),
                                vehicleObject.getPosition(),
                                getOs().getPosition(),
                                vehDistance));
                        logWriter.flush();
                    } catch (IOException e) {
                        getLog().error("Failed writing Collision log entry", e);
                    }
                }
                // Trigger emergency braking or message broadcasting here
            }
        }

        // send CAM
        getOs().getAdHocModule().sendCam();

//        getOs().getEventManager().addEvent(getOs().getSimulationTime() + CAM_INTERVAL_NS, this);

    }

    private void broadcastCpm(CoopPercepData cpd) {
        long currentTime = getOs().getSimulationTime();

        // Send
        MessageRouting routing = getOs().getAdHocModule().createMessageRouting()
                .broadcast()
                .topological()
                .singlehop()
                .build();
        getOs().getAdHocModule().sendV2xMessage(new CoopPercepMessage(routing, cpd));
        for (PerceivedTrafficLight tl : cpd.getPerceivedObjects()) {
            getLog().info("Transmitting perceived traffic signal {} state of {}", tl.getTrafficLightId(), tl.getSignalState());
        }
    }

    private void logVehicleData() {
        VehicleData veh = getOs().getVehicleData();
        if (shouldLogVehicleData() && veh != null) {
            try {
                logWriter.write(String.format("VEHICLE_DATA,%d,%s,%s,%s,%b,%s,%f,%f,%f,%f,%f,%f%n",
                        getOs().getSimulationTime(),
                        veh.getName(),
                        veh.getRouteId(),
                        veh.getRoadPosition().getConnectionId() + "_" + veh.getRoadPosition().getLaneIndex(),
                        veh.getVehicleStopMode().isParking(),
                        veh.getPosition().getLatitude() + ":" + veh.getPosition().getLongitude(),
                        veh.getHeading(),
                        veh.getSpeed(),
                        veh.getThrottle(),
                        veh.getBrake(),
                        veh.getLongitudinalAcceleration(),
                        veh.getSlope()
                ));
                logWriter.flush();
            } catch (IOException e) {
                getLog().error("Failed writing Vehicle log entry", e);
            }
        }
    }

    @Override
    public void onVehicleUpdated(VehicleData previous, VehicleData updated) {
        boolean passedLoggingIntersection = updateLoggingWindow(updated);
        processPerception();
        logVehicleData();
        if (passedLoggingIntersection) {
            loggingWindowActive = false;
            getLog().info("Stopped vehicle data logging for {} after passing intersection {}",
                    getOs().getId(), loggingIntersection.getId());
        }
    }

    private boolean updateLoggingWindow(VehicleData vehicle) {
        String currentLane = vehicle.getRoadPosition().getConnectionId() + "_"
                + vehicle.getRoadPosition().getLaneIndex();

        if (loggingIntersection == null) {
            double nearestDistance = Double.POSITIVE_INFINITY;
            for (IntersectionGeometry geometry : intersectionGeometries.values()) {
                double distance = vehicle.getPosition().distanceTo(geometry.getRefPoint());
                if (distance <= LOGGING_RADIUS_M && distance < nearestDistance) {
                    loggingIntersection = geometry;
                    nearestDistance = distance;
                }
            }
            if (loggingIntersection != null) {
                loggingWindowActive = true;
                getLog().info("Started vehicle data logging for {} within {}m of intersection {}",
                        getOs().getId(), LOGGING_RADIUS_M, loggingIntersection.getId());
            }
        }

        if (!loggingWindowActive || loggingIntersection == null) {
            return false;
        }

        Map<String, GenericLane> lanes = loggingIntersection.getLaneSet();
        if (lanes.containsKey(currentLane)) {
            enteredLoggingIntersection = true;
        } else if (enteredLoggingIntersection && isOutgoingLane(lanes, currentLane)) {
            passedLoggingIntersection = true;
        }

        return passedLoggingIntersection
                && vehicle.getPosition().distanceTo(loggingIntersection.getRefPoint()) > LOGGING_RADIUS_M;
    }

    private boolean isOutgoingLane(Map<String, GenericLane> lanes, String currentLane) {
        return lanes.values().stream()
                .flatMap(lane -> lane.getConnectsTo().stream())
                .anyMatch(connection -> currentLane.equals(connection.getConnectingLane()));
    }

    private boolean shouldLogVehicleData() {
        return logWriter != null && loggingWindowActive;
    }

    @Override
    public void onMessageReceived(ReceivedV2xMessage receivedV2xMessage) {
        V2xMessage msg = receivedV2xMessage.getMessage();
        String messageId = String.valueOf(msg.getId());
        String messageAttack = AttackLogger.getInstance().getMessageAttack(messageId);
        if (shouldLogVehicleData()) {
            try {
                logWriter.write(String.format("MESSAGE_RECEIVED,%d,%s,%s,%b,%s%n",
                        getOs().getSimulationTime(),
                        msg.getClass().getSimpleName(),
                        messageId,
                        messageAttack != null,
                        messageAttack != null ? messageAttack : "BENIGN"));
                logWriter.flush();
            } catch (IOException e) {
                getLog().error("Failed writing received-message label", e);
            }
        }
        if (msg instanceof CoopPercepMessage cpm) {
            CoopPercepData content = cpm.getContent();
            for (PerceivedTrafficLight tl : content.getPerceivedObjects()) {
                getLog().infoSimTime(this,
                        "SHARED_TL_PERCEPTION: sourceVehicle={}, sourceVehicleLat={}, sourceVehicleLong={}, id={}, lane={}, distance={}, angleOffCenter={}, oneHot={}, cameraConf={}, " +
                                "visibilityScore={}, confVisWeighted={}",
                        content.getSourceVehicle(),
                        content.getLatitude(),
                        content.getLongitude(),
                        tl.getTrafficLightId(),
                        tl.getIncomingLane(),
                        tl.getDistance(),
                        tl.getAngleOffCenter(),
                        tl.getOneHotState(),
                        tl.getCameraConf(),
                        tl.getCurVisScore(),
                        tl.getConfVisWeighted()
                );
//            }
                if (shouldLogVehicleData()) {
                    try {
                        logWriter.write(String.format("SHARED_TL_PERCEPTION,%d,%s,%s,%s,%s,%f,%f,%d,%d,%d,%f,%f,%f%n",
                                getOs().getSimulationTime(),
                                content.getSourceVehicle(),
                                content.getLatitude() + ":" + content.getLongitude(),
                                tl.getTrafficLightId(),
                                tl.getIncomingLane(),
                                tl.getDistance(),
                                tl.getAngleOffCenter(),
                                tl.getOneHotState()[0],
                                tl.getOneHotState()[1],
                                tl.getOneHotState()[2],
                                tl.getCameraConf(),
                                tl.getCurVisScore(),
                                tl.getConfVisWeighted()));
                        logWriter.flush();
                    } catch (IOException e) {
                        getLog().error("Failed writing Perception log entry", e);
                    }
                }
            }
        } else if (msg instanceof FullSpatm spatm) {
            FullSpatContent content = spatm.getContent();
            // get vehicle data to determine light to compare to perception
            GenericLane currentIntersectionLane = null;
            if(!intersectionGeometries.isEmpty()) {
                VehicleData veh = getOs().getVehicleData();
                assert veh != null;
                IntersectionGeometry curGeom = intersectionGeometries.get(content.getIntersectionStateList().getFirst().getId());
                if (curGeom != null) {
                    currentIntersectionLane = curGeom.getLaneSet().get(
                            veh.getRoadPosition().getConnectionId() + "_" + veh.getRoadPosition().getLaneIndex());
                }
            }
            GenericLane finalCurrentIntersectionLane = currentIntersectionLane;
            // ------------
            content.getIntersectionStateList().getFirst().getMovementList().forEach(movementState -> {
                getLog().info("Received SPaT from {} at t={} state={} remaining={}",
                        movementState.getSignalId(), content.getTime(),
                        movementState.getState(), movementState.getTime().getMinEndTime());

                if (shouldLogVehicleData()) {
                    try {
                        logWriter.write(String.format("SPAT_RECEIVED,%d,%s,%d,%s,%s,%s%n",
                                getOs().getSimulationTime(),
                                movementState.getSignalId(),
                                content.getTime(),
                                movementState.getState(),
                                movementState.getTime().getMaxEndTime(),
                                movementState.getTime().getMinEndTime()));
                        logWriter.flush();
                    } catch (IOException e) {
                        getLog().error("Failed writing SPAT log entry", e);
                    }
                }

                if (finalCurrentIntersectionLane != null && lastPerception != null) {
                    // compare to just first light for now since we have no way of determining what lane the vehicle wishes to go into
                    String lightId = finalCurrentIntersectionLane.getConnectsTo().getFirst().getSignalId();
                    if (Objects.equals(movementState.getSignalId(), lightId)) {
                        getLog().info("PERCEPTION V2X COMPARISON - Perception identified signal {}, SPaT dictated signal {}, match={}",
                                lastPerception.getSignalState(), movementState.getState(), lastPerception.getSignalState() == movementState.getState());
                    }
                }

            });

            // handle driving based on current spat
            reactToSpat(content);

        } else if (msg instanceof Cam camMsg) {
            // Handle received CAM
            getLog().info("Received CAM from {} at t={} position={} distance={} awareness={}",
                    camMsg.getUnitID(), camMsg.getGenerationTime(),
                    camMsg.getPosition(), camMsg.getPosition().distanceTo(getOs().getPosition()),
                    camMsg.getAwarenessData());

            if (shouldLogVehicleData()) {
                try {
                    VehicleAwarenessData ad = (VehicleAwarenessData) camMsg.getAwarenessData();
                    logWriter.write(String.format("CAM_RECEIVED,%d,%s,%s,%s,%f,%s%n",
                            getOs().getSimulationTime(),
                            camMsg.getUnitID(),
                            camMsg.getGenerationTime(),
                            camMsg.getPosition().getLongitude() + ":" + camMsg.getPosition().getLatitude(),
                            camMsg.getPosition().distanceTo(getOs().getPosition()),
                            ad.getVehicleClass() + ":" + ad.getLength() + ":" + ad.getWidth() + ":" +
                                    ad.getHeading() + ":" + ad.getSpeed() + ":" + ad.getLaneIndex() + ":" +
                                    ad.getLongitudinalAcceleration()
                    ));
                    logWriter.flush();
                } catch (IOException e) {
                    getLog().error("Failed writing CAM log entry", e);
                }
            }
        } else if (msg instanceof MapDataMessage mapMsg) {
            MapData content = mapMsg.getContent();
            // Handle received MAP
            for (IntersectionGeometry geometry : content.getIntersectionGeometryList()) {
                // add to local intersection maps
                this.intersectionGeometries.put(geometry.getId(), geometry);
                getLog().info("Received Intersection MAP for {} at t={} position={}",
                        geometry.getId(), content.getTimeStamp(), geometry.getRefPoint());
                if (shouldLogVehicleData()) {
                    // log for each lane in the laneset might want to do for each connection as well unsure how to log cleanly
                    geometry.getLaneSet().values().forEach(l -> {
                        try {
                            // build out the lane connections string
                            StringBuilder laneConnections = new StringBuilder();
                            for (LaneConnection c : l.getConnectsTo()) {
                                if (!laneConnections.isEmpty()) {
                                    laneConnections.append(";");
                                }
                                laneConnections.append(c.toString());
                            }
                            logWriter.write(String.format("MAP_LANE_RECEIVED,%d,%d,%s,%s,%s,%s,%s%n",
                                    getOs().getSimulationTime(),
                                    content.getTimeStamp(),
                                    geometry.getId(),
                                    l.getLaneId(),
                                    l.getLaneAttributes(),
                                    laneConnections.toString(),
                                    geometry.getRefPoint().getLatitude() + ":" + geometry.getRefPoint().getLongitude()));
                            logWriter.flush();
                        } catch (IOException e) {
                            getLog().error("Failed writing MAP log entry", e);
                        }
                    });

                }
            }
        }
    }

    private double getDistanceToUpcomingIntersection() {
        // return the distance to the perceived intersection
        return lastPerception != null ? getOs().getPosition().distanceTo(lastPerception.getPosition().toGeo()) : REACTION_DISTANCE_M;
    }

    @Override
    public void onAcknowledgementReceived(ReceivedAcknowledgement receivedAcknowledgement) {

    }

    @Override
    public void onCamBuilding(CamBuilder camBuilder) {

    }

    @Override
    public void onMessageTransmitted(V2xMessageTransmission v2xMessageTransmission) {

    }

    @Override
    public void processEvent(Event event) {
        Object resource = event.getResource();

        // Check if it matches your expected identifier type
        if (resource instanceof String) {
            String uniqueId = (String) resource;
            getLog().info("Processing event with ID: {}", uniqueId);

            // Use the ID to determine logic
            if ("resetSpeed".equals(uniqueId)) {
                getOs().resetSpeed();
            }
        }

    }

    @Override
    public void onShutdown() {
        try {
            if (logWriter != null) logWriter.close();
            getOs().getAdHocModule().disable();
        } catch (IOException ignored) {
        }
        getLog().info("SpatLoggingDriverApp shutting down");
    }

    private PerceivedTrafficLight processTrafficLightPerception(TrafficLightObject light) {
        // update visibility_score for current sim time from your schedule
        currentVisibilityScore = lookupVisibilityForTime(getOs().getSimulationTime());
        // get current vehicle data
        VehicleData veh = getOs().getVehicleData();
        assert veh != null;

//            for (Object obj : perceivedLights) {
        // TrafficLightObject tl = (TrafficLightObject) obj;

        // --- geometry: distance & angle-off-boresight ---
        double egoX = veh.getProjectedPosition().getX();
        double egoY = veh.getProjectedPosition().getY();
        double egoHeadingDeg = veh.getHeading();

        double tlX = light.getPosition().toCartesian().getX();
        double tlY = light.getPosition().toCartesian().getY();

//                System.out.println(egoX + "," + egoY + " " + tlX + "," + tlY);
        double dx = tlX - egoX;
        double dy = tlY - egoY;
        double distance = Math.sqrt(dx * dx + dy * dy);

        double bearingToTl = (90 - Math.toDegrees(Math.atan2(dy, dx)) + 360) % 360;
        double angleOffCenter = normalizeAngle(bearingToTl - egoHeadingDeg);

        // --- camera_conf: geometric detection confidence ---
        double cameraConf = computeCameraConf(distance, angleOffCenter);

        // --- conf_vis_weighted ---
        double confVisWeighted = cameraConf * currentVisibilityScore;

        // --- ground truth state (confirm exact getter/enum via javap) ---
        TrafficLightState trueColor = TrafficLightState.convertToState(light.getTrafficLightState()); // tl.<stateGetter>().toString() -- confirm

        // --- camera_color: possibly misclassified based on confVisWeighted ---
        TrafficLightState detectedColor = classifyWithNoise(trueColor, confVisWeighted);
        int[] oneHot = toOneHot(detectedColor); // [red, yellow, green]

        return new PerceivedTrafficLight(getOs().getSimulationTime(), light.getId(),
                tlX, tlY,
                detectedColor,
                light.getIncomingLane(),
                distance,
                angleOffCenter,
                oneHot,
                cameraConf,
                currentVisibilityScore,
                confVisWeighted,
                light.getPosition());
    }


    /**
     * Get the closest light which is facing the vehicle i.e the most relevant light to which the vehicle is reacting
     *
     * @param perceivedLights List of lights which have been perceived
     * @return The most relevant light
     */
    public TrafficLightObject getClosestRelevantLight(List<TrafficLightObject> perceivedLights) {

        if (perceivedLights.isEmpty()) return null;
        if (intersectionGeometries.isEmpty()) return null;

        VehicleData veh = getOs().getVehicleData();

        if (veh == null) return null;

        TrafficLightObject closestLight = null;
//        double minDistance = Double.MAX_VALUE;
//
//        GeoPoint vehiclePos = getOs().getPosition();
//        double vehicleHeading = Objects.requireNonNull(getOs().getVehicleData()).getHeading(); // Radians
//
//        for (TrafficLightObject light : perceivedLights) {
//            GeoPoint lightPos = light.getPosition().toGeo();
//            double distance = vehiclePos.distanceTo(lightPos);
//
//            // Calculate bearing from Vehicle TO Light
//            double azimuthDeg = GeoUtils.azimuth(vehiclePos, lightPos);
//            double bearingToLight = Math.toRadians(azimuthDeg);
//
//            // Check if vehicle is driving TOWARDS the light
//            double angleDiff = MathUtils.angleDif(vehicleHeading, bearingToLight);
//
//            // Tolerance: +/- 45 degrees
//            double tolerance = Math.PI / 4;
//            boolean isApproaching = Math.abs(angleDiff) < tolerance;
//
//            // Select closest among those approached
//            if (isApproaching && distance < minDistance) {
//                minDistance = distance;
//                closestLight = light;
//            }
//        }
        for (TrafficLightObject light : perceivedLights) {
            if (Objects.equals(light.getIncomingLane(),
                    veh.getRoadPosition().getConnectionId() + "_" + veh.getRoadPosition().getLaneIndex())) {
                closestLight = light;
            }
        }
        return closestLight;
    }

    protected void reactToSpat(FullSpatContent content) {
        // if simply using cam perception to determine light then do so and exit
        if (percepOrSpatFlag) {
            if (lastPerception != null) {
                if (lastPerception.getSignalState() == TrafficLightState.GREEN) {
                    getLog().infoSimTime(this,
                            "Vehicle {} trusts Perception {} (phase={}) -> proceeding at {} m/s",
                            getOs().getId(), lastPerception.getTrafficLightId(), lastPerception.getSignalState(), PROCEED_SPEED_MS);

                    // command the vehicle to hold/accelerate to proceed.
                    logDecision("PERCEPTION," + lastPerception.getSignalState() + ",PROCEED");
//                    getOs().changeSpeedWithInterval(PROCEED_SPEED_MS, getOs().getSimulationTime() + (TIME.SECOND));
//                    getOs().getEventManager().addEvent(new Event(getOs().getSimulationTime() + SPEED_COMMAND_INTERVAL, this, "resetSpeed"));
                } else {
                    getLog().infoSimTime(this,
                            "Vehicle {} trusts Perception {} (phase={}) -> braking for signal",
                            getOs().getId(), lastPerception.getTrafficLightId(), lastPerception.getSignalState());
                    // brake as needed to stop before intersection
                    logDecision("PERCEPTION," + lastPerception.getSignalState() + ",BRAKE");
//                getOs().changeSpeedWithForcedAcceleration(0.0, Math.floor(Math.pow(veh.getSpeed(), 2)/(2*(getDistanceToUpcomingIntersection()))));
//                getOs().changeSpeedWithPleasantAcceleration(0);
//                    getOs().changeSpeedWithInterval(0, getOs().getSimulationTime() + (TIME.SECOND));
                }
            }
            return;
        }

        if (intersectionGeometries.isEmpty()) return;

        Set<String> approachEdges = intersectionGeometries.get(content.getIntersectionStateList().getFirst().getId()).getLaneSet().keySet();
        if (approachEdges.isEmpty()) {
            getLog().infoSimTime(this,
                    "No MAP data yet for intersection {}; ignoring SPaT until geometry is known",
                    content.getIntersectionStateList().getFirst().getId());
            return;
        }

        VehicleData veh = getOs().getVehicleData();

        assert veh != null;
        String currentEdgeId = veh.getRoadPosition().getConnectionId() + "_" + veh.getRoadPosition().getLaneIndex();

        ApproachState previousState = intersectionState.getOrDefault(content.getIntersectionStateList().getFirst().getId()
                , ApproachState.NOT_APPROACHING);

        if (approachEdges.contains(currentEdgeId)) {
            if (previousState == ApproachState.PASSED) {
                // The vehicle left this intersection's approach edges before
                // and has now re-entered one -- e.g. it looped around the
                // block. Treat this as a fresh approach: SPaT is actionable
                // again, with no memory of the earlier pass-through held
                // against it.
                getLog().infoSimTime(this,
                        "Vehicle {} has re-entered an approach edge for intersection {} after previously passing it; resuming normal SPaT handling",
                        getOs().getId(), content.getIntersectionStateList().getFirst().getId());
            }
            intersectionState.put(content.getIntersectionStateList().getFirst().getId(), ApproachState.APPROACHING);
//            getOs().requestVehicleParametersUpdate()
//                    .changeSpeedMode(SpeedMode.AGGRESSIVE)
//                    .apply();
        } else {
            if (previousState == ApproachState.APPROACHING) {
                // Was confirmed on an approach edge, now isn't -- crossed
                // the junction. Mark passed (for now -- reversible if the
                // vehicle later re-approaches, per the branch above).
                intersectionState.put(content.getIntersectionStateList().getFirst().getId(), ApproachState.PASSED);

                getLog().infoSimTime(this,
                        "Vehicle {} has passed intersection {} (left approach edges, now on {}); ignoring SPaT about it unless it re-approaches",
                        getOs().getId(), content.getIntersectionStateList().getFirst().getId(), currentEdgeId);
            }
            // Not currently on an approach edge for this intersection --
            // whether never-yet-approached, already passed, or received from
            // long range before ever reaching it -- this SPaT isn't
            // actionable right now.
//            getOs().requestVehicleParametersUpdate()
//                    .changeSpeedMode(SpeedMode.DEFAULT)
//                    .apply();
//            getOs().resetSpeed();
            return;
        }

        // handle moving the vehicle through the intersection based on SPaT data (real or fake) that has been received
        double distanceToStopLine = getDistanceToUpcomingIntersection();
        if (distanceToStopLine > REACTION_DISTANCE_M) {
            return; // not close enough to the signal to act on this message yet
        }

        /* NAIVE TRUST: act directly on the payload's claimed remaining time,
         with no independent verification (no camera cross-check, no
         freshness/sequence validation, no plausibility bound against a
         known minimum signal cycle length) */
        IntersectionMovement currentLight = null;

        // get the laneset for the given signal (this assumes the intersection state list only has one intersection)
        // then get the lights for the specific lane this vehicle is in
        GenericLane currentIntersectionLane = intersectionGeometries.get(
                content.getIntersectionStateList().getFirst().getId()).getLaneSet().get(
                        veh.getRoadPosition().getConnectionId() + "_" + veh.getRoadPosition().getLaneIndex());

        if (currentIntersectionLane == null) {
            return;
        }
        // compare to just first light for now since we have no way of determining what lane the vehicle wishes to go into
        String lightId = currentIntersectionLane.getConnectsTo().getFirst().getSignalId();

        // get the current light that we are in the lane for
        for (IntersectionMovement m : content.getIntersectionStateList().getFirst().getMovementList()) {
            if (Objects.equals(lightId, m.getSignalId())) {
                currentLight = m;
                break;
            }
        }

        if (currentLight != null) {
            if (currentLight.getState() == TrafficLightState.GREEN && currentLight.getTime().getMinEndTime() > 3000L) {
                getLog().infoSimTime(this,
                        "Vehicle {} trusts SPaT {} (phase={}, believedRemaining={}ms) -> proceeding at {} m/s",
                        getOs().getId(), currentLight.getSignalId(), currentLight.getState(), currentLight.getTime().getMinEndTime(), PROCEED_SPEED_MS);

                // command the vehicle to hold/accelerate to proceed.
                logDecision("SPAT," + currentLight.getState() + ",PROCEED");
//                getOs().changeSpeedWithInterval(PROCEED_SPEED_MS, getOs().getSimulationTime() + (TIME.SECOND));
//                getOs().getEventManager().addEvent(new Event(getOs().getSimulationTime() + SPEED_COMMAND_INTERVAL, this, "resetSpeed"));
            // only stop if we are close to the intersection
            } else if (distanceToStopLine <= 10) {
                getLog().infoSimTime(this,
                        "Vehicle {} trusts SPaT {} (phase={}, believedRemaining={}ms) -> braking for signal",
                        getOs().getId(), currentLight.getSignalId(), currentLight.getState(), currentLight.getTime().getMinEndTime());
                // brake as needed to stop before intersection
                logDecision("SPAT," + currentLight.getState() + ",BRAKE");
//                getOs().changeSpeedWithForcedAcceleration(0.0, Math.floor(Math.pow(veh.getSpeed(), 2)/(2*(getDistanceToUpcomingIntersection()))));
//                getOs().changeSpeedWithPleasantAcceleration(0);
//                getOs().changeSpeedWithInterval(0, getOs().getSimulationTime() + (TIME.SECOND));
            }

        }
    }

    private double computeCameraConf(double distance, double angleOffCenter) {
        double distanceRatio = Math.min(distance / VIEWING_RANGE, 1.0);
        double angleRatio = Math.min(Math.abs(angleOffCenter) / (VIEWING_ANGLE / 2.0), 1.0);

        double distanceFactor = Math.max(0.0, 1 - Math.pow(distanceRatio, 2));
        double angleFactor = Math.max(0.0, 1 - Math.pow(angleRatio, 2));

        double base = 0.4 + 0.6 * distanceFactor * angleFactor; // floor of 0.4: "detected but uncertain"
        double noise = getRandom().nextGaussian() * 0.03;       // small sensor jitter
        return clamp(base + noise, 0.0, 1.0);
    }

    private TrafficLightState classifyWithNoise(TrafficLightState trueColor, double confVisWeighted) {
        if (getRandom().nextDouble() < confVisWeighted) {
            return trueColor; // correctly classified
        }
        // misclassify: pick one of the other two colors at random
        List<TrafficLightState> others = Stream.of(TrafficLightState.RED, TrafficLightState.YELLOW, TrafficLightState.GREEN)
                .filter(c -> !c.equals(trueColor)).toList();
        return others.get(getRandom().nextInt(others.size()));
    }

    private int[] toOneHot(TrafficLightState color) {
        return switch (color) {
            case RED -> new int[]{1, 0, 0};
            case YELLOW -> new int[]{0, 1, 0};
            default -> new int[]{0, 0, 1}; // GREEN
        };
    }

    private void applyDrivingDecision(String detectedColor, double confVisWeighted) {
        if (confVisWeighted < CONF_THRESHOLD) {
            // low trust: ignore what color it thinks it saw, default to cautious speed
            getOs().changeSpeedWithInterval(SAFE_FALLBACK_SPEED, 2 * TIME.SECOND);
            return;
        }
        switch (detectedColor.toUpperCase()) {
            case "RED" -> getOs().changeSpeedWithInterval(0.0, 3 * TIME.SECOND);
            case "YELLOW" -> getOs().changeSpeedWithInterval(SAFE_FALLBACK_SPEED, 2 * TIME.SECOND);
            default -> { /* GREEN, high confidence: proceed, no override */ }
        }
    }

    private double lookupVisibilityForTime(long simTimeNs) {
        // TODO: replace with real schedule lookup from application_config.json
        return currentVisibilityScore;
    }

    private double normalizeAngle(double angle) {
        double a = angle % 360;
        if (a > 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private void logDecision(String decision) {
        if (!shouldLogVehicleData()) {
            return;
        }
        try {
            logWriter.write(String.format("PROCEED_DECISION,%d,%s%n",
                    getOs().getSimulationTime(),
                    decision
            ));
            logWriter.flush();
        } catch (IOException e) {
            getLog().error("Failed writing DECISION log entry", e);
        }
    }
}