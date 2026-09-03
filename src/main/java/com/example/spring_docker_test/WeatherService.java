package com.example.spring_docker_test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonProperty;

@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private static final double LAT = 40.12623;
    private static final double LON = -74.0493;
    private static final double MARINE_LAT = 40.10;
    private static final double MARINE_LON = -73.96;
    private static final String NOAA_STATION = "8532591";
    private static final ZoneId LOCAL_ZONE = ZoneId.of("America/New_York");
    private static final long CACHE_TTL_SECONDS = 600;
    private static final DateTimeFormatter NOAA_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final long RADAR_CACHE_TTL_SECONDS = 300;
    /** How soon to re-try after a failed forecast fetch, instead of waiting out the full TTL. */
    private static final long FAILED_FETCH_RETRY_SECONDS = 60;

    private final RestClient forecastClient = client("https://api.open-meteo.com");
    private final RestClient marineClient = client("https://marine-api.open-meteo.com");
    private final RestClient noaaClient = client("https://api.tidesandcurrents.noaa.gov");
    private final RestClient radarClient = client("https://api.librewxr.net");
    private final RestClient airQualityClient = client("https://air-quality-api.open-meteo.com");

    /**
     * These had no timeouts, so an upstream that hung rather than refused left snapshot() - which
     * is synchronized - holding the lock for however long the OS took to give up, with every other
     * request for the weather queued behind it. A blip should fail in seconds and be retried, not
     * stall the page.
     */
    private static RestClient client(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(8000);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    private volatile WeatherSnapshot cached;
    private volatile Instant cachedAt = Instant.EPOCH;
    private volatile String cachedRadarJson;
    private volatile Instant radarCachedAt = Instant.EPOCH;

    public synchronized WeatherSnapshot snapshot() {
        if (cached != null && Instant.now().getEpochSecond() - cachedAt.getEpochSecond() < CACHE_TTL_SECONDS) {
            return cached;
        }
        WeatherSnapshot fresh = fetch();
        if (!fresh.hourly().isEmpty()) {
            cached = fresh;
            cachedAt = Instant.now();
            return cached;
        }
        // Open-Meteo always sends an hourly block, so an empty one means the call failed. Keep
        // serving the last good snapshot rather than caching the hole: overwriting it blanked the
        // home page's chart for a full TTL every time upstream so much as blipped. Same reasoning
        // as radarFrames() below. Retry sooner than the full TTL so it recovers in a minute.
        if (cached == null) {
            cached = fresh;
        }
        cachedAt = Instant.now().minusSeconds(CACHE_TTL_SECONDS - FAILED_FETCH_RETRY_SECONDS);
        return cached;
    }

    public synchronized String radarFrames() {
        if (cachedRadarJson != null && Instant.now().getEpochSecond() - radarCachedAt.getEpochSecond() < RADAR_CACHE_TTL_SECONDS) {
            return cachedRadarJson;
        }
        try {
            String json = radarClient.get()
                    .uri("/public/weather-maps.json")
                    .retrieve()
                    .body(String.class);
            if (json != null) {
                cachedRadarJson = json;
                radarCachedAt = Instant.now();
                log.info("Refreshed radar frames from api.librewxr.net ({} bytes)", json.length());
            }
        } catch (RuntimeException e) {
            log.warn("Failed to fetch radar frames from api.librewxr.net: {}", e.toString(), e);
            // serve the last-known-good frames (if any) rather than fail the whole radar card
        }
        return cachedRadarJson == null ? "{}" : cachedRadarJson;
    }

    private WeatherSnapshot fetch() {
        OpenMeteoResponse forecast = fetchForecast();
        CurrentConditions current = forecast == null ? null : toCurrent(forecast);
        List<HourPoint> hourly = forecast == null ? List.of() : toHourly(forecast);
        List<DayPoint> daily = forecast == null ? List.of() : toDaily(forecast);
        AirQualityResult air = fetchAirQuality();
        return new WeatherSnapshot(current, hourly, daily, fetchMarine(), fetchTides(), air.aqi(), air.alert());
    }

    // Open-Meteo's `current.us_aqi` uses whichever of the 1-hour or 8-hour rolling ozone average is
    // worse, which spikes on sunny afternoons well above what EPA's official (8-hour-only) AQI - and
    // therefore most phone weather apps - would show for the same moment. To avoid alerting on a
    // single noisy hourly reading, the alert only fires when the last 2 hourly readings both clear
    // the threshold, not just the live snapshot.
    private static final int AQI_ALERT_THRESHOLD = 150;

    private AirQualityResult fetchAirQuality() {
        try {
            AirQualityResponse resp = airQualityClient.get()
                    .uri(b -> b.path("/v1/air-quality")
                            .queryParam("latitude", LAT)
                            .queryParam("longitude", LON)
                            .queryParam("current", "us_aqi")
                            .queryParam("hourly", "us_aqi")
                            .queryParam("past_hours", 1)
                            .queryParam("forecast_hours", 0)
                            .queryParam("timezone", "America/New_York")
                            .build())
                    .retrieve()
                    .body(AirQualityResponse.class);
            if (resp == null || resp.current() == null) {
                return new AirQualityResult(null, false);
            }
            Integer currentAqi = resp.current().usAqi();
            List<Integer> recentHours = resp.hourly() == null ? null : resp.hourly().usAqi();
            boolean sustained = recentHours != null && recentHours.size() >= 2
                    && recentHours.stream().skip(recentHours.size() - 2)
                            .allMatch(v -> v != null && v >= AQI_ALERT_THRESHOLD);
            return new AirQualityResult(currentAqi, sustained);
        } catch (RuntimeException e) {
            log.warn("Failed to fetch air quality: {}", e.toString(), e);
            return new AirQualityResult(null, false);
        }
    }

    private record AirQualityResult(Integer aqi, boolean alert) {
    }

    private OpenMeteoResponse fetchForecast() {
        try {
            return forecastClient.get()
                    .uri(b -> b.path("/v1/forecast")
                            .queryParam("latitude", LAT)
                            .queryParam("longitude", LON)
                            .queryParam("current", "temperature_2m,apparent_temperature,weather_code,uv_index,wind_direction_10m")
                            .queryParam("hourly", "temperature_2m,weather_code,precipitation_probability")
                            .queryParam("daily", "weather_code,temperature_2m_max,temperature_2m_min,uv_index_max,sunrise,sunset,precipitation_probability_max")
                            .queryParam("temperature_unit", "fahrenheit")
                            .queryParam("wind_speed_unit", "mph")
                            .queryParam("timezone", "America/New_York")
                            .queryParam("forecast_days", 7)
                            .build())
                    .retrieve()
                    .body(OpenMeteoResponse.class);
        } catch (RuntimeException e) {
            log.warn("Failed to fetch forecast from api.open-meteo.com: {}", e.toString());
            return null;
        }
    }

    private Marine fetchMarine() {
        try {
            MarineResponse resp = marineClient.get()
                    .uri(b -> b.path("/v1/marine")
                            .queryParam("latitude", MARINE_LAT)
                            .queryParam("longitude", MARINE_LON)
                            .queryParam("current", "wave_height,sea_surface_temperature")
                            .queryParam("temperature_unit", "fahrenheit")
                            .queryParam("length_unit", "imperial")
                            .queryParam("timezone", "America/New_York")
                            .build())
                    .retrieve()
                    .body(MarineResponse.class);
            if (resp == null || resp.current() == null || resp.current().waveHeightFt() == null
                    || resp.current().seaSurfaceTempF() == null) {
                return null;
            }
            double wave = Math.round(resp.current().waveHeightFt() * 10) / 10.0;
            int waterTemp = (int) Math.round(resp.current().seaSurfaceTempF());
            return new Marine(wave, waterTemp);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<TidePoint> fetchTides() {
        try {
            String beginDate = LocalDateTime.now(LOCAL_ZONE).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            NoaaResponse resp = noaaClient.get()
                    .uri(b -> b.path("/api/prod/datagetter")
                            .queryParam("product", "predictions")
                            .queryParam("application", "family_dashboard")
                            .queryParam("begin_date", beginDate)
                            .queryParam("range", 48)
                            .queryParam("datum", "MLLW")
                            .queryParam("station", NOAA_STATION)
                            .queryParam("time_zone", "lst_ldt")
                            .queryParam("units", "english")
                            .queryParam("interval", "hilo")
                            .queryParam("format", "json")
                            .build())
                    .retrieve()
                    .body(NoaaResponse.class);
            if (resp == null || resp.predictions() == null) {
                return List.of();
            }
            LocalDateTime now = LocalDateTime.now(LOCAL_ZONE);
            List<TidePoint> upcoming = new ArrayList<>();
            for (NoaaPrediction p : resp.predictions()) {
                if (p.t() == null || p.v() == null || p.type() == null) {
                    continue;
                }
                LocalDateTime time = LocalDateTime.parse(p.t(), NOAA_FORMAT);
                if (time.isAfter(now) && upcoming.size() < 4) {
                    upcoming.add(new TidePoint(p.t(), p.type(), Double.parseDouble(p.v())));
                }
            }
            return upcoming;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static CurrentConditions toCurrent(OpenMeteoResponse resp) {
        OpenMeteoCurrent c = resp.current();
        if (c == null || c.temperatureF() == null) {
            return null;
        }
        WeatherLabel label = describeCode(c.weatherCode());
        return new CurrentConditions(
                (int) Math.round(c.temperatureF()),
                c.apparentTemperatureF() == null ? (int) Math.round(c.temperatureF()) : (int) Math.round(c.apparentTemperatureF()),
                label.icon(),
                label.label(),
                c.uvIndex() == null ? 0 : (int) Math.round(c.uvIndex()),
                onshoreWind(c.windDirectionDeg()));
    }

    /**
     * Manasquan's coastline runs roughly north-south with the ocean to the east, so a wind
     * direction with an easterly component (blowing from the ocean toward land) is onshore.
     */
    private static Boolean onshoreWind(Integer windDirectionDeg) {
        if (windDirectionDeg == null) {
            return null;
        }
        return Math.sin(Math.toRadians(windDirectionDeg)) >= 0;
    }

    private static int currentHourIndex(List<String> times) {
        if (times == null || times.isEmpty()) {
            return -1;
        }
        LocalDateTime now = LocalDateTime.now(LOCAL_ZONE).withMinute(0).withSecond(0).withNano(0);
        for (int i = 0; i < times.size(); i++) {
            if (!LocalDateTime.parse(times.get(i)).isBefore(now)) {
                return i;
            }
        }
        return times.size() - 1;
    }

    private static List<HourPoint> toHourly(OpenMeteoResponse resp) {
        OpenMeteoHourly h = resp.hourly();
        if (h == null || h.time() == null) {
            return List.of();
        }
        int start = Math.max(0, currentHourIndex(h.time()));
        List<HourPoint> points = new ArrayList<>();
        for (int i = start; i < h.time().size() && points.size() < 12; i++) {
            Double temp = h.temperature() == null ? null : h.temperature().get(i);
            Integer code = h.weatherCode() == null ? null : h.weatherCode().get(i);
            Integer precip = h.precipProbability() == null ? null : h.precipProbability().get(i);
            WeatherLabel label = describeCode(code);
            points.add(new HourPoint(h.time().get(i), temp == null ? 0 : (int) Math.round(temp), label.icon(), precip == null ? 0 : precip));
        }
        return points;
    }

    private static List<DayPoint> toDaily(OpenMeteoResponse resp) {
        OpenMeteoDaily d = resp.daily();
        if (d == null || d.time() == null) {
            return List.of();
        }
        List<DayPoint> points = new ArrayList<>();
        for (int i = 0; i < d.time().size(); i++) {
            Integer code = d.weatherCode() == null ? null : d.weatherCode().get(i);
            WeatherLabel label = describeCode(code);
            Double hi = d.tempMax() == null ? null : d.tempMax().get(i);
            Double lo = d.tempMin() == null ? null : d.tempMin().get(i);
            Double uv = d.uvIndexMax() == null ? null : d.uvIndexMax().get(i);
            Integer precip = d.precipProbabilityMax() == null ? null : d.precipProbabilityMax().get(i);
            points.add(new DayPoint(
                    d.time().get(i),
                    label.icon(),
                    label.label(),
                    hi == null ? 0 : (int) Math.round(hi),
                    lo == null ? 0 : (int) Math.round(lo),
                    uv == null ? 0 : (int) Math.round(uv),
                    d.sunrise() == null ? null : d.sunrise().get(i),
                    d.sunset() == null ? null : d.sunset().get(i),
                    precip == null ? 0 : precip,
                    hourlyForDate(resp.hourly(), d.time().get(i))));
        }
        return points;
    }

    /** The hourly block covers all 7 forecast days in one flat list; slice out just this date's 24 hours. */
    private static List<HourPoint> hourlyForDate(OpenMeteoHourly h, String date) {
        if (h == null || h.time() == null) {
            return List.of();
        }
        List<HourPoint> points = new ArrayList<>();
        for (int i = 0; i < h.time().size(); i++) {
            if (!h.time().get(i).startsWith(date)) {
                continue;
            }
            Double temp = h.temperature() == null ? null : h.temperature().get(i);
            Integer code = h.weatherCode() == null ? null : h.weatherCode().get(i);
            Integer precip = h.precipProbability() == null ? null : h.precipProbability().get(i);
            WeatherLabel label = describeCode(code);
            points.add(new HourPoint(h.time().get(i), temp == null ? 0 : (int) Math.round(temp), label.icon(), precip == null ? 0 : precip));
        }
        return points;
    }

    private static WeatherLabel describeCode(Integer code) {
        if (code == null) {
            return new WeatherLabel("clear", "Clear");
        }
        return switch (code) {
            case 0 -> new WeatherLabel("clear", "Clear");
            case 1 -> new WeatherLabel("clear", "Mainly Clear");
            case 2 -> new WeatherLabel("cloud-sun", "Partly Cloudy");
            case 3 -> new WeatherLabel("cloud", "Overcast");
            case 45, 48 -> new WeatherLabel("cloud", "Fog");
            case 51, 53, 55 -> new WeatherLabel("rain", "Drizzle");
            case 56, 57 -> new WeatherLabel("rain", "Freezing Drizzle");
            case 61, 63, 65 -> new WeatherLabel("rain", "Rain");
            case 66, 67 -> new WeatherLabel("rain", "Freezing Rain");
            case 71, 73, 75, 77 -> new WeatherLabel("snow", "Snow");
            case 80, 81, 82 -> new WeatherLabel("rain", "Showers");
            case 85, 86 -> new WeatherLabel("snow", "Snow Showers");
            case 95 -> new WeatherLabel("storm", "Thunderstorms");
            case 96, 99 -> new WeatherLabel("storm", "Severe Storms");
            default -> new WeatherLabel("clear", "Clear");
        };
    }

    private record WeatherLabel(String icon, String label) {
    }

    private record OpenMeteoResponse(
            @JsonProperty("current") OpenMeteoCurrent current,
            @JsonProperty("hourly") OpenMeteoHourly hourly,
            @JsonProperty("daily") OpenMeteoDaily daily) {
    }

    private record OpenMeteoCurrent(
            @JsonProperty("temperature_2m") Double temperatureF,
            @JsonProperty("apparent_temperature") Double apparentTemperatureF,
            @JsonProperty("weather_code") Integer weatherCode,
            @JsonProperty("uv_index") Double uvIndex,
            @JsonProperty("wind_direction_10m") Integer windDirectionDeg) {
    }

    private record OpenMeteoHourly(
            @JsonProperty("time") List<String> time,
            @JsonProperty("temperature_2m") List<Double> temperature,
            @JsonProperty("weather_code") List<Integer> weatherCode,
            @JsonProperty("precipitation_probability") List<Integer> precipProbability) {
    }

    private record OpenMeteoDaily(
            @JsonProperty("time") List<String> time,
            @JsonProperty("weather_code") List<Integer> weatherCode,
            @JsonProperty("temperature_2m_max") List<Double> tempMax,
            @JsonProperty("temperature_2m_min") List<Double> tempMin,
            @JsonProperty("uv_index_max") List<Double> uvIndexMax,
            @JsonProperty("sunrise") List<String> sunrise,
            @JsonProperty("sunset") List<String> sunset,
            @JsonProperty("precipitation_probability_max") List<Integer> precipProbabilityMax) {
    }

    private record AirQualityResponse(
            @JsonProperty("current") AirQualityCurrent current,
            @JsonProperty("hourly") AirQualityHourly hourly) {
    }

    private record AirQualityCurrent(@JsonProperty("us_aqi") Integer usAqi) {
    }

    private record AirQualityHourly(@JsonProperty("us_aqi") List<Integer> usAqi) {
    }

    private record MarineResponse(@JsonProperty("current") MarineCurrent current) {
    }

    private record MarineCurrent(
            @JsonProperty("wave_height") Double waveHeightFt,
            @JsonProperty("sea_surface_temperature") Double seaSurfaceTempF) {
    }

    private record NoaaResponse(@JsonProperty("predictions") List<NoaaPrediction> predictions) {
    }

    private record NoaaPrediction(
            @JsonProperty("t") String t,
            @JsonProperty("v") String v,
            @JsonProperty("type") String type) {
    }

    public record WeatherSnapshot(
            CurrentConditions current,
            List<HourPoint> hourly,
            List<DayPoint> daily,
            Marine marine,
            List<TidePoint> tides,
            Integer aqi,
            boolean aqiAlert) {
    }

    public record CurrentConditions(
            int tempF, int feelsLikeF, String icon, String label, int uv, Boolean onshoreWind) {
    }

    public record HourPoint(String time, int tempF, String icon, int precipChance) {
    }

    public record DayPoint(
            String date, String icon, String label, int hiF, int loF, int uvMax,
            String sunrise, String sunset, int precipChance, List<HourPoint> hourly) {
    }

    public record Marine(double waveFt, int waterTempF) {
    }

    public record TidePoint(String time, String type, double heightFt) {
    }
}
