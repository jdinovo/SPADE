package org.example.attacks;

import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.CamBuilder;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.ReceivedAcknowledgement;
import org.eclipse.mosaic.fed.application.app.AbstractApplication;
import org.eclipse.mosaic.fed.application.app.api.CommunicationApplication;
import org.eclipse.mosaic.fed.application.app.api.os.RoadSideUnitOperatingSystem;
import org.eclipse.mosaic.interactions.communication.V2xMessageTransmission;
import org.eclipse.mosaic.lib.util.scheduling.Event;
import org.example.AttackLogger;
import org.example.CsvOutputDirectory;

public abstract class StandaloneRsuAttackApp extends AbstractApplication<RoadSideUnitOperatingSystem>
        implements CommunicationApplication {

    private static final long ATTACK_INTERVAL_NS = org.eclipse.mosaic.rti.TIME.SECOND;
    private static final int BLOCK_SIZE = 5;

    private RsuAttackApp.Scheduler scheduler;
    private boolean attackInProgress;

    protected abstract void attack();

    protected abstract void attackEnd();

    @Override
    public void onStartup() {
        getOs().getAdHocModule().enable();
        AttackLogger.getInstance().init(CsvOutputDirectory.forConfiguration(
                getOs().getConfigurationPath().toPath()));
        scheduler = new RsuAttackApp.Scheduler(this, ATTACK_INTERVAL_NS, BLOCK_SIZE,
                -1, -1, this::onScheduledTick, getRandom());
        scheduler.start();
    }

    private void onScheduledTick(boolean isAttack) {
        if (isAttack) {
            attackInProgress = true;
            attack();
        } else if (attackInProgress) {
            attackEnd();
            attackInProgress = false;
        }
    }

    @Override
    public void processEvent(Event event) {
        if (event.getResource() == scheduler) {
            scheduler.handleEvent(event);
        }
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
    public void onShutdown() {
        if (scheduler != null) {
            scheduler.stop();
        }
        if (attackInProgress) {
            attackEnd();
            attackInProgress = false;
        }
    }
}
