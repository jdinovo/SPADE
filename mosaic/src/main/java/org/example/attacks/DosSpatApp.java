package org.example.attacks;

import org.eclipse.mosaic.lib.geo.GeoCircle;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.example.AttackLogger;
import org.example.CsvOutputDirectory;
import org.example.spat.FullSpatm;

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
        FullSpatm attackMessage = new FullSpatm(routing, createSpatContent());
        AttackLogger.getInstance().markMessageMalicious(String.valueOf(attackMessage.getId()), "DOS");
        getOs().getAdHocModule().sendV2xMessage(attackMessage);
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
