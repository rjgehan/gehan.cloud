# House Dashboard

A wall-mounted family smart-home dashboard, built as a single Spring Boot application with no frontend framework, no build step, and no JavaScript bundler — just server-rendered HTML, vanilla CSS, and vanilla JS, designed to run unattended on a kiosk tablet 24/7.

PIN login, live weather/radar, real camera feeds, a real iCloud calendar, real Home Assistant fan/light control, and a real meal plan and grocery list are all wired up and working.

## What it does

- **Home** — the always-on idle screen. Live clock, current conditions, a sun-position arc that dims after sunset, a UV gauge, next tide, what's for dinner tonight, and a rotating "what's on today" ticker. Shows a top-of-screen air quality alert when the AQI is bad enough to matter. No controls here by design — this screen is meant to be glanced at, not tapped. Auto-returns here after 5 minutes of no interaction on any other page.
- **Weather** — current conditions, an hourly temperature chart, a 7-day forecast (tap a day for an hour-by-hour breakdown), a UV gauge, sunrise/sunset, air quality, and a **live rain radar map with a ~60-minute forecast** (Leaflet + LibreWXR radar/nowcast imagery over a dark CARTO basemap), plus marine conditions (water temp, wave height) and tide times pulled from the nearest NOAA station.
- **Lights** — 4 ceiling fans (speed + two light channels each) with live state and real on/off/speed control via a Home Assistant instance, plus a Fence Lights tile driving the two gazebo sockets.
- **Cameras** — live-ish snapshot tiles from RTSP cameras (opt-in via `APP_CAMERAS`), tap a tile for a fullscreen view.
- **Music** — Music Assistant-style now-playing UI and one-tap scene buttons (not wired to a backend yet).
- **Calendar** — a real iCloud calendar over CalDAV (opt-in via `APP_ICLOUD_*`), month view with a day-detail popup.
- **Kitchen** — four quarters, all in view at once: cooking timers, the week's meal plan a row per day with today picked out, a **View Recipes** launcher, and a unit converter (volume/weight/temperature). Recipes open in a full-width popup with ingredients beside steps, so you can cook from it without scrolling; pin the one you're making and it gets its own one-tap button on the Kitchen page. Tapping a planned meal jumps straight to its recipe.
- **Grocery** — the family list on its own page, flowing in columns so a long list stays in view. Tick items off and add from the wall; it syncs live to everyone's phone. Meals, recipes and groceries all come from a self-hosted Meal Planner over its integration API (opt-in via `MEALS_*`).
- **Night dim** — between midnight and 6am the screen sits behind a dark sheet after a minute of quiet, so it isn't a lantern at 3am. A tap lifts it, and that first tap is swallowed on purpose: it wakes the screen rather than pressing whatever was underneath it.
- **Theme** — eight color themes (Dusk, Sunrise, Midday, Sunset, Night, Autumn, Holiday, Winter) that swap live via CSS custom properties and persist in `localStorage`.

The whole UI is one continuously-mounted single-page app: the sidebar just toggles which `data-page` section is visible client-side, so navigation never triggers a reload — nav important for something that's meant to sit on a wall.

## Live data, no API keys

Weather, marine conditions, tides, and radar imagery are all real, fetched from free/keyless public APIs:

| Source | Used for |
|---|---|
| [Open-Meteo](https://open-meteo.com) | Forecast, hourly/daily conditions, UV index |
| [Open-Meteo Marine](https://open-meteo.com) | Water temperature, wave height |
| [NOAA CO-OPS](https://tidesandcurrents.noaa.gov) | Tide predictions from the nearest station |
| [LibreWXR](https://librewxr.net) | Radar tile imagery, plus a ~60-minute precipitation nowcast |

`WeatherService.java` fetches and caches this server-side (10-minute TTL) and exposes it as a small JSON contract at `/api/weather`; the dashboard polls that endpoint client-side and re-renders in place.

## Tech stack

- **Java 21 / Spring Boot 4** — Spring MVC, Spring Security (form login, BCrypt-hashed PIN), Spring Data JPA + H2 for user persistence
- **No frontend framework** — server-rendered HTML via Java text blocks, one shared stylesheet using CSS custom properties for theming, one vanilla JS file for all interactivity (tab routing, live chart rendering, the radar map, form controls)
- **Leaflet** (CDN) for the radar map
- **Docker** — multistage build, runs as a non-root user, `/data` declared as a volume for the embedded H2 database

## Running locally

```bash
./mvnw spring-boot:run
```

Drop a `.env` file in the project root (gitignored) with any of the optional integration variables
below — it's loaded automatically on local runs (`SpringDockerTestApplication.loadDotEnvIfPresent`)
and never overrides a real environment variable, so it has zero effect on Docker/production, which
never sees a `.env` file to begin with.

The app boots on `:8080`. On first run with no existing users, it generates a bootstrap PIN and prints it to the startup logs (see [DEPLOYMENT.md](DEPLOYMENT.md) for setting your own via `APP_PIN`, the outbound network access the weather/radar features need, and every optional integration's env vars).

## Testing

```bash
./mvnw test
```

Covers authentication (redirect-to-login for anonymous users, PIN login success/failure, bootstrap + reset PIN flows) and basic page rendering.

## Deployment

See [DEPLOYMENT.md](DEPLOYMENT.md) for PIN configuration, user persistence, and the outbound network requirements for live weather/radar data.
