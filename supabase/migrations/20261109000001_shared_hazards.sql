-- DriveLink shared hazards: categories, expiry, dedupe, confirmations, "cleared" reports, audit, cleanup.
-- ADDITIVE migration on top of 20261106000001_core_schema.sql. Existing road_hazards rows are preserved
-- (legacy event_type values are mapped to the new categories). Review docs/HAZARDS.md before applying.

-- ---------------------------------------------------------------- category configuration (expiry etc.)
create table if not exists public.hazard_category_config (
  category text primary key,
  label text not null,
  default_ttl_minutes integer not null check (default_ttl_minutes between 5 and 10080),
  extend_on_confirm_minutes integer not null check (extend_on_confirm_minutes between 0 and 10080),
  max_lifetime_minutes integer not null check (max_lifetime_minutes between 5 and 20160),
  dedupe_radius_m integer not null check (dedupe_radius_m between 10 and 2000),
  relevance_radius_m integer not null check (relevance_radius_m between 100 and 20000),
  severity smallint not null check (severity between 1 and 3)
);
insert into public.hazard_category_config values
  ('accident',           'Accident',            60,   30,  240,  75,  3000, 3),
  ('road_blockage',      'Road blockage',       90,   45,  360,  75,  3000, 3),
  ('construction',       'Construction',        480, 240, 1440, 100,  3000, 1),
  ('pothole',            'Pothole',             720, 360, 4320,  25,  1500, 1),
  ('vehicle_breakdown',  'Vehicle breakdown',   45,   30,  240,  40,  2000, 2),
  ('traffic_congestion', 'Traffic congestion',  30,   20,  180, 300,  5000, 1),
  ('road_closure',       'Road closure',        240, 120, 1440, 100,  5000, 3),
  ('other_danger',       'Other danger',        60,   30,  240,  50,  2000, 2)
on conflict (category) do nothing;
alter table public.hazard_category_config enable row level security;
drop policy if exists hazard_config_read on public.hazard_category_config;
create policy hazard_config_read on public.hazard_category_config for select to authenticated using (true);

-- ---------------------------------------------------------------- road_hazards: new shape
alter table public.road_hazards drop constraint if exists road_hazards_event_type_check;
update public.road_hazards set event_type = case event_type
  when 'obstruction' then 'road_blockage'
  when 'broken_down_vehicle' then 'vehicle_breakdown'
  when 'road_damage' then 'pothole'
  when 'other' then 'other_danger'
  else event_type end;
alter table public.road_hazards drop constraint if exists road_hazards_event_type_fk;
alter table public.road_hazards add constraint road_hazards_event_type_fk
  foreign key (event_type) references public.hazard_category_config(category);

alter table public.road_hazards
  add column if not exists status text not null default 'active' check (status in ('active','cleared','expired')),
  add column if not exists updated_at timestamptz not null default now(),
  add column if not exists confirmations integer not null default 1 check (confirmations >= 0),
  add column if not exists clear_votes integer not null default 0 check (clear_votes >= 0),
  add column if not exists cleared_at timestamptz,
  add column if not exists accuracy_m double precision check (accuracy_m is null or accuracy_m >= 0);

-- Hot path: "active hazards inside this bounding box". Partial index keeps it small as history grows.
create index if not exists road_hazards_active_geo_idx on public.road_hazards(latitude, longitude)
  where status = 'active';
create index if not exists road_hazards_status_exp_idx on public.road_hazards(status, expires_at);

-- ---------------------------------------------------------------- votes and audit
create table if not exists public.road_hazard_votes (
  hazard_id uuid not null references public.road_hazards(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  vote text not null check (vote in ('confirm','clear')),
  created_at timestamptz not null default now(),
  primary key (hazard_id, user_id)
);
alter table public.road_hazard_votes enable row level security;
drop policy if exists hazard_votes_own on public.road_hazard_votes;
create policy hazard_votes_own on public.road_hazard_votes for select to authenticated using (user_id = (select auth.uid()));

create table if not exists public.road_hazard_audit (
  id bigint generated always as identity primary key,
  hazard_id uuid,
  actor_id uuid,
  action text not null check (action in ('report','confirm','clear_vote','cleared','expired','merged')),
  at timestamptz not null default now()
);
create index if not exists road_hazard_audit_at_idx on public.road_hazard_audit(at);
alter table public.road_hazard_audit enable row level security;
drop policy if exists hazard_audit_no_access on public.road_hazard_audit;
create policy hazard_audit_no_access on public.road_hazard_audit for select to authenticated using (false);

-- ---------------------------------------------------------------- RLS: all writes go through the RPCs below
drop policy if exists hazards_select on public.road_hazards;
drop policy if exists hazards_insert on public.road_hazards;
drop policy if exists hazards_delete on public.road_hazards;
-- Expired hazards are never visible as active, even if the cleanup job has not run yet.
drop policy if exists hazards_select on public.road_hazards;
create policy hazards_select on public.road_hazards for select to authenticated
  using (status = 'active' and expires_at > now() and verification_status <> 'dismissed');
revoke insert, update, delete on public.road_hazards from authenticated, anon;
-- Hide who reported a hazard: column-level read grant without reporter_id.
revoke select on public.road_hazards from authenticated, anon;
grant select (id, event_type, latitude, longitude, description, created_at, expires_at, verification_status,
              status, updated_at, confirmations, clear_votes, accuracy_m) on public.road_hazards to authenticated;

-- ---------------------------------------------------------------- helpers
create or replace function public.hazard_confidence(p_confirmations integer, p_clear_votes integer, p_verification text)
returns text language sql immutable set search_path = public as $$
  select case
    when p_verification = 'verified' then 'verified'
    when p_clear_votes > 0 and p_clear_votes >= p_confirmations then 'disputed'
    when p_confirmations >= 3 then 'high'
    when p_confirmations = 2 then 'medium'
    else 'low' end
$$;

create or replace function public.hazard_distance_m(lat1 double precision, lon1 double precision, lat2 double precision, lon2 double precision)
returns double precision language sql immutable set search_path = public as $$
  select 2 * 6371000 * asin(sqrt(
    power(sin(radians(lat2 - lat1) / 2), 2) +
    cos(radians(lat1)) * cos(radians(lat2)) * power(sin(radians(lon2 - lon1) / 2), 2)))
$$;

-- Shape returned to clients (no reporter id).
create or replace function public.hazard_json(h public.road_hazards, p_my_vote text default null)
returns jsonb language sql stable set search_path = public as $$
  select jsonb_build_object(
    'id', h.id, 'category', h.event_type, 'lat', h.latitude, 'lon', h.longitude, 'description', h.description,
    'created_at', h.created_at, 'updated_at', h.updated_at, 'expires_at', h.expires_at, 'status', h.status,
    'confirmations', h.confirmations, 'clear_votes', h.clear_votes,
    'confidence', public.hazard_confidence(h.confirmations, h.clear_votes, h.verification_status),
    'my_vote', p_my_vote)
$$;

-- ---------------------------------------------------------------- report (validated, rate limited, deduplicated)
create or replace function public.report_hazard(
  p_category text, p_lat double precision, p_lon double precision,
  p_description text default null, p_accuracy_m double precision default null)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  uid uuid := auth.uid();
  cfg public.hazard_category_config;
  existing public.road_hazards;
  created public.road_hazards;
  desc_clean text := nullif(btrim(coalesce(p_description, '')), '');
begin
  if uid is null then raise exception 'not authenticated' using errcode = '28000'; end if;
  if not exists (select 1 from public.profiles where id = uid and account_status = 'active') then
    raise exception 'account not active' using errcode = '42501';
  end if;
  select * into cfg from public.hazard_category_config where category = p_category;
  if not found then raise exception 'invalid category' using errcode = '22023'; end if;
  if p_lat is null or p_lon is null or p_lat not between -90 and 90 or p_lon not between -180 and 180
     or p_lat = 0 and p_lon = 0 then
    raise exception 'invalid coordinates' using errcode = '22023';
  end if;
  if desc_clean is not null and char_length(desc_clean) > 280 then raise exception 'description too long' using errcode = '22023'; end if;
  if p_accuracy_m is not null and (p_accuracy_m < 0 or p_accuracy_m > 100) then
    raise exception 'location accuracy too low to report' using errcode = '22023';
  end if;
  -- Abuse limit: at most 8 reports / confirmations per user in 10 minutes.
  if (select count(*) from public.road_hazard_audit where actor_id = uid and action in ('report','merged')
        and at > now() - interval '10 minutes') >= 8 then
    raise exception 'too many reports, try again later' using errcode = '54000';
  end if;

  -- Dedupe: same category, still active, within the category's radius -> treat as a confirmation.
  select * into existing from public.road_hazards
   where event_type = p_category and status = 'active' and expires_at > now()
     and latitude between p_lat - (cfg.dedupe_radius_m / 111000.0) and p_lat + (cfg.dedupe_radius_m / 111000.0)
     and longitude between p_lon - (cfg.dedupe_radius_m / (111000.0 * greatest(cos(radians(p_lat)), 0.01)))
                       and p_lon + (cfg.dedupe_radius_m / (111000.0 * greatest(cos(radians(p_lat)), 0.01)))
     and public.hazard_distance_m(latitude, longitude, p_lat, p_lon) <= cfg.dedupe_radius_m
   order by public.hazard_distance_m(latitude, longitude, p_lat, p_lon) limit 1 for update;

  if found then
    insert into public.road_hazard_votes(hazard_id, user_id, vote) values (existing.id, uid, 'confirm')
      on conflict (hazard_id, user_id) do nothing;
    if found then  -- a new vote row was inserted (first time this user speaks about this hazard)
      update public.road_hazards set confirmations = confirmations + 1, updated_at = now(),
        expires_at = least(created_at + make_interval(mins => cfg.max_lifetime_minutes),
                           greatest(expires_at, now() + make_interval(mins => cfg.extend_on_confirm_minutes)))
       where id = existing.id returning * into existing;
      insert into public.road_hazard_audit(hazard_id, actor_id, action) values (existing.id, uid, 'merged');
    end if;
    return public.hazard_json(existing, 'confirm');
  end if;

  insert into public.road_hazards(reporter_id, event_type, latitude, longitude, description, accuracy_m, expires_at)
  values (uid, p_category, p_lat, p_lon, desc_clean, p_accuracy_m, now() + make_interval(mins => cfg.default_ttl_minutes))
  returning * into created;
  insert into public.road_hazard_votes(hazard_id, user_id, vote) values (created.id, uid, 'confirm');
  insert into public.road_hazard_audit(hazard_id, actor_id, action) values (created.id, uid, 'report');
  return public.hazard_json(created, 'confirm');
end $$;

-- ---------------------------------------------------------------- confirm / clear votes
create or replace function public.vote_hazard(p_id uuid, p_vote text, p_lat double precision, p_lon double precision)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  uid uuid := auth.uid();
  h public.road_hazards;
  cfg public.hazard_category_config;
  prev text;
  is_reporter boolean;
begin
  if uid is null then raise exception 'not authenticated' using errcode = '28000'; end if;
  if p_vote not in ('confirm','clear') then raise exception 'invalid vote' using errcode = '22023'; end if;
  if p_lat is null or p_lon is null or p_lat not between -90 and 90 or p_lon not between -180 and 180 then
    raise exception 'invalid coordinates' using errcode = '22023';
  end if;
  select * into h from public.road_hazards where id = p_id and status = 'active' and expires_at > now() for update;
  if not found then raise exception 'hazard not found or no longer active' using errcode = 'P0002'; end if;
  -- Validation: the voter must claim to be close to the hazard (cannot be proven server-side; limits drive-by abuse).
  if public.hazard_distance_m(h.latitude, h.longitude, p_lat, p_lon) > 1500 then
    raise exception 'too far from the hazard to vote' using errcode = '22023';
  end if;
  select * into cfg from public.hazard_category_config where category = h.event_type;
  select vote into prev from public.road_hazard_votes where hazard_id = p_id and user_id = uid;
  is_reporter := (h.reporter_id = uid);

  if prev is distinct from p_vote then
    insert into public.road_hazard_votes(hazard_id, user_id, vote) values (p_id, uid, p_vote)
      on conflict (hazard_id, user_id) do update set vote = excluded.vote, created_at = now();
    update public.road_hazards set
      confirmations = confirmations + (case when p_vote = 'confirm' then 1 else 0 end) - (case when prev = 'confirm' then 1 else 0 end),
      clear_votes   = clear_votes   + (case when p_vote = 'clear'   then 1 else 0 end) - (case when prev = 'clear'   then 1 else 0 end),
      updated_at = now()
     where id = p_id returning * into h;
    insert into public.road_hazard_audit(hazard_id, actor_id, action)
      values (p_id, uid, case when p_vote = 'confirm' then 'confirm' else 'clear_vote' end);
  end if;

  if p_vote = 'confirm' then
    update public.road_hazards set updated_at = now(),
      expires_at = least(created_at + make_interval(mins => cfg.max_lifetime_minutes),
                         greatest(expires_at, now() + make_interval(mins => cfg.extend_on_confirm_minutes)))
     where id = p_id returning * into h;
  elsif h.clear_votes >= 2 or (is_reporter and h.confirmations <= 1) or (h.clear_votes >= 1 and h.clear_votes > h.confirmations) then
    -- Cleared only on independent agreement (2 votes), by the lone reporter retracting, or when clear votes outnumber confirmations.
    update public.road_hazards set status = 'cleared', cleared_at = now(), updated_at = now() where id = p_id returning * into h;
    insert into public.road_hazard_audit(hazard_id, actor_id, action) values (p_id, uid, 'cleared');
  end if;
  return public.hazard_json(h, p_vote);
end $$;

-- ---------------------------------------------------------------- read: bounding-box query (geographic filtering)
create or replace function public.hazards_in_bbox(
  p_min_lat double precision, p_min_lon double precision, p_max_lat double precision, p_max_lon double precision,
  p_limit integer default 200)
returns setof jsonb language plpgsql stable security definer set search_path = public as $$
declare uid uuid := auth.uid();
begin
  if uid is null then raise exception 'not authenticated' using errcode = '28000'; end if;
  if p_min_lat > p_max_lat or p_min_lat < -90 or p_max_lat > 90 or p_min_lon < -180 or p_max_lon > 180 then
    raise exception 'invalid bounding box' using errcode = '22023';
  end if;
  -- Refuse continent-sized requests: relevance is local by design.
  if (p_max_lat - p_min_lat) > 2.0 or (p_max_lon - p_min_lon) > 2.0 then
    raise exception 'bounding box too large' using errcode = '22023';
  end if;
  return query
    select public.hazard_json(h, v.vote)
      from public.road_hazards h
      left join public.road_hazard_votes v on v.hazard_id = h.id and v.user_id = uid
     where h.status = 'active' and h.expires_at > now() and h.verification_status <> 'dismissed'
       and h.latitude between p_min_lat and p_max_lat and h.longitude between p_min_lon and p_max_lon
     order by h.created_at desc
     limit least(greatest(p_limit, 1), 500);
end $$;

-- ---------------------------------------------------------------- cleanup
create or replace function public.purge_expired_hazards() returns integer
language plpgsql security definer set search_path = public as $$
declare n integer;
begin
  with e as (update public.road_hazards set status = 'expired', updated_at = now()
              where status = 'active' and expires_at <= now() returning id)
  insert into public.road_hazard_audit(hazard_id, action) select id, 'expired' from e;
  get diagnostics n = row_count;
  delete from public.road_hazards where status in ('expired','cleared') and updated_at < now() - interval '7 days';
  delete from public.road_hazard_audit where at < now() - interval '30 days';
  return n;
end $$;

-- ---------------------------------------------------------------- privileges
revoke all on function public.report_hazard(text, double precision, double precision, text, double precision) from public, anon;
revoke all on function public.vote_hazard(uuid, text, double precision, double precision) from public, anon;
revoke all on function public.hazards_in_bbox(double precision, double precision, double precision, double precision, integer) from public, anon;
revoke all on function public.purge_expired_hazards() from public, anon, authenticated;
revoke all on function public.hazard_json(public.road_hazards, text) from public, anon;
grant execute on function public.report_hazard(text, double precision, double precision, text, double precision) to authenticated;
grant execute on function public.vote_hazard(uuid, text, double precision, double precision) to authenticated;
grant execute on function public.hazards_in_bbox(double precision, double precision, double precision, double precision, integer) to authenticated;
grant execute on function public.hazard_json(public.road_hazards, text) to authenticated;

-- Schedule hourly cleanup when pg_cron is enabled (Dashboard > Database > Extensions). Safe no-op otherwise.
do $$ begin
  if exists (select 1 from pg_extension where extname = 'pg_cron') then
    perform cron.schedule('drivelink-purge-hazards', '*/15 * * * *', 'select public.purge_expired_hazards()');
  end if;
end $$;
