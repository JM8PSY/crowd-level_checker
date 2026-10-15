// 教室の混雑度ページ。/api/v1/rooms を30秒ごとに読み直し、選んだ教室の推移をグラフにする
"use strict";

const REFRESH_MS = 30_000;
const state = { rooms: [], selected: null, charts: {} };

const $ = (id) => document.getElementById(id);
const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

function fmtTime(iso) {
  if (!iso) return "—";
  return new Date(iso).toLocaleTimeString("ja-JP", { hour: "2-digit", minute: "2-digit" });
}
function fmtDateTime(iso) {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("ja-JP", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" });
}
function todayStr() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}

// ---- 教室カード ----

async function loadRooms() {
  try {
    const res = await fetch("/api/v1/rooms");
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    state.rooms = data.rooms;
    $("now").textContent = `更新 ${fmtDateTime(data.now)}（30秒ごとに自動更新）`;
    renderRooms();
    renderRoomSelect();
    if (!state.selected && state.rooms.length) selectRoom(state.rooms[0].id);
    else if (state.selected && $("date").value === todayStr()) loadHistory();
  } catch (e) {
    $("now").textContent = `読み込みに失敗しました: ${e.message}`;
  }
}

function renderRooms() {
  const box = $("rooms");
  if (!state.rooms.length) {
    box.innerHTML = `<p class="muted">教室がありません。<code>python -m app.cli seed-demo</code> でデモデータを作れます。</p>`;
    return;
  }
  box.innerHTML = state.rooms.map((r) => {
    const cls = r.level ?? "unknown";
    const people = r.estimated_people != null ? `推定 約${Math.round(r.estimated_people)}人` : "推定できません（稼働中のノードなし）";
    const nodes = r.nodes.map((n) => `
      <li><span class="badge ${n.status}">${n.status === "online" ? "稼働中" : "停止"}</span>
      ${esc(n.id)}・最終受信 ${fmtTime(n.last_seen_at)}${n.battery != null ? `・電池 ${n.battery}%` : ""}</li>`).join("");
    return `
      <button class="card ${cls}" data-room="${esc(r.id)}" aria-pressed="${r.id === state.selected}">
        <div class="name">${esc(r.name)}</div>
        <div class="level"><span class="dot" aria-hidden="true"></span>${esc(r.level_label)}</div>
        <div class="people">${people}</div>
        <div class="muted">最終観測 ${fmtTime(r.updated_at)}
          ${r.calibration.calibrated ? "" : `<span class="uncal">（未校正：-80dBm以上の端末数をそのまま人数とみなしています）</span>`}</div>
        <ul class="nodes">${nodes}</ul>
      </button>`;
  }).join("");
  box.querySelectorAll(".card").forEach((el) => el.addEventListener("click", () => selectRoom(el.dataset.room)));
}

function renderRoomSelect() {
  const sel = $("gt-room");
  const cur = sel.value || state.selected;
  sel.innerHTML = state.rooms.map((r) => `<option value="${esc(r.id)}">${esc(r.name)}</option>`).join("");
  if (cur) sel.value = cur;
}

function selectRoom(id) {
  state.selected = id;
  $("detail").hidden = false;
  renderRooms();
  $("gt-room").value = id;
  loadHistory();
}

// ---- 推移グラフ ----

async function loadHistory() {
  const room = state.rooms.find((r) => r.id === state.selected);
  if (!room) return;
  const day = $("date").value || todayStr();
  const from = `${day}T00:00:00+09:00`;
  const next = new Date(`${day}T00:00:00+09:00`);
  next.setDate(next.getDate() + 1);
  const to = next.toISOString();
  const res = await fetch(`/api/v1/rooms/${encodeURIComponent(room.id)}/history?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`);
  if (!res.ok) return;
  const h = await res.json();
  $("detail-title").textContent = `${room.name} の推移`;
  $("detail-note").textContent =
    `推定に使う特徴量: ${h.feature} dBm 以上の端末数${h.calibrated ? "" : "（未校正の仮の式）"}。` +
    `点線は段階の閾値（やや混雑 ${h.threshold_moderate}人 / 混雑 ${h.threshold_crowded}人）。観測 ${h.points.length} 点`;
  drawPeople(h, day);
  drawRaw(h, day);
  renderTable(h);
}

function dayRange(day) {
  const start = new Date(`${day}T00:00:00+09:00`).getTime();
  return { start, end: start + 24 * 3600 * 1000 };
}

function baseOptions(day, yTitle) {
  const { start, end } = dayRange(day);
  const muted = css("--muted");
  const grid = css("--grid");
  // データがある時間帯に寄せる（0時〜24時だと昼の変化が見づらい）
  return {
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    parsing: false,
    normalized: true,
    interaction: { mode: "nearest", axis: "x", intersect: false },
    scales: {
      x: {
        type: "linear",
        min: start, max: end,
        grid: { color: grid },
        border: { color: css("--axis") },
        ticks: {
          color: muted, stepSize: 3600 * 1000, maxRotation: 0, autoSkip: true,
          callback: (v) => new Date(v).toLocaleTimeString("ja-JP", { hour: "2-digit", minute: "2-digit" }),
        },
      },
      y: {
        beginAtZero: true,
        grid: { color: grid },
        border: { display: false },
        ticks: { color: muted, precision: 0 },
        title: { display: true, text: yTitle, color: muted },
      },
    },
    plugins: {
      legend: { position: "top", align: "start", labels: { color: css("--text-2"), boxWidth: 12, boxHeight: 12 } },
      tooltip: {
        callbacks: {
          title: (items) => items.length ? fmtTime(new Date(items[0].parsed.x).toISOString()) : "",
        },
      },
    },
  };
}

function fitXToData(opts, xs, day) {
  if (!xs.length) return;
  const { start, end } = dayRange(day);
  const hour = 3600 * 1000;
  opts.scales.x.min = Math.max(start, Math.floor((Math.min(...xs) - start) / hour) * hour + start);
  opts.scales.x.max = Math.min(end, Math.ceil((Math.max(...xs) - start) / hour) * hour + start);
  if (opts.scales.x.max <= opts.scales.x.min) opts.scales.x.max = opts.scales.x.min + hour;
}

function hline(value, label, color, xmin, xmax) {
  return {
    label, type: "line", data: [{ x: xmin, y: value }, { x: xmax, y: value }],
    borderColor: color, borderWidth: 1, borderDash: [4, 4], pointRadius: 0, pointHoverRadius: 0,
    // 閾値線はツールチップに出さない
    tooltip: false,
  };
}

function drawPeople(h, day) {
  const people = h.points.filter((p) => p.estimated_people != null)
    .map((p) => ({ x: new Date(p.observed_at).getTime(), y: p.estimated_people }));
  const truth = h.ground_truth.map((g) => ({ x: new Date(g.observed_at).getTime(), y: g.people }));
  const opts = baseOptions(day, "人");
  fitXToData(opts, [...people, ...truth].map((p) => p.x), day);
  opts.plugins.tooltip.filter = (item) => item.dataset.tooltip !== false;
  opts.plugins.legend.labels.filter = (item, data) => data.datasets[item.datasetIndex].tooltip !== false;

  const datasets = [
    {
      label: "推定人数", data: people,
      borderColor: css("--series-1"), backgroundColor: css("--series-1"),
      borderWidth: 2, pointRadius: 0, pointHoverRadius: 4, tension: 0,
    },
    {
      label: "実人数（入力値）", data: truth, showLine: false,
      borderColor: css("--surface"), backgroundColor: css("--series-2"),
      borderWidth: 2, pointRadius: 5, pointHoverRadius: 7,
    },
    hline(h.threshold_moderate, "やや混雑", css("--warning"), opts.scales.x.min, opts.scales.x.max),
    hline(h.threshold_crowded, "混雑", css("--critical"), opts.scales.x.min, opts.scales.x.max),
  ];
  state.charts.people?.destroy();
  state.charts.people = new Chart($("chart-people"), { type: "line", data: { datasets }, options: opts });
}

function drawRaw(h, day) {
  const show = $("show-raw").checked;
  $("raw-box").hidden = !show;
  state.charts.raw?.destroy();
  state.charts.raw = null;
  if (!show) return;
  const keys = ["-60", "-70", "-80", "-90"];
  const colors = ["--ramp-1", "--ramp-2", "--ramp-3", "--ramp-4"].map(css);
  const datasets = keys.map((k, i) => ({
    label: `${k} dBm 以上`,
    data: h.points.filter((p) => p.counts_by_rssi[k] != null)
      .map((p) => ({ x: new Date(p.observed_at).getTime(), y: p.counts_by_rssi[k] })),
    borderColor: colors[i], backgroundColor: colors[i],
    borderWidth: 2, pointRadius: 0, pointHoverRadius: 4,
  }));
  const opts = baseOptions(day, "端末数");
  fitXToData(opts, h.points.map((p) => new Date(p.observed_at).getTime()), day);
  state.charts.raw = new Chart($("chart-raw"), { type: "line", data: { datasets }, options: opts });
}

function renderTable(h) {
  const truthByMin = new Map(h.ground_truth.map((g) => [fmtTime(g.observed_at), g.people]));
  const rows = h.points.slice().reverse().map((p) => `
    <tr><td>${fmtTime(p.observed_at)}</td><td>${p.estimated_people ?? "—"}</td><td>${truthByMin.get(fmtTime(p.observed_at)) ?? ""}</td>
    <td>${p.counts_by_rssi["-60"] ?? ""}</td><td>${p.counts_by_rssi["-70"] ?? ""}</td><td>${p.counts_by_rssi["-80"] ?? ""}</td>
    <td>${p.counts_by_rssi["-90"] ?? ""}</td><td>${p.counts_by_rssi["all"] ?? ""}</td><td>${p.nodes}</td></tr>`).join("");
  $("table").innerHTML = `<thead><tr><th>時刻</th><th>推定</th><th>実人数</th><th>-60</th><th>-70</th><th>-80</th><th>-90</th><th>全</th><th>ノード数</th></tr></thead><tbody>${rows}</tbody>`;
}

// ---- 実人数の入力 ----

$("gt-form").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  const token = $("gt-token").value;
  try { sessionStorage.setItem("adminToken", token); } catch { /* 保存できなくても続ける */ }
  const body = { room_id: $("gt-room").value, people: Number($("gt-people").value), note: $("gt-note").value || null };
  if ($("gt-time").value) body.observed_at = `${$("gt-time").value}:00+09:00`;
  const res = await fetch("/api/v1/ground-truth", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (res.ok) {
    $("gt-msg").textContent = `登録しました：${fmtDateTime(data.observed_at)} ${data.people}人`;
    $("gt-people").value = "";
    $("gt-note").value = "";
    if (state.selected === body.room_id) loadHistory();
  } else {
    $("gt-msg").textContent = `登録できませんでした（${res.status}）：${data.detail ?? ""}`;
  }
});

// ---- 起動 ----

$("date").value = todayStr();
$("date").addEventListener("change", loadHistory);
$("show-raw").addEventListener("change", loadHistory);
try { $("gt-token").value = sessionStorage.getItem("adminToken") ?? ""; } catch { /* 無視 */ }
window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", loadHistory);
loadRooms();
setInterval(loadRooms, REFRESH_MS);
