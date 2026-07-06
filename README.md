# Beach House Dashboard

A wall-mounted family smart-home dashboard, built as a single Spring Boot application with no frontend framework, no build step, and no JavaScript bundler — just server-rendered HTML, vanilla CSS, and vanilla JS, designed to run unattended on a kiosk tablet 24/7.

PIN login is already in place; the rest is a from-scratch redesign: a glanceable home screen, a full live weather/radar page, and placeholder control surfaces for lights, climate, security, media, and calendar that are wired up and ready to talk to a Home Assistant instance.

## What it does

- **Home** — the always-on idle screen. Live clock, current conditions, a sun-position arc that dims after sunset, a UV gauge, next tide, and a rotating "what's on today" ticker. No controls here by design — this screen is meant to be glanced at, not tapped.
- **Weather** — current conditions, an hourly temperature chart, a 7-day forecast, a UV gauge, sunrise/sunset, and a **live rain radar map** (Leaflet + RainViewer imagery over a dark CARTO basemap), plus marine conditions (water temp, wave height) and tide times pulled from the nearest NOAA station.
- **Lights, Climate, Security, Media, Calendar** — full control UIs (toggles, sliders, a wind-style compass, scene buttons, an agenda list) that don't call anything yet. They're built to be wired to a Home Assistant API without needing a redesign.
- **Theme** — eight color themes (Dusk, Sunrise, Midday, Sunset, Night, Autumn, Holiday, Winter) that swap live via CSS custom properties and persist in `localStorage`.

The whole UI is one continuously-mounted single-page app: the sidebar just toggles which `data-page` section is visible client-side, so navigation never triggers a reload — nav important for something that's meant to sit on a wall.

## Live data, no API keys

Weather, marine conditions, tides, and radar imagery are all real, fetched from free/keyless public APIs:

| Source | Used for |
|---|---|
| [Open-Meteo](https://open-meteo.com) | Forecast, hourly/daily conditions, UV index |
| [Open-Meteo Marine](https://open-meteo.com) | Water temperature, wave height |
| [NOAA CO-OPS](https://tidesandcurrents.noaa.gov) | Tide predictions from the nearest station |
| [RainViewer](https://www.rainviewer.com) | Radar tile imagery |

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

The app boots on `:8080`. On first run with no existing users, it generates a bootstrap PIN and prints it to the startup logs (see [DEPLOYMENT.md](DEPLOYMENT.md) for setting your own via `APP_PIN`, and for the outbound network access the weather/radar features need).

## Testing

```bash
./mvnw test
```

Covers authentication (redirect-to-login for anonymous users, PIN login success/failure, bootstrap + reset PIN flows) and basic page rendering.

## Deployment

See [DEPLOYMENT.md](DEPLOYMENT.md) for PIN configuration, user persistence, and the outbound network requirements for live weather/radar data.
