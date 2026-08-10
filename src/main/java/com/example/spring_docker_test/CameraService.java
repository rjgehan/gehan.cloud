package com.example.spring_docker_test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Pulls a single JPEG frame from each configured RTSP camera via ffmpeg, on demand, and caches it
 * briefly. This is a "refreshing snapshot" approach rather than true streaming video: much lighter
 * on the host (no continuously running transcode processes) and simple enough to not need any
 * media-server infrastructure, at the cost of the feed only updating every couple of seconds.
 */
@Service
public class CameraService {

    private static final Logger log = LoggerFactory.getLogger(CameraService.class);
    private static final long SNAPSHOT_CACHE_TTL_MS = 2000;
    private static final long STALE_AFTER_MS = 20_000;
    // Cameras vary widely in keyframe interval; ffmpeg can't produce a frame until the next
    // keyframe arrives, so this needs enough headroom for cameras with a multi-second GOP.
    private static final long FFMPEG_TIMEOUT_SECONDS = 15;

    private final List<Camera> cameras;
    private final ConcurrentHashMap<String, CachedSnapshot> cache = new ConcurrentHashMap<>();

    public CameraService(@Value("${app.cameras:}") String camerasConfig) {
        this.cameras = parseCameras(camerasConfig);
    }

    public List<Camera> cameras() {
        return cameras;
    }

    public byte[] snapshot(String id) {
        Camera camera = cameras.stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
        if (camera == null) {
            return null;
        }
        CachedSnapshot cached = cache.get(id);
        if (cached != null && Instant.now().toEpochMilli() - cached.fetchedAtMs() < SNAPSHOT_CACHE_TTL_MS) {
            return cached.jpegBytes();
        }
        byte[] fresh = captureFrame(camera.rtspUrl());
        if (fresh != null) {
            cache.put(id, new CachedSnapshot(fresh, Instant.now().toEpochMilli()));
            return fresh;
        }
        // ffmpeg failed this round; ride out a brief blip with the last-known-good frame, but
        // don't keep serving it forever — past STALE_AFTER_MS the camera should read as offline.
        if (cached != null && Instant.now().toEpochMilli() - cached.fetchedAtMs() < STALE_AFTER_MS) {
            return cached.jpegBytes();
        }
        return null;
    }

    private byte[] captureFrame(String rtspUrl) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y",
                    "-rtsp_transport", "tcp",
                    "-timeout", "5000000",
                    "-i", rtspUrl,
                    "-frames:v", "1",
                    "-q:v", "4",
                    "-f", "image2", "-");
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = pb.start();

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Process finalProcess = process;
            Thread reader = new Thread(() -> {
                try (InputStream in = finalProcess.getInputStream()) {
                    in.transferTo(buffer);
                } catch (IOException ignored) {
                    // process was likely destroyed; buffer holds whatever was captured so far
                }
            });
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("ffmpeg snapshot timed out for {}", redact(rtspUrl));
                return null;
            }
            reader.join(2000);

            if (process.exitValue() != 0 || buffer.size() == 0) {
                log.warn("ffmpeg snapshot failed for {} (exit {}, {} bytes)", redact(rtspUrl), process.exitValue(), buffer.size());
                return null;
            }
            return buffer.toByteArray();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (process != null) {
                process.destroyForcibly();
            }
            log.warn("Failed to capture snapshot for {}: {}", redact(rtspUrl), e.toString());
            return null;
        }
    }

    private static String redact(String rtspUrl) {
        return rtspUrl.replaceAll("://[^@/]+@", "://***@");
    }

    private static List<Camera> parseCameras(String config) {
        List<Camera> result = new ArrayList<>();
        if (config == null || config.isBlank()) {
            return result;
        }
        for (String entry : config.split(",")) {
            int sep = entry.indexOf('|');
            if (sep < 0) {
                continue;
            }
            String name = entry.substring(0, sep).trim();
            String url = entry.substring(sep + 1).trim();
            if (name.isEmpty() || url.isEmpty()) {
                continue;
            }
            result.add(new Camera(slug(name), name, url));
        }
        return result;
    }

    private static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "camera" : s;
    }

    public record Camera(String id, String name, String rtspUrl) {
    }

    private record CachedSnapshot(byte[] jpegBytes, long fetchedAtMs) {
    }
}
