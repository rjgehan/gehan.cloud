package com.example.spring_docker_test;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Thin client over Home Assistant's REST API. Every call degrades to a no-op/null when HA isn't
 * configured (no HA_URL/HA_TOKEN) or unreachable, so the dashboard keeps working without it.
 */
@Service
public class HomeAssistantService {

    private static final Logger log = LoggerFactory.getLogger(HomeAssistantService.class);

    private final RestClient client;
    private final boolean configured;

    public HomeAssistantService(
            @Value("${app.homeassistant.url:}") String baseUrl,
            @Value("${app.homeassistant.token:}") String token) {
        this.configured = !baseUrl.isBlank() && !token.isBlank();
        if (!configured) {
            this.client = null;
            return;
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(5000);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + token)
                .build();
    }

    public EntityState state(String entityId) {
        if (!configured) {
            return null;
        }
        try {
            return client.get().uri("/api/states/{id}", entityId).retrieve().body(EntityState.class);
        } catch (Exception e) {
            log.warn("Failed to fetch Home Assistant state for {}: {}", entityId, e.toString());
            return null;
        }
    }

    public void callService(String domain, String service, String entityId, Map<String, Object> extraData) {
        if (!configured) {
            return;
        }
        Map<String, Object> body = new HashMap<>(extraData);
        body.put("entity_id", entityId);
        try {
            client.post().uri("/api/services/{domain}/{service}", domain, service)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Failed to call Home Assistant service {}.{} on {}: {}", domain, service, entityId, e.toString());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EntityState(
            @JsonProperty("entity_id") String entityId,
            String state,
            Map<String, Object> attributes) {
    }
}
