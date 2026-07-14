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

    /* ---- Theme switcher ---- */
    const THEME_KEY = "dashboard-theme";
    const swatches = [...document.querySelectorAll("[data-theme-choice]")];

    function applyTheme(theme, persist) {
        if (theme === "dusk") {
            document.documentElement.removeAttribute("data-theme");
        } else {
            document.documentElement.setAttribute("data-theme", theme);
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

    /* ---- Thermostat stepper ---- */
    document.querySelectorAll("[data-stepper]").forEach((stepper) => {
        const key = stepper.dataset.stepper;
        const targets = [...document.querySelectorAll(`[data-stepper-target="${key}"]`)];
        const ring = document.querySelector(`[data-thermo-ring="${key}"]`);
        const min = parseInt(stepper.dataset.min || "60", 10);
        const max = parseInt(stepper.dataset.max || "85", 10);
        let value = parseInt(stepper.dataset.value || "72", 10);

        function render() {
            targets.forEach((el) => { el.textContent = value + "°"; });
            if (ring) {
                const pct = Math.round(((value - min) / (max - min)) * 100);
                ring.style.setProperty("--pct", String(pct));
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
    });

    /* ---- Now-playing transport (play/pause icon swap only) ---- */
    document.querySelectorAll("[data-play-pause]").forEach((btn) => {
        btn.addEventListener("click", () => btn.classList.toggle("is-playing"));
    });

    /* ---- Today ticker (cycles only if more than one event) ---- */
    document.querySelectorAll("[data-today-ticker]").forEach((ticker) => {
        const slides = [...ticker.querySelectorAll(".today-slide")];
        if (slides.length <= 1) {
            return;
        }
        let index = slides.findIndex((slide) => slide.classList.contains("is-active"));
        if (index < 0) {
            index = 0;
            slides[0].classList.add("is-active");
        }
        setInterval(() => {
            slides[index].classList.remove("is-active");
            index = (index + 1) % slides.length;
            slides[index].classList.add("is-active");
        }, 4000);
    });

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
})();
