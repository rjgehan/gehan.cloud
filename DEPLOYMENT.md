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
```

If `APP_PIN` is not set and the user database is empty, the app generates a bootstrap PIN and prints it in the startup logs.

**`SESSION_COOKIE_SECURE`** defaults to `false`. Only set it to `true` if the dashboard is actually
served over HTTPS (e.g. behind a TLS-terminating reverse proxy) — setting it `true` while accessing
the app over plain HTTP (including a raw `http://<lan-ip>:8080` address) causes the browser to
silently drop the session cookie after login, which looks like an infinite login redirect loop.

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

## Cameras (RTSP)

The Security page can show live-ish camera tiles from RTSP cameras (e.g. an IC Realtime NVR/camera
set). This is opt-in: if `APP_CAMERAS` isn't set, `GET /api/cameras` returns an empty list and the
page just shows "No cameras configured."

**This requires the deploy host to reach each camera's RTSP URL directly** — same local network as
the cameras, or a VPN (e.g. Tailscale) bridging the two. RTSP isn't reachable over the open
internet the way HTTPS is, and port-forwarding raw RTSP to the public internet is not recommended
(weak auth, unencrypted stream). If the app is hosted somewhere other than the cameras' own network,
set up a VPN between the two before this will do anything.

Set one comma-separated list of `Name|rtsp://...` pairs:

```text
APP_CAMERAS=Driveway|rtsp://user:pass@192.168.1.50:554/stream1,Front Door|rtsp://user:pass@192.168.1.51:554/stream1
```

Each camera's display name becomes its tile label and is slugified into an id used in the snapshot
URL (`/api/cameras/{id}/snapshot`), e.g. "Front Door" → `front-door`.

`CameraService.java` takes the "refreshing snapshot" approach rather than true streaming video:
on each request it shells out to `ffmpeg` to pull a single JPEG frame from the RTSP stream
(`-frames:v 1`), with a 15-second timeout (cameras vary widely in keyframe interval, and ffmpeg
can't produce a frame until the next one arrives), and caches that frame for 2 seconds so concurrent tile
polls don't spawn redundant ffmpeg processes. The frontend polls each tile's snapshot endpoint
every 2 seconds and swaps the image in once it's loaded, so it reads as "basically live" without
running a continuous transcode process per camera. **The deploy host needs `ffmpeg` installed** —
already included in this project's `Dockerfile`, but if you're running the jar directly outside
Docker, install it yourself (`apt install ffmpeg` / `brew install ffmpeg`).

If you outgrow snapshot polling and want real low-latency streaming video instead, look at
[go2rtc](https://github.com/AlexxIT/go2rtc) or [MediaMTX](https://github.com/bluenviron/mediamtx) as
a sidecar RTSP→WebRTC/HLS bridge — a bigger infrastructure change than what's here today.

## Lights & Fans (Home Assistant)

The Lights page controls 4 ceiling fans through a Home Assistant instance's REST API. This is
opt-in: if `HA_URL`/`HA_TOKEN` aren't set, `HomeAssistantService` never makes a request, `GET
/api/fans` reports every fan as off, and pressing a control is a no-op — nothing breaks, it just
doesn't do anything.

1. In Home Assistant: profile icon (bottom-left) → **Security** tab → **Long-Lived Access Tokens**
   → **Create Token**.
2. Set:

```text
HA_URL=http://<home-assistant-host>:8123
HA_TOKEN=<the generated long-lived access token>
```

The deploy host needs network access to `HA_URL` — same LAN, or a VPN bridging the two, same
constraint as the RTSP cameras above.

`FanController.java` hardcodes the mapping from each dashboard card to its Home Assistant entity
ids (`fan.*` for speed, two `light.*` entities per fan for the two light channels) — there's no
discovery UI, so adding, removing, or repointing a fan means editing that map directly. Fan speed
is 0-10 on the dashboard, which maps to Home Assistant's native 0-100% in steps of 10. Each light
channel is 0-5, mapped to `brightness_pct` in steps of 20. The two lights per fan are plain
brightness dimmers in Home Assistant with no real "warm" vs "cool" distinction — that's just how
the physical fixtures happen to be labeled, so which HA light id is warm vs cool per fan is worth
double-checking by hand (open the fan's popup, bump "Warm Light" up from 0, see which bulb actually
lights up) rather than assumed correct from the entity name alone.

The Fence Lights tile on the same page drives the two gazebo smart sockets
(`switch.gazebo_socket_1` and `switch.gazebo_socket_2`) as one, through `FenceLightController`.
Being outdoor plugs they report `unavailable` rather than `off` whenever they're unplugged or out
of range, so the tile distinguishes the two: it disables itself and says "Sockets unavailable"
instead of showing a dead socket as merely switched off.

## Kitchen (Meal Planner)

The Kitchen page's week panel and recipe popup, the Home page's dinner tile, and the whole
Grocery page all read from the Meal Planner's integration API. Opt-in the same way as everything else: with `MEALS_URL`/`MEALS_API_KEY`
unset, `/api/kitchen/*` answers `503` and those panels say "Meal planner not connected". The timers
and the converter are local and work regardless.

Which recipe is pinned to the Kitchen page is per-screen, kept in `localStorage` rather than on the
planner — the wall display and a phone can each be parked on a different recipe, and neither is
anyone else's business.

```text
MEALS_URL=https://meals.gehan.cloud
MEALS_API_KEY=<the planner's INTEGRATION_API_KEY>
MEALS_HOUSEHOLD_ID=<optional; defaults to the first household the planner returns>
```

The key must match `INTEGRATION_API_KEY` on the Meal Planner server, which has to set it too — it
defaults to off there, since that app is reachable from the public internet.

Two things to keep in mind about that key:

- **It's an operator credential**, not a user's. It can read every household on that server, so the
  browser never sees it: the page calls `/api/kitchen/*` here and `MealPlannerService` is the only
  thing that talks to the planner.
- **The grocery list is the only writable thing** on that API. Adding an item, ticking one off and
  deleting one all work from the dashboard and broadcast live to anyone with the list open on their
  phone. Meals and recipes are read-only — there's no endpoint to plan a meal or edit a recipe.

`MEALS_URL` can be the public hostname or `http://<tailscale-name>:8090`; the paths are identical
either way. Recipe images come back as relative paths and are rewritten to absolute against
whichever base URL is configured, so the two aren't interchangeable per-request — the browser has
to be able to reach whatever `MEALS_URL` says. Image URLs need no key and no login; the random UUID
in the path is all that keeps them unlisted.

The dashboard polls `plan` and `grocery-list` every 30s while the Kitchen page is open, and stops
when you navigate away. There's no websocket — the planner has one for its own UI, but it's bound
to a signed-in user's token, not this key.

