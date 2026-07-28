(function () {
    "use strict";

    /* ---- Tab routing ---- */
    const pages = [...document.querySelectorAll("[data-page]")];
    const navItems = [...document.querySelectorAll("[data-target]")];
    const navRail = document.querySelector("[data-nav-rail]");
    const navIndicator = document.querySelector("[data-nav-indicator]");

    function moveIndicator(pageName) {
        if (!navRail || !navIndicator) {
            return;
        }
        const activeItem = navRail.querySelector(`.nav-item[data-target="${pageName}"]`);
        if (!activeItem) {
            navIndicator.style.opacity = "0";
            return;
        }
        navIndicator.style.opacity = "1";
        navIndicator.style.transform = `translateY(${activeItem.offsetTop}px)`;
        navIndicator.style.height = `${activeItem.offsetHeight}px`;
    }

    let lastHourly = [];

    function showPage(name) {
        const pageName = pages.some((page) => page.dataset.page === name) ? name : "home";
        pages.forEach((page) => page.classList.toggle("is-active", page.dataset.page === pageName));
        navItems.forEach((item) => item.classList.toggle("is-active", item.dataset.target === pageName));
        moveIndicator(pageName);
        if (pageName === "weather") {
            if (!radarMap) {
                initRadar();
            } else {
                setTimeout(() => radarMap.invalidateSize(), 50);
            }
        }
        if (pageName === "home" || pageName === "weather") {
            renderChart(lastHourly);
        }
        if (location.hash.slice(1) !== pageName) {
            history.replaceState(null, "", "#" + pageName);
        }
    }

    navItems.forEach((item) => {
        item.addEventListener("click", () => showPage(item.dataset.target));
        if (item.getAttribute("role") === "button") {
            item.addEventListener("keydown", (event) => {
                if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault();
                    showPage(item.dataset.target);
                }
            });
        }
    });

    /* ---- Rain radar (Leaflet + LibreWXR radar/nowcast) ---- */
    const RADAR_LAT = 40.12623;
    const RADAR_LON = -74.0493;
    let radarMap = null;
    let radarLayer = null;
    let radarHost = "https://api.librewxr.net";
    let radarFrames = [];
    let radarPastCount = 0;
    let radarFrameIndex = 0;
    let radarPlaying = false;
    let radarTimer = null;

    function initRadar() {
        const container = document.querySelector("[data-radar-map]");
        if (!container || typeof L === "undefined" || radarMap) {
            return;
        }
        // Attribution control intentionally off: this runs on a single private household tablet, not a public site.
        radarMap = L.map(container, { zoomControl: false, attributionControl: false }).setView([RADAR_LAT, RADAR_LON], 7);
        L.tileLayer("https://basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png", {
            attribution: "&copy; OpenStreetMap, &copy; CARTO",
            maxZoom: 19,
        }).addTo(radarMap);
        L.circleMarker([RADAR_LAT, RADAR_LON], {
            radius: 6,
            color: "#75d4f2",
            fillColor: "#75d4f2",
            fillOpacity: 1,
            weight: 2,
        }).addTo(radarMap);

        const playBtn = document.querySelector("[data-radar-play]");
        if (playBtn) {
            playBtn.addEventListener("click", toggleRadarPlay);
        }
        const slider = document.querySelector("[data-radar-slider]");
        if (slider) {
            slider.addEventListener("input", () => {
                stopRadarPlayback();
                showRadarFrame(parseInt(slider.value, 10));
            });
        }

        fetch("/api/radar/frames")
            .then((res) => res.json())
            .then((data) => {
                radarHost = data.host || radarHost;
                const past = (data.radar && data.radar.past) || [];
                const nowcast = (data.radar && data.radar.nowcast) || [];
                radarFrames = [...past, ...nowcast];
                radarPastCount = past.length;
                if (radarFrames.length === 0) {
                    setAll("[data-radar-time]", (el) => { el.textContent = "Radar unavailable"; });
                    return;
                }
                if (slider) {
                    slider.max = String(radarFrames.length - 1);
                }
                showRadarFrame(radarPastCount > 0 ? radarPastCount - 1 : radarFrames.length - 1);
                setTimeout(() => radarMap.invalidateSize(), 50);
            })
            .catch(() => {
                setAll("[data-radar-time]", (el) => { el.textContent = "Radar unavailable"; });
            });
    }

    function showRadarFrame(index) {
        if (!radarMap || !radarFrames[index]) {
            return;
        }
        radarFrameIndex = index;
        if (radarLayer) {
            radarMap.removeLayer(radarLayer);
        }
        const frame = radarFrames[index];
        radarLayer = L.tileLayer(`${radarHost}${frame.path}/256/{z}/{x}/{y}/6/1_1.png`, {
            opacity: 0.65,
        }).addTo(radarMap);

        const slider = document.querySelector("[data-radar-slider]");
        if (slider) {
            slider.value = String(index);
        }

        const isForecast = index >= radarPastCount;
        const label = new Date(frame.time * 1000).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
        setAll("[data-radar-time]", (el) => {
            el.textContent = `${isForecast ? "Forecast" : "Observed"} · ${label}`;
        });
    }

    function stopRadarPlayback() {
        radarPlaying = false;
        clearInterval(radarTimer);
        const btn = document.querySelector("[data-radar-play]");
        if (btn) {
            btn.classList.remove("is-playing");
        }
    }

    function toggleRadarPlay() {
        radarPlaying = !radarPlaying;
        const btn = document.querySelector("[data-radar-play]");
        if (btn) {
            btn.classList.toggle("is-playing", radarPlaying);
        }
        if (radarPlaying) {
            radarTimer = setInterval(() => {
                showRadarFrame((radarFrameIndex + 1) % radarFrames.length);
            }, 600);
        } else {
            clearInterval(radarTimer);
        }
    }

    window.addEventListener("hashchange", () => showPage(location.hash.slice(1)));
    window.addEventListener("resize", () => moveIndicator(location.hash.slice(1) || "home"));
    showPage(location.hash.slice(1) || "home");

    /* ---- Live clock, date ---- */
    const clockEl = document.querySelector("[data-clock]");
    const dateEl = document.querySelector("[data-date]");

    function tick() {
        const now = new Date();

        if (clockEl) {
            clockEl.textContent = now.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
        }

        if (dateEl) {
            dateEl.textContent = now.toLocaleDateString([], {
                weekday: "long",
                month: "long",
                day: "numeric",
            });
        }
    }

    tick();
    setInterval(tick, 15000);

    /* ---- Live theme (continuous time-of-day color drift) ---- */
    const LIVE_VAR_NAMES = [
        "ink", "muted", "line", "surface", "surface-strong", "surface-soft", "page",
        "accent", "accent-ink", "accent-soft", "sand", "bg-a", "bg-b", "bg-c", "bg-d",
    ];

    // Minutes-since-midnight anchors. Long flat stretches at midday/midnight, with the
    // interesting color movement concentrated around dawn and dusk, like real daylight.
    const LIVE_STOPS = [
        { time: 120, vars: { // 02:00 deep night — lowest brightness point
            ink: [216, 212, 204, 1], muted: [100, 96, 88, 1], line: [170, 145, 110, 0.09],
            surface: [7, 6, 9, 0.92], "surface-strong": [13, 11, 15, 0.96], "surface-soft": [9, 8, 11, 0.68],
            page: [3, 3, 5, 1], accent: [120, 95, 66, 1], "accent-ink": [20, 13, 7, 1], "accent-soft": [120, 95, 66, 0.1],
            sand: [95, 80, 60, 1], "bg-a": [2, 2, 4, 1], "bg-b": [8, 7, 11, 1], "bg-c": [14, 11, 9, 1], "bg-d": [5, 4, 8, 1],
        } },
        { time: 330, vars: { // 05:30 pre-dawn — first hint of blue
            ink: [224, 235, 242, 1], muted: [117, 140, 155, 1], line: [140, 180, 205, 0.13],
            surface: [9, 19, 31, 0.86], "surface-strong": [15, 31, 47, 0.94], "surface-soft": [11, 23, 35, 0.6],
            page: [5, 14, 24, 1], accent: [95, 140, 168, 1], "accent-ink": [9, 25, 36, 1], "accent-soft": [95, 140, 168, 0.13],
            sand: [140, 148, 120, 1], "bg-a": [4, 11, 20, 1], "bg-b": [11, 28, 42, 1], "bg-c": [20, 45, 62, 1], "bg-d": [9, 23, 36, 1],
        } },
        { time: 450, vars: { // 07:30 morning — light, fresh blue
            ink: [240, 250, 255, 1], muted: [168, 201, 216, 1], line: [150, 205, 230, 0.2],
            surface: [13, 42, 66, 0.78], "surface-strong": [20, 62, 90, 0.9], "surface-soft": [13, 46, 70, 0.52],
            page: [9, 32, 50, 1], accent: [120, 205, 238, 1], "accent-ink": [8, 32, 46, 1], "accent-soft": [120, 205, 238, 0.17],
            sand: [231, 216, 163, 1], "bg-a": [8, 28, 46, 1], "bg-b": [16, 52, 76, 1], "bg-c": [48, 112, 140, 1], "bg-d": [14, 50, 74, 1],
        } },
        { time: 720, vars: { // 12:00 midday — brightest, most vivid blue
            ink: [244, 252, 255, 1], muted: [176, 220, 232, 1], line: [150, 220, 238, 0.2],
            surface: [10, 40, 62, 0.76], "surface-strong": [16, 60, 86, 0.88], "surface-soft": [10, 45, 68, 0.5],
            page: [10, 38, 58, 1], accent: [110, 210, 240, 1], "accent-ink": [7, 34, 48, 1], "accent-soft": [110, 210, 240, 0.18],
            sand: [240, 224, 168, 1], "bg-a": [9, 32, 52, 1], "bg-b": [18, 58, 82, 1], "bg-c": [54, 130, 155, 1], "bg-d": [16, 56, 80, 1],
        } },
        { time: 1020, vars: { // 17:00 late afternoon — warming begins
            ink: [250, 248, 242, 1], muted: [198, 196, 168, 1], line: [220, 205, 150, 0.2],
            surface: [26, 32, 40, 0.78], "surface-strong": [38, 46, 54, 0.9], "surface-soft": [26, 32, 40, 0.54],
            page: [18, 22, 28, 1], accent: [235, 190, 110, 1], "accent-ink": [30, 20, 6, 1], "accent-soft": [235, 190, 110, 0.18],
            sand: [240, 200, 130, 1], "bg-a": [14, 18, 26, 1], "bg-b": [46, 44, 44, 1], "bg-c": [120, 90, 60, 1], "bg-d": [44, 36, 40, 1],
        } },
        { time: 1110, vars: { // 18:30 dinner / early sunset
            ink: [255, 244, 236, 1], muted: [224, 180, 158, 1], line: [255, 178, 130, 0.2],
            surface: [38, 20, 32, 0.82], "surface-strong": [60, 28, 42, 0.92], "surface-soft": [38, 20, 32, 0.56],
            page: [30, 16, 26, 1], accent: [255, 140, 92, 1], "accent-ink": [44, 17, 7, 1], "accent-soft": [255, 140, 92, 0.18],
            sand: [242, 178, 90, 1], "bg-a": [24, 14, 32, 1], "bg-b": [70, 32, 55, 1], "bg-c": [170, 75, 45, 1], "bg-d": [78, 32, 55, 1],
        } },
        { time: 1200, vars: { // 20:00 sunset peak — richest warm hues
            ink: [255, 243, 236, 1], muted: [224, 185, 168, 1], line: [255, 178, 130, 0.2],
            surface: [38, 18, 30, 0.82], "surface-strong": [63, 27, 40, 0.94], "surface-soft": [38, 18, 30, 0.58],
            page: [36, 19, 31, 1], accent: [255, 138, 92, 1], "accent-ink": [47, 18, 6, 1], "accent-soft": [255, 138, 92, 0.18],
            sand: [242, 178, 90, 1], "bg-a": [28, 16, 48, 1], "bg-b": [74, 31, 61, 1], "bg-c": [179, 80, 47, 1], "bg-d": [92, 36, 64, 1],
        } },
        { time: 1290, vars: { // 21:30 dusk — cooling into night
            ink: [238, 225, 222, 1], muted: [150, 128, 120, 1], line: [200, 150, 120, 0.16],
            surface: [22, 15, 20, 0.86], "surface-strong": [35, 22, 28, 0.94], "surface-soft": [24, 16, 21, 0.62],
            page: [14, 9, 14, 1], accent: [200, 120, 90, 1], "accent-ink": [30, 13, 8, 1], "accent-soft": [200, 120, 90, 0.15],
            sand: [180, 130, 80, 1], "bg-a": [10, 7, 14, 1], "bg-b": [35, 18, 32, 1], "bg-c": [75, 42, 38, 1], "bg-d": [25, 14, 26, 1],
        } },
        { time: 1380, vars: { // 23:00 night settling
            ink: [224, 218, 206, 1], muted: [120, 112, 100, 1], line: [190, 160, 120, 0.12],
            surface: [12, 10, 14, 0.9], "surface-strong": [20, 16, 20, 0.95], "surface-soft": [14, 12, 16, 0.65],
            page: [5, 4, 7, 1], accent: [150, 105, 66, 1], "accent-ink": [22, 14, 8, 1], "accent-soft": [150, 105, 66, 0.12],
            sand: [110, 88, 62, 1], "bg-a": [3, 3, 5, 1], "bg-b": [11, 9, 14, 1], "bg-c": [18, 13, 10, 1], "bg-d": [7, 6, 10, 1],
        } },
    ];

    function lerp(a, b, t) {
        return a + (b - a) * t;
    }

    function formatLiveColor(c) {
        const r = Math.round(c[0]);
        const g = Math.round(c[1]);
        const b = Math.round(c[2]);
        return c[3] >= 1 ? `rgb(${r}, ${g}, ${b})` : `rgba(${r}, ${g}, ${b}, ${c[3].toFixed(2)})`;
    }

    function applyLiveTheme() {
        const now = new Date();
        const minutes = now.getHours() * 60 + now.getMinutes() + now.getSeconds() / 60;

        let from = LIVE_STOPS[LIVE_STOPS.length - 1];
        let to = LIVE_STOPS[0];
        let span = 1440 - from.time + to.time;
        let elapsed = minutes >= from.time ? minutes - from.time : minutes + 1440 - from.time;

        for (let i = 0; i < LIVE_STOPS.length - 1; i++) {
            if (minutes >= LIVE_STOPS[i].time && minutes < LIVE_STOPS[i + 1].time) {
                from = LIVE_STOPS[i];
                to = LIVE_STOPS[i + 1];
                span = to.time - from.time;
                elapsed = minutes - from.time;
                break;
            }
        }

        const t = span > 0 ? elapsed / span : 0;
        const root = document.documentElement.style;
        LIVE_VAR_NAMES.forEach((name) => {
            const a = from.vars[name];
            const b = to.vars[name];
            const mixed = [lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t), lerp(a[3], b[3], t)];
            root.setProperty(`--${name}`, formatLiveColor(mixed));
        });
    }

    function clearLiveTheme() {
        const root = document.documentElement.style;
        LIVE_VAR_NAMES.forEach((name) => root.removeProperty(`--${name}`));
    }

    /* ---- Theme switcher ---- */
    const THEME_KEY = "dashboard-theme";
    const swatches = [...document.querySelectorAll("[data-theme-choice]")];
    let liveThemeTimer = null;

    function applyTheme(theme, persist) {
        if (liveThemeTimer) {
            clearInterval(liveThemeTimer);
            liveThemeTimer = null;
            clearLiveTheme();
        }

        if (theme === "dusk") {
            document.documentElement.removeAttribute("data-theme");
        } else {
            document.documentElement.setAttribute("data-theme", theme);
        }

        if (theme === "live") {
            applyLiveTheme();
            liveThemeTimer = setInterval(applyLiveTheme, 12000);
        }

        swatches.forEach((swatch) => swatch.classList.toggle("is-selected", swatch.dataset.themeChoice === theme));
        if (persist) {
            localStorage.setItem(THEME_KEY, theme);
        }
    }

    const savedTheme = localStorage.getItem(THEME_KEY);
    if (savedTheme) {
        applyTheme(savedTheme, false);
    }

    swatches.forEach((swatch) => {
        swatch.addEventListener("click", () => applyTheme(swatch.dataset.themeChoice, true));
    });

    /* ---- Sliders with live value readout ---- */
    document.querySelectorAll("[data-slider]").forEach((slider) => {
        const output = document.querySelector(`[data-slider-value="${slider.dataset.slider}"]`);
        if (!output) return;
        const unit = output.dataset.unit || "";
        const render = () => { output.textContent = slider.value + unit; };
        slider.addEventListener("input", render);
        render();
    });

    /* ---- Segmented controls (single-select within a group) ---- */
    document.querySelectorAll("[data-segmented]").forEach((group) => {
        group.querySelectorAll("button").forEach((btn) => {
            btn.addEventListener("click", () => {
                group.querySelectorAll("button").forEach((b) => b.classList.remove("is-selected"));
                btn.classList.add("is-selected");
            });
        });
    });

    /* ---- Fan speed dots (single-select within a group) ---- */
    document.querySelectorAll("[data-speed-group]").forEach((group) => {
        group.querySelectorAll("button").forEach((btn) => {
            btn.addEventListener("click", () => {
                group.querySelectorAll("button").forEach((b) => b.classList.remove("is-selected"));
                btn.classList.add("is-selected");
            });
        });
    });

    /* ---- Simple toggle buttons (locks, icon-buttons acting as on/off) ---- */
    document.querySelectorAll("[data-toggle-active]").forEach((btn) => {
        btn.addEventListener("click", () => btn.classList.toggle("is-active"));
    });

    /* ---- Stepper (reused for thermostat, fan speed, light stages) ---- */
    function wireStepper(stepper, onChange) {
        const key = stepper.dataset.stepper;
        const targets = [...document.querySelectorAll(`[data-stepper-target="${key}"]`)];
        const ring = document.querySelector(`[data-thermo-ring="${key}"]`);
        const min = parseInt(stepper.dataset.min || "0", 10);
        const max = parseInt(stepper.dataset.max || "10", 10);
        const unit = stepper.dataset.unit || "";
        const zeroLabel = stepper.dataset.zeroLabel || "";
        let value = parseInt(stepper.dataset.value || String(min), 10);

        function format(v) {
            return zeroLabel && v === 0 ? zeroLabel : `${v}${unit}`;
        }

        function render() {
            targets.forEach((el) => { el.textContent = format(value); });
            if (ring) {
                const pct = Math.round(((value - min) / (max - min)) * 100);
                ring.style.setProperty("--pct", String(pct));
            }
            if (onChange) {
                onChange(value);
            }
        }

        stepper.querySelector("[data-step='up']").addEventListener("click", () => {
            value = Math.min(max, value + 1);
            render();
        });
        stepper.querySelector("[data-step='down']").addEventListener("click", () => {
            value = Math.max(min, value - 1);
            render();
        });
        render();
    }

    document.querySelectorAll("[data-stepper]").forEach((stepper) => wireStepper(stepper));

    /* ---- Ceiling fans (fan speed + warm/cool light stages) ---- */
    const FAN_STATE = {};
    document.querySelectorAll("[data-fan-card]").forEach((card) => {
        const id = card.dataset.fanCard;
        FAN_STATE[id] = {
            name: card.dataset.fanName,
            speed: parseInt(card.dataset.fanSpeed, 10),
            warm: parseInt(card.dataset.warmStage, 10),
            cool: parseInt(card.dataset.coolStage, 10),
        };
        card.addEventListener("click", () => openFanModal(id));
    });

    function fanChipLabel(kind, value) {
        if (kind === "speed") {
            return value === 0 ? "Off" : `Speed ${value}`;
        }
        const label = kind === "warm" ? "Warm" : "Cool";
        return value === 0 ? "Off" : `${label} ${value}`;
    }

    function syncFanCard(id) {
        const card = document.querySelector(`[data-fan-card="${id}"]`);
        const state = FAN_STATE[id];
        if (!card || !state) {
            return;
        }
        [["speed", state.speed], ["warm", state.warm], ["cool", state.cool]].forEach(([kind, value]) => {
            const chip = card.querySelector(`[data-chip="${kind}"]`);
            if (!chip) {
                return;
            }
            chip.querySelector("span").textContent = fanChipLabel(kind, value);
            chip.classList.toggle("is-on", value > 0);
        });
    }

    function openFanModal(id) {
        const state = FAN_STATE[id];
        if (!state) {
            return;
        }
        const body = `
            <div class="fan-modal-group">
                <div class="fan-modal-row">
                    <span class="fan-modal-label">Fan Speed</span>
                    <div class="stepper" data-stepper="fan-speed" data-min="0" data-max="10" data-value="${state.speed}" data-zero-label="Off">
                        <button type="button" data-step="down">&minus;</button>
                        <span class="stepper-value" data-stepper-target="fan-speed"></span>
                        <button type="button" data-step="up">+</button>
                    </div>
                </div>
                <div class="fan-modal-row">
                    <span class="fan-modal-label">Warm Light</span>
                    <div class="stepper stepper-warm" data-stepper="fan-warm" data-min="0" data-max="5" data-value="${state.warm}" data-zero-label="Off">
                        <button type="button" data-step="down">&minus;</button>
                        <span class="stepper-value" data-stepper-target="fan-warm"></span>
                        <button type="button" data-step="up">+</button>
                    </div>
                </div>
                <div class="fan-modal-row">
                    <span class="fan-modal-label">Cool Light</span>
                    <div class="stepper stepper-cool" data-stepper="fan-cool" data-min="0" data-max="5" data-value="${state.cool}" data-zero-label="Off">
                        <button type="button" data-step="down">&minus;</button>
                        <span class="stepper-value" data-stepper-target="fan-cool"></span>
                        <button type="button" data-step="up">+</button>
                    </div>
                </div>
            </div>`;
        openModal(state.name, body);
        wireStepper(document.querySelector('[data-stepper="fan-speed"]'), (v) => { state.speed = v; syncFanCard(id); });
        wireStepper(document.querySelector('[data-stepper="fan-warm"]'), (v) => { state.warm = v; syncFanCard(id); });
        wireStepper(document.querySelector('[data-stepper="fan-cool"]'), (v) => { state.cool = v; syncFanCard(id); });
    }

    /* ---- Now-playing transport (play/pause icon swap only) ---- */
    document.querySelectorAll("[data-play-pause]").forEach((btn) => {
        btn.addEventListener("click", () => btn.classList.toggle("is-playing"));
    });

    /* ---- Today ticker (cycles only if more than one event) ---- */
    function wireTodayTicker(ticker) {
        if (ticker.dataset.tickerTimer) {
            clearInterval(Number(ticker.dataset.tickerTimer));
            delete ticker.dataset.tickerTimer;
        }
        const slides = [...ticker.querySelectorAll(".today-slide")];
        if (slides.length <= 1) {
            return;
        }
        let index = slides.findIndex((slide) => slide.classList.contains("is-active"));
        if (index < 0) {
            index = 0;
            slides[0].classList.add("is-active");
        }
        const timer = setInterval(() => {
            slides[index].classList.remove("is-active");
            index = (index + 1) % slides.length;
            slides[index].classList.add("is-active");
        }, 4000);
        ticker.dataset.tickerTimer = String(timer);
    }

    function relativeDayLabel(dateStr) {
        const [y, m, d] = dateStr.split("-").map(Number);
        const date = new Date(y, m - 1, d);
        const today = new Date();
        today.setHours(0, 0, 0, 0);
        const diffDays = Math.round((date - today) / 86400000);
        if (diffDays === 1) {
            return "Tomorrow";
        }
        if (diffDays > 1 && diffDays < 7) {
            return date.toLocaleDateString([], { weekday: "long" });
        }
        return date.toLocaleDateString([], { month: "short", day: "numeric" });
    }

    function todaySlidesHtml(events, titlePrefix, noteSuffix) {
        return events.map((e, i) =>
            `<div class="today-slide${i === 0 ? " is-active" : ""}">` +
            `<span class="stat-value">${titlePrefix}${e.title}</span>` +
            `<span class="stat-note">${e.time}${noteSuffix}</span></div>`
        ).join("");
    }

    function renderTodayTicker() {
        const ticker = document.querySelector("[data-today-ticker]");
        if (!ticker) {
            return;
        }
        const todayIso = calIso(new Date());
        const todayEvents = CAL_EVENTS[todayIso] || [];
        if (todayEvents.length > 0) {
            ticker.innerHTML = todaySlidesHtml(todayEvents, "", "");
            wireTodayTicker(ticker);
            return;
        }

        const upcomingDate = Object.keys(CAL_EVENTS).filter((date) => date > todayIso).sort()[0];
        if (upcomingDate) {
            ticker.innerHTML = todaySlidesHtml(CAL_EVENTS[upcomingDate], "Next: ", ` &middot; ${relativeDayLabel(upcomingDate)}`);
            wireTodayTicker(ticker);
            return;
        }

        ticker.innerHTML = `<div class="today-slide is-active"><span class="stat-value">Nothing planned</span></div>`;
    }

    /* ---- Live weather (fetched from /api/weather) ---- */
    const WX_ICON_PATHS = {
        clear: '<circle cx="12" cy="12" r="4.6"/><path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1"/>',
        "cloud-sun": '<circle cx="12" cy="9" r="3.4"/><path d="M12 2.8v1.8M12 13.6v1.8M5.5 9h1.8M16.7 9h1.8M7.4 4.4l1.3 1.3M15.6 4.4l-1.3 1.3M7.4 13.6l1.3-1.3M15.6 13.6l-1.3-1.3"/><path d="M6.5 21a3.6 3.6 0 0 1 .4-7.2 4.6 4.6 0 0 1 8.7 1.4A3.1 3.1 0 0 1 15.2 21Z"/>',
        cloud: '<path d="M7 18a4 4 0 0 1 .4-8 5 5 0 0 1 9.4 1.6A3.4 3.4 0 0 1 16.6 18H7Z"/>',
        rain: '<path d="M7 15a4 4 0 0 1 .4-8 5 5 0 0 1 9.4 1.6A3.4 3.4 0 0 1 16.4 15H7Z"/><path d="M8 18l-1 2M12 18l-1 2M16 18l-1 2"/>',
        snow: '<path d="M12 3v18M6 6l12 12M18 6 6 18M3 12h18"/>',
        storm: '<path d="M13 2 4 14h6l-1 8 9-12h-6l1-8Z"/>',
    };

    function wxIcon(name, cls) {
        const inner = WX_ICON_PATHS[name] || WX_ICON_PATHS.clear;
        const clsAttr = cls ? ` class="${cls}"` : "";
        return `<svg${clsAttr} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${inner}</svg>`;
    }

    function uvBucket(uv) {
        if (uv >= 11) return { label: "Extreme", cls: "pill-bad", tip: "Avoid sun" };
        if (uv >= 8) return { label: "Very High", cls: "pill-bad", tip: "Heavy sunblock" };
        if (uv >= 6) return { label: "High", cls: "pill-warn", tip: "Sunblock needed" };
        if (uv >= 3) return { label: "Moderate", cls: "pill-warn", tip: "Light sunblock" };
        return { label: "Low", cls: "pill-good", tip: "No sunblock needed" };
    }

    function fmtTime(iso) {
        const d = new Date(iso.includes("T") ? iso : iso.replace(" ", "T"));
        return d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
    }

    function fmtDay(dateStr) {
        const d = new Date(dateStr + "T00:00");
        const today = new Date();
        const diffDays = Math.round((d - new Date(today.getFullYear(), today.getMonth(), today.getDate())) / 86400000);
        if (diffDays === 0) return "Today";
        if (diffDays === 1) return "Tomorrow";
        return d.toLocaleDateString([], { weekday: "short" });
    }

    function tideLabel(type) {
        return type === "H" ? "High" : "Low";
    }

    function setAll(selector, fn) {
        document.querySelectorAll(selector).forEach(fn);
    }

    function buildSmoothPath(points) {
        if (points.length === 0) return "";
        if (points.length === 1) return `M${points[0].x},${points[0].y}`;
        let d = `M${points[0].x},${points[0].y}`;
        for (let i = 0; i < points.length - 1; i++) {
            const p0 = points[i - 1] || points[i];
            const p1 = points[i];
            const p2 = points[i + 1];
            const p3 = points[i + 2] || p2;
            const cp1x = p1.x + (p2.x - p0.x) / 6;
            const cp1y = p1.y + (p2.y - p0.y) / 6;
            const cp2x = p2.x - (p3.x - p1.x) / 6;
            const cp2y = p2.y - (p3.y - p1.y) / 6;
            d += ` C${cp1x.toFixed(1)},${cp1y.toFixed(1)} ${cp2x.toFixed(1)},${cp2y.toFixed(1)} ${p2.x},${p2.y}`;
        }
        return d;
    }

    function renderChart(hourly) {
        setAll("[data-wx-chart]", (el) => {
            if (!hourly || hourly.length === 0) {
                el.innerHTML = "";
                return;
            }
            const rect = el.getBoundingClientRect();
            const width = Math.max(200, Math.round(rect.width));
            const height = Math.max(90, Math.round(rect.height));
            el.setAttribute("viewBox", `0 0 ${width} ${height}`);

            const top = 24;
            const bottom = height - 34;
            const temps = hourly.map((h) => h.tempF);
            const tempMin = Math.min(...temps) - 3;
            const tempMax = Math.max(...temps) + 3;
            const span = Math.max(1, tempMax - tempMin);
            const stepX = hourly.length > 1 ? width / (hourly.length - 1) : 0;

            const points = hourly.map((h, i) => ({
                x: Math.round(i * stepX),
                y: Math.round(top + ((tempMax - h.tempF) / span) * (bottom - top)),
            }));

            const linePath = buildSmoothPath(points);
            const last = points[points.length - 1];
            const areaPath = `${linePath} L${last.x},${bottom} L${points[0].x},${bottom} Z`;

            const dots = points.map((p) => `<circle class="chart-dot" cx="${p.x}" cy="${p.y}" r="3.5"/>`).join("");
            const tempLabels = points.map((p, i) => `<text class="chart-temp-label" x="${p.x}" y="${p.y - 12}" text-anchor="middle">${hourly[i].tempF}&deg;</text>`).join("");
            const rainLabels = points.map((p, i) => {
                if (hourly[i].precipChance < 20) {
                    return "";
                }
                return `<text class="chart-rain-label" x="${p.x}" y="${p.y - 26}" text-anchor="middle">${hourly[i].precipChance}%</text>`;
            }).join("");
            const hourLabels = points.map((p, i) => {
                const label = i === 0 ? "Now" : new Date(hourly[i].time).toLocaleTimeString([], { hour: "numeric" });
                return `<text x="${p.x}" y="${height - 14}" text-anchor="middle">${label}</text>`;
            }).join("");

            el.innerHTML = `
                <defs>
                    <linearGradient id="chartGradient" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0" style="stop-color:var(--accent);stop-opacity:0.35"/>
                        <stop offset="1" style="stop-color:var(--accent);stop-opacity:0"/>
                    </linearGradient>
                </defs>
                <path class="chart-fill" d="${areaPath}" fill="url(#chartGradient)"/>
                <path class="chart-line" d="${linePath}"/>
                ${dots}
                ${tempLabels}
                ${rainLabels}
                ${hourLabels}`;
        });
    }

    function renderDaylightArc(sunriseIso, sunsetIso) {
        if (!sunriseIso || !sunsetIso) {
            return;
        }
        const sunrise = new Date(sunriseIso);
        const sunset = new Date(sunsetIso);
        const now = new Date();
        const total = sunset - sunrise;
        const fraction = total > 0 ? (now - sunrise) / total : 0;
        const clamped = Math.max(0, Math.min(1, fraction));
        const isDaytime = now >= sunrise && now <= sunset;

        const cx = 100;
        const cy = 92;
        const r = 82;
        const angle = Math.PI * (1 - clamped);
        const mx = (cx + r * Math.cos(angle)).toFixed(1);
        const my = (cy - r * Math.sin(angle)).toFixed(1);

        setAll("[data-daylight-marker]", (el) => {
            el.setAttribute("transform", `translate(${mx}, ${my})`);
            el.classList.toggle("is-set", !isDaytime);
        });

        setAll("[data-daylight-fill]", (el) => {
            el.setAttribute("d", clamped <= 0 ? "" : `M18,92 A82,82 0 0 1 ${mx},${my}`);
        });
    }

    function renderWeather(data) {
        const current = data.current;

        if (current) {
            setAll("[data-wx-icon]", (el) => { el.innerHTML = wxIcon(current.icon, "wx-icon"); });
            setAll("[data-wx-temp]", (el) => { el.textContent = current.tempF; });
            setAll("[data-wx-label]", (el) => { el.textContent = `${current.label} · Feels like ${current.feelsLikeF}°`; });
            setAll("[data-wx-uv]", (el) => { el.textContent = current.uv; });
            setAll("[data-wx-uv-tip]", (el) => {
                el.textContent = uvBucket(current.uv).tip;
            });
            setAll("[data-wx-uv-marker]", (el) => {
                el.style.left = Math.max(2, Math.min(98, (current.uv / 11) * 100)) + "%";
            });
        } else {
            setAll("[data-wx-label]", (el) => { el.textContent = "Weather unavailable"; });
        }

        lastHourly = data.hourly || [];
        renderChart(lastHourly);

        setAll("[data-wx-daily]", (el) => {
            if (data.daily.length === 0) {
                return;
            }
            const scaleMin = Math.min(...data.daily.map((d) => d.loF)) - 2;
            const scaleMax = Math.max(...data.daily.map((d) => d.hiF)) + 2;
            const span = Math.max(1, scaleMax - scaleMin);
            el.innerHTML = data.daily.map((d) => {
                const left = ((d.loF - scaleMin) / span) * 100;
                const width = ((d.hiF - d.loF) / span) * 100;
                return `
                    <div class="day-row">
                        <span>${fmtDay(d.date)}</span>
                        <span class="day-precip">${d.precipChance >= 20 ? d.precipChance + "%" : ""}</span>
                        ${wxIcon(d.icon, "day-icon")}
                        <div class="range-bar"><span style="left:${left}%;width:${width}%"></span></div>
                        <div class="day-range"><span class="lo">${d.loF}&deg;</span><strong>${d.hiF}&deg;</strong></div>
                    </div>`;
            }).join("");
        });

        if (data.daily.length > 0) {
            setAll("[data-wx-hi]", (el) => { el.textContent = data.daily[0].hiF; });
            setAll("[data-wx-lo]", (el) => { el.textContent = data.daily[0].loF; });
            setAll("[data-wx-sunrise]", (el) => { el.textContent = data.daily[0].sunrise ? fmtTime(data.daily[0].sunrise) : "—"; });
            setAll("[data-wx-sunset]", (el) => { el.textContent = data.daily[0].sunset ? fmtTime(data.daily[0].sunset) : "—"; });
            renderDaylightArc(data.daily[0].sunrise, data.daily[0].sunset);
        }

        if (data.marine) {
            setAll("[data-marine-water]", (el) => { el.textContent = `${data.marine.waterTempF}°F`; });
            setAll("[data-marine-wave]", (el) => { el.textContent = `${data.marine.waveFt} ft`; });
        }

        setAll("[data-tide-tile]", (el) => {
            if (data.tides.length === 0) {
                el.hidden = true;
                return;
            }
            el.hidden = false;
            const next = data.tides[0];
            el.querySelector("[data-tide-next]").textContent = `${tideLabel(next.type)} till ${fmtTime(next.time)}`;
            const followingEl = el.querySelector("[data-tide-following]");
            if (followingEl) {
                followingEl.textContent = current && current.onshoreWind === true ? "Ocean breeze · cool"
                    : current && current.onshoreWind === false ? "Inland breeze"
                    : "";
            }
        });
    }

    function loadWeather() {
        fetch("/api/weather")
            .then((res) => {
                if (!res.ok) throw new Error("weather request failed");
                return res.json();
            })
            .then(renderWeather)
            .catch(() => {
                setAll("[data-wx-label]", (el) => { el.textContent = "Weather unavailable"; });
            });
    }

    loadWeather();
    setInterval(loadWeather, 10 * 60 * 1000);

    /* ---- Calendar (month/year/decade drill navigation, modeled on Apple Calendar) ---- */
    function calIso(d) {
        return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
    }

    // Events keyed by ISO date, loaded from the family's iCloud calendar via /api/calendar.
    const CAL_EVENTS = {};

    function loadCalendarEvents() {
        fetch("/api/calendar")
            .then((res) => {
                if (!res.ok) throw new Error("calendar request failed");
                return res.json();
            })
            .then((events) => {
                Object.keys(CAL_EVENTS).forEach((key) => delete CAL_EVENTS[key]);
                events.forEach((e) => {
                    (CAL_EVENTS[e.date] = CAL_EVENTS[e.date] || []).push({
                        time: e.time,
                        title: e.title,
                        initial: e.initial,
                        color: e.color,
                    });
                });
                calRender();
                renderTodayTicker();
            })
            .catch(() => {
                // keep showing whatever was last successfully loaded
            });
    }

    const calToday = new Date();
    const calState = {
        level: "month",
        year: calToday.getFullYear(),
        month: calToday.getMonth(),
        selected: calIso(calToday),
        decadeStart: calToday.getFullYear() - (calToday.getFullYear() % 12),
    };

    function calTitle() {
        if (calState.level === "month") {
            return new Date(calState.year, calState.month, 1).toLocaleDateString([], { month: "long", year: "numeric" });
        }
        if (calState.level === "year") {
            return String(calState.year);
        }
        return `${calState.decadeStart}–${calState.decadeStart + 11}`;
    }

    const MAX_CELL_EVENTS = 2;

    function buildMonthCells(year, month) {
        const todayIso = calIso(new Date());
        const firstWeekday = new Date(year, month, 1).getDay();
        const daysInMonth = new Date(year, month + 1, 0).getDate();
        const daysInPrevMonth = new Date(year, month, 0).getDate();
        const totalCells = Math.ceil((firstWeekday + daysInMonth) / 7) * 7;

        let html = "";
        for (let i = 0; i < totalCells; i++) {
            const dayNum = i - firstWeekday + 1;
            let cellYear = year;
            let cellMonth = month;
            let cellDate;
            let isOutside = false;
            if (dayNum < 1) {
                cellMonth = month - 1 < 0 ? 11 : month - 1;
                cellYear = month - 1 < 0 ? year - 1 : year;
                cellDate = daysInPrevMonth + dayNum;
                isOutside = true;
            } else if (dayNum > daysInMonth) {
                cellMonth = month + 1 > 11 ? 0 : month + 1;
                cellYear = month + 1 > 11 ? year + 1 : year;
                cellDate = dayNum - daysInMonth;
                isOutside = true;
            } else {
                cellDate = dayNum;
            }
            const cellIso = calIso(new Date(cellYear, cellMonth, cellDate));
            const isToday = cellIso === todayIso;
            const isSelected = cellIso === calState.selected;
            const events = CAL_EVENTS[cellIso] || [];
            const pills = events.slice(0, MAX_CELL_EVENTS)
                .map((e) => `<span class="cal-event-pill" style="background:${e.color}22;color:${e.color}">${e.title}</span>`)
                .join("");
            const more = events.length > MAX_CELL_EVENTS
                ? `<span class="cal-event-more">+${events.length - MAX_CELL_EVENTS} more</span>`
                : "";
            html += `<div class="cal-day${isOutside ? " is-outside" : ""}${isToday ? " is-today" : ""}${isSelected ? " is-selected" : ""}"` +
                ` data-cal-day="${cellDate}" data-cal-month="${cellMonth}" data-cal-year="${cellYear}">` +
                `<span class="cal-day-num">${cellDate}</span>` +
                `<span class="cal-day-events">${pills}${more}</span></div>`;
        }
        return html;
    }

    function buildYearCells(year) {
        const today = new Date();
        let html = "";
        for (let m = 0; m < 12; m++) {
            const label = new Date(year, m, 1).toLocaleDateString([], { month: "short" });
            const firstWeekday = new Date(year, m, 1).getDay();
            const daysInMonth = new Date(year, m + 1, 0).getDate();
            const totalCells = Math.ceil((firstWeekday + daysInMonth) / 7) * 7;
            let miniHtml = "";
            for (let i = 0; i < totalCells; i++) {
                const dayNum = i - firstWeekday + 1;
                if (dayNum < 1 || dayNum > daysInMonth) {
                    miniHtml += `<span class="cal-mini-day is-outside"></span>`;
                    continue;
                }
                const isToday = year === today.getFullYear() && m === today.getMonth() && dayNum === today.getDate();
                miniHtml += `<span class="cal-mini-day${isToday ? " is-today" : ""}">${dayNum}</span>`;
            }
            html += `<div class="cal-mini-month" data-cal-month-select="${m}">` +
                `<div class="cal-mini-month-label">${label}</div>` +
                `<div class="cal-mini-grid">${miniHtml}</div></div>`;
        }
        return html;
    }

    function buildDecadeCells(startYear) {
        const currentYear = new Date().getFullYear();
        let html = "";
        for (let i = 0; i < 12; i++) {
            const y = startYear + i;
            html += `<div class="cal-year-cell${y === currentYear ? " is-current" : ""}" data-cal-year-select="${y}">${y}</div>`;
        }
        return html;
    }

    function openModal(title, bodyHtml) {
        const modal = document.querySelector("[data-modal]");
        const titleEl = document.querySelector("[data-modal-title]");
        const bodyEl = document.querySelector("[data-modal-body]");
        if (!modal || !titleEl || !bodyEl) {
            return;
        }
        titleEl.textContent = title;
        bodyEl.innerHTML = bodyHtml;
        modal.hidden = false;
    }

    function closeModal() {
        const modal = document.querySelector("[data-modal]");
        if (modal) {
            modal.hidden = true;
        }
    }

    document.querySelectorAll("[data-modal-close]").forEach((el) => {
        el.addEventListener("click", closeModal);
    });

    function openCalModal(dateIso) {
        const [y, m, d] = dateIso.split("-").map(Number);
        const date = new Date(y, m - 1, d);
        const todayIso = calIso(new Date());
        const title = dateIso === todayIso
            ? "Today"
            : date.toLocaleDateString([], { weekday: "long", month: "long", day: "numeric" });
        const events = CAL_EVENTS[dateIso] || [];
        const body = events.length
            ? events.map((e) => `<div class="agenda-item"><span class="agenda-time">${e.time}</span>` +
                `<span class="agenda-title">${e.title}</span>` +
                `<span class="member-dot" style="background:${e.color}">${e.initial}</span></div>`).join("")
            : `<p class="modal-empty">No events scheduled</p>`;
        openModal(title, body);
    }

    function attachCalGridHandlers() {
        document.querySelectorAll("[data-cal-day]").forEach((el) => {
            el.addEventListener("click", () => {
                const y = Number(el.dataset.calYear);
                const m = Number(el.dataset.calMonth);
                const d = Number(el.dataset.calDay);
                calState.selected = calIso(new Date(y, m, d));
                calState.year = y;
                calState.month = m;
                calRender();
                openCalModal(calState.selected);
            });
        });
        document.querySelectorAll("[data-cal-month-select]").forEach((el) => {
            el.addEventListener("click", () => {
                calState.month = Number(el.dataset.calMonthSelect);
                calState.level = "month";
                calRender();
            });
        });
        document.querySelectorAll("[data-cal-year-select]").forEach((el) => {
            el.addEventListener("click", () => {
                calState.year = Number(el.dataset.calYearSelect);
                calState.level = "year";
                calRender();
            });
        });
    }

    function calRender() {
        const grid = document.querySelector("[data-calendar-grid]");
        const weekdays = document.querySelector("[data-cal-weekdays]");
        const titleBtn = document.querySelector("[data-cal-title]");
        if (!grid || !weekdays || !titleBtn) {
            return;
        }

        titleBtn.textContent = calTitle();
        grid.className = "cal-grid" + (calState.level !== "month" ? ` is-${calState.level}` : "");

        if (calState.level === "month") {
            weekdays.hidden = false;
            weekdays.innerHTML = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"].map((d) => `<span>${d}</span>`).join("");
            grid.innerHTML = buildMonthCells(calState.year, calState.month);
        } else if (calState.level === "year") {
            weekdays.hidden = true;
            weekdays.innerHTML = "";
            grid.innerHTML = buildYearCells(calState.year);
        } else {
            weekdays.hidden = true;
            weekdays.innerHTML = "";
            grid.innerHTML = buildDecadeCells(calState.decadeStart);
        }

        attachCalGridHandlers();
    }

    function calPrev() {
        if (calState.level === "month") {
            calState.month--;
            if (calState.month < 0) {
                calState.month = 11;
                calState.year--;
            }
        } else if (calState.level === "year") {
            calState.year--;
        } else {
            calState.decadeStart -= 12;
        }
        calRender();
    }

    function calNext() {
        if (calState.level === "month") {
            calState.month++;
            if (calState.month > 11) {
                calState.month = 0;
                calState.year++;
            }
        } else if (calState.level === "year") {
            calState.year++;
        } else {
            calState.decadeStart += 12;
        }
        calRender();
    }

    function calDrillUp() {
        if (calState.level === "month") {
            calState.level = "year";
        } else if (calState.level === "year") {
            calState.decadeStart = calState.year - (calState.year % 12);
            calState.level = "decade";
        }
        calRender();
    }

    const calPrevBtn = document.querySelector("[data-cal-prev]");
    const calNextBtn = document.querySelector("[data-cal-next]");
    const calTodayBtn = document.querySelector("[data-cal-today]");
    const calTitleBtn = document.querySelector("[data-cal-title]");

    if (calPrevBtn) {
        calPrevBtn.addEventListener("click", calPrev);
    }
    if (calNextBtn) {
        calNextBtn.addEventListener("click", calNext);
    }
    if (calTitleBtn) {
        calTitleBtn.addEventListener("click", calDrillUp);
    }
    if (calTodayBtn) {
        calTodayBtn.addEventListener("click", () => {
            const t = new Date();
            calState.level = "month";
            calState.year = t.getFullYear();
            calState.month = t.getMonth();
            calState.selected = calIso(t);
            calRender();
        });
    }

    calRender();
    setInterval(calRender, 5 * 60 * 1000);
    loadCalendarEvents();
    setInterval(loadCalendarEvents, 15 * 60 * 1000);
})();
