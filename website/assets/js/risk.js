/* Experimental collision-risk awareness (mirror of the Android RiskEngine). Not an ADAS. */
(function () {
  var R = 6371000, MAX_AGE_MS = 5000, MAX_ACC_M = 50;
  function toLocal(lat0, lon0, lat, lon) {
    var d = Math.PI / 180;
    return { x: (lon - lon0) * d * R * Math.cos(lat0 * d), y: (lat - lat0) * d * R };
  }
  function velocity(kmh, heading) {
    var s = kmh / 3.6, h = heading * Math.PI / 180;
    return { x: s * Math.sin(h), y: s * Math.cos(h) };
  }
  function distanceM(lat1, lon1, lat2, lon2) {
    var d = Math.PI / 180, dl = (lat2 - lat1) * d, dg = (lon2 - lon1) * d;
    var a = Math.sin(dl / 2) ** 2 + Math.cos(lat1 * d) * Math.cos(lat2 * d) * Math.sin(dg / 2) ** 2;
    return 2 * R * Math.asin(Math.sqrt(a));
  }
  // v = {lat, lon, speedKmh, heading, accuracyM, timestampMs}
  function assess(me, o, nowMs) {
    if (nowMs - me.timestampMs > MAX_AGE_MS || nowMs - o.timestampMs > MAX_AGE_MS)
      return { level: 'NORMAL', insufficient: true, reason: 'Position data too old' };
    if (me.accuracyM > MAX_ACC_M || o.accuracyM > MAX_ACC_M)
      return { level: 'NORMAL', insufficient: true, reason: 'GPS accuracy too low to assess risk' };
    var r = toLocal(me.lat, me.lon, o.lat, o.lon);
    var vm = velocity(me.speedKmh, me.heading), vo = velocity(o.speedKmh, o.heading);
    var v = { x: vo.x - vm.x, y: vo.y - vm.y };
    var v2 = v.x * v.x + v.y * v.y;
    if (v2 < 0.25) return { level: 'NORMAL', reason: 'No relative motion' };
    var t = -(r.x * v.x + r.y * v.y) / v2;
    if (t < 0) return { level: 'NORMAL', reason: 'Vehicles are diverging' };
    var cx = r.x + v.x * t, cy = r.y + v.y * t;
    var dmin = Math.sqrt(cx * cx + cy * cy);
    var margin = 8 + me.accuracyM + o.accuracyM;
    var level = 'NORMAL';
    if (t <= 6 && dmin <= margin) level = 'HIGH_RISK';
    else if (t <= 12 && dmin <= margin * 2.5) level = 'CAUTION';
    return { level: level, ttc: t, dmin: dmin, reason: level === 'NORMAL' ? 'Paths do not conflict soon' : 'Projected paths converge' };
  }
  window.DLRisk = { assess: assess, distanceM: distanceM, toLocal: toLocal, velocity: velocity };
})();
