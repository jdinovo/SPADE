package org.example.spat;

import org.eclipse.mosaic.lib.objects.ToDataOutput;

import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import java.util.List;

// Custom Content implementing MessagePayload
public class FullSpatContent implements ToDataOutput, Serializable {
    private final long time;
    private final List<IntersectionState> intersectionStateList; // List of individual signal states
    public FullSpatContent(long time, List<IntersectionState> movements) {
        this.time = time;
        this.intersectionStateList = movements;
    }

    public List<IntersectionState> getIntersectionStateList() {
        return intersectionStateList;
    }

    public long getTime() {
        return time;
    }

    @Override
    public void toDataOutput(DataOutput dataOutput) throws IOException {
        dataOutput.writeLong(time);

        dataOutput.writeInt(intersectionStateList.size());

        for (IntersectionState m : intersectionStateList) {
            m.toDataOutput(dataOutput);
        }
    }
}

