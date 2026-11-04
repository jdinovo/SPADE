package org.example;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CsvOutputDirectory {

    private CsvOutputDirectory() {
    }

    public static Path forConfiguration(Path configurationPath) {
        long seed;
        Path scenarioConfigPath = configurationPath.resolve("..").normalize().resolve("scenario_config.json");
        try (var configReader = Files.newBufferedReader(scenarioConfigPath)) {
            seed = JsonParser.parseReader(configReader).getAsJsonObject()
                    .getAsJsonObject("simulation").get("randomSeed").getAsLong();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read scenario random seed from " + scenarioConfigPath, e);
        }

        Path applicationDirectory = configurationPath.toAbsolutePath().normalize();
        Path outputDirectory = applicationDirectory.resolve("logs_" + seed).normalize();
        if (!outputDirectory.startsWith(applicationDirectory)) {
            throw new IllegalStateException("CSV output directory must remain inside " + applicationDirectory);
        }
        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create CSV output directory " + outputDirectory, e);
        }
        return outputDirectory;
    }
}
