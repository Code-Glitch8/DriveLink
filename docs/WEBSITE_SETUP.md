# Website setup

Static site: `index.html` + `website/` (no build step, no Node.js). Supabase JS is loaded from jsDelivr, pinned to 2.45.4.
`website/config/config.js` holds the public Supabase URL and anon key (safe by design; protected by RLS) and an optional Maps key.

1. Enable GitHub Pages with source **GitHub Actions**; run **Deploy website**.
2. Supabase → Authentication → URL Configuration: Site URL = your Pages URL; add it to Redirect URLs.
3. Optional: secret `GOOGLE_MAPS_API_KEY_WEB` (Maps JavaScript API, HTTP-referrer restricted to your Pages domain) enables the simulator map.
4. Local preview: `python3 -m http.server` in the repo root and open http://localhost:8000.

Features: register/verify email/sign in/out/reset and change password, profile, vehicle CRUD with active vehicle, emergency-verification requests,
account deletion (Edge Function), light/dark theme, collision-risk simulator.

**Download button:** set `apkUrl` in `website/config/config.js` to a direct link (for example a GitHub Release asset) and the landing page's *Download APK* button will point to it.
