package com.example.spring_docker_test;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

class CalendarServiceTest {

    private final CalendarService service = new CalendarService("user@example.com", "app-password", "");
    private final ZonedDateTime now = ZonedDateTime.of(2026, 7, 27, 12, 0, 0, 0, ZoneId.of("America/New_York"));

    @Test
    void parsesTimedEvent() {
        String ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-event-1@example.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260727T130000Z
                DTEND:20260727T140000Z
                SUMMARY:Beach Cleanup
                END:VEVENT
                END:VCALENDAR
                """;

        List<CalendarService.CalEvent> events = service.parseIcs(ics, now);

        assertThat(events).hasSize(1);
        CalendarService.CalEvent event = events.get(0);
        assertThat(event.date()).isEqualTo("2026-07-27");
        assertThat(event.time()).isEqualTo("9:00 AM");
        assertThat(event.title()).isEqualTo("Beach Cleanup");
        assertThat(event.initial()).isEqualTo("B");
    }

    @Test
    void parsesAllDayEvent() {
        String ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-event-2@example.com
                DTSTAMP:20260101T000000Z
                DTSTART;VALUE=DATE:20260728
                DTEND;VALUE=DATE:20260729
                SUMMARY:Family Reunion
                END:VEVENT
                END:VCALENDAR
                """;

        List<CalendarService.CalEvent> events = service.parseIcs(ics, now);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).date()).isEqualTo("2026-07-28");
        assertThat(events.get(0).time()).isEqualTo("All Day");
    }

    @Test
    void multiDayAllDayEventShowsOnEveryDayItSpans() {
        // iCal all-day DTEND is exclusive, so Aug 1 - Aug 4 covers Aug 1, 2, 3 (not 4).
        String ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:trip@example.com
                DTSTAMP:20260101T000000Z
                DTSTART;VALUE=DATE:20260801
                DTEND;VALUE=DATE:20260804
                SUMMARY:Squan Squad Weekend
                END:VEVENT
                END:VCALENDAR
                """;

        List<CalendarService.CalEvent> events = service.parseIcs(ics, now);

        assertThat(events).extracting(CalendarService.CalEvent::date)
                .containsExactly("2026-08-01", "2026-08-02", "2026-08-03");
        assertThat(events).allMatch(e -> e.time().equals("All Day"));
        assertThat(events).allMatch(e -> e.title().equals("Squan Squad Weekend"));
    }

    @Test
    void multiDayTimedEventShowsOnEveryDayItSpansWithTimeOnlyOnFirstDay() {
        String ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:camping@example.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260801T180000Z
                DTEND:20260803T140000Z
                SUMMARY:Camping Trip
                END:VEVENT
                END:VCALENDAR
                """;

        List<CalendarService.CalEvent> events = service.parseIcs(ics, now);

        assertThat(events).extracting(CalendarService.CalEvent::date)
                .containsExactly("2026-08-01", "2026-08-02", "2026-08-03");
        assertThat(events.get(0).time()).isEqualTo("2:00 PM");
        assertThat(events.get(1).time()).isEqualTo("All Day");
        assertThat(events.get(2).time()).isEqualTo("All Day");
    }

    @Test
    void skipsMalformedIcsInsteadOfThrowing() {
        List<CalendarService.CalEvent> events = service.parseIcs("not a valid calendar", now);

        assertThat(events).isEmpty();
    }

    @Test
    void sameTitleAlwaysGetsSameColor() {
        String ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:a@example.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260727T130000Z
                DTEND:20260727T140000Z
                SUMMARY:Soccer Practice
                END:VEVENT
                BEGIN:VEVENT
                UID:b@example.com
                DTSTAMP:20260101T000000Z
                DTSTART:20260728T130000Z
                DTEND:20260728T140000Z
                SUMMARY:Soccer Practice
                END:VEVENT
                END:VCALENDAR
                """;

        List<CalendarService.CalEvent> events = service.parseIcs(ics, now);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).color()).isEqualTo(events.get(1).color());
    }
}
