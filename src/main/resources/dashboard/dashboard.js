/* ============================================================================
   BrinsonDashboard(mountEl, opts) — builds one fully-live dashboard.
   opts = { variant:"desk"|"studio"|"ledger", theme:"light"|"dark",
            pf:Number, heroKey:String }
   Returns { destroy }. Reuses the original analytics:
   TWR geometric linking · TE = stdev(active)·√252 · IR = mean(active)·252/TE
   · max drawdown on the compounded path. Attribution / contributors / weights
   are full-period.
   ========================================================================== */
window.BrinsonDashboard = function (mount, opts) {
  "use strict";
  var D = window.BRINSON_DATA;
  var variant = opts.variant || "desk";
  var state = {
    variant: variant,
    theme: opts.theme || "light",
    pf: opts.pf == null ? 6 : opts.pf,
    range: "all",
    hero: opts.heroKey || "active",
    tweakable: !!opts.tweakable,
    layout: opts.layout || (variant === "desk" ? "grid" : "hero"),
    density: opts.density || (variant === "ledger" ? "roomy" : "cozy"),
    radius: opts.radius,
    cardStyle: opts.cardStyle || (variant === "studio" ? "shadow" : variant === "desk" ? "outline" : "flat"),
    accent: opts.accent || null,
    lineWeight: opts.lineWeight,
    areaFill: opts.areaFill != null ? opts.areaFill : (variant === "studio"),
    gridLines: opts.gridLines || "light",
    fit: opts.fit === false ? "scroll" : "fill"
  };
  var charts = {};

  // ── math helpers (from the original) ──────────────────────────────────
  function fmtPct(x, dp) { return (x >= 0 ? "+" : "") + (100 * x).toFixed(dp == null ? 2 : dp) + "%"; }
  function fmtBps(x) { return (x >= 0 ? "+" : "") + (10000 * x).toFixed(1); }
  function mean(a) { var s = 0; for (var i = 0; i < a.length; i++) s += a[i]; return s / a.length; }
  function stdev(a) { var m = mean(a), s = 0; for (var i = 0; i < a.length; i++) s += (a[i] - m) * (a[i] - m); return Math.sqrt(s / (a.length - 1)); }
  function cum(a) { var o = [], g = 1; for (var i = 0; i < a.length; i++) { g *= 1 + a[i]; o.push(g - 1); } return o; }
  function maxDd(a) { var g = 1, p = 1, dd = 0; for (var i = 0; i < a.length; i++) { g *= 1 + a[i]; if (g > p) p = g; dd = Math.min(dd, g / p - 1); } return dd; }
  function slice(a) { return state.range === "all" ? a : a.slice(Math.max(0, a.length - parseInt(state.range, 10))); }

  // sector categorical palette (cool, harmonious)
  var PALETTE = ["#4F6BED", "#1F97A6", "#6E62C7", "#3FA06A", "#C98A3C", "#C7586A",
    "#5B6B86", "#5FA8DE", "#9AA13E", "#A86BB6", "#46A0A0"];

  // Canvas text ignores CSS media queries; bump chart fonts on phones.
  var MOBILE = typeof matchMedia !== "undefined" && matchMedia("(max-width: 600px)").matches;
  var TICK = MOBILE ? 11 : 9, TICK_WF = MOBILE ? 10.5 : 8.5, TICK_Y = MOBILE ? 11.5 : 9.5;
  var TT_TITLE = MOBILE ? 12 : 10, TT_BODY = MOBILE ? 13 : 11;

  function shortSec(i) {
    return (D.sectorsShort && D.sectorsShort[i]) || D.sectors[i].split(" ")[0];
  }
  function tok(n) { return getComputedStyle(root).getPropertyValue(n).trim(); }
  function hexA(h, a) {
    h = h.replace("#", "");
    var r = parseInt(h.substr(0, 2), 16), g = parseInt(h.substr(2, 2), 16), b = parseInt(h.substr(4, 2), 16);
    return "rgba(" + r + "," + g + "," + b + "," + a + ")";
  }

  // ── DOM scaffold ──────────────────────────────────────────────────────
  var root = document.createElement("div");
  root.className = "brinson";
  root.setAttribute("data-variant", state.variant);
  root.setAttribute("data-theme", state.theme);

  function applyStyle() {
    root.setAttribute("data-theme", state.theme);
    root.setAttribute("data-fit", state.fit);
    root.setAttribute("data-kpilayout", state.layout);
    root.setAttribute("data-cardstyle", state.cardStyle);
    root.setAttribute("data-density", state.density);
    // Always bake provided style opts: production passes the values chosen in
    // the design canvas but runs with the tweaks panel disabled.
    {
      var dmap = { compact: ["10px", "14px"], cozy: ["16px", "18px"], roomy: ["22px", "24px"] };
      var dd = dmap[state.density] || dmap.cozy;
      root.style.setProperty("--gap", dd[0]);
      root.style.setProperty("--pad", dd[1]);
      if (state.radius != null) root.style.setProperty("--radius", state.radius + "px");
      if (state.accent) root.style.setProperty("--accent", state.accent);
      else root.style.removeProperty("--accent");
    }
  }

  var MARK = '<svg class="bx-mark" viewBox="0 0 30 30" fill="none">' +
    '<rect x="2" y="17" width="5" height="11" rx="1" fill="currentColor"/>' +
    '<rect x="9.5" y="11" width="5" height="17" rx="1" fill="currentColor" opacity="0.78"/>' +
    '<rect x="17" y="6" width="5" height="22" rx="1" fill="currentColor" opacity="0.56"/>' +
    '<rect x="24.5" y="2" width="3.5" height="26" rx="1" fill="currentColor"/></svg>';

  function EXPAND(k) {
    return '<button class="bx-expand" data-expand="' + k + '" title="Expand" aria-label="Expand">' +
      '<svg width="13" height="13" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M7 1h4v4M5 11H1V7M11 1L7.2 4.8M1 11l3.8-3.8"/></svg></button>';
  }

  root.innerHTML =
    '<header class="bx-header">' +
      '<div class="bx-brand">' + MARK +
        '<div class="bx-titles"><h1>Brinson</h1><span class="bx-tag">Attribution Engine</span></div>' +
      '</div>' +
      '<div class="bx-controls">' +
        '<select class="bx-select" data-pf></select>' +
        '<div class="bx-ranges" data-ranges>' +
          '<button data-r="all" class="on">All</button>' +
          '<button data-r="252">1Y</button>' +
          '<button data-r="126">6M</button>' +
          '<button data-r="63">3M</button>' +
        '</div>' +
        '<span class="bx-period" data-period></span>' +
        '<button class="bx-theme bx-tweaksbtn" data-tweaks-open title="Open Tweaks" style="display:none">' +
          '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"><line x1="2.5" y1="5.5" x2="13.5" y2="5.5"/><line x1="2.5" y1="10.5" x2="13.5" y2="10.5"/><circle cx="6" cy="5.5" r="1.9" fill="var(--surface)"/><circle cx="10" cy="10.5" r="1.9" fill="var(--surface)"/></svg>' +
        '</button>' +
        '<button class="bx-theme" data-theme-toggle title="Toggle theme"></button>' +
        '<a class="bx-theme" href="guide.html" title="How this dashboard works" style="text-decoration:none;display:inline-flex;align-items:center;justify-content:center">?</a>' +
      '</div>' +
    '</header>' +
    '<div class="bx-body">' +
      '<div class="bx-kpis" data-kpis></div>' +
      '<section class="bx-card bx-perf">' +
        '<div class="bx-cardhead"><h2>Cumulative return <span class="bx-sub2">vs benchmark · TWR</span></h2>' +
          '<div class="bx-head-right"><div class="bx-legend" data-perflegend></div>' + EXPAND("perf") + '</div></div>' +
        '<div class="bx-chartwrap"><canvas data-perf></canvas></div>' +
      '</section>' +
      '<div class="bx-grid2">' +
        '<section class="bx-card bx-attr">' +
          '<div class="bx-cardhead"><h2>Brinson–Fachler attribution <span class="bx-sub2">Cariño-linked · bps</span></h2>' + EXPAND("wf") + '</div>' +
          '<div class="bx-chartwrap bx-wf-wrap"><canvas data-wf></canvas></div>' +
          '<div class="bx-tablewrap" data-atab></div>' +
        '</section>' +
        '<div class="bx-col">' +
          '<section class="bx-card">' +
            '<div class="bx-cardhead"><h2>Contributors <span class="bx-sub2">full period · bps</span></h2>' + EXPAND("contrib") + '</div>' +
            '<div class="bx-chartwrap bx-contrib-wrap"><canvas data-contrib></canvas></div>' +
          '</section>' +
          '<section class="bx-card">' +
            '<div class="bx-cardhead"><h2>Sector weights <span class="bx-sub2">weekly</span></h2>' +
              '<div class="bx-head-right"><div class="bx-legend" data-wtslegend style="flex-wrap:wrap;max-width:300px;justify-content:flex-end"></div>' + EXPAND("wts") + '</div></div>' +
            '<div class="bx-chartwrap bx-wts-wrap"><canvas data-wts></canvas></div>' +
          '</section>' +
        '</div>' +
      '</div>' +
    '</div>' +
    '<div class="bx-modal" data-modal>' +
      '<div class="bx-modal-card">' +
        '<div class="bx-modal-head">' +
          '<div><h2 data-modal-title></h2><span class="bx-sub2" data-modal-sub></span></div>' +
          '<button class="bx-modal-x" data-modal-close aria-label="Close">✕</button>' +
        '</div>' +
        '<div class="bx-modal-body" data-modal-body></div>' +
      '</div>' +
    '</div>';

  mount.appendChild(root);

  // canvas helper — make canvas fill its (sized) wrapper
  root.querySelectorAll(".bx-chartwrap").forEach(function (w) {
    w.style.position = "relative";
    var c = w.querySelector("canvas");
    c.style.position = "absolute"; c.style.inset = "0"; c.style.display = "block";
  });

  // populate portfolio select
  var sel = root.querySelector("[data-pf]");
  D.portfolios.forEach(function (p, i) {
    var o = document.createElement("option"); o.value = i; o.textContent = p.name; sel.appendChild(o);
  });
  sel.value = state.pf;

  // ── KPI model ─────────────────────────────────────────────────────────
  function computeKpis() {
    var p = D.portfolios[state.pf];
    var rp = slice(p.rp), rb = slice(D.rb), dates = slice(D.dates);
    var act = rp.map(function (x, i) { return x - rb[i]; });
    var cp = cum(rp), cb = cum(rb);
    var twr = cp[cp.length - 1], btwr = cb[cb.length - 1];
    var te = stdev(act) * Math.sqrt(252);
    var ir = te > 0 ? mean(act) * 252 / te : null;
    return {
      dates: dates, cp: cp, cb: cb,
      list: [
        { key: "twr", k: "Total return", v: fmtPct(twr), signed: true, raw: twr },
        { key: "bench", k: "Benchmark", v: fmtPct(btwr), signed: true, raw: btwr },
        { key: "active", k: "Active", v: fmtPct(twr - btwr), signed: true, raw: twr - btwr },
        { key: "te", k: "Tracking error", v: fmtPct(te), signed: false, raw: te },
        { key: "ir", k: "Info ratio", v: ir == null ? "n/a" : ir.toFixed(2), signed: true, raw: ir },
        { key: "mdd", k: "Max drawdown", v: fmtPct(maxDd(rp)), signed: true, raw: maxDd(rp) }
      ]
    };
  }

  function signClass(m) {
    if (!m.signed) return "";
    return (typeof m.raw === "number" && m.raw < 0) ? "neg" : "pos";
  }

  function kpiCardHTML(m, hero) {
    return '<div class="kpi selectable' + (hero ? " hero" : "") + '" data-key="' + m.key + '">' +
      (hero ? '<span class="kpi-pin">HERO</span>' : "") +
      '<div class="kpi-k">' + m.k + '</div>' +
      '<div class="kpi-v ' + signClass(m) + '">' + m.v + '</div>' +
      (hero ? '<div class="kpi-meta" data-herometa></div>' : "") +
      '</div>';
  }

  function renderKpis(model) {
    var box = root.querySelector("[data-kpis]");
    var hero = state.hero;
    if (state.layout === "grid") {
      box.innerHTML = model.list.map(function (m) { return kpiCardHTML(m, m.key === hero); }).join("");
    } else {
      var heroM = model.list.filter(function (m) { return m.key === hero; })[0] || model.list[0];
      var rest = model.list.filter(function (m) { return m.key !== heroM.key; });
      box.innerHTML = kpiCardHTML(heroM, true) +
        '<div class="kpi-rest">' + rest.map(function (m) { return kpiCardHTML(m, false); }).join("") + "</div>";
    }
    // hero meta line
    var hm = box.querySelector("[data-herometa]");
    if (hm) {
      var d = model.dates;
      hm.textContent = d[0] + " → " + d[d.length - 1] + "  ·  " + d.length + " trading days";
    }
    // click to set hero
    box.querySelectorAll(".kpi").forEach(function (el) {
      el.addEventListener("click", function () {
        var key = el.getAttribute("data-key");
        if (key === state.hero) return;
        state.hero = key; render();
      });
    });
  }

  // ── charts ────────────────────────────────────────────────────────────
  function mk(id, cfg) { if (charts[id]) charts[id].destroy(); charts[id] = new Chart(root.querySelector("[data-" + id + "]"), cfg); }

  function baseScales(extra) {
    var grid = tok("--grid"), faint = tok("--faint");
    var showX = state.gridLines === "full";
    var showY = state.gridLines !== "none";
    var x = Object.assign({ grid: { display: showX, color: grid, drawBorder: false }, ticks: { color: faint, font: { family: "IBM Plex Mono", size: TICK }, maxTicksLimit: 7, maxRotation: 0 } }, (extra && extra.x) || {});
    var y = Object.assign({ grid: { display: showY, color: grid, drawBorder: false }, border: { display: false }, ticks: { color: faint, font: { family: "IBM Plex Mono", size: TICK } } }, (extra && extra.y) || {});
    return { x: x, y: y };
  }

  function tooltipCfg(extra) {
    var dark = state.theme === "dark";
    return Object.assign({
      backgroundColor: dark ? "#0a0c11" : "#161b26",
      titleColor: "#fff", bodyColor: "#e7eaf1", borderColor: "rgba(255,255,255,0.08)", borderWidth: 1,
      padding: 9, cornerRadius: 6, titleFont: { family: "IBM Plex Mono", size: TT_TITLE },
      bodyFont: { family: "IBM Plex Mono", size: TT_BODY }, displayColors: false
    }, extra || {});
  }

  // Vertical guide pinned to the hovered date on the cumulative-return chart,
  // so it is explicit where in time the tooltip values apply. Drawn whenever the
  // index-mode tooltip is active (mouse or touch), in cards and in the modal.
  var bxCrosshair = {
    id: "bxCrosshair",
    afterDatasetsDraw: function (chart) {
      var t = chart.tooltip;
      if (!t || !t.getActiveElements || t.getActiveElements().length === 0 || t.opacity === 0) return;
      var x = t.getActiveElements()[0].element.x;
      var area = chart.chartArea, c = chart.ctx;
      if (x < area.left || x > area.right) return;
      c.save();
      c.beginPath();
      c.setLineDash([4, 3]);
      c.lineWidth = 1;
      c.strokeStyle = tok("--faint");
      c.globalAlpha = 0.85;
      c.moveTo(x, area.top);
      c.lineTo(x, area.bottom);
      c.stroke();
      c.restore();
    }
  };

  function perfCfg(model) {
    var accent = tok("--accent"), muted = tok("--faint"), fillStudio = state.areaFill;
    var tension = state.variant === "studio" ? 0.28 : 0;
    var lw = state.lineWeight != null ? state.lineWeight : (state.variant === "desk" ? 1.75 : (state.variant === "ledger" ? 2.25 : 2.5));
    return {
      type: "line",
      plugins: [bxCrosshair],
      data: {
        labels: model.dates,
        datasets: [
          { label: "Portfolio", data: model.cp.map(function (x) { return 100 * x; }), borderColor: accent, backgroundColor: fillStudio ? hexA(accent.startsWith("#") ? accent : "#1f97a6", 0.12) : "transparent", fill: fillStudio, pointRadius: 0, borderWidth: lw, tension: tension },
          { label: "Benchmark", data: model.cb.map(function (x) { return 100 * x; }), borderColor: muted, borderDash: state.variant === "ledger" ? [4, 3] : [], pointRadius: 0, borderWidth: state.variant === "desk" ? 1.25 : 1.5, tension: tension }
        ]
      },
      options: {
        responsive: true, maintainAspectRatio: false, animation: false,
        interaction: { mode: "index", intersect: false },
        plugins: { legend: { display: false }, tooltip: tooltipCfg({ callbacks: { label: function (c) { return c.dataset.label + "  " + (c.raw >= 0 ? "+" : "") + c.raw.toFixed(2) + "%"; } } }) },
        scales: baseScales({ y: { ticks: { callback: function (v) { return v + "%"; } } } })
      }
    };
  }
  function perfLegend() {
    return '<span><i style="background:' + tok("--accent") + '"></i>Portfolio</span>' +
      '<span><i style="background:' + tok("--faint") + '"></i>Benchmark</span>';
  }
  function renderPerf(model) {
    root.querySelector("[data-perflegend]").innerHTML = perfLegend();
    mk("perf", perfCfg(model));
  }

  function attributionRows() {
    var p = D.portfolios[state.pf];
    var rows = D.sectors.map(function (s, i) {
      var a = p.attribution[i];
      return { s: s, wp: a.wp, wb: a.wb, a: a.a, sel: a.s, i: a.i, tot: a.a + a.s + a.i };
    });
    rows.sort(function (x, y) { return y.tot - x.tot; });
    return rows;
  }

  function renderWaterfall(rows) {
    mk("wf", wfCfg(rows));
  }
  function wfCfg(rows) {
    var up = tok("--up"), down = tok("--down"), accent = tok("--accent");
    var run = 0, bars = [], colors = [];
    rows.forEach(function (r) { bars.push([10000 * run, 10000 * (run + r.tot)]); run += r.tot; colors.push(r.tot >= 0 ? up : down); });
    bars.push([0, 10000 * run]); colors.push(accent);
    return {
      type: "bar",
      data: { labels: rows.map(function (r) { return shortSec(D.sectors.indexOf(r.s)); }).concat(["Active"]), datasets: [{ data: bars, backgroundColor: colors, borderSkipped: false, borderRadius: state.variant === "studio" ? 3 : 1, barPercentage: 0.74 }] },
      options: {
        responsive: true, maintainAspectRatio: false, animation: false,
        plugins: { legend: { display: false }, tooltip: tooltipCfg({ callbacks: { label: function (c) { return (c.raw[1] - c.raw[0]).toFixed(1) + " bps"; } } }) },
        scales: baseScales({ x: { ticks: { maxRotation: 38, minRotation: 38, font: { family: "IBM Plex Mono", size: TICK_WF } } }, y: { ticks: { callback: function (v) { return v + ""; } } } })
      }
    };
  }

  function renderTable(rows, target) {
    var html = '<table class="bx-tab"><thead><tr><th>Sector</th><th>w<sub>p</sub></th><th>w<sub>b</sub></th><th>Alloc</th><th>Select</th><th>Inter</th><th>Total</th></tr></thead><tbody>';
    rows.forEach(function (r) {
      function cell(x) { return '<td class="' + (x >= 0 ? "pos" : "neg") + '">' + fmtBps(x) + "</td>"; }
      html += "<tr><td>" + r.s + "</td><td>" + (100 * r.wp).toFixed(1) + "%</td><td>" + (100 * r.wb).toFixed(1) + "%</td>" +
        cell(r.a) + cell(r.sel) + cell(r.i) +
        '<td class="tot ' + (r.tot >= 0 ? "pos" : "neg") + '">' + fmtBps(r.tot) + "</td></tr>";
    });
    (target || root.querySelector("[data-atab]")).innerHTML = html + "</tbody></table>";
  }

  function renderContrib() { mk("contrib", contribCfg()); }
  function contribCfg() {
    var p = D.portfolios[state.pf];
    var up = tok("--up"), down = tok("--down");
    var cons = p.top.concat(p.bottom.slice().reverse());
    return {
      type: "bar",
      data: { labels: cons.map(function (c) { return c.t; }), datasets: [{ data: cons.map(function (c) { return 10000 * c.c; }), backgroundColor: cons.map(function (c) { return c.c >= 0 ? up : down; }), borderRadius: state.variant === "studio" ? 3 : 1, barPercentage: 0.82 }] },
      options: {
        indexAxis: "y", responsive: true, maintainAspectRatio: false, animation: false,
        plugins: { legend: { display: false }, tooltip: tooltipCfg({ callbacks: { label: function (c) { return (c.raw >= 0 ? "+" : "") + c.raw.toFixed(1) + " bps"; } } }) },
        scales: baseScales({ x: { ticks: { callback: function (v) { return v; } } }, y: { ticks: { font: { family: "IBM Plex Mono", size: TICK_Y } } } })
      }
    };
  }

  function wtsLegend() {
    return D.sectors.map(function (s, i) {
      return '<span><i style="background:' + PALETTE[i % PALETTE.length] + '"></i>' + shortSec(i) + "</span>";
    }).join("");
  }
  function renderWeights() {
    var p = D.portfolios[state.pf];
    if (!p.weights) { // matrix arrives after first paint in split-API mode
      root.querySelector("[data-wtslegend]").innerHTML = "";
      if (charts.wts) { charts.wts.destroy(); delete charts.wts; }
      return;
    }
    root.querySelector("[data-wtslegend]").innerHTML = wtsLegend();
    mk("wts", wtsCfg());
  }
  function wtsCfg() {
    var p = D.portfolios[state.pf];
    return {
      type: "line",
      data: {
        labels: D.weekIdx.map(function (i) { return D.dates[i]; }),
        datasets: D.sectors.map(function (s, si) {
          var c = PALETTE[si % PALETTE.length];
          return { label: s, data: p.weights[si].map(function (w) { return 100 * w; }), borderColor: hexA(c, 0.9), backgroundColor: hexA(c, 0.55), fill: true, pointRadius: 0, borderWidth: 0.6, tension: 0.2 };
        })
      },
      options: {
        responsive: true, maintainAspectRatio: false, animation: false,
        plugins: { legend: { display: false }, tooltip: { enabled: false } },
        scales: baseScales({ y: { stacked: true, max: 100, ticks: { callback: function (v) { return v + "%"; }, maxTicksLimit: 4 } } })
      }
    };
  }

  // ── full render ───────────────────────────────────────────────────────
  function render() {
    applyStyle();
    root.querySelector("[data-theme-toggle]").textContent = state.theme === "dark" ? "☀" : "☾";
    var model = computeKpis();
    var d = model.dates;
    root.querySelector("[data-period]").textContent = d[0] + " .. " + d[d.length - 1] + " · " + d.length + "d";
    renderKpis(model);
    renderPerf(model);
    var rows = attributionRows();
    renderWaterfall(rows);
    renderTable(rows);
    renderContrib();
    renderWeights();
    if (modalKey) buildModalBody(modalKey);
  }

  // ── expand-to-modal ───────────────────────────────────────────────────
  var modalCharts = [];
  var modalKey = null;
  var PANELS = {
    perf:    { title: "Cumulative return", sub: "vs benchmark · TWR" },
    wf:      { title: "Brinson–Fachler attribution", sub: "Cariño-linked · bps" },
    contrib: { title: "Contributors & detractors", sub: "full period · bps" },
    wts:     { title: "Sector weights", sub: "weekly · stacked to 100%" }
  };
  function destroyModalCharts() { modalCharts.forEach(function (c) { c.destroy(); }); modalCharts = []; }
  function buildModalBody(key) {
    var body = root.querySelector("[data-modal-body]");
    var legend = key === "perf" ? perfLegend() : (key === "wts" ? wtsLegend() : "");
    body.innerHTML =
      (legend ? '<div class="bx-legend bx-modal-legend">' + legend + '</div>' : "") +
      '<div class="bx-modal-chart"><canvas data-modalcanvas></canvas></div>' +
      (key === "wf" ? '<div class="bx-modal-table" data-modal-table></div>' : "");
    var canvas = body.querySelector("[data-modalcanvas]");
    var cfg;
    if (key === "perf") cfg = perfCfg(computeKpis());
    else if (key === "wf") { var rows = attributionRows(); cfg = wfCfg(rows); renderTable(rows, body.querySelector("[data-modal-table]")); }
    else if (key === "contrib") cfg = contribCfg();
    else {
      if (!D.portfolios[state.pf].weights) { return; }
      cfg = wtsCfg();
    }
    destroyModalCharts();
    modalCharts.push(new Chart(canvas, cfg));
  }
  function openPanel(key) {
    if (state.fit !== "scroll" || !PANELS[key]) return;
    modalKey = key;
    var ov = root.querySelector("[data-modal]");
    ov.querySelector("[data-modal-title]").textContent = PANELS[key].title;
    ov.querySelector("[data-modal-sub]").textContent = PANELS[key].sub;
    ov.classList.add("on");
    document.body.style.overflow = "hidden";
    buildModalBody(key);
  }
  function closePanel() {
    destroyModalCharts(); modalKey = null;
    root.querySelector("[data-modal]").classList.remove("on");
    document.body.style.overflow = "";
  }

  // ── events ────────────────────────────────────────────────────────────
  sel.addEventListener("change", function () {
    var v = parseInt(sel.value, 10);
    var ready = opts.ensurePortfolio ? opts.ensurePortfolio(v) : null;
    if (ready && ready.then) { ready.then(function () { state.pf = v; render(); }); }
    else { state.pf = v; render(); }
  });
  root.querySelector("[data-ranges]").addEventListener("click", function (e) {
    var b = e.target.closest("button"); if (!b) return;
    state.range = b.getAttribute("data-r");
    root.querySelectorAll("[data-ranges] button").forEach(function (x) { x.classList.remove("on"); });
    b.classList.add("on");
    render();
  });
  root.querySelector("[data-theme-toggle]").addEventListener("click", function () {
    state.theme = state.theme === "dark" ? "light" : "dark";
    root.setAttribute("data-theme", state.theme);
    render();
  });
  var twBtn = root.querySelector("[data-tweaks-open]");
  if (state.tweakable && twBtn) {
    twBtn.style.display = "";
    twBtn.addEventListener("click", function () {
      if (opts.onOpenTweaks) opts.onOpenTweaks();
      else window.postMessage({ type: "__activate_edit_mode" }, "*");
    });
  }
  root.addEventListener("click", function (e) {
    var ex = e.target.closest("[data-expand]");
    if (ex) { openPanel(ex.getAttribute("data-expand")); }
  });
  var ov = root.querySelector("[data-modal]");
  ov.addEventListener("click", function (e) {
    if (e.target === ov || e.target.closest("[data-modal-close]")) closePanel();
  });
  function onKey(e) { if (e.key === "Escape" && modalKey) closePanel(); }
  document.addEventListener("keydown", onKey);

  applyStyle();
  render();
  return {
    destroy: function () {
      document.removeEventListener("keydown", onKey);
      destroyModalCharts();
      Object.keys(charts).forEach(function (k) { charts[k].destroy(); });
      mount.removeChild(root);
    },
    update: function (p) { Object.assign(state, p); render(); },
    getState: function () { return state; },
    root: root
  };
};
