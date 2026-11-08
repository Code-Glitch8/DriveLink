// Issues a short-lived, server-signed credential proving a vehicle is an admin-verified emergency vehicle.
// Android peers verify the signature offline with the matching public key (CRED_PUBLIC_KEY_B64).
// Required secret: CRED_SIGNING_KEY_PKCS8_B64 (see scripts/gen-credential-keys.sh).
import { createClient } from "npm:@supabase/supabase-js@2.45.4";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (b: unknown, status = 200) =>
  new Response(JSON.stringify(b), { status, headers: { ...cors, "Content-Type": "application/json" } });
const b64 = (buf: ArrayBuffer | Uint8Array) => btoa(String.fromCharCode(...new Uint8Array(buf)));
const unb64 = (s: string) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  const keyB64 = Deno.env.get("CRED_SIGNING_KEY_PKCS8_B64");
  if (!keyB64) return json({ error: "CRED_SIGNING_KEY_PKCS8_B64 secret is not configured" }, 500);

  const userClient = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: req.headers.get("Authorization") ?? "" } },
  });
  const { data: { user } } = await userClient.auth.getUser();
  if (!user) return json({ error: "unauthorized" }, 401);

  const { vehicle_id } = await req.json().catch(() => ({}));
  if (typeof vehicle_id !== "string") return json({ error: "vehicle_id required" }, 400);

  // RLS guarantees the caller owns the vehicle.
  const { data: v } = await userClient.from("vehicles")
    .select("drive_link_vehicle_id, emergency_accounts(emergency_type, verification_status)")
    .eq("id", vehicle_id).maybeSingle();
  const em = Array.isArray(v?.emergency_accounts) ? v!.emergency_accounts[0] : v?.emergency_accounts;
  if (!v || !em || em.verification_status !== "verified") return json({ error: "not a verified emergency vehicle" }, 403);

  const payload = JSON.stringify({ v: 1, vid: v.drive_link_vehicle_id, etype: em.emergency_type, exp: Math.floor(Date.now() / 1000) + 24 * 3600 });
  const key = await crypto.subtle.importKey("pkcs8", unb64(keyB64), { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, new TextEncoder().encode(payload));
  // sig is IEEE P1363 (r||s, 64 bytes)
  return json({ payload: b64(new TextEncoder().encode(payload)), sig: b64(sig) });
});
