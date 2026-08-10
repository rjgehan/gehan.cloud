package com.example.spring_docker_test;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CameraController {

    private final CameraService cameraService;

    public CameraController(CameraService cameraService) {
        this.cameraService = cameraService;
    }

    @GetMapping("/api/cameras")
    public List<CameraSummary> cameras() {
        return cameraService.cameras().stream()
                .map(c -> new CameraSummary(c.id(), c.name()))
                .toList();
    }

    @GetMapping("/api/cameras/{id}/snapshot")
    public ResponseEntity<byte[]> snapshot(@PathVariable String id) {
        byte[] jpeg = cameraService.snapshot(id);
        if (jpeg == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore())
                .body(jpeg);
    }

    public record CameraSummary(String id, String name) {
    }
}
