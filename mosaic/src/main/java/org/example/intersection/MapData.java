package org.example.intersection;

import org.eclipse.mosaic.lib.objects.ToDataOutput;

import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import java.util.List;

public class MapData implements ToDataOutput, Serializable {

    private final long timeStamp;
    private final List<IntersectionGeometry> intersectionGeometryList;

    public MapData(long timeStamp, List<IntersectionGeometry> intersectionGeometryList) {
        this.timeStamp = timeStamp;
        this.intersectionGeometryList = intersectionGeometryList;
    }

    public long getTimeStamp() {
        return timeStamp;
    }

    public List<IntersectionGeometry> getIntersectionGeometryList() {
        return intersectionGeometryList;
    }

    @Override
    public void toDataOutput(DataOutput dataOutput) throws IOException {
        dataOutput.writeLong(timeStamp);

        dataOutput.writeInt(intersectionGeometryList.size());

        for (IntersectionGeometry m : intersectionGeometryList) {
            m.toDataOutput(dataOutput);
        }
    }
}
