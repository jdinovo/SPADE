package org.example.attacks;

import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.ReceivedV2xMessage;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.example.AttackLogger;
import org.example.PerceivedTrafficLight;
import org.example.cpm.CoopPercepData;
import org.example.cpm.CoopPercepMessage;
import org.example.spat.enums.TrafficLightState;

import java.util.ArrayList;
import java.util.List;

public class SybilSpatApp extends StandaloneRsuAttackApp {

    private CoopPercepMessage templateCoopMessage;

    @Override
    public void onMessageReceived(ReceivedV2xMessage receivedV2xMessage) {
        if (templateCoopMessage == null
                && receivedV2xMessage.getMessage() instanceof CoopPercepMessage cpm) {
            templateCoopMessage = cpm;
        }
    }

    @Override
    protected void attack() {
        if (templateCoopMessage == null) {
            return;
        }

        long now = getOs().getSimulationTime();
        AttackLogger.getInstance().startAttack("SYBIL", now);
        String fakeId = "veh_" + now + "_" + getRandom().nextInt(20, 999);

        CoopPercepData fakeContent = new CoopPercepData(templateCoopMessage.getContent());
        fakeContent.setTimeStamp(now);
        fakeContent.setSourceVehicle(fakeId);
        List<PerceivedTrafficLight> fakePerceptions = new ArrayList<>();
        for (PerceivedTrafficLight perception : fakeContent.getPerceivedObjects()) {
            PerceivedTrafficLight fakePerception = perception.clone();
            switch (perception.getSignalState()) {
                case RED -> {
                    fakePerception.setSignalState(TrafficLightState.GREEN);
                    fakePerception.setOneHotState(new int[]{0, 0, 1});
                }
                case GREEN -> {
                    fakePerception.setSignalState(TrafficLightState.RED);
                    fakePerception.setOneHotState(new int[]{1, 0, 0});
                }
                default -> {
                }
            }
            fakePerceptions.add(fakePerception);
        }
        fakeContent.setPerceivedObjects(fakePerceptions);

        MessageRouting routing = getOs().getAdHocModule().createMessageRouting()
                .broadcast()
                .topological()
                .singlehop()
                .build();
        CoopPercepMessage fakeMessage = new CoopPercepMessage(routing, fakeContent);
        AttackLogger.getInstance().markMessageMalicious(String.valueOf(fakeMessage.getId()), "SYBIL");
        getOs().getAdHocModule().sendV2xMessage(fakeMessage);
    }

    @Override
    protected void attackEnd() {
        AttackLogger.getInstance().stopAttack("SYBIL", getOs().getSimulationTime());
    }
}
