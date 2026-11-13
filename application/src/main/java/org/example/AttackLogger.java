package org.example;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AttackLogger {

    private static final AttackLogger INSTANCE = new AttackLogger();

    private BufferedWriter writer;
    private final Set<String> activeAttacks = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Map<String, String> maliciousMessages = new ConcurrentHashMap<>();

//    private AttackLogger() {
//        try {
//            writer = new BufferedWriter(new FileWriter(CSV_PATH));
//            writer.write("simTimeNs,activeAttacks\n");
//            writer.flush();
//        } catch (IOException e) {
//            throw new RuntimeException("Could not open attack log CSV at " + CSV_PATH, e);
//        }
//    }

    public synchronized void init(Path outputDirectory) {
        if (writer != null) return; // already initialized, e.g. by another unit's onStartup
        Path csvPath = outputDirectory.resolve("attack_labels.csv").normalize();
        if (!csvPath.startsWith(outputDirectory)) {
            throw new IllegalArgumentException("Attack CSV path must remain inside " + outputDirectory);
        }
        try {
            writer = new BufferedWriter(new FileWriter(csvPath.toFile()));
            writer.write("simTimeNs,activeAttacks\n");
            logTransition(0);
        } catch (IOException e) {
            throw new RuntimeException("Could not open attack log CSV at " + csvPath, e);
        }
    }

    public static AttackLogger getInstance() {
        return INSTANCE;
    }

    public void markMessageMalicious(String messageId, String attackType) {
        maliciousMessages.put(messageId, attackType);
    }

    public String getMessageAttack(String messageId) {
        return maliciousMessages.get(messageId);
    }

    public void startAttack(String attackType, long simTimeNs) {
        activeAttacks.add(attackType);
        logTransition(simTimeNs);
    }

    public void stopAttack(String attackType, long simTimeNs) {
        activeAttacks.remove(attackType);
        logTransition(simTimeNs);
    }

    public synchronized void tick(long simTimeNs) {
        writeRow(simTimeNs);
    }

    public synchronized void logTransition(long simTimeNs) {
        writeRow(simTimeNs);
    }

    private void writeRow(long simTimeNs) {
        String label = activeAttacks.isEmpty() ? "BENIGN" : String.join("|", activeAttacks);
        try {
            writer.write(simTimeNs + "," + label + "\n");
            writer.flush();
        } catch (IOException e) {
            throw new RuntimeException("Failed writing attack log row", e);
        }
    }

    public synchronized void close() {
        try {
            writer.close();
        } catch (IOException ignored) { }
    }
}