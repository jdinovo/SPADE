package org.example.attacks;

import org.eclipse.mosaic.lib.geo.GeoCircle;
import org.eclipse.mosaic.lib.objects.trafficlight.TrafficLight;
import org.eclipse.mosaic.lib.objects.trafficlight.TrafficLightProgramPhase;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.example.AttackLogger;
import org.example.CsvOutputDirectory;
import org.example.intersection.enums.IntersectionStatus;
import org.example.spat.FullSpatContent;
import org.example.spat.FullSpatm;
import org.example.spat.IntersectionMovement;
import org.example.spat.IntersectionState;
import org.example.spat.TimeChangeDetails;
import org.example.spat.enums.TrafficLightState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class TimingSpatApp extends RsuAttackApp {

    @Override
    protected void attack() {
        AttackLogger.getInstance().init(CsvOutputDirectory.forConfiguration(
                getOs().getConfigurationPath().toPath()));
        AttackLogger.getInstance().startAttack("TIMING", getOs().getSimulationTime());

        Collection<TrafficLight> lights = getOs().getAllTrafficLights();
        TrafficLightProgramPhase phase = getOs().getCurrentPhase();
        long currentTime = getOs().getSimulationTime();

        List<IntersectionMovement> movements = new ArrayList<>();
        lights.forEach(light -> {
            movements.add(new IntersectionMovement(
                    getOs().getTrafficLightGroup().getGroupId() + "_" + light.getIndex(),
                    TrafficLightState.convertToState(light.getCurrentState()) == TrafficLightState.RED
                            ? TrafficLightState.GREEN : TrafficLightState.RED,
                    TimeChangeDetails.convert(phase, currentTime + org.eclipse.mosaic.rti.TIME.SECOND * 10)));
        });

        List<IntersectionState> stateList = new ArrayList<>();
        stateList.add(new IntersectionState(getOs().getTrafficLightGroup().getGroupId(),
                IntersectionStatus.FIXED_TIME_OPERATION, movements));
        FullSpatContent content = new FullSpatContent(currentTime, stateList);
        MessageRouting routing = getOs().getAdHocModule().createMessageRouting()
                .broadcast()
                .geographical(new GeoCircle(getOs().getPosition(), 300))
                .singlehop()
                .build();

        FullSpatm message = new FullSpatm(routing, content);
        AttackLogger.getInstance().markMessageMalicious(String.valueOf(message.getId()), "TIMING");
        getOs().getAdHocModule().sendV2xMessage(message);
    }

    @Override
    protected void attackEnd() {
        AttackLogger.getInstance().stopAttack("TIMING", getOs().getSimulationTime());
    }

    @Override
    public void onShutdown() {
        super.onShutdown();
        AttackLogger.getInstance().stopAttack("TIMING", getOs().getSimulationTime());
    }
}
