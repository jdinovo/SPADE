package org.example.intersection;

import org.eclipse.mosaic.lib.objects.ToDataOutput;
import org.example.intersection.enums.LaneDirection;
import org.example.intersection.enums.LaneSharing;
import org.example.intersection.enums.LaneTypeAttribute;

import java.io.DataOutput;
import java.io.IOException;

public class LaneAttributes implements ToDataOutput {
    private LaneDirection directionalUse;
    private LaneSharing sharedWith;
    private LaneTypeAttribute laneType;

    public LaneAttributes() {
    }

    public LaneAttributes(LaneDirection directionalUse, LaneSharing sharedWith, LaneTypeAttribute laneType) {
        this.directionalUse = directionalUse;
        this.sharedWith = sharedWith;
        this.laneType = laneType;
    }

    @Override
    public void toDataOutput(DataOutput dataOutput) throws IOException {
        dataOutput.writeInt(directionalUse.ordinal());
        dataOutput.writeInt(sharedWith.ordinal());
        dataOutput.writeInt(laneType.ordinal());
    }

    public LaneDirection getDirectionalUse() {
        return directionalUse;
    }

    public LaneSharing getSharedWith() {
        return sharedWith;
    }

    public LaneTypeAttribute getLaneType() {
        return laneType;
    }

    @Override
    public String toString() {
        return getDirectionalUse() + ":" + getSharedWith() + ":" + getLaneType();
    }
}
