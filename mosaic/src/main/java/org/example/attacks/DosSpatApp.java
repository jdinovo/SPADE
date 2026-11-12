package org.example.attacks;

import org.eclipse.mosaic.lib.geo.GeoCircle;
import org.eclipse.mosaic.lib.objects.trafficlight.TrafficLight;
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
import java.util.List;

public class DosSpatApp extends RsuAttackApp {

    @Override
    protected void attack() {
        AttackLogger.getInstance().init(CsvOutputDirectory.forConfiguration(
                getOs().getConfigurationPath().toPath()));
        AttackLogger.getInstance().startAttack("DOS", getOs().getSimulationTime());

        MessageRouting routing = getOs().getAdHocModule().createMessageRouting()
                .broadcast()
                .geographical(new GeoCircle(getOs().getPosition(), 300))
                .singlehop()
                .build();
        FullSpatm attackMessage = new FullSpatm(routing, createOneFilledSpatContent());
        AttackLogger.getInstance().markMessageMalicious(String.valueOf(attackMessage.getId()), "DOS");
        getOs().getAdHocModule().sendV2xMessage(attackMessage);
    }

    private FullSpatContent createOneFilledSpatContent() {
        List<IntersectionMovement> movements = new ArrayList<>();
        for (TrafficLight light : getOs().getAllTrafficLights()) {
            movements.add(new IntersectionMovement(
                    getOs().getTrafficLightGroup().getGroupId() + "_" + light.getIndex(),
                    TrafficLightState.OFF,
                    new TimeChangeDetails(1L, 1L, 1L, 1L, 1, 1L)));
        }

        List<IntersectionState> states = new ArrayList<>();
        states.add(new IntersectionState(
                getOs().getTrafficLightGroup().getGroupId(),
                IntersectionStatus.OFF,
                movements));
        return new FullSpatContent(1L, states);
    }

    @Override
    protected void attackEnd() {
        AttackLogger.getInstance().stopAttack("DOS", getOs().getSimulationTime());
    }

    @Override
    public void onShutdown() {
        super.onShutdown();
        AttackLogger.getInstance().stopAttack("DOS", getOs().getSimulationTime());
    }
}
