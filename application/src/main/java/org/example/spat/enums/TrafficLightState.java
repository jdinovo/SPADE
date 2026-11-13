package org.example.spat.enums;

import java.io.Serializable;

public enum TrafficLightState implements Serializable {
    GREEN,
    YELLOW,
    RED,
    OFF;

    public static TrafficLightState convertToState(org.eclipse.mosaic.lib.objects.trafficlight.TrafficLightState s) {
        if (s.isGreen()) return TrafficLightState.GREEN;
        if (s.isRed()) return TrafficLightState.RED;
        if (s.isYellow() || s.isRedYellow()) return TrafficLightState.YELLOW;
        return TrafficLightState.OFF;
    }

}
