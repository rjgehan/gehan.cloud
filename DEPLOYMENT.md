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
- [RainViewer](https://www.rainviewer.com/api.html) — radar imagery tiles

No API keys are required, but the deploy host needs outbound internet access to those domains, plus `unpkg.com` and `basemaps.cartocdn.com` (Leaflet + base map tiles loaded client-side on the Weather page).

The location is currently hardcoded for a specific beach house (Manasquan, NJ) as constants in `WeatherService.java` (`LAT`/`LON`, `MARINE_LAT`/`MARINE_LON`, `NOAA_STATION`). Reusing this project for a different location means updating those constants and finding the nearest NOAA tide station ID for the new coordinates.

