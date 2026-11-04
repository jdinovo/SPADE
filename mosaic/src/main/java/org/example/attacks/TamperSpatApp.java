package org.example.attacks;

import org.eclipse.mosaic.lib.geo.GeoCircle;
import org.eclipse.mosaic.lib.objects.trafficlight.TrafficLight;
import org.eclipse.mosaic.lib.objects.trafficlight.TrafficLightProgramPhase;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.example.AttackLogger;
import org.example.CsvOutputDirectory;
import org.example.spat.*;
import org.example.intersection.enums.*;
import org.example.spat.enums.TrafficLightState;

import java.util.*;

/**
 * TAMPERING ATTACK
 * -----------------
 * Mapped to a traffic-light unit that has been "compromised" (i.e. the attacker
 * controls the application logic running on the RSU co-located with the signal
 * controller). Instead of broadcasting the true phase/timing produced by SUMO's
 * traffic light program, this application deliberately falsifies specific fields
 * before transmission -- e.g. stretching the reported "green" duration or
 * reporting a phase that is one step ahead/behind the real one.
 *
 * This models an attacker with code-execution or configuration access on the
 * RSU/controller (cf. Ghena et al. 2014, "Green Lights Forever"), distinguishing
 * it from a pure in-transit MITM: here the falsified message IS the "official"
 * broadcast, not an intercepted copy of someone else's.
 *
 */
public class TamperSpatApp extends RsuAttackApp {

    @Override
    public void onStartup() {
        super.onStartup();
    }

    @Override
    protected void attack() {
        AttackLogger.getInstance().init(CsvOutputDirectory.forConfiguration(
                getOs().getConfigurationPath().toPath()));
        AttackLogger.getInstance().startAttack("TAMPER", getOs().getSimulationTime());

        // Tampered SPaT broadcast (called only on attack ticks)
        Collection<TrafficLight> lights = getOs().getAllTrafficLights();
        TrafficLightProgramPhase phase = getOs().getCurrentPhase();
        long currentTime = getOs().getSimulationTime();

        List<IntersectionMovement> movements = new ArrayList<>();
        HashSet<String> dedup = new HashSet<>();
        lights.forEach(light -> {
            if (!dedup.contains(light.getIncomingLane())) {
                getLog().info("(TAMPER) Current states for lane {} on light {}: {}", light.getIncomingLane(), getOs().getTrafficLightGroup().getGroupId(), light.getCurrentState());
                dedup.add(light.getIncomingLane());
            }
            // Example tampering: flip red<->green
            movements.add(new IntersectionMovement(getOs().getTrafficLightGroup().getGroupId() + "_" + light.getIndex(),
                    TrafficLightState.convertToState(light.getCurrentState()) == TrafficLightState.RED ? TrafficLightState.GREEN : TrafficLightState.RED,
                    TimeChangeDetails.convert(phase, getOs().getSimulationTime())));
        });

        List<IntersectionState> stateList = new ArrayList<>();
        stateList.add(new IntersectionState(getOs().getTrafficLightGroup().getGroupId(), IntersectionStatus.FIXED_TIME_OPERATION, movements));
        FullSpatContent content = new FullSpatContent(currentTime, stateList);
        MessageRouting routing = getOs().getAdHocModule().createMessageRouting()
                .broadcast()
                .geographical(new GeoCircle(getOs().getPosition(), 300))
                .singlehop()
                .build();

        FullSpatm message = new FullSpatm(routing, content);
        getOs().getAdHocModule().sendV2xMessage(message);
    }

    @Override
    protected void attackEnd() {
        // Log transition back to benign behavior; keep scheduler running for future attacks.
        getLog().info("(TAMPER) attack ended at {}", getOs().getSimulationTime());
        AttackLogger.getInstance().stopAttack("TAMPER", getOs().getSimulationTime());
    }

    @Override
    protected long getAttackStartTick() {
        return 50;
    }

    @Override
    protected long getAttackEndTick() {
        return 100;
    }

    @Override
    public void onShutdown() {
        super.onShutdown();
        AttackLogger.getInstance().stopAttack("TAMPER", getOs().getSimulationTime());
    }
}
