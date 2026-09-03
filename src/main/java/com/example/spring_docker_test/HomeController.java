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
                + securityPage()
                + mediaPage()
                + calendarPage()
                + kitchenPage()
                + groceryPage()
                + themePage();

        String body = """
                <meta name="csrf-token" content="%s">
                <meta name="csrf-header" content="%s">
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
                <div class="modal" data-modal hidden>
                    <div class="modal-backdrop" data-modal-close></div>
                    <div class="modal-card">
                        <div class="modal-head">
                            <p class="modal-title" data-modal-title></p>
                            <button type="button" class="icon-button" data-modal-close title="Close">%s</button>
                        </div>
                        <div class="modal-body" data-modal-body></div>
                    </div>
                </div>
                <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
                <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                <script src="/js/dashboard.js"></script>
                """.formatted(
                        csrfToken.getToken(), csrfToken.getHeaderName(),
                        pages, navItems(), csrfToken.getParameterName(), csrfToken.getToken(), icon("logout"), icon("close"));

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
                + navItem("security", "Cameras", false, "camera")
                + navItem("media", "Music", false)
                + navItem("calendar", "Calendar", false)
                + navItem("kitchen", "Kitchen", false)
                + navItem("grocery", "Grocery", false, "basket")
                + navItem("theme", "Theme", false);
    }

    private static String navItem(String target, String label, boolean active) {
        return navItem(target, label, active, target);
    }

    private static String navItem(String target, String label, boolean active, String iconKey) {
        return """
                <button type="button" class="nav-item%s" data-target="%s"><span class="nav-icon-badge">%s</span><span class="nav-label">%s</span></button>
                """.formatted(active ? " is-active" : "", target, icon(iconKey), label);
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
                        <div class="home-bento-main">
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

                            <div class="stat-card tint-success" data-dinner-tile data-target="kitchen" role="button" tabindex="0" hidden>
                                <span class="stat-icon-badge">%s</span>
                                <div class="stat-body">
                                    <span class="stat-label">Dinner</span>
                                    <span class="stat-value" data-dinner-name>&mdash;</span>
                                    <span class="stat-note" data-dinner-note></span>
                                </div>
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
                                    <div class="today-ticker" data-today-ticker></div>
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

                    <div class="home-timers-widget" data-home-timers hidden>
                        <div class="home-timers-head"><span class="icon-badge">%s</span><span>Timers</span></div>
                        <div class="home-timers-list"></div>
                    </div>

                    <div class="home-air-alert" data-home-air-alert hidden>
                        <span class="icon-badge">%s</span>
                        <div class="home-air-alert-body">
                            <span class="home-air-alert-title" data-home-air-title></span>
                            <span class="home-air-alert-note" data-home-air-note></span>
                        </div>
                    </div>
                </section>
                """.formatted(icon("weather", "wx-icon"), icon("kitchen"), icon("weather"), icon("waves"),
                        icon("calendar"), icon("timer"), icon("warn"));
    }

    /* ======================================================================
       Weather — detail
       ====================================================================== */

    private static String weatherPage() {
        return """
                <section class="dashboard-page" data-page="weather">
                    <div class="wx-layout">
                        <div class="wx-sidebar">
                            <div class="detail-card wx-sidebar-combined" data-wx-current>
                                <p class="wx-hero-place">Manasquan, NJ</p>
                                <span class="wx-hero-icon" data-wx-icon>%s</span>
                                <div class="wx-hero-temp"><span data-wx-temp>&mdash;</span>&deg;</div>
                                <div class="wx-hero-label" data-wx-label>Loading&hellip;</div>
                                <div class="wx-hero-range">H:<span data-wx-hi>&mdash;</span>&deg;&nbsp;&nbsp;L:<span data-wx-lo>&mdash;</span>&deg;</div>

                                <div class="wx-sidebar-divider"></div>

                                <div class="detail-head">%sUV Index</div>
                                <div class="wx-uv-row">
                                    <div class="detail-value" data-wx-uv>&mdash;</div>
                                    <div class="uv-scale"><span class="uv-marker" data-wx-uv-marker></span></div>
                                </div>

                                <div class="wx-sidebar-tide" data-tide-tile hidden>
                                    <div class="wx-sidebar-divider"></div>
                                    <div class="detail-head">%sTide</div>
                                    <div class="wx-tide-value" data-tide-next>&mdash;</div>
                                    <p class="detail-note" data-tide-following></p>
                                </div>

                                <div class="wx-sidebar-divider"></div>

                                <div class="wx-sidebar-marine">
                                    <span>%sWater <strong data-marine-water>&mdash;</strong></span>
                                    <span>%sWaves <strong data-marine-wave>&mdash;</strong></span>
                                </div>
                                <div class="wx-sidebar-marine wx-sidebar-aqi-row">
                                    <span>Air Quality <strong data-wx-aqi>&mdash;</strong></span>
                                </div>
                            </div>
                        </div>

                        <div class="wx-main">
                            <div class="card wx-main-daily">
                                <div class="card-head"><h3>7-Day Forecast</h3></div>
                                <div class="daily-list" data-wx-daily></div>
                            </div>

                            <div class="card wx-radar-card">
                                <div class="radar-wrap">
                                    <div class="radar-map" data-radar-map></div>
                                    <div class="radar-overlay-bar">
                                        <button type="button" class="icon-button" data-radar-play title="Play/Pause">%s</button>
                                        <input type="range" min="0" max="0" value="0" step="any" data-radar-slider>
                                        <span class="radar-time-overlay" data-radar-time></span>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                </section>
                """.formatted(
                        icon("weather", "wx-icon"), icon("weather", "detail-icon"), icon("waves", "detail-icon"),
                        icon("waves"), icon("waves"),
                        icon("play"));
    }

    /* ======================================================================
       Lights — four fan/light combos
       ====================================================================== */

    private static String lightsPage() {
        String fans = fanCard("kitchen", "Kitchen Fan", 0, 0, 0)
                + fanCard("bunkbed", "Bunkbed Fan", 0, 0, 0)
                + fanCard("double", "Double Beds Fan", 0, 0, 0)
                + fanCard("living", "Living Room Fan", 0, 0, 0)
                + fenceLightsCard();

        return """
                <section class="dashboard-page" data-page="lights">
                    <div class="page-head">
                        <div>
                            <p class="eyebrow">Lights &amp; Fans</p>
                            <h1>Ceiling Fans</h1>
                        </div>
                        <span class="page-sub">Connected via Home Assistant</span>
                    </div>
                    <div class="tile-grid" style="grid-template-columns:repeat(auto-fit,minmax(240px,1fr))">
                        %s
                    </div>
                </section>
                """.formatted(fans);
    }

    private static String fenceLightsCard() {
        return """
                <button type="button" class="card fan-card fence-card" data-fence-toggle aria-pressed="false">
                    <div class="card-head">
                        <h3>Fence Lights</h3>
                        <span class="icon-badge">%s</span>
                    </div>
                    <div class="fan-card-status">
                        <span class="fan-status-chip" data-fence-chip>%s<span>Off</span></span>
                    </div>
                    <span class="fence-status-note" data-fence-note hidden></span>
                </button>
                """.formatted(icon("lights"), icon("lights"));
    }

    private static String fanCard(String id, String name, int fanSpeed, int warmStage, int coolStage) {
        return """
                <button type="button" class="card fan-card" data-fan-card="%s" data-fan-name="%s"
                        data-fan-speed="%d" data-warm-stage="%d" data-cool-stage="%d">
                    <div class="card-head">
                        <h3>%s</h3>
                        <span class="icon-badge">%s</span>
                    </div>
                    <div class="fan-card-status">
                        <span class="fan-status-chip%s" data-chip="speed">%s<span>%s</span></span>
                        <span class="fan-status-chip chip-warm%s" data-chip="warm">%s<span>%s</span></span>
                        <span class="fan-status-chip chip-cool%s" data-chip="cool">%s<span>%s</span></span>
                    </div>
                </button>
                """.formatted(
                        id, escapeHtml(name), fanSpeed, warmStage, coolStage,
                        escapeHtml(name), icon("fan"),
                        fanSpeed > 0 ? " is-on" : "", icon("fan"), fanSpeed == 0 ? "Off" : "Speed " + fanSpeed,
                        warmStage > 0 ? " is-on" : "", icon("lights"), warmStage == 0 ? "Off" : "Warm " + warmStage,
                        coolStage > 0 ? " is-on" : "", icon("lights"), coolStage == 0 ? "Off" : "Cool " + coolStage);
    }

    /* ======================================================================
       Security — cameras, locks, garage, alarm
       ====================================================================== */

    private static String securityPage() {
        return """
                <section class="dashboard-page" data-page="security">
                    <div class="card camera-card">
                        <div class="camera-grid" data-camera-grid>
                            <p class="modal-empty">No cameras configured.</p>
                        </div>
                    </div>
                    <div class="camera-viewer" data-camera-viewer hidden>
                        <div class="camera-viewer-head">
                            <span data-camera-viewer-title></span>
                            <span class="camera-viewer-status" data-camera-viewer-status>Live</span>
                            <button type="button" class="icon-button" data-camera-viewer-close title="Close">%s</button>
                        </div>
                        <img class="camera-viewer-img" data-camera-viewer-img alt="">
                    </div>
                </section>
                """.formatted(icon("close"));
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
                            <p class="eyebrow">Music &amp; Scenes</p>
                            <h1>Music</h1>
                        </div>
                        <span class="page-sub">Not yet connected &middot; will control Home Assistant devices</span>
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
                    <div class="card">
                        <div class="card-head"><h3>Scenes</h3></div>
                        <div class="scene-grid">%s</div>
                    </div>
                </section>
                """.formatted(
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
        return """
                <section class="dashboard-page" data-page="calendar">
                    <div class="page-head">
                        <div>
                            <p class="eyebrow">Calendar</p>
                            <h1>Family Schedule</h1>
                        </div>
                    </div>
                    <div class="cal-card card">
                        <div class="cal-toolbar">
                            <button type="button" class="icon-button" data-cal-prev title="Previous">%s</button>
                            <button type="button" class="cal-title" data-cal-title></button>
                            <button type="button" class="icon-button" data-cal-next title="Next">%s</button>
                            <button type="button" class="cal-today-btn" data-cal-today>Today</button>
                        </div>
                        <div class="cal-weekdays" data-cal-weekdays></div>
                        <div class="cal-grid" data-calendar-grid></div>
                    </div>
                </section>
                """.formatted(iconRotated("chevron", -90), iconRotated("chevron", 90));
    }

    /* ======================================================================
       Kitchen — timers, the week's meals, a recipe launcher and the converter
       ====================================================================== */

    private static String kitchenPage() {
        return """
                <section class="dashboard-page" data-page="kitchen">
                    <div class="kitchen-grid">
                        <div class="card kitchen-timers-card">
                            <div class="card-head"><h3>Timers</h3><span class="icon-badge">%s</span></div>
                            <div class="timer-setup">
                                <div class="timer-wheel-row">
                                    <div class="timer-wheel-frame"></div>
                                    %s
                                    <span class="timer-wheel-unit">hr</span>
                                    %s
                                    <span class="timer-wheel-unit">min</span>
                                    %s
                                    <span class="timer-wheel-unit">sec</span>
                                </div>
                                <button type="button" class="timer-start-btn" data-timer-custom-start>Start</button>
                            </div>
                            <div class="timer-card-grid" data-timers-list>
                                <p class="modal-empty">No timers running. Set one above.</p>
                            </div>
                        </div>

                        <div class="card meal-week-card">
                            <div class="card-head"><h3>This Week</h3><span class="icon-badge">%s</span></div>
                            <div class="meal-week" data-meal-week></div>
                        </div>

                        <div class="card recipe-launch-card">
                            <div class="card-head"><h3>Recipes</h3><span class="icon-badge">%s</span></div>
                            <button type="button" class="recipe-launch-btn" data-recipe-browse>
                                <span class="recipe-launch-icon">%s</span>
                                <span>View Recipes</span>
                            </button>
                            <button type="button" class="recipe-pin-btn" data-recipe-pinned hidden>
                                <span class="recipe-pin-label">Pinned</span>
                                <span class="recipe-pin-name" data-recipe-pinned-name></span>
                            </button>
                        </div>

                        <div class="card kitchen-convert-card">
                            <div class="card-head"><h3>Convert</h3></div>
                            <div class="segmented convert-tabs" data-convert-tabs>
                                <button type="button" class="is-selected" data-convert-tab="volume">Volume</button>
                                <button type="button" data-convert-tab="weight">Weight</button>
                                <button type="button" data-convert-tab="temp">Temp</button>
                            </div>
                            %s
                        </div>
                    </div>
                </section>
                """.formatted(
                        icon("timer"),
                        timerWheel("hours", 24, false), timerWheel("minutes", 60, true), timerWheel("seconds", 60, true),
                        icon("calendar"), icon("kitchen"), icon("kitchen"),
                        convertPanel("volume", true,
                                new String[] {"tsp", "tbsp", "cup", "flOz", "pint", "quart", "gallon", "ml", "l"},
                                new String[] {"tsp", "tbsp", "cup", "fl oz", "pint", "quart", "gallon", "ml", "L"},
                                "cup", "flOz")
                                + convertPanel("weight", false,
                                        new String[] {"oz", "lb", "g", "kg"},
                                        new String[] {"oz", "lb", "g", "kg"},
                                        "oz", "g")
                                + convertPanel("temp", false,
                                        new String[] {"f", "c"},
                                        new String[] {"&deg;F", "&deg;C"},
                                        "f", "c"));
    }

    /* ======================================================================
       Grocery — its own page; the list is long enough to want the whole screen
       ====================================================================== */

    private static String groceryPage() {
        return """
                <section class="dashboard-page" data-page="grocery">
                    <div class="page-head">
                        <div>
                            <p class="eyebrow">Kitchen</p>
                            <h1>Grocery List</h1>
                        </div>
                        <button type="button" class="ghost-button grocery-clear" data-grocery-clear hidden>Clear ticked</button>
                    </div>
                    <div class="card grocery-card">
                        <form class="grocery-add" data-grocery-add>
                            <input type="text" placeholder="Add an item" aria-label="Add grocery item"
                                   autocomplete="off" data-grocery-input>
                            <button type="submit" class="ghost-button">Add</button>
                        </form>
                        <div class="grocery-list" data-grocery-list></div>
                    </div>
                </section>
                """;
    }

    private static String convertPanel(String category, boolean active, String[] values, String[] labels, String fromDefault, String toDefault) {
        return """
                <div class="convert-panel" data-convert-panel="%s"%s>
                    <div class="convert-row">
                        <input type="number" value="1" data-convert-input>
                        <select data-convert-from>%s</select>
                    </div>
                    <div class="convert-equals">=</div>
                    <div class="convert-row">
                        <output class="convert-result" data-convert-result></output>
                        <select data-convert-to>%s</select>
                    </div>
                </div>
                """.formatted(
                        category, active ? "" : " hidden",
                        convertOptions(values, labels, fromDefault),
                        convertOptions(values, labels, toDefault));
    }

    private static String convertOptions(String[] values, String[] labels, String selected) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            String sel = values[i].equals(selected) ? " selected" : "";
            sb.append("<option value=\"").append(values[i]).append("\"").append(sel).append(">")
                    .append(labels[i]).append("</option>");
        }
        return sb.toString();
    }

    private static String timerWheel(String unit, int count, boolean padded) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < count; i++) {
            items.append("<div class=\"timer-wheel-item\">")
                    .append(padded ? String.format("%02d", i) : String.valueOf(i))
                    .append("</div>");
        }
        return """
                <div class="timer-wheel" data-timer-wheel="%s">
                    <div class="timer-wheel-spacer"></div>
                    %s
                    <div class="timer-wheel-spacer"></div>
                </div>
                """.formatted(unit, items);
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
                + themeSwatch("winter", "Winter", false)
                + themeSwatch("live", "Live", false, "Colors shift automatically through the day");

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
        return themeSwatch(key, label, selected, "");
    }

    private static String themeSwatch(String key, String label, boolean selected, String title) {
        String titleAttr = title.isBlank() ? "" : " title=\"" + escapeHtml(title) + "\"";
        return """
                <button type="button" class="theme-swatch%s" data-theme-choice="%s"%s>
                    <span class="swatch-preview" data-theme="%s" style="background:linear-gradient(135deg,var(--bg-a),var(--bg-c) 55%%,var(--sand))"></span>
                    <span class="swatch-name">%s<span class="swatch-check">%s</span></span>
                </button>
                """.formatted(selected ? " is-selected" : "", key, titleAttr, key, label, icon("check"));
    }

    /* ======================================================================
       Icons — small inline SVG set, no external assets
       ====================================================================== */

    private static final Map<String, String> ICONS = Map.ofEntries(
            Map.entry("home", "<path d=\"M4 11.5 12 4l8 7.5\"/><path d=\"M6 10.5V20h5v-6h2v6h5v-9.5\"/>"),
            Map.entry("weather", "<circle cx=\"12\" cy=\"9\" r=\"3.4\"/><path d=\"M12 2.8v1.8M12 13.6v1.8M5.5 9h1.8M16.7 9h1.8M7.4 4.4l1.3 1.3M15.6 4.4l-1.3 1.3M7.4 13.6l1.3-1.3M15.6 13.6l-1.3-1.3\"/><path d=\"M6.5 21a3.6 3.6 0 0 1 .4-7.2 4.6 4.6 0 0 1 8.7 1.4A3.1 3.1 0 0 1 15.2 21Z\"/>"),
            Map.entry("lights", "<path d=\"M9 18h6M10 21h4\"/><path d=\"M12 3a6 6 0 0 0-3.2 11.1c.6.5 1 1.2 1 2h4.4c0-.8.4-1.5 1-2A6 6 0 0 0 12 3Z\"/>"),
            Map.entry("security", "<path d=\"M12 3l7 3v5c0 5-3.4 7.8-7 9-3.6-1.2-7-4-7-9V6l7-3Z\"/><path d=\"M9 12l2 2 4-4.5\"/>"),
            Map.entry("media", "<circle cx=\"12\" cy=\"12\" r=\"8.5\"/><path d=\"M10 8.3v7.4l6-3.7Z\"/>"),
            Map.entry("calendar", "<rect x=\"4\" y=\"5\" width=\"16\" height=\"15\" rx=\"2\"/><path d=\"M4 9.5h16M8 3v4M16 3v4\"/>"),
            Map.entry("basket", "<path d=\"M5 9h14l-1.2 9.2a2 2 0 0 1-2 1.8H8.2a2 2 0 0 1-2-1.8L5 9Z\"/><path d=\"m9 9 3-6 3 6\"/><path d=\"M10 13v3\"/><path d=\"M14 13v3\"/>"),
            Map.entry("kitchen", "<path d=\"M7.5 10.5a4 4 0 1 1 3-6.6 4 4 0 0 1 3 0 4 4 0 1 1 3 6.6\"/><path d=\"M7 10.5h10V17H7Z\"/><path d=\"M7 17h10v3H7Z\"/>"),
            Map.entry("timer", "<circle cx=\"12\" cy=\"13\" r=\"8\"/><path d=\"M12 9v4l3 2\"/><path d=\"M9 2h6M12 2v3\"/>"),
            Map.entry("warn", "<path d=\"M12 3 22 20H2Z\"/><path d=\"M12 9v5M12 17v.01\"/>"),
            Map.entry("theme", "<path d=\"M12 3a9 9 0 1 0 0 18c1.1 0 1.9-.9 1.9-1.9 0-.5-.2-.9-.5-1.2-.3-.3-.4-.7-.4-1.1 0-.9.7-1.5 1.6-1.5H16a4 4 0 0 0 4-4c0-4.6-3.9-8.3-8-8.3Z\"/><circle cx=\"7.6\" cy=\"10.6\" r=\"1\"/><circle cx=\"10.4\" cy=\"7.4\" r=\"1\"/><circle cx=\"15\" cy=\"8\" r=\"1\"/><circle cx=\"16.4\" cy=\"12.2\" r=\"1\"/>"),
            Map.entry("logout", "<path d=\"M9 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h3\"/><path d=\"M14 8l4 4-4 4M18 12H9\"/>"),
            Map.entry("fan", "<path d=\"M10.827 16.379a6.082 6.082 0 0 1-8.618-7.002l5.412 1.45a6.082 6.082 0 0 1 7.002-8.618l-1.45 5.412a6.082 6.082 0 0 1 8.618 7.002l-5.412-1.45a6.082 6.082 0 0 1-7.002 8.618l1.45-5.412Z\"/><circle cx=\"12\" cy=\"12\" r=\"0.8\"/>"),
            Map.entry("lock", "<rect x=\"6\" y=\"11\" width=\"12\" height=\"9\" rx=\"2\"/><path d=\"M9 11V8a3 3 0 0 1 6 0v3\"/>"),
            Map.entry("unlock", "<rect x=\"6\" y=\"11\" width=\"12\" height=\"9\" rx=\"2\"/><path d=\"M9 11V8a3 3 0 0 1 5.7-1.4\"/>"),
            Map.entry("camera", "<rect x=\"3\" y=\"7\" width=\"13\" height=\"10\" rx=\"2\"/><path d=\"M16 10.2 21 8v8l-5-2.2Z\"/><circle cx=\"9\" cy=\"12\" r=\"2.3\"/>"),
            Map.entry("garage", "<rect x=\"4\" y=\"6\" width=\"16\" height=\"13\" rx=\"1.5\"/><path d=\"M4 10.2h16M4 14.4h16\"/>"),
            Map.entry("play", "<path d=\"M9 6.5v11l9-5.5Z\"/>"),
            Map.entry("pause", "<rect x=\"7.5\" y=\"6\" width=\"3.2\" height=\"12\" rx=\"1\"/><rect x=\"13.3\" y=\"6\" width=\"3.2\" height=\"12\" rx=\"1\"/>"),
            Map.entry("prev", "<path d=\"M15.5 6 7 12l8.5 6Z\"/><rect x=\"5\" y=\"6\" width=\"2\" height=\"12\" rx=\"0.6\"/>"),
            Map.entry("next", "<path d=\"M8.5 6 17 12l-8.5 6Z\"/><rect x=\"17\" y=\"6\" width=\"2\" height=\"12\" rx=\"0.6\"/>"),
            Map.entry("volume", "<path d=\"M4 9.5v5h3.4l4.6 3.8V5.7L7.4 9.5Z\"/><path d=\"M16.5 9.2a4 4 0 0 1 0 5.6\"/>"),
            Map.entry("chevron", "<path d=\"M6 15l6-6 6 6\"/>"),
            Map.entry("waves", "<path d=\"M2 15c1.6-1.8 3.4-1.8 5 0s3.4 1.8 5 0 3.4-1.8 5 0 3.4 1.8 5 0\"/><path d=\"M2 19c1.6-1.8 3.4-1.8 5 0s3.4 1.8 5 0 3.4-1.8 5 0 3.4 1.8 5 0\"/>"),
            Map.entry("check", "<path d=\"M5 12.5l4.5 4.5L19 7\"/>"),
            Map.entry("close", "<path d=\"M6 6l12 12M18 6 6 18\"/>"),
            Map.entry("music", "<path d=\"M9 18V5.2L20 3v12.8\"/><circle cx=\"6.5\" cy=\"18\" r=\"2.5\"/><circle cx=\"17.5\" cy=\"15.8\" r=\"2.5\"/>"),
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
