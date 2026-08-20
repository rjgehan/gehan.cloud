package com.example.spring_docker_test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SpringDockerTestApplication {

    private static final Logger log = LoggerFactory.getLogger(SpringDockerTestApplication.class);

    public static void main(String[] args) {
        loadDotEnvIfPresent();
        SpringApplication.run(SpringDockerTestApplication.class, args);
    }

    /**
     * For local `mvnw spring-boot:run` / `java -jar` convenience only - Docker never sees a .env
     * file (it isn't copied into the image), so this is a no-op in every deployed environment.
     * Never overrides a variable that's already set in the real environment or as a -D system
     * property, so it can't shadow anything Docker/Portainer's env_file already provides.
     */
    private static void loadDotEnvIfPresent() {
        Path envFile = Path.of(".env");
        if (!Files.isRegularFile(envFile)) {
            return;
        }
        int loaded = 0;
        try {
            List<String> lines = Files.readAllLines(envFile);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                                || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                if (System.getenv(key) == null && System.getProperty(key) == null) {
                    System.setProperty(key, value);
                    loaded++;
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read .env: {}", e.toString());
            return;
        }
        log.info("Loaded {} variable(s) from .env for local dev", loaded);
    }

    @Bean
    ApplicationListener<WebServerInitializedEvent> localUrlLogger() {
        return event -> log.info("Dashboard ready: http://localhost:{}", event.getWebServer().getPort());
    }
}
