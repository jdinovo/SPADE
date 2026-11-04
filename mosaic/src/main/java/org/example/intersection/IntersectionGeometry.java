package org.example.intersection;

import org.eclipse.mosaic.lib.geo.GeoPoint;
import org.eclipse.mosaic.lib.objects.ToDataOutput;

import java.io.DataOutput;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;

public class IntersectionGeometry implements ToDataOutput {
    private String id;
    private GeoPoint refPoint;
    private HashMap<String, GenericLane> laneSet;

    public IntersectionGeometry() {

    }

    public IntersectionGeometry(String id, GeoPoint refPoint, HashMap<String, GenericLane> laneSet) {
        this.id = id;
        this.refPoint = refPoint;
        this.laneSet = laneSet;
    }

    @Override
    public void toDataOutput(DataOutput dataOutput) throws IOException {
        dataOutput.writeUTF(id);
        dataOutput.writeUTF(refPoint.toString());
        dataOutput.writeInt(laneSet.size());

        for (GenericLane l: laneSet.values()) {
            l.toDataOutput(dataOutput);
        }
    }

    public String getId() {
        return id;
    }

    public GeoPoint getRefPoint() {
        return refPoint;
    }

    public HashMap<String, GenericLane> getLaneSet() {
        return laneSet;
    }
}
