package org.example.intersection;

import org.eclipse.mosaic.lib.objects.ToDataOutput;

import java.io.DataOutput;
import java.io.IOException;

public class LaneConnection implements ToDataOutput {
    private final String connectingLane;
    private final String signalId;

    /**
     *
     * @param connectingLane
     * @param signalId this is signalgroupId_lightId since it is meant to identify the individual signal for linking to spat
     */
    public LaneConnection(String connectingLane, String signalId) {
        this.connectingLane = connectingLane;
        this.signalId = signalId;
    }

    public String getConnectingLane() {
        return connectingLane;
    }

    public String getSignalId() {
        return signalId;
    }

    @Override
    public String toString() {
        return signalId + ":" + connectingLane;
    }

    @Override
    public void toDataOutput(DataOutput dataOutput) throws IOException {
        dataOutput.writeUTF(connectingLane);
        dataOutput.writeUTF(signalId);
    }
}
