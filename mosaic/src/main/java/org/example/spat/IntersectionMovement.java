package org.example.spat;

import org.eclipse.mosaic.lib.objects.ToDataOutput;
import org.example.spat.enums.TrafficLightState;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

// Helper class for individual movements
public class IntersectionMovement implements ToDataOutput {
    private final String signalId;
    private final TrafficLightState state;
    private final TimeChangeDetails time;

    /**
     *
     * @param signalId this is signalgroupId_lightId since it is meant to identify the individual signal for linking to spat
     * @param state
     * @param time
     */
    public IntersectionMovement(String signalId, TrafficLightState state, TimeChangeDetails time) {
        this.signalId = signalId;
        this.state = state;
        this.time = time;
    }

    @Override
    public void toDataOutput(DataOutput out) throws IOException {
        out.writeUTF(signalId);
//        out.writeInt(getStateCode(state));
        out.writeInt(state.ordinal());

    }

    public IntersectionMovement(DataInput in) throws IOException {
        this.signalId = in.readUTF();
//        this.state = getStateFromCode(in.readInt());
        this.state = TrafficLightState.values()[getState().ordinal()];
        this.time = new TimeChangeDetails(in);
    }

//    private int getStateCode(TrafficLightState s) {
//        if (s == TrafficLightState.GREEN) return 0;
//        if (s == TrafficLightState.RED) return 1;
//        if (s == TrafficLightState.YELLOW) return 2;
//        if (s == TrafficLightState.RED_YELLOW) return 3;
//        if (s == TrafficLightState.OFF) return 4;
//        return 5;
//    }
//
//    private TrafficLightState getStateFromCode(int code) {
//        return switch (code) {
//            case 0 -> TrafficLightState.GREEN;
//            case 1 -> TrafficLightState.RED;
//            case 2 -> TrafficLightState.YELLOW;
//            case 3 -> TrafficLightState.RED_YELLOW;
//            default -> TrafficLightState.OFF;
//        };
//    }

    public TrafficLightState getState() { return state; }
    public String getSignalId() { return signalId; }
    public TimeChangeDetails getTime() { return time; }
}
