package com.example.spring_docker_test;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The fence lights are two smart sockets out by the gazebo, switched together from the single
 * dashboard card. They're outdoor plugs, so Home Assistant reports them as "unavailable" rather
 * than "off" whenever they're unplugged or out of range - the card surfaces that instead of
 * pretending they're merely switched off.
 */
@RestController
@RequestMapping("/api/fence-lights")
public class FenceLightController {

    private static final List<String> SOCKETS = List.of("switch.gazebo_socket_1", "switch.gazebo_socket_2");

    private final HomeAssistantService ha;

    public FenceLightController(HomeAssistantService ha) {
        this.ha = ha;
    }

    @GetMapping
    public FenceState state() {
        boolean on = false;
        boolean available = false;
        for (String socket : SOCKETS) {
            HomeAssistantService.EntityState state = ha.state(socket);
            if (state == null) {
                continue;
            }
            // Anything other than on/off (unavailable, unknown) means the socket isn't reachable.
            if ("on".equals(state.state())) {
                on = true;
                available = true;
            } else if ("off".equals(state.state())) {
                available = true;
            }
        }
        return new FenceState(on, available);
    }

    @PostMapping
    public ResponseEntity<Void> set(@RequestBody StateRequest req) {
        String service = req.on() ? "turn_on" : "turn_off";
        SOCKETS.forEach(socket -> ha.callService("switch", service, socket, Map.of()));
        return ResponseEntity.ok().build();
    }

    public record FenceState(boolean on, boolean available) {
    }

    public record StateRequest(boolean on) {
    }
}
