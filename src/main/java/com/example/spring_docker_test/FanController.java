package com.example.spring_docker_test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Maps the dashboard's 4 ceiling-fan cards onto Home Assistant fan + light entities. Fan speed is
 * 0-10 (HA uses 0-100% in steps of 10, so it's a straight x10 conversion). The two lights per fan
 * are plain brightness dimmers with no real warm/cool distinction in HA - that's just how the
 * physical fixtures are labeled, so it's a coin-flip guess which HA light is which until wired up
 * and checked by hand; each is 0-5 (HA brightness_pct in steps of 20).
 */
@RestController
@RequestMapping("/api/fans")
public class FanController {

    private static final Map<String, FanMapping> FANS = Map.of(
            "kitchen", new FanMapping("fan.kitchen_fan", "light.kitchen_fan", "light.kitchen_fan_light_2"),
            "bunkbed", new FanMapping("fan.bunkbed_fan", "light.bunkbed_fan", "light.bunkbed_fan_light_2"),
            "double", new FanMapping("fan.twin_beds_fan", "light.twin_beds_fan", "light.twin_beds_fan_light_2"),
            "living", new FanMapping("fan.living_room_fan", "light.living_room_fan", "light.living_room_fan_light_2"));

    private final HomeAssistantService ha;

    public FanController(HomeAssistantService ha) {
        this.ha = ha;
    }

    @GetMapping
    public Map<String, FanState> fans() {
        Map<String, FanState> result = new LinkedHashMap<>();
        FANS.forEach((id, mapping) -> result.put(id, readState(mapping)));
        return result;
    }

    @PostMapping("/{id}/speed")
    public ResponseEntity<Void> setSpeed(@PathVariable String id, @RequestBody ValueRequest req) {
        FanMapping mapping = FANS.get(id);
        if (mapping == null) {
            return ResponseEntity.notFound().build();
        }
        int value = clamp(req.value(), 0, 10);
        ha.callService("fan", "set_percentage", mapping.fanEntity(), Map.of("percentage", value * 10));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/warm")
    public ResponseEntity<Void> setWarm(@PathVariable String id, @RequestBody ValueRequest req) {
        return setLight(id, req, FanMapping::warmEntity);
    }

    @PostMapping("/{id}/cool")
    public ResponseEntity<Void> setCool(@PathVariable String id, @RequestBody ValueRequest req) {
        return setLight(id, req, FanMapping::coolEntity);
    }

    private ResponseEntity<Void> setLight(String id, ValueRequest req, Function<FanMapping, String> pickEntity) {
        FanMapping mapping = FANS.get(id);
        if (mapping == null) {
            return ResponseEntity.notFound().build();
        }
        int value = clamp(req.value(), 0, 5);
        String entity = pickEntity.apply(mapping);
        if (value == 0) {
            ha.callService("light", "turn_off", entity, Map.of());
        } else {
            ha.callService("light", "turn_on", entity, Map.of("brightness_pct", value * 20));
        }
        return ResponseEntity.ok().build();
    }

    private FanState readState(FanMapping mapping) {
        return new FanState(
                fanSpeed(ha.state(mapping.fanEntity())),
                lightStage(ha.state(mapping.warmEntity())),
                lightStage(ha.state(mapping.coolEntity())));
    }

    private static int fanSpeed(HomeAssistantService.EntityState state) {
        if (state == null || !"on".equals(state.state())) {
            return 0;
        }
        Object percentage = state.attributes().get("percentage");
        if (!(percentage instanceof Number n)) {
            return 0;
        }
        return clamp((int) Math.round(n.doubleValue() / 10.0), 0, 10);
    }

    private static int lightStage(HomeAssistantService.EntityState state) {
        if (state == null || !"on".equals(state.state())) {
            return 0;
        }
        Object brightness = state.attributes().get("brightness");
        if (!(brightness instanceof Number n)) {
            return 0;
        }
        double pct = n.doubleValue() / 255.0 * 100.0;
        return clamp((int) Math.round(pct / 20.0), 0, 5);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record FanMapping(String fanEntity, String warmEntity, String coolEntity) {
    }

    public record FanState(int speed, int warm, int cool) {
    }

    public record ValueRequest(int value) {
    }
}
