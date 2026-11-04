package org.example;

import org.eclipse.mosaic.lib.math.Vector3d;
import org.eclipse.mosaic.lib.objects.ToDataOutput;
import org.example.spat.enums.TrafficLightState;

import java.io.DataOutput;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;

public class PerceivedTrafficLight implements ToDataOutput, Serializable {
    @Serial
    private static final long serialVersionUID = 199991L;

    private long time;
    private String trafficLightId;
    private double posX;
    private double posY;
    private TrafficLightState signalState;
    private String incomingLane;
    private double distance;
    private double angleOffCenter;
    private int[] oneHotState; // [RED, YELLOW, GREEN]
    private double cameraConf;
    private double curVisScore;
    private double confVisWeighted;
    private Vector3d position;

    public PerceivedTrafficLight(long time, String trafficLightId, double posX, double posY,
                                 TrafficLightState signalState, String incomingLane, double distance,
                                 double angleOffCenter, int[] oneHotState, double cameraConf,
                                 double curVisScore, double confVisWeighted, Vector3d position) {
        this.time = time;
        this.trafficLightId = trafficLightId;
        this.posX = posX;
        this.posY = posY;
        this.signalState = signalState;
        this.incomingLane = incomingLane;
        this.distance = distance;
        this.angleOffCenter = angleOffCenter;
        this.oneHotState = oneHotState;
        this.cameraConf = cameraConf;
        this.curVisScore = curVisScore;
        this.confVisWeighted = confVisWeighted;
        this.position = position;
    }

    public PerceivedTrafficLight clone() {
        return new PerceivedTrafficLight(this.time, this.trafficLightId, this.posX, this.posY, this.signalState,
                this.incomingLane, this.distance, this.angleOffCenter, this.oneHotState, this.cameraConf, this.curVisScore,
                this.confVisWeighted, new Vector3d(this.position));
    }

    public long getTime() {
        return time;
    }

    public String getTrafficLightId() {
        return trafficLightId;
    }

    public double getPosX() {
        return posX;
    }

    public double getPosY() {
        return posY;
    }

    public TrafficLightState getSignalState() {
        return signalState;
    }

    public String getIncomingLane() {
        return incomingLane;
    }

    public double getDistance() {
        return distance;
    }

    public double getAngleOffCenter() {
        return angleOffCenter;
    }

    public int[] getOneHotState() {
        return oneHotState;
    }

    public double getCameraConf() {
        return cameraConf;
    }

    public double getCurVisScore() {
        return curVisScore;
    }

    public double getConfVisWeighted() {
        return confVisWeighted;
    }

    public Vector3d getPosition() {
        return position;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public void setTrafficLightId(String trafficLightId) {
        this.trafficLightId = trafficLightId;
    }

    public void setPosX(double posX) {
        this.posX = posX;
    }

    public void setPosY(double posY) {
        this.posY = posY;
    }

    public void setSignalState(TrafficLightState signalState) {
        this.signalState = signalState;
    }

    public void setIncomingLane(String incomingLane) {
        this.incomingLane = incomingLane;
    }

    public void setDistance(double distance) {
        this.distance = distance;
    }

    public void setAngleOffCenter(double angleOffCenter) {
        this.angleOffCenter = angleOffCenter;
    }

    public void setOneHotState(int[] oneHotState) {
        this.oneHotState = oneHotState;
    }

    public void setCameraConf(double cameraConf) {
        this.cameraConf = cameraConf;
    }

    public void setCurVisScore(double curVisScore) {
        this.curVisScore = curVisScore;
    }

    public void setConfVisWeighted(double confVisWeighted) {
        this.confVisWeighted = confVisWeighted;
    }

    public void setPosition(Vector3d position) {
        this.position = position;
    }

    @Override
    public void toDataOutput(DataOutput out) throws IOException {
        out.writeLong(time);
        out.writeUTF(trafficLightId);
        out.writeDouble(posX);
        out.writeDouble(posY);
        out.writeInt(signalState.ordinal());
        out.writeUTF(incomingLane);
        out.writeDouble(distance);
        out.writeDouble(angleOffCenter);
        out.writeInt(oneHotState[0]);
        out.writeInt(oneHotState[1]);
        out.writeInt(oneHotState[2]);
        out.writeDouble(cameraConf);
        out.writeDouble(curVisScore);
        out.writeDouble(confVisWeighted);

    }

    @Override
    public String toString() {
        return time +
                "," + trafficLightId +
                "," + posX +
                "," + posY +
                "," + signalState +
                "," + incomingLane +
                "," + distance +
                "," + angleOffCenter +
                "," + oneHotState[0] + ":" + oneHotState[1] + ":" + oneHotState[2]  +
                "," + cameraConf +
                "," + curVisScore +
                "," + confVisWeighted +
                "," + position.toGeo().getLongitude() + ":" + position.toGeo().getLatitude();
    }
}