package org.example.attacks;

import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.ReceivedV2xMessage;
import org.eclipse.mosaic.lib.geo.GeoCircle;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.example.AttackLogger;
import org.example.spat.FullSpatContent;
import org.example.spat.FullSpatm;
import org.example.spat.IntersectionMovement;
import org.example.spat.IntersectionState;
import org.example.spat.enums.TrafficLightState;

import java.util.ArrayList;
import java.util.List;

public class ImpersonationSpatApp extends StandaloneRsuAttackApp {

    private FullSpatContent template;

    @Override
    public void onMessageReceived(ReceivedV2xMessage receivedV2xMessage) {
        if (template == null && receivedV2xMessage.getMessage() instanceof FullSpatm spatMessage) {
            template = spatMessage.getContent();
        }
    }

    @Override
    protected void attack() {
        if (template == null) {
            return;
        }

        AttackLogger.getInstance().startAttack("IMPERSONATION", getOs().getSimulationTime());

        List<IntersectionState> stateList = new ArrayList<>();
        for (IntersectionState state : template.getIntersectionStateList()) {
            List<IntersectionMovement> movements = new ArrayList<>();
            for (IntersectionMovement movement : state.getMovementList()) {
                TrafficLightState falseState = movement.getState() == TrafficLightState.RED
                        ? TrafficLightState.GREEN : TrafficLightState.RED;
                movements.add(new IntersectionMovement(
                        movement.getSignalId(), falseState, movement.getTime()));
            }
            stateList.add(new IntersectionState(state.getId(), state.getStatus(), movements));
        }

        FullSpatContent content = new FullSpatContent(getOs().getSimulationTime(), stateList);
        MessageRouting routing = getOs().getAdHocModule().createMessageRouting()
                .broadcast()
                .geographical(new GeoCircle(getOs().getPosition(), 300))
                .singlehop()
                .build();
        FullSpatm message = new FullSpatm(routing, content);
        AttackLogger.getInstance().markMessageMalicious(String.valueOf(message.getId()), "IMPERSONATION");
        getOs().getAdHocModule().sendV2xMessage(message);
    }

    @Override
    protected void attackEnd() {
        AttackLogger.getInstance().stopAttack("IMPERSONATION", getOs().getSimulationTime());
    }

    @Override
    public void onShutdown() {
        super.onShutdown();
        AttackLogger.getInstance().stopAttack("IMPERSONATION", getOs().getSimulationTime());
    }
}
