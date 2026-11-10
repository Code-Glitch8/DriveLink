# Database migrations — how to deploy safely

Migrations live in `supabase/migrations/` and are applied in filename order. The app's existing project
(`zfvbaicrqejktfiszefr`) already has `20261106000001` and `20261106000002`. **`20261109000001_shared_hazards.sql` is new and NOT applied.**

## What the new migration changes (all additive or tightening, no user data deleted)
- New tables: `hazard_category_config` (seeded), `road_hazard_votes`, `road_hazard_audit`.
- `road_hazards`: new columns (`status`, `updated_at`, `confirmations`, `clear_votes`, `cleared_at`, `accuracy_m`), partial geo index, status/expiry index.
- Existing hazard rows are **kept**; legacy `event_type` values are remapped: `obstruction→road_blockage`, `broken_down_vehicle→vehicle_breakdown`,
  `road_damage→pothole`, `other→other_danger` (`accident`, `road_closure` unchanged). `event_type` becomes a foreign key to `hazard_category_config`.
- Policies on `road_hazards`: direct insert/delete policies are dropped and table-level insert/update/delete revoked (writes go through RPCs);
  `reporter_id` is no longer selectable by clients.
- New functions: `report_hazard`, `vote_hazard`, `hazards_in_bbox`, `purge_expired_hazards`, helpers `hazard_json/hazard_confidence/hazard_distance_m`.
- Optional `pg_cron` schedule (only if the extension is already enabled).
- **Breaking for anything that reads/writes `road_hazards` directly** (the shipped app did not; the website does not). Use the RPCs.

## Recommended deployment
1. **Back up** (Dashboard → Database → Backups, or `pg_dump`).
2. Test on a **Supabase branch** or a scratch project first (`supabase db push` against it, then run `supabase/tests/hazards_test.sql`).
3. Apply to production: SQL editor → paste the file → run (it is re-runnable), or `supabase db push`.
4. (Optional) Enable the `pg_cron` extension, then re-run the last `do $$ … $$` block, or schedule `select public.purge_expired_hazards();` every 15 min.
5. Smoke test with a real account: report a hazard in the app, confirm it appears on a second account, then clear it with two accounts.

## Rollback
Restore the backup, or (if only hazards data matters) `drop function` the four RPCs, re-grant table privileges and recreate the old
policies from `20261106000001_core_schema.sql`. New columns/tables can stay; they are harmless to the old app.

## Verified how
The migration and `supabase/tests/hazards_test.sql` were run on a **scratch PostgreSQL 16** with Supabase-style roles and `auth.uid()` stubs: legacy rows
preserved and remapped, re-run is idempotent, dedupe, expiry caps, clear voting, RLS/privilege checks, rate limit. It was **not** run against your real
Supabase project (no access, by design). Realtime publication is not enabled by this migration.
