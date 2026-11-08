# Database

Migrations: `supabase/migrations/20261106000001_core_schema.sql`, `…000002_lock_down_function_privileges.sql` (both applied).

Tables: `profiles`, `vehicles`, `user_settings`, `active_vehicle_sessions`, `emergency_accounts`, `vehicle_events`, `road_hazards`,
plus internal `admin_users` and `vehicle_id_counters` (no client access).

- **Vehicle IDs** (`DL-IND-000001`) come from `generate_drivelink_vehicle_id()`: one atomic `INSERT … ON CONFLICT DO UPDATE` per country, so
  concurrent registrations cannot collide. The function is not callable by clients; a `BEFORE INSERT` trigger assigns the ID and forces
  `owner_id = auth.uid()` and `verification_status = 'unverified'`. Registration numbers are unique per country/region (whitespace/case-insensitive).
- **Immutable for ordinary users:** `drive_link_vehicle_id`, `owner_id`, `country_code`, `verification_status`, `ownership_status`.
- **Emergency privileges:** clients cannot write `emergency_accounts`. Owners call `request_emergency_account()` (creates a *pending* row);
  admins call `admin_set_emergency_status()`. Admin role = row in `admin_users`, writable only with the SQL editor/service role.
- **RLS:** owners see/change only their own rows; hazards are readable by signed-in users until expiry; no policy allows reading others' vehicles.
- Registration numbers are never exposed to nearby devices; only the owner (and admins) can select them.
- Passwords live only in Supabase Auth. Deleting an account (Edge Function `delete-account`) cascades to all rows.
- Live positions are not stored. Old `vehicle_events`/`road_hazards` rows should be purged periodically (`delete from … where expires_at < now()`; add a pg_cron job if desired).
