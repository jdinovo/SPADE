package org.example.attacks;

import org.eclipse.mosaic.lib.geo.GeoCircle;
import org.eclipse.mosaic.lib.objects.trafficlight.TrafficLight;
import org.eclipse.mosaic.lib.objects.trafficlight.TrafficLightProgramPhase;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.eclipse.mosaic.lib.util.scheduling.Event;
import org.eclipse.mosaic.rti.TIME;
import org.example.AttackLogger;
import org.example.CsvOutputDirectory;
import org.example.RsuSpatApp;
import org.example.spat.*;
import org.example.intersection.enums.*;
import org.example.spat.enums.TrafficLightState;

import java.util.*;

/**
 * Timing ATTACK
 * -----------------
 * Mapped to a traffic-light unit that has been "compromised" (i.e. the attacker
 * controls the application logic running on the RSU co-located with the signal
 * controller). Instead of broadcasting the true phase/timing produced by SUMO's
 * traffic light program, this application deliberately falsifies specific fields
 * before transmission -- e.g. stretching the reported state duration
 *
 * This models an attacker with code-execution or configuration access on the
 * RSU/controller (cf. Ghena et al. 2014, "Green Lights Forever"), distinguishing
 * it from a pure in-transit MITM: here the falsified message IS the "official"
 * broadcast, not an intercepted copy of someone else's.
 *
 */

public class TimingSpatApp extends RsuSpatApp {

    @Override
    public void onStartup() {
        super.onStartup();
        AttackLogger.getInstance().init(CsvOutputDirectory.forConfiguration(
                getOs().getConfigurationPath().toPath()));
        AttackLogger.getInstance().startAttack("TIMING", getOs().getSimulationTime());
    }

    protected void broadcastSpatMessage() {

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
            // TODO :- MANIPULATE DATA
            // Manipulate timings
            movements.add(new IntersectionMovement(getOs().getTrafficLightGroup().getGroupId() + "_" + light.getIndex(),
                    TrafficLightState.convertToState(light.getCurrentState()) == TrafficLightState.RED ? TrafficLightState.GREEN : TrafficLightState.RED,
                    TimeChangeDetails.convert(phase, getOs().getSimulationTime() + (TIME.SECOND * 10))));
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


    @Override
    public void onShutdown() {
        super.onShutdown();
        AttackLogger.getInstance().stopAttack("TIMING", getOs().getSimulationTime());
    }
}
