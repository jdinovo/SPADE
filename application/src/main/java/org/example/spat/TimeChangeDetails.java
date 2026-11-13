package org.example.spat;

import org.eclipse.mosaic.lib.objects.ToDataOutput;
import org.eclipse.mosaic.lib.objects.trafficlight.TrafficLightProgramPhase;
import org.example.spat.enums.TrafficLightState;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class TimeChangeDetails implements ToDataOutput {
    private final Long startTime;    // ns since simulation start; null if not applicable
    private final long minEndTime;   // ns since simulation start
    private final long maxEndTime;   // ns since simulation start
    private final long likelyTime;   // ns since simulation start
    private final int confidence; // 0-15
    private final Long nextTime;     // ns since simulation start; null if not computed

    public TimeChangeDetails(long startTime, long minEndTime, long maxEndTime, long likelyTime, int confidence, Long nextTime) {
        this.startTime = startTime;
        this.minEndTime = minEndTime;
        this.maxEndTime = maxEndTime;
        this.likelyTime = likelyTime;
        this.confidence = confidence;
        this.nextTime = nextTime;
    }

    public static TimeChangeDetails convert(TrafficLightProgramPhase phase, long currentSimTimeNs) {
        long remainingNs = phase.getRemainingDuration();
        long configuredNs = phase.getConfiguredDuration();
        long elapsedInPhaseNs = configuredNs - remainingNs;

        long phaseStartSimTimeNs = currentSimTimeNs - elapsedInPhaseNs;
        long phaseEndSimTimeNs = currentSimTimeNs + remainingNs;

        // Fixed-time signal: min/max/likely all converge on the same known end point.
        // Confidence is 15 which means deterministic fixed-time plan,
        // nextTime will populate via getCurrentProgram() look-ahead if needed

        return new TimeChangeDetails(phaseStartSimTimeNs, phaseEndSimTimeNs, phaseEndSimTimeNs, phaseEndSimTimeNs, 15, null);
    }

    @Override
    public void toDataOutput(DataOutput out) throws IOException {
        out.writeLong(startTime);
        out.writeLong(minEndTime);
        out.writeLong(maxEndTime);
        out.writeLong(likelyTime);
        out.writeInt(confidence);
        out.writeLong(nextTime);

    }

    public TimeChangeDetails(DataInput in) throws IOException {
        this(in.readLong(), in.readLong(), in.readLong(), in.readLong(), in.readInt(), in.readLong());
    }

    public Long getStartTime() {
        return startTime;
    }

    public long getMinEndTime() {
        return minEndTime;
    }

    public long getMaxEndTime() {
        return maxEndTime;
    }

    public long getLikelyTime() {
        return likelyTime;
    }

    public int getConfidence() {
        return confidence;
    }

    public Long getNextTime() {
        return nextTime;
    }
}
