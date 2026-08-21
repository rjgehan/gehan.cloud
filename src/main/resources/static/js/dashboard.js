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
    let lastDaily = [];
    let cameraViewerId = null;
    let cameraViewerTimer = null;
    let fanPollTimer = null;

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
        if (pageName === "security") {
            refreshCameraSnapshots();
        } else {
            closeCameraViewer();
        }
        if (pageName === "lights") {
            startFanPolling();
        } else {
            stopFanPolling();
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
    const RADAR_REFRESH_MS = 5 * 60 * 1000;
    const RADAR_FRAME_MS = 400;
    const RADAR_FADE_MS = 180;
    const RADAR_ICON_SVG_OPEN = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">';
    const RADAR_PLAY_ICON = `${RADAR_ICON_SVG_OPEN}<path d="M9 6.5v11l9-5.5Z"/></svg>`;
    const RADAR_PAUSE_ICON = `${RADAR_ICON_SVG_OPEN}<rect x="7.5" y="6" width="3.2" height="12" rx="1"/><rect x="13.3" y="6" width="3.2" height="12" rx="1"/></svg>`;
    let radarAutoPlayPending = true;
    let radarMap = null;
    let radarHost = "https://api.librewxr.net";
    let radarFrames = [];
    let radarPastCount = 0;
    let radarFrameIndex = 0;
    let radarPlaying = false;
    let radarTimer = null;
    // Tile layers are cached per frame path (not removed/re-added) so switching
    // frames is an instant opacity swap instead of a network-bound flash, and
    // replaying already-seen frames doesn't refetch tiles.
    const radarLayerCache = new Map();

    function radarLayerFor(frame) {
        let layer = radarLayerCache.get(frame.path);
        if (!layer) {
            layer = L.tileLayer(`${radarHost}${frame.path}/256/{z}/{x}/{y}/6/1_1.png`, { opacity: 0 });
            layer.addTo(radarMap);
            radarLayerCache.set(frame.path, layer);
        }
        return layer;
    }

    function pruneRadarLayerCache() {
        const validPaths = new Set(radarFrames.map((f) => f.path));
        radarLayerCache.forEach((layer, path) => {
            if (!validPaths.has(path)) {
                radarMap.removeLayer(layer);
                radarLayerCache.delete(path);
            }
        });
    }

    function preloadRadarFrames() {
        radarFrames.forEach((frame) => radarLayerFor(frame));
    }

    // Tweens the slider's numeric value over a few animation frames instead of
    // snapping it straight to the target, so it glides in step with the tile crossfade.
    let radarSliderAnim = null;
    function animateSliderTo(slider, target, duration) {
        if (!slider) {
            return;
        }
        const start = parseFloat(slider.value);
        if (radarSliderAnim) {
            cancelAnimationFrame(radarSliderAnim);
            radarSliderAnim = null;
        }
        if (!isFinite(start) || start === target) {
            slider.value = String(target);
            return;
        }
        const startTime = performance.now();
        const step = (now) => {
            const t = Math.min(1, (now - startTime) / duration);
            slider.value = String(start + (target - start) * t);
            if (t < 1) {
                radarSliderAnim = requestAnimationFrame(step);
            } else {
                slider.value = String(target);
                radarSliderAnim = null;
            }
        };
        radarSliderAnim = requestAnimationFrame(step);
    }

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
                showRadarFrame(Math.round(parseFloat(slider.value)));
            });
        }

        loadRadarFrames();
        // The backend's own cache rolls over every 5 min; without this the frame
        // list (and its tile paths) goes stale on a kiosk tab that's never reloaded.
        setInterval(loadRadarFrames, RADAR_REFRESH_MS);
    }

    function loadRadarFrames() {
        if (!radarMap) {
            return;
        }
        fetch("/api/radar/frames")
            .then((res) => res.json())
            .then((data) => {
                radarHost = data.host || radarHost;
                const past = (data.radar && data.radar.past) || [];
                const nowcast = (data.radar && data.radar.nowcast) || [];
                radarFrames = [...past, ...nowcast];
                radarPastCount = past.length;
                pruneRadarLayerCache();
                if (radarFrames.length === 0) {
                    setAll("[data-radar-time]", (el) => { el.textContent = "Radar unavailable"; });
                    return;
                }
                const slider = document.querySelector("[data-radar-slider]");
                if (slider) {
                    slider.max = String(radarFrames.length - 1);
                }
                showRadarFrame(radarPastCount > 0 ? radarPastCount - 1 : radarFrames.length - 1);
                setTimeout(() => radarMap.invalidateSize(), 50);
                preloadRadarFrames();
                if (radarAutoPlayPending) {
                    radarAutoPlayPending = false;
                    toggleRadarPlay();
                }
            })
            .catch(() => {
                setAll("[data-radar-time]", (el) => { el.textContent = "Radar unavailable"; });
            });
    }

    function showRadarFrame(index, syncSlider) {
        if (!radarMap || !radarFrames[index]) {
            return;
        }
        const previousFrame = radarFrames[radarFrameIndex];
        radarFrameIndex = index;
        const frame = radarFrames[index];

        radarLayerFor(frame).setOpacity(0.65);
        if (previousFrame && previousFrame.path !== frame.path) {
            const prevLayer = radarLayerCache.get(previousFrame.path);
            if (prevLayer) {
                prevLayer.setOpacity(0);
            }
        }

        if (syncSlider !== false) {
            const slider = document.querySelector("[data-radar-slider]");
            if (slider) {
                slider.value = String(index);
            }
        }

        const label = new Date(frame.time * 1000).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
        setAll("[data-radar-time]", (el) => {
            el.textContent = label;
        });
    }

    function stopRadarPlayback() {
        radarPlaying = false;
        clearInterval(radarTimer);
        const btn = document.querySelector("[data-radar-play]");
        if (btn) {
            btn.classList.remove("is-playing");
            btn.innerHTML = RADAR_PLAY_ICON;
        }
    }

    function toggleRadarPlay() {
        radarPlaying = !radarPlaying;
        const btn = document.querySelector("[data-radar-play]");
        if (btn) {
            btn.classList.toggle("is-playing", radarPlaying);
            btn.innerHTML = radarPlaying ? RADAR_PAUSE_ICON : RADAR_PLAY_ICON;
        }
        if (radarPlaying) {
            radarTimer = setInterval(() => {
                const nextIndex = (radarFrameIndex + 1) % radarFrames.length;
                showRadarFrame(nextIndex, false);
                animateSliderTo(document.querySelector("[data-radar-slider]"), nextIndex, RADAR_FADE_MS);
            }, RADAR_FRAME_MS);
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

    /* ---- Ceiling fans (fan speed + warm/cool light stages), backed by Home Assistant ---- */
    const FAN_STATE = {};
    document.querySelectorAll("[data-fan-card]").forEach((card) => {
        const id = card.dataset.fanCard;
        FAN_STATE[id] = { name: card.dataset.fanName, speed: 0, warm: 0, cool: 0 };
        card.addEventListener("click", () => openFanModal(id));
    });

    function csrfHeaders() {
        const token = document.querySelector('meta[name="csrf-token"]');
        const header = document.querySelector('meta[name="csrf-header"]');
        const headers = { "Content-Type": "application/json" };
        if (token && header) {
            headers[header.content] = token.content;
        }
        return headers;
    }

    async function loadFanStates() {
        try {
            const res = await fetch("/api/fans");
            if (!res.ok) {
                return;
            }
            const data = await res.json();
            Object.entries(data).forEach(([id, state]) => {
                if (!FAN_STATE[id]) {
                    return;
                }
                FAN_STATE[id].speed = state.speed;
                FAN_STATE[id].warm = state.warm;
                FAN_STATE[id].cool = state.cool;
                syncFanCard(id);
            });
        } catch (err) {
            // Home Assistant unreachable; leave cards showing their last-known state.
        }
    }

    function sendFanValue(id, kind, value) {
        fetch(`/api/fans/${id}/${kind}`, {
            method: "POST",
            headers: csrfHeaders(),
            body: JSON.stringify({ value }),
        }).catch(() => {});
    }

    // Fires on the leading edge (instant if idle), then at most once per `wait` while calls keep
    // coming in, and always finishes with one trailing call so the final value is never dropped.
    function throttleTrailing(fn, wait) {
        let lastCall = 0;
        let timer = null;
        let pendingArgs = null;
        return (...args) => {
            pendingArgs = args;
            const now = Date.now();
            const remaining = wait - (now - lastCall);
            if (remaining <= 0) {
                lastCall = now;
                clearTimeout(timer);
                timer = null;
                fn(...pendingArgs);
            } else if (!timer) {
                timer = setTimeout(() => {
                    lastCall = Date.now();
                    timer = null;
                    fn(...pendingArgs);
                }, remaining);
            }
        };
    }

    const fanSliderThrottled = {};
    const FAN_SLIDER_THROTTLE_MS = 400;

    // For continuous slider drags: updates land periodically while dragging, not just on release.
    function throttledPostFanValue(id, kind, value) {
        const key = `${id}:${kind}`;
        if (!fanSliderThrottled[key]) {
            fanSliderThrottled[key] = throttleTrailing((v) => sendFanValue(id, kind, v), FAN_SLIDER_THROTTLE_MS);
        }
        fanSliderThrottled[key](value);
    }

    const FAN_POLL_MS = 5000;

    function startFanPolling() {
        loadFanStates();
        clearInterval(fanPollTimer);
        fanPollTimer = setInterval(loadFanStates, FAN_POLL_MS);
    }

    function stopFanPolling() {
        clearInterval(fanPollTimer);
        fanPollTimer = null;
    }

    /* ---- Fence lights (not wired to Home Assistant yet - local toggle only) ---- */
    document.querySelectorAll("[data-fence-toggle]").forEach((btn) => {
        btn.addEventListener("click", () => {
            const isOn = btn.getAttribute("aria-pressed") === "true";
            btn.setAttribute("aria-pressed", String(!isOn));
            const chip = btn.querySelector("[data-fence-chip]");
            if (!chip) {
                return;
            }
            chip.classList.toggle("is-on", !isOn);
            chip.querySelector("span").textContent = !isOn ? "On" : "Off";
        });
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

    const FAN_SLIDER_COLORS = { speed: "var(--accent)", warm: "#f2a65a", cool: "#75d4f2" };

    function paintFanSliderTrack(slider, kind) {
        const min = Number(slider.min);
        const max = Number(slider.max);
        const pct = ((Number(slider.value) - min) / (max - min)) * 100;
        const color = FAN_SLIDER_COLORS[kind];
        slider.style.background = `linear-gradient(to right, ${color} ${pct}%, var(--line) ${pct}%)`;
    }

    function wireFanSlider(slider, kind, onChange) {
        const valueEl = document.querySelector(`[data-fan-slider-value="${kind}"]`);
        const min = Number(slider.min);
        const max = Number(slider.max);
        function paint() {
            const v = Number(slider.value);
            if (valueEl) {
                valueEl.textContent = v === 0 ? "Off" : String(v);
            }
            paintFanSliderTrack(slider, kind);
        }
        function commit(v) {
            slider.value = String(v);
            paint();
            onChange(Number(slider.value));
        }
        slider.addEventListener("input", () => commit(Number(slider.value)));
        paint();
        return {
            setValue(v) {
                commit(Math.max(min, Math.min(max, v)));
            },
            step(delta) {
                commit(Math.max(min, Math.min(max, Number(slider.value) + delta)));
            },
        };
    }

    function fanSliderRow(kind, label, max, value) {
        return `
            <div class="fan-modal-slider-row">
                <div class="fan-modal-slider-head">
                    <span class="fan-modal-label">${label}</span>
                    <span class="fan-modal-value" data-fan-slider-value="${kind}">${value === 0 ? "Off" : value}</span>
                </div>
                <div class="fan-slider-track-row">
                    <button type="button" class="fan-slider-step" data-fan-slider-step="${kind}" data-dir="down" aria-label="Decrease ${label}">&minus;</button>
                    <input type="range" class="fan-slider fan-slider-${kind}" min="0" max="${max}" step="1" value="${value}" data-fan-slider="${kind}">
                    <button type="button" class="fan-slider-step" data-fan-slider-step="${kind}" data-dir="up" aria-label="Increase ${label}">+</button>
                </div>
            </div>`;
    }

    function openFanModal(id) {
        const state = FAN_STATE[id];
        if (!state) {
            return;
        }
        const body = `
            <div class="fan-modal-group">
                ${fanSliderRow("speed", "Fan Speed", 10, state.speed)}
                ${fanSliderRow("warm", "Warm Light", 5, state.warm)}
                ${fanSliderRow("cool", "Cool Light", 5, state.cool)}
                <button type="button" class="fan-modal-all-off" data-fan-all-off>All Off</button>
            </div>`;
        openModal(state.name, body);
        const sliders = {
            speed: wireFanSlider(document.querySelector('[data-fan-slider="speed"]'), "speed", (v) => { state.speed = v; syncFanCard(id); throttledPostFanValue(id, "speed", v); }),
            warm: wireFanSlider(document.querySelector('[data-fan-slider="warm"]'), "warm", (v) => { state.warm = v; syncFanCard(id); throttledPostFanValue(id, "warm", v); }),
            cool: wireFanSlider(document.querySelector('[data-fan-slider="cool"]'), "cool", (v) => { state.cool = v; syncFanCard(id); throttledPostFanValue(id, "cool", v); }),
        };
        document.querySelectorAll("[data-fan-slider-step]").forEach((btn) => {
            btn.addEventListener("click", () => {
                sliders[btn.dataset.fanSliderStep].step(btn.dataset.dir === "up" ? 1 : -1);
            });
        });
        const allOffBtn = document.querySelector("[data-fan-all-off]");
        if (allOffBtn) {
            allOffBtn.addEventListener("click", () => {
                sliders.speed.setValue(0);
                sliders.warm.setValue(0);
                sliders.cool.setValue(0);
            });
        }
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

    function aqiBucket(aqi) {
        if (aqi >= 301) return { label: "Hazardous", cls: "pill-bad" };
        if (aqi >= 201) return { label: "Very Unhealthy", cls: "pill-bad" };
        if (aqi >= 151) return { label: "Unhealthy", cls: "pill-bad" };
        if (aqi >= 101) return { label: "Unhealthy for Sensitive Groups", cls: "pill-warn" };
        if (aqi >= 51) return { label: "Moderate", cls: "pill-warn" };
        return { label: "Good", cls: "pill-good" };
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

    // Shared by the home/weather rolling chart and the per-day detail modal chart.
    // `firstIsNow` labels point 0 as "Now" (rolling chart); otherwise all points get clock-time labels.
    // Labels thin out to every 3rd point once there are more than 12 (a full day's 24 hours would collide otherwise).
    function buildHourlyChartSvg(hourly, width, height, opts) {
        opts = opts || {};
        const gradientId = opts.gradientId || "chartGradient";
        if (!hourly || hourly.length === 0) {
            return { viewBox: `0 0 ${width} ${height}`, markup: "" };
        }

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
        const labelStep = hourly.length > 12 ? 3 : 1;

        // The first/last points sit exactly on the chart edge; centering text on them would
        // clip half the label outside the viewBox, so anchor those two inward instead.
        function edgeAnchor(i) {
            if (i === 0) return "start";
            if (i === points.length - 1) return "end";
            return "middle";
        }

        const dots = points.map((p) => `<circle class="chart-dot" cx="${p.x}" cy="${p.y}" r="3.5"/>`).join("");
        const tempLabels = points.map((p, i) => {
            if (i % labelStep !== 0) {
                return "";
            }
            return `<text class="chart-temp-label" x="${p.x}" y="${p.y - 12}" text-anchor="${edgeAnchor(i)}">${hourly[i].tempF}&deg;</text>`;
        }).join("");
        const rainLabels = points.map((p, i) => {
            if (i % labelStep !== 0 || hourly[i].precipChance < 20) {
                return "";
            }
            return `<text class="chart-rain-label" x="${p.x}" y="${p.y - 26}" text-anchor="${edgeAnchor(i)}">${hourly[i].precipChance}%</text>`;
        }).join("");
        const hourLabels = points.map((p, i) => {
            if (i % labelStep !== 0) {
                return "";
            }
            const label = opts.firstIsNow && i === 0 ? "Now" : new Date(hourly[i].time).toLocaleTimeString([], { hour: "numeric" });
            return `<text x="${p.x}" y="${height - 14}" text-anchor="${edgeAnchor(i)}">${label}</text>`;
        }).join("");

        const markup = `
            <defs>
                <linearGradient id="${gradientId}" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0" style="stop-color:var(--accent);stop-opacity:0.35"/>
                    <stop offset="1" style="stop-color:var(--accent);stop-opacity:0"/>
                </linearGradient>
            </defs>
            <path class="chart-fill" d="${areaPath}" fill="url(#${gradientId})"/>
            <path class="chart-line" d="${linePath}"/>
            ${dots}
            ${tempLabels}
            ${rainLabels}
            ${hourLabels}`;

        return { viewBox: `0 0 ${width} ${height}`, markup };
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
            const chart = buildHourlyChartSvg(hourly, width, height, { firstIsNow: true, gradientId: "chartGradient" });
            el.setAttribute("viewBox", chart.viewBox);
            el.innerHTML = chart.markup;
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

        lastDaily = data.daily || [];

        setAll("[data-wx-daily]", (el) => {
            if (data.daily.length === 0) {
                return;
            }
            const scaleMin = Math.min(...data.daily.map((d) => d.loF)) - 2;
            const scaleMax = Math.max(...data.daily.map((d) => d.hiF)) + 2;
            const span = Math.max(1, scaleMax - scaleMin);
            el.innerHTML = data.daily.map((d, i) => {
                const left = ((d.loF - scaleMin) / span) * 100;
                const width = ((d.hiF - d.loF) / span) * 100;
                return `
                    <div class="day-row" data-day-index="${i}">
                        <span>${fmtDay(d.date)}</span>
                        <span class="day-precip">${d.precipChance >= 20 ? d.precipChance + "%" : ""}</span>
                        ${wxIcon(d.icon, "day-icon")}
                        <div class="range-bar"><span style="left:${left}%;width:${width}%"></span></div>
                        <div class="day-range"><span class="lo">${d.loF}&deg;</span><strong>${d.hiF}&deg;</strong></div>
                    </div>`;
            }).join("");
            el.querySelectorAll("[data-day-index]").forEach((row) => {
                row.addEventListener("click", () => openDayDetail(Number(row.dataset.dayIndex)));
            });
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

        setAll("[data-wx-aqi]", (el) => { el.textContent = data.aqi != null ? data.aqi : "—"; });
        renderAirAlert(data.aqi, data.aqiAlert);
    }

    function openDayDetail(index) {
        const d = lastDaily[index];
        if (!d) {
            return;
        }
        const uv = uvBucket(d.uvMax);
        const title = new Date(d.date + "T00:00").toLocaleDateString([], { weekday: "long", month: "long", day: "numeric" });
        const hourly = d.hourly || [];
        const chart = buildHourlyChartSvg(hourly, 360, 130, { gradientId: "dayChartGradient" });
        const chartHtml = hourly.length > 0
            ? `<svg class="wx-chart day-detail-chart" viewBox="${chart.viewBox}" preserveAspectRatio="none">${chart.markup}</svg>`
            : "";
        const body = `
            ${chartHtml}
            <div class="info-rows">
                <div class="info-row"><span>Condition</span><span>${d.label}</span></div>
                <div class="info-row"><span>High / Low</span><span>${d.hiF}&deg; / ${d.loF}&deg;</span></div>
                <div class="info-row"><span>Precip Chance</span><span>${d.precipChance}%</span></div>
                <div class="info-row"><span>UV Index</span><span>${d.uvMax} &middot; ${uv.label}</span></div>
                <div class="info-row"><span>Sunrise</span><span>${d.sunrise ? fmtTime(d.sunrise) : "—"}</span></div>
                <div class="info-row"><span>Sunset</span><span>${d.sunset ? fmtTime(d.sunset) : "—"}</span></div>
            </div>`;
        openModal(title, body);
    }

    function renderAirAlert(aqi, aqiAlert) {
        const widget = document.querySelector("[data-home-air-alert]");
        if (!widget) {
            return;
        }
        if (aqi == null || !aqiAlert) {
            widget.hidden = true;
            return;
        }
        const bucket = aqiBucket(aqi);
        widget.hidden = false;
        const titleEl = document.querySelector("[data-home-air-title]");
        const noteEl = document.querySelector("[data-home-air-note]");
        if (titleEl) {
            titleEl.textContent = `Air Quality: ${bucket.label}`;
        }
        if (noteEl) {
            noteEl.textContent = `AQI ${aqi} · consider limiting time outdoors`;
        }
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

    /* ---- Kitchen: timers (Apple Timer-style wheel picker + ring countdowns) ---- */
    const TIMER_ROW_HEIGHT = 32;
    const timersList = document.querySelector("[data-timers-list]");
    const TIMERS = [];
    let timerSeq = 0;

    function initTimerWheel(el, defaultValue) {
        const items = [...el.querySelectorAll(".timer-wheel-item")];
        let settleTimer = null;
        let synced = false;

        function highlightNearest() {
            const idx = Math.max(0, Math.min(items.length - 1, Math.round(el.scrollTop / TIMER_ROW_HEIGHT)));
            items.forEach((item, i) => item.classList.toggle("is-selected", i === idx));
            return idx;
        }

        el.addEventListener("scroll", () => {
            highlightNearest();
            clearTimeout(settleTimer);
            settleTimer = setTimeout(highlightNearest, 100);
        });

        // The wheel starts inside a hidden (display:none) page, so it has no
        // scrollable height yet. Wait until it's actually laid out before
        // scrolling it to its default value.
        if (typeof ResizeObserver !== "undefined") {
            const ro = new ResizeObserver((entries) => {
                if (!synced && entries[0].contentRect.height > 0) {
                    synced = true;
                    el.scrollTop = defaultValue * TIMER_ROW_HEIGHT;
                    highlightNearest();
                    ro.disconnect();
                }
            });
            ro.observe(el);
        }

        el.getValue = highlightNearest;
    }

    const timerHourWheel = document.querySelector('[data-timer-wheel="hours"]');
    const timerMinuteWheel = document.querySelector('[data-timer-wheel="minutes"]');
    const timerSecondWheel = document.querySelector('[data-timer-wheel="seconds"]');
    if (timerHourWheel) {
        initTimerWheel(timerHourWheel, 0);
    }
    if (timerMinuteWheel) {
        initTimerWheel(timerMinuteWheel, 5);
    }
    if (timerSecondWheel) {
        initTimerWheel(timerSecondWheel, 0);
    }

    const TIMER_COLORS = ["#75d4f2", "#f2a65a", "#9be29b", "#f28fa5", "#b79df2"];

    function formatClock(totalSeconds) {
        const s = Math.max(0, Math.round(totalSeconds));
        const h = Math.floor(s / 3600);
        const m = Math.floor((s % 3600) / 60);
        const sec = s % 60;
        return h > 0
            ? `${h}:${String(m).padStart(2, "0")}:${String(sec).padStart(2, "0")}`
            : `${m}:${String(sec).padStart(2, "0")}`;
    }

    function renderHomeTimers() {
        const widget = document.querySelector("[data-home-timers]");
        if (!widget) {
            return;
        }
        if (TIMERS.length === 0) {
            widget.hidden = true;
            return;
        }
        widget.hidden = false;
        const list = widget.querySelector(".home-timers-list");
        list.innerHTML = TIMERS.map((t) => {
            const done = t.remaining <= 0;
            return `
                <div class="home-timer-row">
                    <span class="home-timer-dot" style="background:${t.color || "var(--accent)"}"></span>
                    <span class="home-timer-label">${t.label}</span>
                    <span class="home-timer-clock">${done ? "Done" : formatClock(t.remaining)}</span>
                </div>`;
        }).join("");
    }

    function beep() {
        try {
            const ctx = new (window.AudioContext || window.webkitAudioContext)();
            [0, 260, 520].forEach((delayMs) => {
                const osc = ctx.createOscillator();
                const gain = ctx.createGain();
                osc.type = "sine";
                osc.frequency.value = 880;
                const startAt = ctx.currentTime + delayMs / 1000;
                gain.gain.setValueAtTime(0.0001, startAt);
                gain.gain.exponentialRampToValueAtTime(0.3, startAt + 0.02);
                gain.gain.exponentialRampToValueAtTime(0.0001, startAt + 0.2);
                osc.connect(gain).connect(ctx.destination);
                osc.start(startAt);
                osc.stop(startAt + 0.22);
            });
        } catch (err) {
            // audio unavailable; the timer ring still shows visually
        }
    }

    function renderTimers() {
        renderHomeTimers();
        if (!timersList) {
            return;
        }
        if (TIMERS.length === 0) {
            timersList.innerHTML = `<p class="modal-empty">No timers running. Set one above.</p>`;
            timersList.classList.remove("is-scrollable");
            return;
        }
        timersList.innerHTML = TIMERS.map((t) => {
            const done = t.remaining <= 0;
            const pct = done ? 0 : Math.max(0, Math.min(100, Math.round((t.remaining / t.duration) * 100)));
            const stateClass = done ? " is-done" : (t.running ? "" : " is-paused");
            const ringStyle = `--pct:${pct}${t.color ? `;--ring-color:${t.color}` : ""}`;
            const colorBtn = done ? "" : `<button type="button" class="timer-color-btn" style="background:${t.color || "var(--accent)"}" data-timer-color-open="${t.id}" aria-label="Choose timer color"></button>`;
            return `
                <div class="timer-card${stateClass}">${colorBtn}
                    <span class="timer-card-label">${t.label}</span>
                    <div class="timer-ring" style="${ringStyle}">
                        <div class="timer-ring-readout">${done ? "Done" : formatClock(t.remaining)}</div>
                    </div>
                    <div class="timer-card-actions">
                        <button type="button" class="timer-action-btn timer-cancel" data-timer-cancel="${t.id}">${done ? "Dismiss" : "Cancel"}</button>
                        ${done ? "" : `<button type="button" class="timer-action-btn timer-pause" data-timer-pause="${t.id}">${t.running ? "Pause" : "Resume"}</button>`}
                    </div>
                </div>`;
        }).join("");
        timersList.classList.toggle("is-scrollable", timersList.scrollHeight > timersList.clientHeight + 1);
        timersList.querySelectorAll("[data-timer-cancel]").forEach((btn) => {
            btn.addEventListener("click", () => {
                const id = Number(btn.dataset.timerCancel);
                const idx = TIMERS.findIndex((t) => t.id === id);
                if (idx !== -1) {
                    TIMERS.splice(idx, 1);
                }
                renderTimers();
            });
        });
        timersList.querySelectorAll("[data-timer-pause]").forEach((btn) => {
            btn.addEventListener("click", () => {
                const id = Number(btn.dataset.timerPause);
                const timer = TIMERS.find((t) => t.id === id);
                if (timer) {
                    timer.running = !timer.running;
                }
                renderTimers();
            });
        });
        timersList.querySelectorAll("[data-timer-color-open]").forEach((btn) => {
            btn.addEventListener("click", () => {
                const id = Number(btn.dataset.timerColorOpen);
                const timer = TIMERS.find((t) => t.id === id);
                const body = TIMER_COLORS.map((c) =>
                    `<button type="button" class="timer-color-swatch-lg${timer && timer.color === c ? " is-selected" : ""}" style="background:${c}" data-timer-color-pick="${id}" data-color="${c}" aria-label="Set timer color"></button>`
                ).join("");
                openModal("Timer Color", `<div class="timer-color-modal">${body}</div>`);
                document.querySelectorAll("[data-timer-color-pick]").forEach((swatch) => {
                    swatch.addEventListener("click", () => {
                        const t2 = TIMERS.find((t) => t.id === Number(swatch.dataset.timerColorPick));
                        if (t2) {
                            t2.color = swatch.dataset.color;
                        }
                        closeModal();
                        renderTimers();
                    });
                });
            });
        });
    }

    function startTimer(totalSeconds) {
        if (!totalSeconds || totalSeconds <= 0) {
            return;
        }
        timerSeq += 1;
        TIMERS.push({
            id: timerSeq,
            label: `${formatClock(totalSeconds)} timer`,
            duration: totalSeconds,
            remaining: totalSeconds,
            running: true,
            alerted: false,
            color: null,
        });
        renderTimers();
    }

    const timerCustomStart = document.querySelector("[data-timer-custom-start]");
    if (timerCustomStart) {
        timerCustomStart.addEventListener("click", () => {
            const h = timerHourWheel ? timerHourWheel.getValue() : 0;
            const m = timerMinuteWheel ? timerMinuteWheel.getValue() : 0;
            const s = timerSecondWheel ? timerSecondWheel.getValue() : 0;
            startTimer(h * 3600 + m * 60 + s);
        });
    }

    renderTimers();
    setInterval(() => {
        TIMERS.forEach((t) => {
            if (t.running && t.remaining > 0) {
                t.remaining -= 1;
                if (t.remaining <= 0) {
                    t.remaining = 0;
                    t.running = false;
                    if (!t.alerted) {
                        t.alerted = true;
                        beep();
                    }
                }
            }
        });
        renderTimers();
    }, 1000);

    /* ---- Kitchen: food info tiles (grid <-> detail with back button) ---- */
    const infoGrid = document.querySelector("[data-info-grid]");
    const infoDetail = document.querySelector("[data-info-detail]");
    const infoDetailTitle = document.querySelector("[data-info-detail-title]");
    const infoDetailBody = document.querySelector("[data-info-detail-body]");
    const infoBackBtn = document.querySelector("[data-info-back]");

    if (infoGrid && infoDetail && infoDetailTitle && infoDetailBody) {
        infoGrid.querySelectorAll("[data-info-open]").forEach((tile) => {
            tile.addEventListener("click", () => {
                const template = document.querySelector(`[data-info-template="${tile.dataset.infoOpen}"]`);
                infoDetailTitle.textContent = tile.dataset.infoLabel || "";
                infoDetailBody.innerHTML = template ? template.innerHTML : "Info coming soon.";
                infoGrid.hidden = true;
                infoDetail.hidden = false;
                infoDetailBody.classList.toggle("is-scrollable", infoDetailBody.scrollHeight > infoDetailBody.clientHeight + 1);
            });
        });
    }

    if (infoBackBtn) {
        infoBackBtn.addEventListener("click", () => {
            infoDetail.hidden = true;
            infoGrid.hidden = false;
        });
    }

    /* ---- Kitchen: unit converter ---- */
    function factorConvert(factors) {
        return (value, from, to) => (value * factors[from]) / factors[to];
    }

    function tempConvert(value, from, to) {
        if (from === to) {
            return value;
        }
        const celsius = from === "f" ? ((value - 32) * 5) / 9 : value;
        return to === "f" ? (celsius * 9) / 5 + 32 : celsius;
    }

    const CONVERT_FNS = {
        volume: factorConvert({ tsp: 4.92892, tbsp: 14.7868, cup: 236.588, flOz: 29.5735, pint: 473.176, quart: 946.353, gallon: 3785.41, ml: 1, l: 1000 }),
        weight: factorConvert({ oz: 28.3495, lb: 453.592, g: 1, kg: 1000 }),
        temp: tempConvert,
    };

    function formatConvertResult(n) {
        if (!isFinite(n)) {
            return "0";
        }
        return (Math.round(n * 1000) / 1000).toString();
    }

    document.querySelectorAll("[data-convert-panel]").forEach((panel) => {
        const convert = CONVERT_FNS[panel.dataset.convertPanel];
        const input = panel.querySelector("[data-convert-input]");
        const fromSel = panel.querySelector("[data-convert-from]");
        const toSel = panel.querySelector("[data-convert-to]");
        const result = panel.querySelector("[data-convert-result]");
        if (!convert || !input || !fromSel || !toSel || !result) {
            return;
        }

        function recompute() {
            const value = parseFloat(input.value);
            if (isNaN(value)) {
                result.textContent = "0";
                return;
            }
            result.textContent = formatConvertResult(convert(value, fromSel.value, toSel.value));
        }

        [input, fromSel, toSel].forEach((el) => el.addEventListener("input", recompute));
        recompute();
    });

    const convertTabs = document.querySelector("[data-convert-tabs]");
    if (convertTabs) {
        convertTabs.querySelectorAll("[data-convert-tab]").forEach((btn) => {
            btn.addEventListener("click", () => {
                convertTabs.querySelectorAll("[data-convert-tab]").forEach((b) => b.classList.remove("is-selected"));
                btn.classList.add("is-selected");
                document.querySelectorAll("[data-convert-panel]").forEach((panel) => {
                    panel.hidden = panel.dataset.convertPanel !== btn.dataset.convertTab;
                });
            });
        });
    }

    /* ---- Security: real camera snapshots (server-side RTSP -> JPEG polling) ---- */
    const CAMERA_POLL_MS = 2000;
    const CAMERA_ICON_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="7" width="13" height="10" rx="2"/><path d="M16 10.2 21 8v8l-5-2.2Z"/><circle cx="9" cy="12" r="2.3"/></svg>';
    let cameraList = [];

    function renderCameraTiles() {
        const grid = document.querySelector("[data-camera-grid]");
        if (!grid) {
            return;
        }
        if (cameraList.length === 0) {
            grid.innerHTML = `<p class="modal-empty">No cameras configured.</p>`;
            return;
        }
        grid.innerHTML = cameraList.map((cam) => `
            <div class="camera-tile" data-camera-id="${cam.id}" data-camera-name="${cam.name}" tabindex="0" role="button" aria-label="View ${cam.name} camera">
                <span class="cam-icon">${CAMERA_ICON_SVG}</span>
                <img class="cam-feed" data-camera-img alt="${cam.name}">
                <span class="cam-live" data-camera-status>Live</span>
                <span class="cam-label">${cam.name}</span>
            </div>`).join("");
        refreshCameraSnapshots();
        grid.querySelectorAll("[data-camera-id]").forEach((tile) => {
            tile.addEventListener("click", () => openCameraViewer(tile.dataset.cameraId, tile.dataset.cameraName));
            tile.addEventListener("keydown", (event) => {
                if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault();
                    openCameraViewer(tile.dataset.cameraId, tile.dataset.cameraName);
                }
            });
        });
    }

    /* ---- Security: full live view for a single tapped camera ---- */
    const CAMERA_VIEWER_POLL_MS = 800;

    function openCameraViewer(id, name) {
        const viewer = document.querySelector("[data-camera-viewer]");
        const title = document.querySelector("[data-camera-viewer-title]");
        if (!viewer) {
            return;
        }
        cameraViewerId = id;
        if (title) {
            title.textContent = name || "";
        }
        viewer.hidden = false;
        refreshCameraViewer();
        clearInterval(cameraViewerTimer);
        cameraViewerTimer = setInterval(refreshCameraViewer, CAMERA_VIEWER_POLL_MS);
    }

    function closeCameraViewer() {
        cameraViewerId = null;
        clearInterval(cameraViewerTimer);
        cameraViewerTimer = null;
        const viewer = document.querySelector("[data-camera-viewer]");
        if (viewer) {
            viewer.hidden = true;
        }
    }

    function refreshCameraViewer() {
        if (!cameraViewerId) {
            return;
        }
        const img = document.querySelector("[data-camera-viewer-img]");
        const status = document.querySelector("[data-camera-viewer-status]");
        if (!img) {
            return;
        }
        const probe = new Image();
        probe.onload = () => {
            img.src = probe.src;
            if (status) {
                status.textContent = "Live";
                status.classList.remove("is-offline");
            }
        };
        probe.onerror = () => {
            if (status) {
                status.textContent = "Offline";
                status.classList.add("is-offline");
            }
        };
        probe.src = `/api/cameras/${cameraViewerId}/snapshot?t=${Date.now()}`;
    }

    const cameraViewerCloseBtn = document.querySelector("[data-camera-viewer-close]");
    if (cameraViewerCloseBtn) {
        cameraViewerCloseBtn.addEventListener("click", closeCameraViewer);
    }

    function refreshCameraSnapshots() {
        const securityPage = document.querySelector('[data-page="security"]');
        if (!securityPage || !securityPage.classList.contains("is-active")) {
            return;
        }
        document.querySelectorAll("[data-camera-id]").forEach((tile) => {
            const id = tile.dataset.cameraId;
            const img = tile.querySelector("[data-camera-img]");
            const status = tile.querySelector("[data-camera-status]");
            if (!img) {
                return;
            }
            const probe = new Image();
            probe.onload = () => {
                img.src = probe.src;
                img.classList.add("is-loaded");
                tile.classList.remove("is-offline");
                if (status) {
                    status.textContent = "Live";
                }
            };
            probe.onerror = () => {
                tile.classList.add("is-offline");
                if (status) {
                    status.textContent = "Offline";
                }
            };
            probe.src = `/api/cameras/${id}/snapshot?t=${Date.now()}`;
        });
    }

    fetch("/api/cameras")
        .then((res) => res.json())
        .then((cams) => {
            cameraList = cams;
            renderCameraTiles();
        })
        .catch(() => {});

    setInterval(refreshCameraSnapshots, CAMERA_POLL_MS);

    /* ---- Idle timeout: return to Home after 5 minutes with no interaction ---- */
    const IDLE_TIMEOUT_MS = 5 * 60 * 1000;
    let idleTimer = null;

    function resetIdleTimer() {
        clearTimeout(idleTimer);
        idleTimer = setTimeout(() => {
            if (location.hash.slice(1) !== "home") {
                showPage("home");
            }
        }, IDLE_TIMEOUT_MS);
    }

    ["click", "touchstart", "mousemove", "keydown", "scroll", "wheel"].forEach((eventName) => {
        window.addEventListener(eventName, resetIdleTimer, { passive: true });
    });
    resetIdleTimer();
})();
