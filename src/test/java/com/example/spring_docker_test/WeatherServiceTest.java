package com.example.spring_docker_test;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * The home page's chart went blank several times a day: a failed forecast fetch was cached over
 * the last good snapshot, and served for the whole TTL. These pin the recovery behaviour.
 */
class WeatherServiceTest {

    // Nothing listens here, so the forecast call fails fast instead of waiting on a network.
    private static final String UNREACHABLE = "http://127.0.0.1:1";

    private static void set(WeatherService service, String field, Object value) throws Exception {
        Field f = WeatherService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }

    private static WeatherService serviceWithBrokenForecast() throws Exception {
        WeatherService service = new WeatherService();
        set(service, "forecastClient", RestClient.create(UNREACHABLE));
        set(service, "marineClient", RestClient.create(UNREACHABLE));
        set(service, "noaaClient", RestClient.create(UNREACHABLE));
        set(service, "airQualityClient", RestClient.create(UNREACHABLE));
        return service;
    }

    private static WeatherService.WeatherSnapshot goodSnapshot() {
        return new WeatherService.WeatherSnapshot(
                null,
                List.of(new WeatherService.HourPoint("12 PM", 74, "sun", 10)),
                List.of(),
                null, List.of(), 0, false);
    }

    @Test
    void keepsLastGoodSnapshotWhenTheForecastFetchFails() throws Exception {
        WeatherService service = serviceWithBrokenForecast();
        set(service, "cached", goodSnapshot());
        set(service, "cachedAt", Instant.EPOCH); // force the cache to look expired

        WeatherService.WeatherSnapshot result = service.snapshot();

        // The whole point: a failed fetch must not blank the chart the dashboard already drew.
        assertThat(result.hourly()).hasSize(1);
        assertThat(result.hourly().get(0).time()).isEqualTo("12 PM");
    }

    @Test
    void retriesSoonAfterAFailureRatherThanWaitingOutTheFullTtl() throws Exception {
        WeatherService service = serviceWithBrokenForecast();
        set(service, "cached", goodSnapshot());
        set(service, "cachedAt", Instant.EPOCH);

        service.snapshot();

        Field cachedAt = WeatherService.class.getDeclaredField("cachedAt");
        cachedAt.setAccessible(true);
        long staleness = Instant.now().getEpochSecond() - ((Instant) cachedAt.get(service)).getEpochSecond();
        // Left looking ~9 minutes old against a 10 minute TTL, so the next poll retries.
        assertThat(staleness).isBetween(530L, 545L);
    }

    @Test
    void servesTheEmptySnapshotWhenThereIsNoGoodOneToFallBackOn() throws Exception {
        WeatherService service = serviceWithBrokenForecast();

        WeatherService.WeatherSnapshot result = service.snapshot();

        assertThat(result).isNotNull();
        assertThat(result.hourly()).isEmpty();
    }
}
