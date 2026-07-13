package com.example.spring_docker_test;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class HomeController {

    private final String accessUsername;

    public HomeController(@Value("${app.security.username}") String accessUsername) {
        this.accessUsername = accessUsername;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String home(CsrfToken csrfToken) {
        String pages = homePage()
                + weatherPage()
                + lightsPage()
                + climatePage()
                + securityPage()
                + mediaPage()
                + calendarPage()
                + themePage();

        String body = """
                <main class="dashboard-shell">
                    <section class="dashboard-stage" aria-live="polite">
                        %s
                    </section>
                    <nav class="side-nav" aria-label="Dashboard">
                        <div class="nav-rail" data-nav-rail>
                            <span class="nav-indicator" data-nav-indicator></span>
                            %s
                        </div>
                        <div class="nav-divider"></div>
                        <form method="post" action="/logout">
                            <input type="hidden" name="%s" value="%s">
                            <button class="nav-item sign-out" type="submit"><span class="nav-icon-badge">%s</span><span class="nav-label">Sign out</span></button>
                        </form>
                    </nav>
                </main>
                <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
                <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                <script src="/js/dashboard.js"></script>
                """.formatted(pages, navItems(), csrfToken.getParameterName(), csrfToken.getToken(), icon("logout"));

        return page("Home", body);
    }

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String login(CsrfToken csrfToken, String error, String logout) {
        String message = "";
        if (error != null) {
            message = "<p class=\"alert error\">That PIN was not accepted.</p>";
        } else if (logout != null) {
            message = "<p class=\"alert success\">You have been signed out.</p>";
        }

        return page("Login", """
                <main class="login-layout">
                    <section class="login-panel">
                        %s
                        <form class="pin-form" method="post" action="/login" data-pin-form>
                            <input type="hidden" name="%s" value="%s">
                            <input type="hidden" name="username" value="%s">
                            <input id="pin" name="password" type="hidden" autocomplete="one-time-code" required>
                            <div class="pin-display" aria-live="polite" data-pin-display></div>
                            <div class="keypad" aria-label="PIN keypad">
                                <button type="button" data-digit="1">1</button>
                                <button type="button" data-digit="2">2</button>
                                <button type="button" data-digit="3">3</button>
                                <button type="button" data-digit="4">4</button>
                                <button type="button" data-digit="5">5</button>
                                <button type="button" data-digit="6">6</button>
                                <button type="button" data-digit="7">7</button>
                                <button type="button" data-digit="8">8</button>
                                <button type="button" data-digit="9">9</button>
                                <button type="button" data-clear>Clear</button>
                                <button type="button" data-digit="0">0</button>
                                <button type="button" data-backspace>Del</button>
                            </div>
                            <button class="submit-button" type="submit">Sign in</button>
                        </form>
                    </section>
                </main>
                <script>
                    const form = document.querySelector("[data-pin-form]");
                    const pin = document.querySelector("#pin");
                    const display = document.querySelector("[data-pin-display]");

                    function renderPin() {
                        display.textContent = pin.value ? "*".repeat(pin.value.length) : "";
                    }

                    form.addEventListener("click", (event) => {
                        const button = event.target.closest("button");
                        if (!button) {
                            return;
                        }

                        if (button.dataset.digit && pin.value.length < 8) {
                            pin.value += button.dataset.digit;
                        } else if (button.hasAttribute("data-backspace")) {
                            pin.value = pin.value.slice(0, -1);
                        } else if (button.hasAttribute("data-clear")) {
                            pin.value = "";
                        }

                        renderPin();
                    });

                    form.addEventListener("submit", (event) => {
                        if (pin.value.length !== 8) {
                            event.preventDefault();
                            display.classList.remove("shake");
                            void display.offsetWidth;
                            display.classList.add("shake");
                        }
                    });
                </script>
                """.formatted(
                        message,
                        csrfToken.getParameterName(),
                        csrfToken.getToken(),
                        escapeHtml(accessUsername)));
    }

    static String page(String title, String body) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>{{title}}</title>
                    <link rel="stylesheet" href="/css/dashboard.css">
                </head>
                <body>
                    {{body}}
                </body>
                </html>
                """
                .replace("{{title}}", title)
                .replace("{{body}}", body);
    }

    static String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /* ======================================================================
       Sidebar navigation
       ====================================================================== */

    private static String navItems() {
        return navItem("home", "Home", true)
                + navItem("weather", "Weather", false)
                + navItem("lights", "Lights", false)
                + navItem("climate", "Climate", false)
                + navItem("security", "Security", false)
                + navItem("media", "Media", false)
                + navItem("calendar", "Calendar", false)
                + navItem("theme", "Theme", false);
    }

    private static String navItem(String target, String label, boolean active) {
        return """
                <button type="button" class="nav-item%s" data-target="%s"><span class="nav-icon-badge">%s</span><span class="nav-label">%s</span></button>
                """.formatted(active ? " is-active" : "", target, icon(target), label);
    }

    /* ======================================================================
       Home — glance only, no controls
       ====================================================================== */

    private static String homePage() {
        return """
                <section class="dashboard-page is-active home-page" data-page="home">
                    <div class="home-hero">
                        <div class="home-clock" data-clock>--:--</div>
                        <p class="home-date" data-date>&nbsp;</p>
                    </div>

                    <div class="home-bento">
                        <div class="hero-weather-card" data-wx-current data-target="weather" role="button" tabindex="0">
                            <div class="sun-arc">
                                <svg viewBox="0 0 200 100" aria-hidden="true">
                                    <path class="arc-track" d="M18,92 A82,82 0 0 1 182,92"/>
                                    <path class="arc-fill" data-daylight-fill d=""/>
                                    <g class="arc-sun-marker" data-daylight-marker transform="translate(18,92)">
                                        <circle r="5"/>
                                        <path d="M0,-9 L0,-6.3 M0,6.3 L0,9 M-9,0 L-6.3,0 M6.3,0 L9,0 M-6.4,-6.4 L-4.5,-4.5 M4.5,4.5 L6.4,6.4 M-6.4,6.4 L-4.5,4.5 M4.5,-4.5 L6.4,-6.4"/>
                                    </g>
                                </svg>
                                <div class="sun-arc-readout">
                                    <div class="hero-wx-temp"><span data-wx-temp>&mdash;</span>&deg;</div>
                                    <div class="hero-wx-label-row">
                                        <span class="hero-wx-icon-inline" data-wx-icon>%s</span>
                                        <span data-wx-label>Loading&hellip;</span>
                                    </div>
                                </div>
                            </div>
                            <div class="daylight-labels">
                                <span data-wx-sunrise>&mdash;</span>
                                <span data-wx-sunset>&mdash;</span>
                            </div>
                        </div>

                        <div class="stat-stack">
                            <div class="stat-card stat-card-uv tint-warn" data-target="weather" role="button" tabindex="0">
                                <div class="stat-card-uv-top">
                                    <span class="stat-icon-badge">%s</span>
                                    <span class="stat-label">UV Index</span>
                                    <span class="uv-number" data-wx-uv>&mdash;</span>
                                    <span class="stat-aside" data-wx-uv-tip></span>
                                </div>
                                <div class="uv-scale mini"><span class="uv-marker" data-wx-uv-marker></span></div>
                            </div>
                            <div class="stat-card tint-accent" data-tide-tile data-target="weather" role="button" tabindex="0" hidden>
                                <span class="stat-icon-badge">%s</span>
                                <div class="stat-body">
                                    <span class="stat-label">Tide</span>
                                    <span class="stat-value" data-tide-next>&mdash;</span>
                                    <span class="stat-note" data-tide-following></span>
                                </div>
                            </div>
                            <div class="stat-card tint-sand" data-target="calendar" role="button" tabindex="0">
                                <span class="stat-icon-badge">%s</span>
                                <div class="stat-body">
                                    <span class="stat-label">Today</span>
                                    <div class="today-ticker" data-today-ticker>
                                        %s
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>

                    <div class="wx-chart-card" data-target="weather" role="button" tabindex="0">
                        <svg class="wx-chart" data-wx-chart viewBox="0 0 720 160"></svg>
                    </div>

                    <svg class="beach-footer" viewBox="0 0 800 80" preserveAspectRatio="none" aria-hidden="true">
                        <path class="wave-back" d="M0,40 C100,10 200,60 300,35 C400,10 500,55 600,30 C700,10 750,35 800,25 L800,80 L0,80 Z"/>
                        <path class="wave-front" d="M0,55 C120,30 220,65 340,45 C460,25 560,60 680,40 C720,32 760,42 800,38 L800,80 L0,80 Z"/>
                    </svg>
                </section>
                """.formatted(icon("weather", "wx-icon"), icon("weather"), icon("waves"), icon("calendar"), todaySlides());
    }

    private static String todaySlides() {
        return todaySlide("Beach Cleanup", "9:00 AM", true)
                + todaySlide("Grocery Pickup", "12:30 PM", false)
                + todaySlide("Family Dinner", "6:00 PM", false);
    }

    private static String todaySlide(String title, String time, boolean active) {
        return """
                <div class="today-slide%s">
                    <span class="stat-value">%s</span>
                    <span class="stat-note">%s</span>
                </div>
                """.formatted(active ? " is-active" : "", title, time);
    }

    /* ======================================================================
       Weather — detail
       ====================================================================== */

    private static String weatherPage() {
        return """
                <section class="dashboard-page" data-page="weather">
                    <div class="wx-layout">
                        <div class="wx-sidebar">
                            <div class="detail-card wx-hero-card" data-wx-current>
                                <p class="wx-hero-place">Manasquan, NJ</p>
                                <span class="wx-hero-icon" data-wx-icon>%s</span>
                                <div class="wx-hero-temp"><span data-wx-temp>&mdash;</span>&deg;</div>
                                <div class="wx-hero-label" data-wx-label>Loading&hellip;</div>
                                <div class="wx-hero-range">H:<span data-wx-hi>&mdash;</span>&deg;&nbsp;&nbsp;L:<span data-wx-lo>&mdash;</span>&deg;</div>
                                <span class="page-sub" data-wx-updated>Loading&hellip;</span>
                            </div>
                            %s
                            %s
                        </div>

                        <div class="wx-main">
                            <div class="card wx-main-hourly">
                                <div class="card-head"><h3>Hourly Forecast</h3></div>
                                <svg class="wx-chart" data-wx-chart viewBox="0 0 720 170"></svg>
                            </div>

                            <div class="card wx-main-daily">
                                <div class="card-head"><h3>7-Day Forecast</h3></div>
                                <div class="daily-list" data-wx-daily></div>
                            </div>

                            <div class="card wx-radar-card">
                                <div class="radar-wrap">
                                    <div class="radar-map" data-radar-map></div>
                                    <div class="radar-overlay-bar">
                                        <button type="button" class="icon-button" data-radar-play title="Play/Pause">%s</button>
                                        <input type="range" min="0" max="0" value="0" data-radar-slider>
                                        <span class="radar-time-overlay" data-radar-time></span>
                                    </div>
                                </div>
                            </div>

                            <div class="wx-marine-strip">
                                <span>%sWater <strong data-marine-water>&mdash;</strong></span>
                                <span>%sWaves <strong data-marine-wave>&mdash;</strong></span>
                                <span data-tide-tile hidden>%s<strong data-tide-next>&mdash;</strong></span>
                            </div>
                        </div>
                    </div>
                </section>
                """.formatted(
                        icon("weather", "wx-icon"), sunDetailCard(), uvDetailCard(),
                        icon("play"),
                        icon("waves"), icon("waves"), icon("waves"));
    }

    private static String uvDetailCard() {
        return """
                <div class="detail-card">
                    <div class="detail-head">%sUV Index</div>
                    <div class="detail-value" data-wx-uv>&mdash;</div>
                    <div class="uv-scale"><span class="uv-marker" data-wx-uv-marker></span></div>
                    <p class="detail-note" data-wx-uv-note></p>
                </div>
                """.formatted(icon("weather", "detail-icon"));
    }

    private static String sunDetailCard() {
        return """
                <div class="detail-card">
                    <div class="detail-head">%sSunrise &amp; Sunset</div>
                    <div class="sun-arc mini">
                        <svg viewBox="0 0 200 100" aria-hidden="true">
                            <path class="arc-track" d="M18,92 A82,82 0 0 1 182,92"/>
                            <path class="arc-fill" data-daylight-fill d=""/>
                            <g class="arc-sun-marker" data-daylight-marker transform="translate(18,92)">
                                <circle r="5"/>
                                <path d="M0,-9 L0,-6.3 M0,6.3 L0,9 M-9,0 L-6.3,0 M6.3,0 L9,0 M-6.4,-6.4 L-4.5,-4.5 M4.5,4.5 L6.4,6.4 M-6.4,6.4 L-4.5,4.5 M4.5,-4.5 L6.4,-6.4"/>
                            </g>
                        </svg>
                    </div>
                    <div class="detail-split">
                        <div><span class="detail-split-label">%sSunrise</span><strong data-wx-sunrise>&mdash;</strong></div>
                        <div><span class="detail-split-label">%sSunset</span><strong data-wx-sunset>&mdash;</strong></div>
                    </div>
                    <p class="detail-note" data-daylight-status></p>
                </div>
                """.formatted(icon("sunrise", "detail-icon"), icon("sunrise"), icon("sunset"));
    }

    /* ======================================================================
       Lights — four fan/light combos
       ====================================================================== */

    private static String lightsPage() {
        String rooms = roomCard("living", "Living Room", true, 70, true, 2)
                + roomCard("primary", "Primary Suite", true, 45, false, 1)
                + roomCard("bunk", "Bunk Room", false, 0, false, 1)
                + roomCard("porch", "Screened Porch", true, 85, true, 1);

        return """
                <section class="dashboard-page" data-page="lights">
                    <div class="page-head">
                        <div>
                            <p class="eyebrow">Lights &amp; Fans</p>
                            <h1>Room Controls</h1>
                        </div>
                        <span class="page-sub">Not yet connected &middot; will control Home Assistant devices</span>
                    </div>
                    <div class="quick-actions">
                        <button type="button" data-toggle-active>%sAll Lights On</button>
                        <button type="button" class="ghost-button" data-toggle-active>%sAll Lights Off</button>
                        <button type="button" class="ghost-button" data-toggle-active>%sNight Mode</button>
                    </div>
                    <div class="tile-grid" style="grid-template-columns:repeat(auto-fit,minmax(280px,1fr))">
                        %s
                    </div>
                </section>
                """.formatted(icon("plus"), icon("minus"), icon("moon"), rooms);
    }

    private static String roomCard(String id, String room, boolean lightOn, int brightness, boolean fanOn, int fanSpeed) {
        return """
                <div class="card room-card">
                    <div class="card-head">
                        <h3>%s</h3>
                        <span class="icon-badge">%s</span>
                    </div>
                    <div class="control-row">
                        <span class="row-label">%sLight</span>
                        <label class="switch">
                            <input type="checkbox" %s>
                            <span class="switch-track"></span>
                        </label>
                    </div>
                    <div class="slider-row">
                        <span class="slider-label">Brightness</span>
                        <input type="range" min="0" max="100" value="%d" data-slider="%s-bright">
                        <span class="slider-value" data-slider-value="%s-bright" data-unit="%%">%d%%</span>
                    </div>
                    <div class="control-row">
                        <span class="row-label">%sFan</span>
                        <label class="switch">
                            <input type="checkbox" %s>
                            <span class="switch-track"></span>
                        </label>
                    </div>
                    <div class="control-row">
                        <span class="row-label">Speed</span>
                        <div class="fan-speed-dots" data-speed-group>
                            <button type="button" class="%s">1</button>
                            <button type="button" class="%s">2</button>
                            <button type="button" class="%s">3</button>
                        </div>
                    </div>
                    <div class="control-row">
                        <span class="row-label">Direction</span>
                        <button type="button" class="icon-button" data-toggle-active title="Reverse rotation">%s</button>
                    </div>
                </div>
                """.formatted(
                        room, icon("fan"),
                        icon("lights"), lightOn ? "checked" : "",
                        brightness, id, id, brightness,
                        icon("fan"), fanOn ? "checked" : "",
                        fanSpeed == 1 ? "is-selected" : "", fanSpeed == 2 ? "is-selected" : "", fanSpeed == 3 ? "is-selected" : "",
                        icon("auto"));
    }

    /* ======================================================================
       Climate — outdoor fridge monitor + main thermostat
       ====================================================================== */

    private static String climatePage() {
        return """
                <section class="dashboard-page" data-page="climate">
                    <div class="page-head">
                        <div>
                            <p class="eyebrow">Climate</p>
                            <h1>Temperature</h1>
                        </div>
                        <span class="page-sub">Not yet connected &middot; will control Home Assistant devices</span>
                    </div>
                    <div class="tile-grid" style="grid-template-columns:repeat(auto-fit,minmax(300px,1fr))">
                        <div class="card">
                            <div class="card-head">
                                <h3>Outdoor Fridge</h3>
                                <span class="icon-badge">%s</span>
                            </div>
                            <div class="fridge-readout">
                                <span class="big-temp">38&deg;F</span>
                                <span class="pill pill-good">Normal</span>
                            </div>
                            <p class="page-sub">24h range 36&deg;&ndash;41&deg; &middot; alert above 45&deg;F</p>
                            <svg class="sparkline" viewBox="0 0 200 56" preserveAspectRatio="none">
                                <path class="spark-fill" d="M0,34 C20,30 40,38 60,32 C80,24 100,36 120,30 C140,22 160,28 180,24 L200,26 L200,56 L0,56 Z"/>
                                <path d="M0,34 C20,30 40,38 60,32 C80,24 100,36 120,30 C140,22 160,28 180,24 L200,26"/>
                            </svg>
                        </div>
                        <div class="card">
                            <div class="card-head">
                                <h3>Main Thermostat</h3>
                                <span class="icon-badge">%s</span>
                            </div>
                            <div class="thermo-dial">
                                <div class="thermo-ring" data-thermo-ring="main" style="--pct:56">
                                    <div class="thermo-readout">
                                        <strong data-stepper-target="main">74&deg;</strong>
                                        <span>Target</span>
                                    </div>
                                </div>
                                <div>
                                    <div class="stepper" data-stepper="main" data-min="60" data-max="85" data-value="74">
                                        <button type="button" data-step="down">%s</button>
                                        <span class="stepper-value" data-stepper-target="main">74&deg;</span>
                                        <button type="button" data-step="up">%s</button>
                                    </div>
                                    <p class="page-sub" style="margin-top:10px">Currently 76&deg;F inside</p>
                                </div>
                            </div>
                            <div class="segmented" data-segmented style="margin-top:16px">
                                <button type="button" class="is-selected">%sAuto</button>
                                <button type="button">%sCool</button>
                                <button type="button">%sHeat</button>
                                <button type="button">%sOff</button>
                            </div>
                        </div>
                    </div>
                </section>
                """.formatted(
                        icon("climate"), icon("climate"),
                        icon("minus"), icon("plus"),
                        icon("auto"), icon("snow"), icon("flame"), icon("power"));
    }

    /* ======================================================================
       Security — cameras, locks, garage, alarm
       ====================================================================== */

    private static String securityPage() {
        String cameras = cameraTile("Driveway") + cameraTile("Front Door") + cameraTile("Back Deck") + cameraTile("Dock Path");
        String locks = lockItem("Front Door", true) + lockItem("Back Door", true) + lockItem("Slider", false);

        return """
                <section class="dashboard-page" data-page="security">
                    <div class="page-head">
                        <div>
                            <p class="eyebrow">Security</p>
                            <h1>Cameras &amp; Locks</h1>
                        </div>
                        <span class="page-sub">Not yet connected &middot; will control Home Assistant devices</span>
                    </div>
                    <div class="card">
                        <div class="card-head"><h3>Cameras</h3></div>
                        <div class="camera-grid">%s</div>
                    </div>
                    <div class="tile-grid" style="grid-template-columns:repeat(auto-fit,minmax(280px,1fr))">
                        <div class="card">
                            <div class="card-head"><h3>Doors</h3></div>
                            <div class="lock-grid">%s</div>
                        </div>
                        <div class="card">
                            <div class="card-head">
                                <h3>Garage</h3>
                                <span class="icon-badge">%s</span>
                            </div>
                            <p class="page-sub" style="margin-bottom:14px">Currently closed</p>
                            <div class="segmented" data-segmented>
                                <button type="button" class="is-selected">Close</button>
                                <button type="button">Open</button>
                            </div>
                        </div>
                        <div class="card">
                            <div class="card-head">
                                <h3>Alarm</h3>
                                <span class="icon-badge">%s</span>
                            </div>
                            <div class="segmented" data-segmented>
                                <button type="button">Off</button>
                                <button type="button" class="is-selected">Home</button>
                                <button type="button">Away</button>
                            </div>
                        </div>
                    </div>
                </section>
                """.formatted(cameras, locks, icon("garage"), icon("security"));
    }

    private static String cameraTile(String label) {
        return """
                <div class="camera-tile" data-toggle-active tabindex="0" role="button" aria-label="View %s camera">
                    <span class="cam-icon">%s</span>
                    <span class="cam-live">Live</span>
                    <span class="cam-label">%s</span>
                </div>
                """.formatted(label, icon("camera"), label);
    }

    private static String lockItem(String label, boolean locked) {
        return """
                <div class="lock-item">
                    <span>%s</span>
                    <button type="button" class="icon-button lock-toggle %s" data-toggle-active title="Toggle lock">%s</button>
                    <span class="pill %s">%s</span>
                </div>
                """.formatted(
                        label, locked ? "is-active" : "", icon(locked ? "lock" : "unlock"),
                        locked ? "pill-good" : "pill-warn", locked ? "Locked" : "Unlocked");
    }

    /* ======================================================================
       Media & Scenes — Apple TV, Music Assistant, one-tap scenes
       ====================================================================== */

    private static String mediaPage() {
        String scenes = sceneButton("Good Morning", "coffee")
                + sceneButton("Movie Night", "movie")
                + sceneButton("Beach Day", "umbrella")
                + sceneButton("Bedtime", "moon")
                + sceneButton("Party Mode", "party")
                + sceneButton("Away", "security");

        return """
                <section class="dashboard-page" data-page="media">
                    <div class="page-head">
                        <div>
                            <p class="eyebrow">Media &amp; Scenes</p>
                            <h1>Entertainment</h1>
                        </div>
                        <span class="page-sub">Not yet connected &middot; will control Home Assistant devices</span>
                    </div>
                    <div class="tile-grid" style="grid-template-columns:repeat(auto-fit,minmax(280px,1fr))">
                        <div class="card remote-card">
                            <div class="card-head" style="width:100%%"><h3>Apple TV</h3><span class="icon-badge">%s</span></div>
                            <div class="dpad">
                                <button type="button" class="dpad-up" data-toggle-active>%s</button>
                                <button type="button" class="dpad-left" data-toggle-active>%s</button>
                                <button type="button" class="dpad-select" data-toggle-active>Select</button>
                                <button type="button" class="dpad-right" data-toggle-active>%s</button>
                                <button type="button" class="dpad-down" data-toggle-active>%s</button>
                            </div>
                            <div class="remote-buttons">
                                <button type="button" class="icon-button" data-toggle-active title="Menu">%s</button>
                                <button type="button" class="icon-button" data-toggle-active title="Play/Pause">%s</button>
                                <button type="button" class="icon-button" data-toggle-active title="Power">%s</button>
                            </div>
                        </div>
                        <div class="card">
                            <div class="card-head"><h3>Music Assistant</h3><span class="icon-badge">%s</span></div>
                            <div class="now-playing">
                                <div class="album-art">%s</div>
                                <div>
                                    <div class="track-title">Coastal Breeze</div>
                                    <div class="track-artist">Weekend Radio &middot; Living Room</div>
                                    <div class="progress-bar"><span style="width:38%%"></span></div>
                                </div>
                            </div>
                            <div class="transport-row">
                                <button type="button" class="icon-button" data-toggle-active title="Previous">%s</button>
                                <button type="button" class="icon-button play-pause" data-play-pause title="Play/Pause">%s</button>
                                <button type="button" class="icon-button" data-toggle-active title="Next">%s</button>
                                <div class="slider-row" style="flex:1;margin-left:6px">
                                    <span class="slider-label">%sVolume</span>
                                    <input type="range" min="0" max="100" value="55" data-slider="music-vol">
                                    <span class="slider-value" data-slider-value="music-vol" data-unit="%%">55%%</span>
                                </div>
                            </div>
                            <div class="control-row" style="margin-top:16px">
                                <span class="row-label">Room</span>
                                <select>
                                    <option>Living Room</option>
                                    <option>Primary Suite</option>
                                    <option>Porch</option>
                                    <option>Whole House</option>
                                </select>
                            </div>
                        </div>
                    </div>
                    <div class="card">
                        <div class="card-head"><h3>Scenes</h3></div>
                        <div class="scene-grid">%s</div>
                    </div>
                </section>
                """.formatted(
                        icon("tv"),
                        iconRotated("chevron", 0), iconRotated("chevron", -90), iconRotated("chevron", 90), iconRotated("chevron", 180),
                        icon("menu"), icon("play"), icon("power"),
                        icon("music"), icon("music"),
                        icon("prev"), icon("pause"), icon("next"), icon("volume"),
                        scenes);
    }

    private static String sceneButton(String label, String iconName) {
        return """
                <button type="button" class="scene-button" data-toggle-active>%s<span>%s</span></button>
                """.formatted(icon(iconName), label);
    }

    /* ======================================================================
       Calendar — family agenda
       ====================================================================== */

    private static String calendarPage() {
        String today = agendaItem("9:00 AM", "Beach Cleanup", "M", "#75d4f2")
                + agendaItem("12:30 PM", "Grocery Pickup", "D", "#d9bd79")
                + agendaItem("6:00 PM", "Family Dinner", "All", "#9be29b");

        String tomorrow = agendaItem("8:00 AM", "Surf Lesson", "K", "#f2a65a")
                + agendaItem("2:00 PM", "Dock Maintenance", "D", "#d9bd79");

        String weekend = agendaItem("Sat 10:00 AM", "Farmers Market", "M", "#75d4f2")
                + agendaItem("Sat 7:00 PM", "Game Night", "All", "#9be29b")
                + agendaItem("Sun 9:00 AM", "Boat Trip", "All", "#9be29b");

        return """
                <section class="dashboard-page" data-page="calendar">
                    <div class="page-head">
                        <div>
                            <p class="eyebrow">Calendar</p>
                            <h1>Family Schedule</h1>
                        </div>
                        <button type="button" data-toggle-active>%sAdd Event</button>
                    </div>
                    <div class="card">
                        <div class="agenda-group">
                            <p class="agenda-day">Today</p>
                            %s
                            <p class="agenda-day">Tomorrow</p>
                            %s
                            <p class="agenda-day">This Weekend</p>
                            %s
                        </div>
                    </div>
                </section>
                """.formatted(icon("plus"), today, tomorrow, weekend);
    }

    private static String agendaItem(String time, String title, String initial, String colorHex) {
        return """
                <div class="agenda-item">
                    <span class="agenda-time">%s</span>
                    <span class="agenda-title">%s</span>
                    <span class="member-dot" style="background:%s">%s</span>
                </div>
                """.formatted(time, title, colorHex, initial);
    }

    /* ======================================================================
       Theme — live switcher (pure front-end, persisted client-side)
       ====================================================================== */

    private static String themePage() {
        String swatches = themeSwatch("dusk", "Dusk", true)
                + themeSwatch("sunrise", "Sunrise", false)
                + themeSwatch("midday", "Midday", false)
                + themeSwatch("sunset", "Sunset", false)
                + themeSwatch("night", "Night", false)
                + themeSwatch("autumn", "Autumn", false)
                + themeSwatch("holiday", "Holiday", false)
                + themeSwatch("winter", "Winter", false);

        return """
                <section class="dashboard-page" data-page="theme">
                    <div class="page-head">
                        <div>
                            <p class="eyebrow">Appearance</p>
                            <h1>Theme</h1>
                        </div>
                        <span class="page-sub">Pick a mood &middot; saved on this device</span>
                    </div>
                    <div class="card">
                        <div class="card-head"><h3>Choose a theme</h3></div>
                        <div class="theme-grid">%s</div>
                    </div>
                </section>
                """.formatted(swatches);
    }

    private static String themeSwatch(String key, String label, boolean selected) {
        return """
                <button type="button" class="theme-swatch%s" data-theme-choice="%s">
                    <span class="swatch-preview" data-theme="%s" style="background:linear-gradient(135deg,var(--bg-a),var(--bg-c) 55%%,var(--sand))"></span>
                    <span class="swatch-name">%s<span class="swatch-check">%s</span></span>
                </button>
                """.formatted(selected ? " is-selected" : "", key, key, label, icon("check"));
    }

    /* ======================================================================
       Icons — small inline SVG set, no external assets
       ====================================================================== */

    private static final Map<String, String> ICONS = Map.ofEntries(
            Map.entry("home", "<path d=\"M4 11.5 12 4l8 7.5\"/><path d=\"M6 10.5V20h5v-6h2v6h5v-9.5\"/>"),
            Map.entry("weather", "<circle cx=\"12\" cy=\"9\" r=\"3.4\"/><path d=\"M12 2.8v1.8M12 13.6v1.8M5.5 9h1.8M16.7 9h1.8M7.4 4.4l1.3 1.3M15.6 4.4l-1.3 1.3M7.4 13.6l1.3-1.3M15.6 13.6l-1.3-1.3\"/><path d=\"M6.5 21a3.6 3.6 0 0 1 .4-7.2 4.6 4.6 0 0 1 8.7 1.4A3.1 3.1 0 0 1 15.2 21Z\"/>"),
            Map.entry("lights", "<path d=\"M9 18h6M10 21h4\"/><path d=\"M12 3a6 6 0 0 0-3.2 11.1c.6.5 1 1.2 1 2h4.4c0-.8.4-1.5 1-2A6 6 0 0 0 12 3Z\"/>"),
            Map.entry("climate", "<rect x=\"10\" y=\"3\" width=\"4\" height=\"11\" rx=\"2\"/><circle cx=\"12\" cy=\"17\" r=\"3.4\"/>"),
            Map.entry("security", "<path d=\"M12 3l7 3v5c0 5-3.4 7.8-7 9-3.6-1.2-7-4-7-9V6l7-3Z\"/><path d=\"M9 12l2 2 4-4.5\"/>"),
            Map.entry("media", "<circle cx=\"12\" cy=\"12\" r=\"8.5\"/><path d=\"M10 8.3v7.4l6-3.7Z\"/>"),
            Map.entry("calendar", "<rect x=\"4\" y=\"5\" width=\"16\" height=\"15\" rx=\"2\"/><path d=\"M4 9.5h16M8 3v4M16 3v4\"/>"),
            Map.entry("theme", "<path d=\"M12 3a9 9 0 1 0 0 18c1.1 0 1.9-.9 1.9-1.9 0-.5-.2-.9-.5-1.2-.3-.3-.4-.7-.4-1.1 0-.9.7-1.5 1.6-1.5H16a4 4 0 0 0 4-4c0-4.6-3.9-8.3-8-8.3Z\"/><circle cx=\"7.6\" cy=\"10.6\" r=\"1\"/><circle cx=\"10.4\" cy=\"7.4\" r=\"1\"/><circle cx=\"15\" cy=\"8\" r=\"1\"/><circle cx=\"16.4\" cy=\"12.2\" r=\"1\"/>"),
            Map.entry("logout", "<path d=\"M9 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h3\"/><path d=\"M14 8l4 4-4 4M18 12H9\"/>"),
            Map.entry("fan", "<circle cx=\"12\" cy=\"12\" r=\"1.6\"/><path d=\"M12 12c0-3.2 1-6.4 4-7.4 2.1-.7 3.2 1 2.1 3.1-1.1 2.2-3.6 3.7-6.1 4.3Z\"/><path d=\"M12 12c-3.2 0-6.2-1.2-7.2-4.2-.7-2.1 1-3.2 3.1-2.1 2.2 1.1 3.7 3.6 4.1 6.3Z\"/><path d=\"M12 12c2.9.3 5.6 1.7 6.8 4.5.9 2-1 3-3 1.8-2-1.2-3.3-3.7-3.8-6.3Z\"/>"),
            Map.entry("lock", "<rect x=\"6\" y=\"11\" width=\"12\" height=\"9\" rx=\"2\"/><path d=\"M9 11V8a3 3 0 0 1 6 0v3\"/>"),
            Map.entry("unlock", "<rect x=\"6\" y=\"11\" width=\"12\" height=\"9\" rx=\"2\"/><path d=\"M9 11V8a3 3 0 0 1 5.7-1.4\"/>"),
            Map.entry("camera", "<rect x=\"3\" y=\"7\" width=\"13\" height=\"10\" rx=\"2\"/><path d=\"M16 10.2 21 8v8l-5-2.2Z\"/><circle cx=\"9\" cy=\"12\" r=\"2.3\"/>"),
            Map.entry("garage", "<rect x=\"4\" y=\"6\" width=\"16\" height=\"13\" rx=\"1.5\"/><path d=\"M4 10.2h16M4 14.4h16\"/>"),
            Map.entry("flame", "<path d=\"M12 3c1 3-3 4.3-3 8.3a3 3 0 1 0 6 0c0-1-1-1.6-1-3 1 1.2 2 3.1 2 5.1a4 4 0 1 1-8 0C8 9 11 7 12 3Z\"/>"),
            Map.entry("snow", "<path d=\"M12 3v18M6 6l12 12M18 6 6 18M3 12h18\"/>"),
            Map.entry("auto", "<path d=\"M4.5 12a7.5 7.5 0 0 1 13-5M19.5 12a7.5 7.5 0 0 1-13 5\"/><path d=\"M17 4v3.5h-3.5M7 20v-3.5h3.5\"/>"),
            Map.entry("power", "<path d=\"M12 3v8\"/><path d=\"M6.3 6.3a8 8 0 1 0 11.4 0\"/>"),
            Map.entry("play", "<path d=\"M9 6.5v11l9-5.5Z\"/>"),
            Map.entry("pause", "<rect x=\"7.5\" y=\"6\" width=\"3.2\" height=\"12\" rx=\"1\"/><rect x=\"13.3\" y=\"6\" width=\"3.2\" height=\"12\" rx=\"1\"/>"),
            Map.entry("prev", "<path d=\"M15.5 6 7 12l8.5 6Z\"/><rect x=\"5\" y=\"6\" width=\"2\" height=\"12\" rx=\"0.6\"/>"),
            Map.entry("next", "<path d=\"M8.5 6 17 12l-8.5 6Z\"/><rect x=\"17\" y=\"6\" width=\"2\" height=\"12\" rx=\"0.6\"/>"),
            Map.entry("volume", "<path d=\"M4 9.5v5h3.4l4.6 3.8V5.7L7.4 9.5Z\"/><path d=\"M16.5 9.2a4 4 0 0 1 0 5.6\"/>"),
            Map.entry("chevron", "<path d=\"M6 15l6-6 6 6\"/>"),
            Map.entry("menu", "<path d=\"M5 7h14M5 12h14M5 17h14\"/>"),
            Map.entry("waves", "<path d=\"M2 15c1.6-1.8 3.4-1.8 5 0s3.4 1.8 5 0 3.4-1.8 5 0 3.4 1.8 5 0\"/><path d=\"M2 19c1.6-1.8 3.4-1.8 5 0s3.4 1.8 5 0 3.4-1.8 5 0 3.4 1.8 5 0\"/>"),
            Map.entry("check", "<path d=\"M5 12.5l4.5 4.5L19 7\"/>"),
            Map.entry("plus", "<path d=\"M12 5v14M5 12h14\"/>"),
            Map.entry("minus", "<path d=\"M5 12h14\"/>"),
            Map.entry("music", "<path d=\"M9 18V5.2L20 3v12.8\"/><circle cx=\"6.5\" cy=\"18\" r=\"2.5\"/><circle cx=\"17.5\" cy=\"15.8\" r=\"2.5\"/>"),
            Map.entry("tv", "<rect x=\"3\" y=\"5\" width=\"18\" height=\"12\" rx=\"2\"/><path d=\"M9 21h6\"/>"),
            Map.entry("sunrise", "<path d=\"M4 18h16\"/><path d=\"M6 18a6 6 0 0 1 12 0\"/><path d=\"M12 8V5M6.5 10 5 8.4M17.5 10 19 8.4\"/>"),
            Map.entry("sunset", "<path d=\"M4 18h16\"/><path d=\"M6 15a6 6 0 0 1 12 0\"/><path d=\"M12 12V9M6.5 10 5 11.6M17.5 10 19 11.6\"/>"),
            Map.entry("coffee", "<path d=\"M4 9h13v4a5 5 0 0 1-5 5H9a5 5 0 0 1-5-5Z\"/><path d=\"M17 10h1.5a2 2 0 0 1 0 4H17\"/><path d=\"M7 4c0 1-1 1-1 2M11 4c0 1-1 1-1 2\"/>"),
            Map.entry("movie", "<rect x=\"3\" y=\"6\" width=\"18\" height=\"13\" rx=\"2\"/><path d=\"M3 10h18M7 6 5 10M13 6l-2 4M19 6l-2 4\"/>"),
            Map.entry("umbrella", "<path d=\"M12 3a9 9 0 0 1 9 9H3a9 9 0 0 1 9-9Z\"/><path d=\"M12 12v7a2 2 0 0 1-3.5 1.3M12 3v-1\"/>"),
            Map.entry("moon", "<path d=\"M20 14.5A8.5 8.5 0 1 1 9.5 4a7 7 0 0 0 10.5 10.5Z\"/>"),
            Map.entry("party", "<path d=\"M4 20 14 4l1.5 3L7 20Z\"/><circle cx=\"17\" cy=\"6\" r=\"1\"/><circle cx=\"19\" cy=\"10\" r=\"1\"/><circle cx=\"15\" cy=\"9\" r=\"1\"/>"));

    private static String icon(String name) {
        return icon(name, null);
    }

    private static String icon(String name, String extraClass) {
        String inner = ICONS.getOrDefault(name, "");
        String cls = (extraClass == null || extraClass.isBlank()) ? "" : " class=\"" + extraClass + "\"";
        return "<svg" + cls + " viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" "
                + "stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">" + inner + "</svg>";
    }

    private static String iconRotated(String name, int degrees) {
        return "<span style=\"display:inline-flex;transform:rotate(" + degrees + "deg)\">" + icon(name) + "</span>";
    }
}
