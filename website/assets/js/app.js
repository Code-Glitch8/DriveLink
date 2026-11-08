(function () {
  'use strict';
  var cfg = window.DRIVELINK_CONFIG || {};
  var $ = function (id) { return document.getElementById(id); };
  var sb = window.supabase.createClient(cfg.supabaseUrl, cfg.supabaseAnonKey, { auth: { persistSession: true, autoRefreshToken: true } });
  var signup = false, user = null, activeVehicleId = null;

  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function notify(msg, kind) { var n = $('notice'); n.textContent = msg; n.className = kind || ''; n.hidden = !msg; if (msg) setTimeout(function () { if (n.textContent === msg) n.hidden = true; }, 7000); }
  function fail(e) { notify((e && e.message) || String(e), 'err'); }

  // ---------- theme ----------
  function applyTheme(t) { document.documentElement.setAttribute('data-theme', t); try { localStorage.setItem('dl-theme', t); } catch (e) {} }
  try { var saved = localStorage.getItem('dl-theme'); if (saved) applyTheme(saved); } catch (e) {}
  $('themeBtn').onclick = function () { applyTheme(document.documentElement.getAttribute('data-theme') === 'light' ? 'dark' : 'light'); };

  // ---------- navigation ----------
  function show(tab) {
    ['vehicles', 'sim', 'profile', 'about'].forEach(function (t) { $('tab-' + t).hidden = t !== tab; });
    document.querySelectorAll('nav [data-tab]').forEach(function (b) { b.classList.toggle('active', b.dataset.tab === tab); });
    if (tab === 'vehicles') loadVehicles();
    if (tab === 'profile') loadProfile();
  }
  document.querySelectorAll('nav [data-tab]').forEach(function (b) { b.onclick = function () { show(b.dataset.tab); }; });

  function nameOf(u) { var m = (u && u.user_metadata) || {}; return (m.full_name || (u && u.email ? u.email.split('@')[0] : '') || '').trim(); }
  function setSignedIn(u) {
    user = u;
    $('landing').hidden = !!u; $('publicNav').hidden = !!u; $('hdrGuest').hidden = !!u;
    $('nav').hidden = !u; $('logoutBtn').hidden = !u; $('app').hidden = !u;
    if (u) {
      closeAuth(); $('view-reset').hidden = true; show('vehicles');
      var n = nameOf(u); $('welcome').textContent = n ? 'Welcome back, ' + n : 'Your vehicles';
    } else {
      ['vehicles', 'sim', 'profile', 'about'].forEach(function (t) { $('tab-' + t).hidden = true; });
    }
  }

  // ---------- auth ----------
  function setMode(isSignup) {
    signup = isSignup;
    $('authTitle').textContent = signup ? 'Create your account' : 'Welcome back';
    $('authSub').textContent = signup ? 'Register to add vehicles and start journeys.' : 'Sign in to manage your vehicles.';
    $('authBtn').textContent = signup ? 'Create account' : 'Sign in';
    $('toggleAuth').textContent = signup ? 'I already have an account' : 'Create account';
    $('resetBtn').hidden = signup; $('nameRow').hidden = !signup;
    $('password').autocomplete = signup ? 'new-password' : 'current-password';
  }
  function openAuth(mode) { setMode(mode === 'signup'); $('view-auth').hidden = false; setTimeout(function () { $('email').focus(); }, 30); }
  function closeAuth() { $('view-auth').hidden = true; }
  document.querySelectorAll('.open-auth').forEach(function (b) { b.onclick = function () { openAuth(b.dataset.mode); }; });
  $('authClose').onclick = closeAuth;
  $('view-auth').addEventListener('click', function (e) { if (e.target === $('view-auth')) closeAuth(); });
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeAuth(); if (e.key === 'Enter' && !$('view-auth').hidden && e.target.tagName === 'INPUT') $('authBtn').click(); });
  $('toggleAuth').onclick = function () { setMode(!signup); };
  $('authBtn').onclick = async function () {
    var email = $('email').value.trim(), pw = $('password').value;
    if (!email || pw.length < 8) return notify('Enter an email and a password of at least 8 characters.', 'err');
    try {
      if (signup) {
        var r = await sb.auth.signUp({ email: email, password: pw, options: { data: { full_name: $('fullName').value.trim() }, emailRedirectTo: location.origin + location.pathname } });
        if (r.error) throw r.error;
        notify('Account created. Check your email to verify your address, then sign in.', 'ok');
      } else {
        var s = await sb.auth.signInWithPassword({ email: email, password: pw });
        if (s.error) throw s.error;
      }
    } catch (e) { fail(e); }
  };
  $('resetBtn').onclick = async function () {
    var email = $('email').value.trim();
    if (!email) return notify('Enter your email first.', 'err');
    var r = await sb.auth.resetPasswordForEmail(email, { redirectTo: location.origin + location.pathname });
    r.error ? fail(r.error) : notify('Password reset email sent.', 'ok');
  };
  $('setPwBtn').onclick = async function () {
    var pw = $('newPw1').value;
    if (pw.length < 8) return notify('Use at least 8 characters.', 'err');
    var r = await sb.auth.updateUser({ password: pw });
    if (r.error) return fail(r.error);
    notify('Password updated.', 'ok'); $('view-reset').hidden = true; $('newPw1').value = '';
    var s = await sb.auth.getSession(); setSignedIn(s.data.session && s.data.session.user);
  };
  $('logoutBtn').onclick = function () { sb.auth.signOut(); };
  sb.auth.onAuthStateChange(function (ev, session) {
    if (ev === 'PASSWORD_RECOVERY') { $('view-auth').hidden = true; $('view-reset').hidden = false; return; }
    setSignedIn(session && session.user ? session.user : null);
  });

  // ---------- profile ----------
  async function loadProfile() {
    var r = await sb.from('profiles').select('full_name,phone_number').eq('id', user.id).single();
    if (r.error) return fail(r.error);
    $('pName').value = r.data.full_name || ''; $('pPhone').value = r.data.phone_number || ''; $('pEmail').value = user.email || '';
  }
  $('saveProfile').onclick = async function () {
    var r = await sb.from('profiles').update({ full_name: $('pName').value.trim(), phone_number: $('pPhone').value.trim() || null }).eq('id', user.id);
    r.error ? fail(r.error) : notify('Profile saved.', 'ok');
  };
  $('changePw').onclick = async function () {
    var pw = $('pw2').value; if (pw.length < 8) return notify('Use at least 8 characters.', 'err');
    var r = await sb.auth.updateUser({ password: pw });
    r.error ? fail(r.error) : (notify('Password changed.', 'ok'), $('pw2').value = '');
  };
  $('delAccount').onclick = async function () {
    if ($('delConfirm').value !== 'DELETE') return notify('Type DELETE to confirm.', 'err');
    if (!confirm('This permanently deletes your account and all vehicles. Continue?')) return;
    var r = await sb.functions.invoke('delete-account', { method: 'POST' });
    if (r.error) return fail(r.error);
    await sb.auth.signOut(); notify('Account deleted.', 'ok');
  };

  // ---------- vehicles ----------
  var LABEL = { car: 'Car', motorcycle: 'Motorcycle', scooter: 'Scooter', bus: 'Bus', truck: 'Truck', ambulance: 'Ambulance', police: 'Police vehicle', fire_service: 'Fire service vehicle', other: 'Other' };
  var EMERGENCY = { ambulance: 1, police: 1, fire_service: 1 };

  async function loadVehicles() {
    var s = await sb.from('user_settings').select('active_vehicle_id').eq('user_id', user.id).maybeSingle();
    activeVehicleId = s.data ? s.data.active_vehicle_id : null;
    var r = await sb.from('vehicles').select('*, emergency_accounts(verification_status, organisation_name)').order('created_at');
    if (r.error) return fail(r.error);
    var act = r.data.filter(function (v) { return v.id === activeVehicleId; })[0];
    $('statVehicles').textContent = r.data.length;
    $('statActive').textContent = act ? (act.nickname || act.drive_link_vehicle_id) : '\u2014';
    $('statVerified').textContent = act ? act.verification_status : '\u2014';
    var list = $('vehicleList');
    if (!r.data.length) { list.innerHTML = '<div class="card empty">No vehicles yet. Register your first one above.</div>'; return; }
    list.innerHTML = r.data.map(function (v) {
      var em = v.emergency_accounts && v.emergency_accounts[0] ? v.emergency_accounts[0] : (v.emergency_accounts && v.emergency_accounts.verification_status ? v.emergency_accounts : null);
      return '<div class="card"><div class="vcard"><div class="vmain"><div class="vicon">' + '<svg class="ic" style="width:26px;height:26px"><use href="#' + (EMERGENCY[v.vehicle_type] ? 'i-siren' : 'i-car') + '"/></svg>' + '</div><div>' +
        '<div class="vid">' + esc(v.drive_link_vehicle_id) + '</div>' +
        '<div class="vname">' + esc(v.nickname || ((v.manufacturer || '') + ' ' + (v.model || '')).trim() || 'Vehicle') + '</div>' +
        '<div class="muted">' + esc(LABEL[v.vehicle_type]) + ' &middot; <span class="plate">' + esc(v.registration_number) + '</span> ' + esc(v.registration_region) + ' &middot; ' + esc(v.colour || '') + ' ' + esc(v.manufacturing_year || '') + '</div>' +
        (v.id === activeVehicleId ? '<span class="tag active">ACTIVE</span>' : '') +
        '<span class="tag">' + esc(v.verification_status) + '</span>' +
        (em ? '<span class="tag">emergency: ' + esc(em.verification_status) + '</span>' : '') +
        '</div></div><div class="vactions">' +
        '<button data-act="active" data-id="' + v.id + '">Set active</button>' +
        '<button data-act="rename" data-id="' + v.id + '">Rename</button>' +
        (EMERGENCY[v.vehicle_type] && !em ? '<button data-act="emergency" data-id="' + v.id + '" data-type="' + v.vehicle_type + '">Request emergency verification</button>' : '') +
        '<button class="danger" data-act="del" data-id="' + v.id + '">Delete</button></div></div></div>';
    }).join('');
  }
  $('vehicleList').onclick = async function (e) {
    var b = e.target.closest('button'); if (!b) return;
    var id = b.dataset.id, r;
    if (b.dataset.act === 'active') r = await sb.from('user_settings').update({ active_vehicle_id: id }).eq('user_id', user.id);
    else if (b.dataset.act === 'rename') { var n = prompt('Vehicle name'); if (n == null) return; r = await sb.from('vehicles').update({ nickname: n.slice(0, 60) }).eq('id', id); }
    else if (b.dataset.act === 'del') { if (!confirm('Delete this vehicle?')) return; r = await sb.from('vehicles').delete().eq('id', id); }
    else if (b.dataset.act === 'emergency') {
      var org = prompt('Organisation name (an administrator will verify this)'); if (!org) return;
      r = await sb.rpc('request_emergency_account', { p_vehicle: id, p_org: org, p_type: b.dataset.type });
    }
    if (r && r.error) return fail(r.error);
    notify('Done.', 'ok'); loadVehicles();
  };
  $('vehicleForm').onsubmit = async function (e) {
    e.preventDefault();
    var f = new FormData(e.target), row = {};
    f.forEach(function (v, k) { v = String(v).trim(); if (v !== '') row[k] = v; });
    row.country_code = (row.country_code || 'IND').toUpperCase();
    if (row.vin) row.vin = row.vin.toUpperCase();
    if (row.manufacturing_year) row.manufacturing_year = parseInt(row.manufacturing_year, 10);
    row.drive_link_vehicle_id = 'PENDING'; // overwritten by the database trigger
    row.owner_id = user.id;
    var r = await sb.from('vehicles').insert(row).select().single();
    if (r.error) return fail(r.error.code === '23505' ? { message: 'That registration number is already registered.' } : r.error);
    if (!activeVehicleId) await sb.from('user_settings').update({ active_vehicle_id: r.data.id }).eq('user_id', user.id);
    notify('Vehicle registered as ' + r.data.drive_link_vehicle_id, 'ok'); e.target.reset(); e.target.country_code.value = 'IND'; loadVehicles();
  };

  // ---------- simulator ----------
  var sim = null, mapObj = null, markers = [];
  var BASE = { lat: 28.6139, lon: 77.2090 };
  function mkVeh(x, y, speed, heading, acc) { return { x: x, y: y, speed: speed, heading: heading, acc: acc }; }
  function scenarioState(name) {
    if (name === 'cross') return [mkVeh(0, -180, 40, 0, 6), mkVeh(-170, 0, 40, 90, 6)];
    if (name === 'follow') return [mkVeh(0, -120, 60, 0, 6), mkVeh(0, 0, 25, 0, 6)];
    if (name === 'pass') return [mkVeh(-30, -150, 40, 0, 6), mkVeh(30, 0, 40, 180, 6)];
    return [mkVeh(0, -180, 40, 0, 70), mkVeh(-170, 0, 40, 90, 70)];
  }
  function toGeo(v) { var d = Math.PI / 180; return { lat: BASE.lat + v.y / 6371000 / d, lon: BASE.lon + v.x / (6371000 * Math.cos(BASE.lat * d)) / d }; }
  function drawSim() {
    var c = $('simCanvas'), g = c.getContext('2d'), W = c.width, H = c.height, k = 0.9;
    g.fillStyle = '#0b1018'; g.fillRect(0, 0, W, H);
    g.fillStyle = '#1b2434';
    g.fillRect(W / 2 - 22, 0, 44, H); g.fillRect(0, H / 2 - 22, W, 44);
    g.strokeStyle = '#4a5a78'; g.lineWidth = 2; g.setLineDash([14, 12]);
    g.beginPath(); g.moveTo(W / 2, 0); g.lineTo(W / 2, H / 2 - 22); g.moveTo(W / 2, H / 2 + 22); g.lineTo(W / 2, H);
    g.moveTo(0, H / 2); g.lineTo(W / 2 - 22, H / 2); g.moveTo(W / 2 + 22, H / 2); g.lineTo(W, H / 2); g.stroke(); g.setLineDash([]);
    sim.v.forEach(function (v, i) {
      var px = W / 2 + v.x * k, py = H / 2 - v.y * k;
      g.save(); g.translate(px, py); g.rotate(v.heading * Math.PI / 180);
      g.shadowColor = i === 0 ? 'rgba(59,130,246,.8)' : 'rgba(249,115,22,.8)'; g.shadowBlur = 14;
      g.fillStyle = i === 0 ? '#3b82f6' : '#f97316';
      g.beginPath(); g.roundRect ? g.roundRect(-7, -13, 14, 26, 4) : g.rect(-7, -13, 14, 26); g.fill();
      g.shadowBlur = 0; g.fillStyle = 'rgba(255,255,255,.85)'; g.fillRect(-5, -11, 10, 5);
      g.restore();
      g.fillStyle = '#c9d4e8'; g.font = '600 12px system-ui,sans-serif'; g.fillText(i === 0 ? 'You' : 'Vehicle 2', px + 14, py - 10);
    });
  }
  function stepSim(ts) {
    if (!sim) return;
    var dt = Math.min(0.1, (ts - sim.last) / 1000); sim.last = ts; sim.t += dt;
    sim.v.forEach(function (v) { var s = v.speed / 3.6, h = v.heading * Math.PI / 180; v.x += s * Math.sin(h) * dt; v.y += s * Math.cos(h) * dt; });
    var now = Date.now(), g0 = toGeo(sim.v[0]), g1 = toGeo(sim.v[1]);
    var res = DLRisk.assess(
      { lat: g0.lat, lon: g0.lon, speedKmh: sim.v[0].speed, heading: sim.v[0].heading, accuracyM: sim.v[0].acc, timestampMs: now },
      { lat: g1.lat, lon: g1.lon, speedKmh: sim.v[1].speed, heading: sim.v[1].heading, accuracyM: sim.v[1].acc, timestampMs: now }, now);
    var b = $('riskBadge'), cls = res.level === 'HIGH_RISK' ? 'high' : res.level === 'CAUTION' ? 'caution' : 'normal';
    b.className = 'badge ' + cls; b.textContent = res.level.replace('_', ' ');
    var dist = DLRisk.distanceM(g0.lat, g0.lon, g1.lat, g1.lon);
    $('riskInfo').textContent = 'Distance ' + dist.toFixed(0) + ' m · ' + res.reason + (res.insufficient ? ' — data quality insufficient, no warning issued' : '') + (res.ttc != null ? ' · time to closest approach ' + res.ttc.toFixed(1) + ' s' : '');
    if (res.level !== sim.lastLevel) {
      sim.lastLevel = res.level;
      if ($('simVoice').checked && 'speechSynthesis' in window && res.level !== 'NORMAL')
        speechSynthesis.speak(new SpeechSynthesisUtterance(res.level === 'HIGH_RISK' ? 'Potential intersection conflict ahead.' : 'Caution. Vehicle approaching.'));
    }
    drawSim(); updateMap(g0, g1);
    if (sim.t > 25 || Math.abs(sim.v[0].x) > 400 || Math.abs(sim.v[0].y) > 400) { sim = null; return; }
    requestAnimationFrame(stepSim);
  }
  function updateMap(g0, g1) {
    if (!mapObj) return;
    var pts = [g0, g1];
    pts.forEach(function (p, i) { markers[i].setPosition({ lat: p.lat, lng: p.lon }); });
    mapObj.setCenter({ lat: g0.lat, lng: g0.lon });
  }
  function ensureMap() {
    if (!cfg.googleMapsApiKey || mapObj) return;
    $('simMap').style.display = 'block';
    window.__dlMapReady = function () {
      mapObj = new google.maps.Map($('simMap'), { center: { lat: BASE.lat, lng: BASE.lon }, zoom: 17, disableDefaultUI: true });
      markers = [new google.maps.Marker({ map: mapObj, label: 'You' }), new google.maps.Marker({ map: mapObj, label: 'V2' })];
    };
    var s = document.createElement('script');
    s.src = 'https://maps.googleapis.com/maps/api/js?key=' + encodeURIComponent(cfg.googleMapsApiKey) + '&callback=__dlMapReady';
    s.async = true; document.head.appendChild(s);
  }
  $('simStart').onclick = function () {
    ensureMap();
    sim = { v: scenarioState($('scenario').value), t: 0, last: performance.now(), lastLevel: 'NORMAL' };
    requestAnimationFrame(stepSim);
  };
  if (cfg.apkUrl) $('apkLink').href = cfg.apkUrl;
  setSignedIn(null);
  drawSim = (function (orig) { return function () { if (sim) orig(); }; })(drawSim);
})();
