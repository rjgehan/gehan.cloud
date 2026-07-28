# Deployment Notes

This app uses an embedded H2 file database. There is no external database to set up.

## PIN Login

The app uses one shared family PIN. It is stored as a BCrypt hash in the embedded H2 database.

You can set your own first PIN:

```text
APP_PIN=<8 digit PIN>
```

```text
APP_USERNAME=family
SESSION_COOKIE_SECURE=true
```

If `APP_PIN` is not set and the user database is empty, the app generates a bootstrap PIN and prints it in the startup logs.

To reset the PIN from the command line or deploy environment, start once with:

```text
APP_RESET_PIN=<new 8 digit PIN>
```

Remove `APP_RESET_PIN` after the reset. There is no browser UI for changing the PIN.

## User Persistence

The family account and PIN hash are stored at:

```text
/data/users.mv.db
```

The Docker image declares `/data` as a volume. To keep users across redeploys, configure your deploy host to persist or mount `/data`.

## Weather, Marine & Radar Data

The Home and Weather pages call a few free, keyless third-party APIs server-side:

- [Open-Meteo](https://open-meteo.com) — forecast, UV index, and marine conditions
- [NOAA CO-OPS](https://api.tidesandcurrents.noaa.gov) — tide predictions
- [LibreWXR](https://librewxr.net) — radar imagery tiles and short-term (~60-minute) precipitation nowcast, RainViewer-API-compatible

No API keys are required, but the deploy host needs outbound internet access to those domains, plus `unpkg.com` and `basemaps.cartocdn.com` (Leaflet + base map tiles loaded client-side on the Weather page).

The location is currently hardcoded for a specific beach house (Manasquan, NJ) as constants in `WeatherService.java` (`LAT`/`LON`, `MARINE_LAT`/`MARINE_LON`, `NOAA_STATION`). Reusing this project for a different location means updating those constants and finding the nearest NOAA tide station ID for the new coordinates.

## Calendar (iCloud)

The Calendar page reads events from a private iCloud calendar over CalDAV. This is opt-in: if the
credentials below aren't set, `GET /api/calendar` just returns an empty list and the calendar page
shows no events.

1. Generate an app-specific password at [appleid.apple.com](https://appleid.apple.com) → Sign-In and
   Security → App-Specific Passwords. Apple's CalDAV endpoint doesn't accept your normal Apple ID
   password for third-party apps.
2. Set:

```text
APP_ICLOUD_USERNAME=<your Apple ID email>
APP_ICLOUD_APP_PASSWORD=<the generated app-specific password>
```

3. Optionally, restrict which calendars show up on the dashboard (useful if your iCloud account has
   a work calendar mixed in with the family one). Comma-separate the exact calendar display names:

```text
APP_ICLOUD_CALENDARS=Family,Kids
```

Leaving `APP_ICLOUD_CALENDARS` unset pulls events from every calendar on the account.

`CalendarService.java` handles CalDAV discovery (principal → calendar-home-set → calendar list),
fetches events in a rolling window (7 days back, 60 days forward), and caches results for 15
minutes. Discovery itself (which server partition and calendar URLs to use) is cached for a day
since it rarely changes. The deploy host needs outbound access to `caldav.icloud.com` (Apple
redirects to a specific `pNN-caldav.icloud.com` partition per account).

Each event's color-coded avatar (the colored initial in the day popup) is derived from a hash of
the event title, not tied to a specific calendar or family member — there's no concept of
per-person calendars here, just a stable, repeatable color per event name.

