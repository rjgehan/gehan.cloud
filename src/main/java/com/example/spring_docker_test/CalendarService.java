package com.example.spring_docker_test;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.Summary;

/**
 * Reads events from a private iCloud calendar over CalDAV (Apple ID + app-specific password).
 * Discovery (principal / calendar-home-set / calendar list) is cached for a day since it rarely
 * changes; the events themselves are cached for a shorter window, mirroring WeatherService.
 */
@Service
public class CalendarService {

    private static final String DEFAULT_HOST = "caldav.icloud.com";
    private static final ZoneId LOCAL_ZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter EVENT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("h:mm a");
    private static final DateTimeFormatter DAV_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final String[] PALETTE = {"#75d4f2", "#d9bd79", "#9be29b", "#f2a65a"};

    private static final Logger log = LoggerFactory.getLogger(CalendarService.class);

    private static final long EVENTS_CACHE_TTL_SECONDS = 900;
    private static final long DISCOVERY_TTL_SECONDS = 86_400;
    private static final int DAYS_BACK = 7;
    private static final int DAYS_FORWARD = 60;

    private final String username;
    private final String appPassword;
    private final Set<String> calendarFilter;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile String host = DEFAULT_HOST;
    private volatile List<HrefResult> calendarPaths = List.of();
    private volatile Instant discoveredAt = Instant.EPOCH;

    private volatile List<CalEvent> cachedEvents = List.of();
    private volatile Instant cachedAt = Instant.EPOCH;

    public CalendarService(
            @Value("${app.icloud.username}") String username,
            @Value("${app.icloud.app-password}") String appPassword,
            @Value("${app.icloud.calendars}") String calendarNames) {
        this.username = username;
        this.appPassword = appPassword;
        this.calendarFilter = calendarNames == null || calendarNames.isBlank()
                ? Set.of()
                : Arrays.stream(calendarNames.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toSet());
    }

    public synchronized List<CalEvent> events() {
        if (!configured()) {
            return List.of();
        }
        if (!cachedEvents.isEmpty() && Instant.now().getEpochSecond() - cachedAt.getEpochSecond() < EVENTS_CACHE_TTL_SECONDS) {
            return cachedEvents;
        }
        try {
            ensureDiscovered();
            cachedEvents = fetchEvents();
            cachedAt = Instant.now();
        } catch (Exception e) {
            log.warn("Failed to refresh iCloud calendar events: {}", e.toString(), e);
            // serve last-known-good events (if any) rather than fail the whole calendar page
        }
        return cachedEvents;
    }

    private boolean configured() {
        return username != null && !username.isBlank() && appPassword != null && !appPassword.isBlank();
    }

    private void ensureDiscovered() throws Exception {
        if (!calendarPaths.isEmpty() && Instant.now().getEpochSecond() - discoveredAt.getEpochSecond() < DISCOVERY_TTL_SECONDS) {
            return;
        }
        DavResponse principalResp = propfind(host, "/", 0, """
                <?xml version="1.0" encoding="UTF-8"?>
                <A:propfind xmlns:A="DAV:">
                  <A:prop><A:current-user-principal/></A:prop>
                </A:propfind>
                """);
        host = principalResp.host();
        HrefResult principalHref = resolveHref(host, firstHref(principalResp.body(), "current-user-principal"));
        log.info("iCloud CalDAV principal href: {}", principalHref);
        if (principalHref == null) {
            log.warn("iCloud CalDAV principal discovery returned no href. Raw response: {}", principalResp.body());
            return;
        }
        host = principalHref.host();
        String principalPath = principalHref.path();

        DavResponse homeResp = propfind(host, principalPath, 0, """
                <?xml version="1.0" encoding="UTF-8"?>
                <A:propfind xmlns:A="DAV:" xmlns:B="urn:ietf:params:xml:ns:caldav">
                  <A:prop><B:calendar-home-set/></A:prop>
                </A:propfind>
                """);
        HrefResult homeHref = resolveHref(host, firstHref(homeResp.body(), "calendar-home-set"));
        log.info("iCloud CalDAV calendar-home-set href: {}", homeHref);
        if (homeHref == null) {
            log.warn("iCloud CalDAV calendar-home-set discovery returned no href. Raw response: {}", homeResp.body());
            return;
        }
        host = homeHref.host();
        String homePath = homeHref.path();

        DavResponse listResp = propfind(host, homePath, 1, """
                <?xml version="1.0" encoding="UTF-8"?>
                <A:propfind xmlns:A="DAV:" xmlns:B="urn:ietf:params:xml:ns:caldav">
                  <A:prop>
                    <A:resourcetype/>
                    <A:displayname/>
                  </A:prop>
                </A:propfind>
                """);
        List<NamedPath> found = calendarPathsWithNames(listResp.body());
        log.info("iCloud CalDAV found {} calendars: {}", found.size(),
                found.stream().map(NamedPath::name).toList());
        calendarPaths = found.stream()
                .filter(cal -> calendarFilter.isEmpty() || calendarFilter.contains(cal.name()))
                .map(cal -> resolveHref(host, cal.path()))
                .filter(href -> href != null)
                .toList();
        log.info("iCloud CalDAV using {} calendars after filtering", calendarPaths.size());
        discoveredAt = Instant.now();
    }

    private static HrefResult resolveHref(String currentHost, String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        if (href.startsWith("http://") || href.startsWith("https://")) {
            URI uri = URI.create(href);
            String path = uri.getRawPath();
            return new HrefResult(uri.getHost(), path == null || path.isEmpty() ? "/" : path);
        }
        return new HrefResult(currentHost, href);
    }

    private record HrefResult(String host, String path) {
    }

    private List<CalEvent> fetchEvents() throws Exception {
        ZonedDateTime now = ZonedDateTime.now(LOCAL_ZONE);
        String start = now.minusDays(DAYS_BACK).withZoneSameInstant(ZoneId.of("UTC")).format(DAV_TIME);
        String end = now.plusDays(DAYS_FORWARD).withZoneSameInstant(ZoneId.of("UTC")).format(DAV_TIME);
        String reportBody = """
                <?xml version="1.0" encoding="UTF-8"?>
                <C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <D:prop>
                    <C:calendar-data/>
                  </D:prop>
                  <C:filter>
                    <C:comp-filter name="VCALENDAR">
                      <C:comp-filter name="VEVENT">
                        <C:time-range start="%s" end="%s"/>
                      </C:comp-filter>
                    </C:comp-filter>
                  </C:filter>
                </C:calendar-query>
                """.formatted(start, end);

        List<CalEvent> events = new ArrayList<>();
        for (HrefResult calendar : calendarPaths) {
            DavResponse resp = report(calendar.host(), calendar.path(), reportBody);
            for (String ics : calendarDataBlocks(resp.body())) {
                events.addAll(parseIcs(ics, now));
            }
        }
        events.sort((a, b) -> (a.date() + a.time24()).compareTo(b.date() + b.time24()));
        return events;
    }

    List<CalEvent> parseIcs(String ics, ZonedDateTime now) {
        List<CalEvent> events = new ArrayList<>();
        try {
            Calendar calendar = new CalendarBuilder().build(new StringReader(ics));
            Instant rangeStart = now.minusDays(DAYS_BACK).toInstant();
            Instant rangeEnd = now.plusDays(DAYS_FORWARD).toInstant();
            Period<Instant> window = new Period<>(rangeStart, rangeEnd);
            LocalDate windowStartDate = rangeStart.atZone(LOCAL_ZONE).toLocalDate();
            LocalDate windowEndDate = rangeEnd.atZone(LOCAL_ZONE).toLocalDate();

            for (Object o : calendar.getComponents("VEVENT")) {
                VEvent event = (VEvent) o;
                Summary summary = event.getSummary();
                String title = summary == null ? "Untitled" : summary.getValue();

                Temporal startTemporal = event.getStartDate().map(DtStart::getDate).orElse(null);
                boolean allDay = startTemporal instanceof LocalDate;

                Set<Period<Temporal>> occurrences = event.calculateRecurrenceSet(window);
                for (Period<Temporal> p : occurrences) {
                    ZonedDateTime occurrenceStart = toZonedDateTime(p.getStart());
                    ZonedDateTime occurrenceEnd = toZonedDateTime(p.getEnd());

                    LocalDate startDate = occurrenceStart.toLocalDate();
                    LocalDate endDate = occurrenceEnd.toLocalDate();
                    // iCal end times are exclusive: an all-day event's DTEND (or a timed event that
                    // happens to end exactly at midnight) points at the day *after* the last day it
                    // actually covers, so step that back one day before spanning the range.
                    boolean exclusiveEnd = allDay
                            || (occurrenceEnd.toLocalTime().equals(LocalTime.MIDNIGHT) && occurrenceEnd.isAfter(occurrenceStart));
                    if (exclusiveEnd) {
                        endDate = endDate.minusDays(1);
                    }
                    if (endDate.isBefore(startDate)) {
                        endDate = startDate;
                    }

                    LocalDate loopStart = startDate.isBefore(windowStartDate) ? windowStartDate : startDate;
                    LocalDate loopEnd = endDate.isAfter(windowEndDate) ? windowEndDate : endDate;
                    for (LocalDate day = loopStart; !day.isAfter(loopEnd); day = day.plusDays(1)) {
                        boolean isFirstDay = day.equals(startDate);
                        events.add(new CalEvent(
                                day.format(EVENT_DATE),
                                allDay || !isFirstDay ? "All Day" : occurrenceStart.format(EVENT_TIME),
                                allDay || !isFirstDay ? "0000" : occurrenceStart.format(DateTimeFormatter.ofPattern("HHmm")),
                                title,
                                initialFor(title),
                                colorFor(title)));
                    }
                }
            }
        } catch (Exception e) {
            // skip events we can't parse rather than fail the whole calendar
        }
        return events;
    }

    private static ZonedDateTime toZonedDateTime(Temporal temporal) {
        if (temporal instanceof ZonedDateTime zdt) {
            return zdt.withZoneSameInstant(LOCAL_ZONE);
        }
        if (temporal instanceof OffsetDateTime odt) {
            return odt.atZoneSameInstant(LOCAL_ZONE);
        }
        if (temporal instanceof Instant instant) {
            return instant.atZone(LOCAL_ZONE);
        }
        if (temporal instanceof LocalDateTime ldt) {
            return ldt.atZone(LOCAL_ZONE);
        }
        if (temporal instanceof LocalDate ld) {
            return ld.atStartOfDay(LOCAL_ZONE);
        }
        // Fail loudly rather than silently falling back to "now" for a type we don't recognize.
        throw new IllegalArgumentException("Unsupported temporal type: " + temporal.getClass());
    }

    private static String initialFor(String calendarOrTitle) {
        String trimmed = calendarOrTitle == null ? "" : calendarOrTitle.trim();
        return trimmed.isEmpty() ? "?" : trimmed.substring(0, 1).toUpperCase();
    }

    private static String colorFor(String key) {
        int hash = key == null ? 0 : Math.abs(key.hashCode());
        return PALETTE[hash % PALETTE.length];
    }

    // ---- CalDAV transport ----

    private record DavResponse(String host, String body) {
    }

    private record NamedPath(String path, String name) {
    }

    private DavResponse propfind(String host, String path, int depth, String body) throws Exception {
        return send("PROPFIND", host, path, body, String.valueOf(depth));
    }

    private DavResponse report(String host, String path, String body) throws Exception {
        return send("REPORT", host, path, body, "1");
    }

    private DavResponse send(String method, String host, String path, String body, String depth) throws Exception {
        URI uri = URI.create("https://" + host + path);
        String auth = Base64.getEncoder().encodeToString((username + ":" + appPassword).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/xml; charset=utf-8")
                .header("Depth", depth)
                .timeout(Duration.ofSeconds(15))
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        log.info("iCloud CalDAV {} {} -> HTTP {}", method, uri, response.statusCode());
        if (response.statusCode() >= 400) {
            log.warn("iCloud CalDAV {} {} failed with HTTP {}. Body: {}", method, uri, response.statusCode(), response.body());
        }
        return new DavResponse(response.uri().getHost(), response.body());
    }

    // ---- Minimal namespace-agnostic XML extraction ----

    private static String firstHref(String xml, String propertyLocalName) {
        try {
            Document doc = parseXml(xml);
            XPath xpath = XPathFactory.newInstance().newXPath();
            String expr = "//*[local-name()='" + propertyLocalName + "']//*[local-name()='href']/text()";
            return (String) xpath.evaluate(expr, doc, XPathConstants.STRING);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<NamedPath> calendarPathsWithNames(String xml) {
        List<NamedPath> results = new ArrayList<>();
        try {
            Document doc = parseXml(xml);
            XPath xpath = XPathFactory.newInstance().newXPath();
            NodeList responses = (NodeList) xpath.evaluate(
                    "//*[local-name()='response']", doc, XPathConstants.NODESET);
            for (int i = 0; i < responses.getLength(); i++) {
                Element responseEl = (Element) responses.item(i);
                boolean isCalendar = ((Number) xpath.evaluate(
                        "count(.//*[local-name()='resourcetype']//*[local-name()='calendar'])",
                        responseEl, XPathConstants.NUMBER)).intValue() > 0;
                if (!isCalendar) {
                    continue;
                }
                String href = (String) xpath.evaluate(".//*[local-name()='href']/text()", responseEl, XPathConstants.STRING);
                String name = (String) xpath.evaluate(".//*[local-name()='displayname']/text()", responseEl, XPathConstants.STRING);
                if (href != null && !href.isBlank()) {
                    results.add(new NamedPath(href, name == null ? "" : name));
                }
            }
        } catch (Exception e) {
            // no calendars found
        }
        return results;
    }

    private static List<String> calendarDataBlocks(String xml) {
        List<String> blocks = new ArrayList<>();
        try {
            Document doc = parseXml(xml);
            XPath xpath = XPathFactory.newInstance().newXPath();
            NodeList nodes = (NodeList) xpath.evaluate(
                    "//*[local-name()='calendar-data']", doc, XPathConstants.NODESET);
            for (int i = 0; i < nodes.getLength(); i++) {
                blocks.add(nodes.item(i).getTextContent());
            }
        } catch (Exception e) {
            // no event data found
        }
        return blocks;
    }

    private static Document parseXml(String xml) throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    public record CalEvent(String date, String time, String time24, String title, String initial, String color) {
    }
}
