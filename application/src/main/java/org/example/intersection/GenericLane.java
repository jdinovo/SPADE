package org.example.intersection;

import org.eclipse.mosaic.lib.objects.ToDataOutput;

import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class GenericLane implements ToDataOutput {
    private final String laneId;
//    private final String name;
    private final LaneAttributes laneAttributes;
    //private List<GeoPoint> nodeList;
    private final List<LaneConnection> connectsTo;

    public String getLaneId() {
        return laneId;
    }

//    public String getName() {
//        return name;
//    }

    public List<LaneConnection> getConnectsTo() {
        return connectsTo;
    }

    public LaneAttributes getLaneAttributes() {
        return laneAttributes;
    }

    public GenericLane(GenericLane g) {
        this.laneId = g.laneId;
        this.laneAttributes = new LaneAttributes(g.laneAttributes.getDirectionalUse(), g.getLaneAttributes().getSharedWith(), g.getLaneAttributes().getLaneType());
        this.connectsTo = new ArrayList<>();
        this.connectsTo.addAll(g.getConnectsTo());
    }
    public GenericLane(String laneId, LaneAttributes laneAttributes, List<LaneConnection> connectsTo) {
        this.laneId = laneId;
//        this.name = name;
        this.laneAttributes = laneAttributes;
        this.connectsTo = connectsTo;
    }

    @Override
    public void toDataOutput(DataOutput dataOutput) throws IOException {
        dataOutput.writeUTF(laneId);
//        dataOutput.writeUTF(name);
        laneAttributes.toDataOutput(dataOutput);
        for (LaneConnection l: connectsTo) {
            l.toDataOutput(dataOutput);
        }
    }
}
