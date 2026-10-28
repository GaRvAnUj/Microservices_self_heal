/**
 * Healer Control Plane Dashboard Client
 * 100% offline, zero-dependency HTML5 Canvas topology and real-time metric charts.
 * Features:
 * - Dual SSE stream + fallback polling (never leaves screen blank)
 * - Safe HTML5 Canvas rendering (cross-browser compatible roundRect fallback)
 * - Interactive Toast notifications on all chaos actions
 * - Real-time SLA monitoring and incident feedback
 */

// Application State
const state = {
  mode: 'PROPOSED',
  slo: { p95LatencyMs: 0, avgLatencyMs: 0, errorRate: 0, windowRequests: 0, sloHealthy: true },
  services: {},
  activeIncident: null,
  incidentHistory: [],
  chaosFaults: [],
  qPolicy: [],
  zScores: {},
  latencyHistory: [], // array of { time: Date, p95: number, avg: number }
  errorHistory: [],   // array of { time: Date, errorRate: number }
  maxHistoryPoints: 60,
  sseConnected: false
};

// --- Toast Notification System ---
function showToast(message, type = 'info') {
  const container = document.getElementById('toastContainer');
  if (!container) return;

  const toast = document.createElement('div');
  toast.className = `toast toast-${type}`;
  toast.innerHTML = `<span>${type === 'success' ? '✓' : type === 'error' ? '⚠' : 'ℹ'}</span> <span>${message}</span>`;
  container.appendChild(toast);

  setTimeout(() => {
    toast.style.transition = 'opacity 0.3s ease, transform 0.3s ease';
    toast.style.opacity = '0';
    toast.style.transform = 'translateY(10px)';
    setTimeout(() => toast.remove(), 300);
  }, 3500);
}

// --- Safe Cross-Browser Canvas Helper ---
function safeRoundRect(ctx, x, y, w, h, r) {
  if (typeof ctx.roundRect === 'function') {
    ctx.roundRect(x, y, w, h, r);
  } else {
    // Fallback standard path
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }
}

// --- Data Synchronization (SSE + Polling Fallback) ---
function initEventStream() {
  const statusDot = document.getElementById('connectionDot');
  const statusText = document.getElementById('connectionText');

  try {
    const eventSource = new EventSource('/api/stream');

    eventSource.onopen = () => {
      state.sseConnected = true;
      statusDot.classList.remove('disconnected');
      statusText.textContent = 'Live SSE Connected';
    };

    eventSource.addEventListener('status', (e) => {
      try {
        const data = JSON.parse(e.data);
        updateDashboard(data);
      } catch (err) {
        console.error('Failed to parse SSE data', err);
      }
    });

    eventSource.onerror = () => {
      state.sseConnected = false;
      statusDot.classList.add('disconnected');
      statusText.textContent = 'Live Polling Active';
    };
  } catch (e) {
    console.warn('EventSource not supported or blocked, relying on polling', e);
  }
}

// Immediate fetch on load and fallback interval
async function fetchStatusSnapshot() {
  try {
    const res = await fetch('/api/status');
    if (res.ok) {
      const data = await res.json();
      updateDashboard(data);
      const statusDot = document.getElementById('connectionDot');
      const statusText = document.getElementById('connectionText');
      if (!state.sseConnected && statusDot && statusText) {
        statusDot.classList.remove('disconnected');
        statusText.textContent = 'Live Polling Active';
      }
    }
  } catch (err) {
    console.warn('Status poll failed', err);
  }
}

// --- Main State Update ---
function updateDashboard(data) {
  if (!data) return;

  state.mode = data.mode || state.mode;
  state.slo = data.slo || state.slo;
  state.services = data.services || state.services;
  state.activeIncident = data.activeIncident;
  state.incidentHistory = data.incidentHistory || [];
  state.chaosFaults = data.chaosFaults || [];
  state.qPolicy = data.qPolicy || [];
  state.zScores = data.zScores || {};

  // Record history for charts
  const now = new Date();
  state.latencyHistory.push({
    time: now,
    p95: (state.slo && state.slo.p95LatencyMs) || 0,
    avg: (state.slo && state.slo.avgLatencyMs) || 0
  });
  if (state.latencyHistory.length > state.maxHistoryPoints) {
    state.latencyHistory.shift();
  }

  state.errorHistory.push({
    time: now,
    errorRate: ((state.slo && state.slo.errorRate) || 0) * 100
  });
  if (state.errorHistory.length > state.maxHistoryPoints) {
    state.errorHistory.shift();
  }

  try { renderKpiCards(); } catch (e) { console.error('Error in renderKpiCards', e); }
  try { renderModeToggle(); } catch (e) { console.error('Error in renderModeToggle', e); }
  try { renderIncidentFeed(); } catch (e) { console.error('Error in renderIncidentFeed', e); }
  try { renderQPolicyTable(); } catch (e) { console.error('Error in renderQPolicyTable', e); }
  try { renderChaosActiveList(); } catch (e) { console.error('Error in renderChaosActiveList', e); }
  try { renderTopology(); } catch (e) { console.error('Error in renderTopology', e); }
  try { renderCharts(); } catch (e) { console.error('Error in renderCharts', e); }
}

// --- KPI Cards Rendering ---
function renderKpiCards() {
  const slo = state.slo || { p95LatencyMs: 0, errorRate: 0, windowRequests: 0, sloHealthy: true };

  const sloBadge = document.getElementById('sloBadge');
  if (sloBadge) {
    const sloHealthy = slo.sloHealthy;
    sloBadge.className = `badge ${sloHealthy ? 'badge-success' : 'badge-error'}`;
    sloBadge.textContent = sloHealthy ? 'SLO HEALTHY' : 'SLO BREACHED';
  }

  const p95El = document.getElementById('kpiP95');
  if (p95El) {
    p95El.textContent = `${Math.round(slo.p95LatencyMs || 0)} ms`;
    p95El.style.color = (slo.p95LatencyMs || 0) > 400 ? 'var(--error)' : 'var(--text-primary)';
  }

  const errEl = document.getElementById('kpiErrorRate');
  if (errEl) {
    const errPct = ((slo.errorRate || 0) * 100).toFixed(1);
    errEl.textContent = `${errPct}%`;
    errEl.style.color = (slo.errorRate || 0) > 0.03 ? 'var(--error)' : 'var(--text-primary)';
  }

  const rpsEl = document.getElementById('kpiThroughput');
  if (rpsEl) {
    const rps = ((slo.windowRequests || 0) / 10).toFixed(1);
    rpsEl.textContent = `${rps} req/s`;
  }

  const incidentBadge = document.getElementById('incidentBadge');
  if (incidentBadge) {
    if (state.activeIncident) {
      incidentBadge.className = 'badge badge-warning';
      incidentBadge.textContent = `ACTIVE (${state.activeIncident.status || 'HEALING'})`;
    } else {
      incidentBadge.className = 'badge badge-success';
      incidentBadge.textContent = 'NO INCIDENT';
    }
  }
}

// --- Mode Toggle ---
function renderModeToggle() {
  const btnProposed = document.getElementById('btnModeProposed');
  const btnBaseline = document.getElementById('btnModeBaseline');
  if (!btnProposed || !btnBaseline) return;

  if (state.mode === 'PROPOSED') {
    btnProposed.classList.add('active');
    btnBaseline.classList.remove('active');
  } else {
    btnBaseline.classList.add('active');
    btnProposed.classList.remove('active');
  }
}

window.setMode = async function (newMode) {
  try {
    const res = await fetch(`/api/mode?mode=${newMode}`, { method: 'POST' });
    if (res.ok) {
      state.mode = newMode;
      renderModeToggle();
      showToast(`Operating mode switched to ${newMode}`, 'info');
      fetchStatusSnapshot();
    }
  } catch (err) {
    showToast(`Failed to switch mode: ${err.message}`, 'error');
  }
};

// --- Incident Timeline Rendering ---
function renderIncidentFeed() {
  const container = document.getElementById('incidentFeed');
  if (!container) return;

  let html = '';

  if (state.activeIncident) {
    const inc = state.activeIncident;
    const actionsHtml = (inc.actions || []).map(a =>
      `<div style="margin-top:4px; font-size:11px; color:#595959;">
         • Step ${a.stepNum}: <b>${a.actionType}</b> on <code>${a.targetComponent}</code>
         ${a.verdict ? `[Verdict: <b style="color:${a.verdict === 'SUCCESS' ? 'var(--success)' : 'var(--error)'}">${a.verdict}</b>]` : '(verifying...)'}
       </div>`
    ).join('');

    html += `
      <div class="incident-item">
        <div class="incident-header">
          <span style="font-weight:700; color:var(--error);">🚨 ACTIVE INCIDENT (${inc.mode || 'PROPOSED'})</span>
          <span class="incident-time">${inc.startedAt ? new Date(inc.startedAt).toLocaleTimeString() : 'now'}</span>
        </div>
        <div>${inc.triggerReason || 'SLO violation'}</div>
        <div>Suspect: <b>${inc.targetEntity || 'Diagnosing...'}</b> [${inc.rootCauseType || ''}]</div>
        ${actionsHtml}
      </div>
    `;
  }

  const history = [...(state.incidentHistory || [])].reverse().slice(0, 5);
  for (const inc of history) {
    const isResolved = inc.status === 'RESOLVED';
    html += `
      <div class="incident-item ${isResolved ? 'resolved' : 'escalated'}">
        <div class="incident-header">
          <span><b>${inc.status || 'COMPLETED'}</b> (${inc.mode || ''})</span>
          <span class="incident-time">${inc.startedAt ? new Date(inc.startedAt).toLocaleTimeString() : ''}</span>
        </div>
        <div style="color:var(--text-secondary);">${inc.triggerReason || ''}</div>
        <div>Root Cause: <b>${inc.targetEntity || 'unknown'}</b> (${inc.rootCauseType || ''})</div>
      </div>
    `;
  }

  if (!html) {
    html = `<div style="padding:16px; text-align:center; color:var(--text-secondary); font-size:12px;">No incidents recorded yet. System running smoothly.</div>`;
  }

  container.innerHTML = html;
}

// --- Q-Policy Matrix Table ---
function renderQPolicyTable() {
  const tbody = document.getElementById('qPolicyBody');
  if (!tbody) return;

  if (!state.qPolicy || state.qPolicy.length === 0) {
    tbody.innerHTML = `<tr><td colspan="4" style="text-align: center; color:var(--text-secondary);">Initializing Q-Policy table...</td></tr>`;
    return;
  }

  let html = '';
  for (const row of state.qPolicy) {
    const qVal = typeof row.qValue === 'number' ? row.qValue : 0.9;
    const scoreColor = qVal >= 0.8 ? 'var(--success)' : qVal <= 0.4 ? 'var(--error)' : 'var(--warning)';
    html += `
      <tr>
        <td><code>${row.faultType}</code></td>
        <td><b>${row.actionName}</b></td>
        <td>
          <span style="font-weight:700; color:${scoreColor};">${qVal.toFixed(3)}</span>
          <div style="width:60px; height:4px; background:#E5E7EB; border-radius:2px; display:inline-block; margin-left:6px; vertical-align:middle;">
            <div style="width:${Math.min(100, Math.round(qVal * 100))}%; height:100%; background:${scoreColor}; border-radius:2px;"></div>
          </div>
        </td>
        <td>${row.updateCount || 0}</td>
      </tr>
    `;
  }
  tbody.innerHTML = html;
}

// --- Active Chaos List ---
function renderChaosActiveList() {
  const container = document.getElementById('activeChaosList');
  if (!container) return;

  if (!state.chaosFaults || state.chaosFaults.length === 0) {
    container.innerHTML = `<span style="font-size:12px; color:var(--text-secondary);">No active faults injected. System in nominal state.</span>`;
    return;
  }

  let html = '<div style="display:flex; flex-wrap:wrap; gap:8px;">';
  for (const f of state.chaosFaults) {
    html += `
      <span class="badge badge-error">
        Link: ${f.caller} ➔ ${f.target} (+${f.delayMs}ms, ${f.lossPct}% loss)
      </span>
    `;
  }
  html += '</div>';
  container.innerHTML = html;
}

// --- Chaos Form Actions ---
window.injectChaos = async function () {
  const btn = document.getElementById('btnInjectChaos');
  const type = document.getElementById('chaosType').value;
  const target = document.getElementById('chaosTarget').value;
  const delay = document.getElementById('chaosDelay').value || 0;
  const loss = document.getElementById('chaosLoss').value || 0;

  let caller = 'compose-post';
  if (type === 'link_delay' || type === 'link_loss') {
    if (target === 'compose-post') {
      caller = 'gateway';
    } else {
      caller = 'compose-post';
    }
  }

  if (btn) {
    btn.disabled = true;
    btn.textContent = 'Injecting...';
  }

  try {
    const params = new URLSearchParams({
      type: type,
      caller: caller,
      target: target,
      delayMs: delay,
      lossPct: loss
    });
    const res = await fetch(`/api/chaos/inject?${params.toString()}`, { method: 'POST' });
    if (res.ok) {
      showToast(`Injected ${type} on ${target} (+${delay}ms, ${loss}%)`, 'success');
      await fetchStatusSnapshot();
    } else {
      const err = await res.json().catch(() => ({}));
      showToast(`Injection failed: ${err.error || res.statusText}`, 'error');
    }
  } catch (err) {
    showToast(`Error injecting fault: ${err.message}`, 'error');
  } finally {
    if (btn) {
      btn.disabled = false;
      btn.textContent = 'Inject Fault';
    }
  }
};

window.clearAllChaos = async function () {
  const btn = document.getElementById('btnClearChaos');
  if (btn) {
    btn.disabled = true;
    btn.textContent = 'Clearing...';
  }

  try {
    const res = await fetch('/api/chaos/clear', { method: 'POST' });
    if (res.ok) {
      showToast('All chaos impairments cleared successfully!', 'success');
      await fetchStatusSnapshot();
    } else {
      showToast('Failed to clear chaos', 'error');
    }
  } catch (err) {
    showToast(`Error clearing chaos: ${err.message}`, 'error');
  } finally {
    if (btn) {
      btn.disabled = false;
      btn.textContent = 'Clear All Chaos';
    }
  }
};

window.restartService = async function (serviceName) {
  showToast(`Initiating restart for ${serviceName}...`, 'info');
  try {
    const res = await fetch(`/api/service/${serviceName}/restart`, { method: 'POST' });
    if (res.ok) {
      showToast(`Service ${serviceName} restarted successfully`, 'success');
      fetchStatusSnapshot();
    } else {
      showToast(`Failed to restart ${serviceName}`, 'error');
    }
  } catch (err) {
    showToast(`Error restarting ${serviceName}: ${err.message}`, 'error');
  }
};

// --- Topology Visualization (Offline Canvas) ---
const topologyNodes = [
  { id: 'gateway', name: 'gateway', port: 8081, x: 120, y: 190, critical: false },
  { id: 'compose-post', name: 'compose-post', port: 8082, x: 340, y: 190, critical: false },
  { id: 'text', name: 'text', port: 8083, x: 620, y: 60, critical: false },
  { id: 'media', name: 'media', port: 8084, x: 620, y: 145, critical: false },
  { id: 'user', name: 'user', port: 8085, x: 620, y: 230, critical: false },
  { id: 'post-storage', name: 'post-storage', port: 8086, x: 620, y: 315, critical: true }
];

const topologyEdges = [
  { from: 'gateway', to: 'compose-post' },
  { from: 'compose-post', to: 'text' },
  { from: 'compose-post', to: 'media' },
  { from: 'compose-post', to: 'user' },
  { from: 'compose-post', to: 'post-storage' }
];

function renderTopology() {
  const canvas = document.getElementById('topologyCanvas');
  if (!canvas) return;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;

  const rect = canvas.getBoundingClientRect();
  const w = rect.width || 700;
  const h = rect.height || 360;

  const dpr = window.devicePixelRatio || 1;
  if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
  }

  ctx.save();
  ctx.scale(dpr, dpr);
  ctx.clearRect(0, 0, w, h);

  const scaleX = w / 780;
  const scaleY = h / 380;

  // 1. Draw Edges
  for (const edge of topologyEdges) {
    const fromNode = topologyNodes.find(n => n.id === edge.from);
    const toNode = topologyNodes.find(n => n.id === edge.to);
    if (!fromNode || !toNode) continue;

    const x1 = fromNode.x * scaleX;
    const y1 = fromNode.y * scaleY;
    const x2 = toNode.x * scaleX;
    const y2 = toNode.y * scaleY;

    // Check active chaos on edge
    const fault = (state.chaosFaults || []).find(f => f.caller === edge.from && f.target === edge.to);

    ctx.beginPath();
    ctx.moveTo(x1 + 45, y1);
    ctx.lineTo(x2 - 50, y2);

    if (fault) {
      ctx.strokeStyle = '#D93025';
      ctx.lineWidth = 2.5;
      ctx.setLineDash([6, 4]);
    } else {
      ctx.strokeStyle = '#CBD5E1';
      ctx.lineWidth = 2;
      ctx.setLineDash([]);
    }
    ctx.stroke();
    ctx.setLineDash([]);

    // Draw Arrowhead
    const angle = Math.atan2(y2 - y1, (x2 - 50) - (x1 + 45));
    const arrowX = x2 - 50;
    const arrowY = y2;
    ctx.fillStyle = fault ? '#D93025' : '#94A3B8';
    ctx.beginPath();
    ctx.moveTo(arrowX, arrowY);
    ctx.lineTo(arrowX - 8 * Math.cos(angle - Math.PI / 6), arrowY - 8 * Math.sin(angle - Math.PI / 6));
    ctx.lineTo(arrowX - 8 * Math.cos(angle + Math.PI / 6), arrowY - 8 * Math.sin(angle + Math.PI / 6));
    ctx.closePath();
    ctx.fill();

    // Fault badge on edge
    if (fault) {
      const midX = (x1 + 45 + x2 - 50) / 2;
      const midY = (y1 + y2) / 2;
      ctx.fillStyle = '#FEE2E2';
      ctx.fillRect(midX - 30, midY - 10, 60, 20);
      ctx.strokeStyle = '#D93025';
      ctx.lineWidth = 1;
      ctx.strokeRect(midX - 30, midY - 10, 60, 20);
      ctx.fillStyle = '#D93025';
      ctx.font = 'bold 10px sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(`+${fault.delayMs}ms`, midX, midY);
    }
  }

  // 2. Draw Nodes
  for (const node of topologyNodes) {
    const nx = node.x * scaleX;
    const ny = node.y * scaleY;
    const nw = 105;
    const nh = 50;
    const rx = nx - nw / 2;
    const ry = ny - nh / 2;

    const svc = (state.services && state.services[node.id]) || { state: 'DOWN', restartCount: 0 };
    const isUp = svc.state === 'UP';
    const isRestarting = svc.state === 'RESTARTING';

    let borderColor = '#0A8754';
    let statusText = 'UP';
    if (isRestarting) {
      borderColor = '#F59E0B';
      statusText = 'RESTARTING';
    } else if (!isUp) {
      borderColor = '#D93025';
      statusText = 'DOWN';
    }

    // Node Box
    ctx.fillStyle = '#FFFFFF';
    safeRoundRect(ctx, rx, ry, nw, nh, 6);
    ctx.fill();

    ctx.strokeStyle = borderColor;
    ctx.lineWidth = 2;
    safeRoundRect(ctx, rx, ry, nw, nh, 6);
    ctx.stroke();

    // Status Pill
    ctx.fillStyle = isUp ? '#0A8754' : isRestarting ? '#F59E0B' : '#D93025';
    ctx.beginPath();
    ctx.arc(rx + 12, ry + 14, 4, 0, Math.PI * 2);
    ctx.fill();

    // Node Name
    ctx.fillStyle = '#2D2D2D';
    ctx.font = 'bold 12px sans-serif';
    ctx.textAlign = 'left';
    ctx.textBaseline = 'middle';
    ctx.fillText(node.name, rx + 22, ry + 14);

    // Node Port & Info
    ctx.fillStyle = '#595959';
    ctx.font = '10px monospace';
    ctx.fillText(`:${node.port} ${node.critical ? '• CRIT' : ''}`, rx + 8, ry + 30);
    ctx.fillText(`Restarts: ${svc.restartCount || 0}`, rx + 8, ry + 42);
  }

  ctx.restore();
}

// --- Real-Time Timeseries Canvas Charts ---
function renderCharts() {
  renderLatencyChart();
  renderErrorChart();
}

function renderLatencyChart() {
  const canvas = document.getElementById('latencyChartCanvas');
  if (!canvas) return;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;

  const rect = canvas.getBoundingClientRect();
  const w = rect.width || 400;
  const h = rect.height || 120;

  const dpr = window.devicePixelRatio || 1;
  if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
  }

  ctx.save();
  ctx.scale(dpr, dpr);
  ctx.clearRect(0, 0, w, h);

  const history = state.latencyHistory || [];
  if (history.length < 2) {
    ctx.fillStyle = '#94A3B8';
    ctx.font = '11px sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText('Accumulating latency samples...', w / 2, h / 2);
    ctx.restore();
    return;
  }

  const maxVal = Math.max(500, ...history.map(d => d.p95)) * 1.15;
  const padLeft = 40;
  const padBottom = 20;
  const chartW = w - padLeft - 10;
  const chartH = h - padBottom - 10;

  // Grid
  ctx.strokeStyle = '#E5E7EB';
  ctx.lineWidth = 1;
  ctx.fillStyle = '#94A3B8';
  ctx.font = '10px sans-serif';
  ctx.textAlign = 'right';

  [0, 200, 400, Math.round(maxVal)].forEach(val => {
    if (val <= maxVal) {
      const y = 10 + chartH - (val / maxVal) * chartH;
      ctx.beginPath();
      ctx.moveTo(padLeft, y);
      ctx.lineTo(w - 10, y);
      ctx.stroke();
      ctx.fillText(`${val}`, padLeft - 4, y + 3);
    }
  });

  // SLO Limit Line (400ms)
  const sloY = 10 + chartH - (400 / maxVal) * chartH;
  ctx.strokeStyle = '#D93025';
  ctx.lineWidth = 1.5;
  ctx.setLineDash([4, 4]);
  ctx.beginPath();
  ctx.moveTo(padLeft, sloY);
  ctx.lineTo(w - 10, sloY);
  ctx.stroke();
  ctx.setLineDash([]);

  // Plot p95 (Primary Blue)
  ctx.strokeStyle = '#004FCB';
  ctx.lineWidth = 2;
  ctx.beginPath();
  for (let i = 0; i < history.length; i++) {
    const x = padLeft + (i / (state.maxHistoryPoints - 1)) * chartW;
    const y = 10 + chartH - (history[i].p95 / maxVal) * chartH;
    if (i === 0) ctx.moveTo(x, y);
    else ctx.lineTo(x, y);
  }
  ctx.stroke();

  ctx.restore();
}

function renderErrorChart() {
  const canvas = document.getElementById('errorChartCanvas');
  if (!canvas) return;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;

  const rect = canvas.getBoundingClientRect();
  const w = rect.width || 400;
  const h = rect.height || 120;

  const dpr = window.devicePixelRatio || 1;
  if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
  }

  ctx.save();
  ctx.scale(dpr, dpr);
  ctx.clearRect(0, 0, w, h);

  const history = state.errorHistory || [];
  if (history.length < 2) {
    ctx.fillStyle = '#94A3B8';
    ctx.font = '11px sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText('Accumulating error samples...', w / 2, h / 2);
    ctx.restore();
    return;
  }

  const maxVal = Math.max(10, ...history.map(d => d.errorRate)) * 1.2;
  const padLeft = 40;
  const padBottom = 20;
  const chartW = w - padLeft - 10;
  const chartH = h - padBottom - 10;

  // Grid
  ctx.strokeStyle = '#E5E7EB';
  ctx.lineWidth = 1;
  ctx.fillStyle = '#94A3B8';
  ctx.font = '10px sans-serif';
  ctx.textAlign = 'right';

  [0, 3, 5, 10].forEach(val => {
    if (val <= maxVal) {
      const y = 10 + chartH - (val / maxVal) * chartH;
      ctx.beginPath();
      ctx.moveTo(padLeft, y);
      ctx.lineTo(w - 10, y);
      ctx.stroke();
      ctx.fillText(`${val}%`, padLeft - 4, y + 3);
    }
  });

  // SLO Limit Line (3%)
  const sloY = 10 + chartH - (3 / maxVal) * chartH;
  ctx.strokeStyle = '#D93025';
  ctx.lineWidth = 1.5;
  ctx.setLineDash([4, 4]);
  ctx.beginPath();
  ctx.moveTo(padLeft, sloY);
  ctx.lineTo(w - 10, sloY);
  ctx.stroke();
  ctx.setLineDash([]);

  // Plot Error Rate
  ctx.strokeStyle = '#D93025';
  ctx.lineWidth = 2;
  ctx.beginPath();
  for (let i = 0; i < history.length; i++) {
    const x = padLeft + (i / (state.maxHistoryPoints - 1)) * chartW;
    const y = 10 + chartH - (history[i].errorRate / maxVal) * chartH;
    if (i === 0) ctx.moveTo(x, y);
    else ctx.lineTo(x, y);
  }
  ctx.stroke();

  ctx.restore();
}

// Window load init
window.addEventListener('DOMContentLoaded', () => {
  // 1. Immediately fetch status snapshot
  fetchStatusSnapshot();

  // 2. Start SSE real-time listener
  initEventStream();

  // 3. Fallback polling every 2s in case SSE is blocked or buffering
  setInterval(fetchStatusSnapshot, 2000);

  // 4. Handle resize
  window.addEventListener('resize', () => {
    renderTopology();
    renderCharts();
  });
});
