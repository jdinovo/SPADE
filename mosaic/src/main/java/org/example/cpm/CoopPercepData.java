package org.example.cpm;

import org.eclipse.mosaic.lib.objects.ToDataOutput;
import org.example.PerceivedTrafficLight;

import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class CoopPercepData implements ToDataOutput, Serializable {

    private long timeStamp;
    private String sourceVehicle;
    private double latitude;
    private double longitude;
    private List<PerceivedTrafficLight> perceivedObjects;

    public CoopPercepData(long timeStamp, String sourceVehicle, double latitude, double longitude, List<PerceivedTrafficLight> perceivedObjects) {
        this.timeStamp = timeStamp;
        this.sourceVehicle = sourceVehicle;
        this.latitude = latitude;
        this.longitude = longitude;
        this.perceivedObjects = perceivedObjects;
    }

    public CoopPercepData(CoopPercepData coopPercepData) {
        this.timeStamp = coopPercepData.getTimeStamp();
        this.sourceVehicle = coopPercepData.getSourceVehicle();
        this.latitude = coopPercepData.getLatitude();
        this.longitude = coopPercepData.getLongitude();
        this.perceivedObjects = new ArrayList<>();
        this.perceivedObjects.addAll(coopPercepData.getPerceivedObjects());
    }

    public long getTimeStamp() {
        return timeStamp;
    }

    public List<PerceivedTrafficLight> getPerceivedObjects() {
        return perceivedObjects;
    }

    public String getSourceVehicle() {
        return sourceVehicle;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setTimeStamp(long timeStamp) {
        this.timeStamp = timeStamp;
    }

    public void setSourceVehicle(String sourceVehicle) {
        this.sourceVehicle = sourceVehicle;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public void setPerceivedObjects(List<PerceivedTrafficLight> perceivedObjects) {
        this.perceivedObjects = perceivedObjects;
    }

    @Override
    public void toDataOutput(DataOutput out) throws IOException {
        out.writeLong(timeStamp);

        for (PerceivedTrafficLight tl : perceivedObjects) {
            tl.toDataOutput(out);
        }
    }
}
