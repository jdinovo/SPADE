package org.example.spat;

import org.eclipse.mosaic.lib.objects.ToDataOutput;
import org.example.intersection.enums.IntersectionStatus;

import java.io.DataOutput;
import java.io.IOException;
import java.util.List;

public class IntersectionState implements ToDataOutput {
    private String id;
    private IntersectionStatus status;
    private List<IntersectionMovement> movementList;

    public IntersectionState(String id, IntersectionStatus status, List<IntersectionMovement> movementList) {
        this.id = id;
        this.status = status;
        this.movementList = movementList;
    }

    public String getId() {
        return id;
    }

    public IntersectionStatus getStatus() {
        return status;
    }

    public List<IntersectionMovement> getMovementList() {
        return movementList;
    }

    @Override
    public void toDataOutput(DataOutput dataOutput) throws IOException {

        dataOutput.writeUTF(id);
        dataOutput.writeInt(status.ordinal());

        for (IntersectionMovement m : movementList) {
            m.toDataOutput(dataOutput);
        }
    }
}
