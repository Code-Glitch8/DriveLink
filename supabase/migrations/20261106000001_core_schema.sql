-- DriveLink core schema: identity, vehicle registry, emergency verification, hazards.
-- Live vehicle positions are NOT stored here; nearby phones exchange them directly.
create extension if not exists pgcrypto;

create or replace function public.set_updated_at() returns trigger
language plpgsql set search_path = public as $$ begin new.updated_at := now(); return new; end $$;

-- ---------- admin role (not grantable by clients) ----------
create table public.admin_users (
  user_id uuid primary key references auth.users(id) on delete cascade,
  granted_at timestamptz not null default now()
);
alter table public.admin_users enable row level security; -- no policies: clients cannot read/write

create or replace function public.is_admin() returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.admin_users where user_id = auth.uid());
$$;

-- ---------- profiles ----------
create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  full_name text not null default '' check (char_length(full_name) <= 120),
  phone_number text check (phone_number is null or phone_number ~ '^\+?[0-9 ()-]{6,20}$'),
  avatar_url text check (avatar_url is null or char_length(avatar_url) <= 500),
  account_status text not null default 'active' check (account_status in ('active','suspended')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create trigger profiles_updated before update on public.profiles
  for each row execute function public.set_updated_at();

-- ---------- vehicles ----------
create table public.vehicle_id_counters (
  country_code text primary key check (country_code ~ '^[A-Z]{3}$'),
  last_value bigint not null default 0
);
alter table public.vehicle_id_counters enable row level security; -- no policies

create or replace function public.generate_drivelink_vehicle_id(p_country text) returns text
language plpgsql security definer set search_path = public as $$
declare v bigint;
begin
  -- atomic upsert: concurrent callers serialize on the row lock, so IDs never repeat
  insert into public.vehicle_id_counters(country_code, last_value) values (upper(p_country), 1)
  on conflict (country_code) do update set last_value = vehicle_id_counters.last_value + 1
  returning last_value into v;
  return 'DL-' || upper(p_country) || '-' || lpad(v::text, 6, '0');
end $$;
revoke all on function public.generate_drivelink_vehicle_id(text) from public, anon, authenticated;

create table public.vehicles (
  id uuid primary key default gen_random_uuid(),
  drive_link_vehicle_id text not null unique,
  owner_id uuid not null references public.profiles(id) on delete cascade,
  country_code text not null default 'IND' check (country_code ~ '^[A-Z]{3}$'),
  registration_number text not null check (char_length(registration_number) between 3 and 20),
  registration_region text not null check (char_length(registration_region) between 2 and 60),
  vin text check (vin is null or vin ~ '^[A-HJ-NPR-Z0-9]{17}$'),
  vehicle_type text not null check (vehicle_type in
    ('car','motorcycle','scooter','bus','truck','ambulance','police','fire_service','other')),
  manufacturer text check (manufacturer is null or char_length(manufacturer) <= 60),
  model text check (model is null or char_length(model) <= 60),
  manufacturing_year int check (manufacturing_year is null or manufacturing_year between 1950 and 2100),
  colour text check (colour is null or char_length(colour) <= 30),
  nickname text check (nickname is null or char_length(nickname) <= 60),
  ownership_status text not null default 'owner' check (ownership_status in ('owner','pending','transferred')),
  verification_status text not null default 'unverified' check (verification_status in ('unverified','pending','verified','rejected')),
  communication_enabled boolean not null default true,
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index vehicles_registration_uniq on public.vehicles
  (country_code, upper(registration_region), upper(regexp_replace(registration_number, '\s', '', 'g')));
create index vehicles_owner_idx on public.vehicles(owner_id);

create or replace function public.vehicles_guard() returns trigger
language plpgsql security definer set search_path = public as $$
declare caller uuid := auth.uid();
begin
  if tg_op = 'INSERT' then
    if caller is not null then new.owner_id := caller; end if;  -- never trust client-supplied ownership
    new.drive_link_vehicle_id := public.generate_drivelink_vehicle_id(new.country_code);
    if not public.is_admin() then new.verification_status := 'unverified'; end if;
  else
    if not public.is_admin() then  -- immutable for ordinary users
      new.drive_link_vehicle_id := old.drive_link_vehicle_id;
      new.owner_id := old.owner_id;
      new.country_code := old.country_code;
      new.verification_status := old.verification_status;
      new.ownership_status := old.ownership_status;
    end if;
  end if;
  return new;
end $$;
create trigger vehicles_guard_trg before insert or update on public.vehicles
  for each row execute function public.vehicles_guard();
create trigger vehicles_updated before update on public.vehicles
  for each row execute function public.set_updated_at();

-- ---------- settings ----------
create table public.user_settings (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  theme text not null default 'dark' check (theme in ('dark','light','system')),
  voice_alerts boolean not null default true,
  communication_preferences jsonb not null default '{}'::jsonb,
  privacy_settings jsonb not null default '{}'::jsonb,
  active_vehicle_id uuid references public.vehicles(id) on delete set null,
  updated_at timestamptz not null default now()
);
create trigger user_settings_updated before update on public.user_settings
  for each row execute function public.set_updated_at();

create or replace function public.user_settings_guard() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if new.active_vehicle_id is not null and not exists
     (select 1 from public.vehicles where id = new.active_vehicle_id and owner_id = new.user_id) then
    raise exception 'active vehicle must belong to the user';
  end if;
  return new;
end $$;
create trigger user_settings_guard_trg before insert or update on public.user_settings
  for each row execute function public.user_settings_guard();

-- ---------- journeys ----------
create table public.active_vehicle_sessions (
  id uuid primary key default gen_random_uuid(),
  vehicle_id uuid not null references public.vehicles(id) on delete cascade,
  owner_id uuid not null references public.profiles(id) on delete cascade,
  session_status text not null default 'active' check (session_status in ('active','ended')),
  started_at timestamptz not null default now(),
  ended_at timestamptz,
  communication_mode text not null default 'nearby' check (communication_mode in ('nearby','offline','simulated'))
);
create index sessions_owner_idx on public.active_vehicle_sessions(owner_id, session_status);

-- ---------- emergency accounts (admin-verified only) ----------
create table public.emergency_accounts (
  id uuid primary key default gen_random_uuid(),
  vehicle_id uuid not null unique references public.vehicles(id) on delete cascade,
  organisation_name text not null check (char_length(organisation_name) between 2 and 120),
  emergency_type text not null check (emergency_type in ('ambulance','police','fire_service')),
  verification_status text not null default 'pending' check (verification_status in ('pending','verified','rejected','revoked')),
  verified_at timestamptz,
  verified_by uuid references auth.users(id)
);

-- Owners can only REQUEST; the row is always created as pending.
create or replace function public.request_emergency_account(p_vehicle uuid, p_org text, p_type text)
returns uuid language plpgsql security definer set search_path = public as $$
declare rid uuid;
begin
  if not exists (select 1 from public.vehicles where id = p_vehicle and owner_id = auth.uid()) then
    raise exception 'vehicle not found';
  end if;
  insert into public.emergency_accounts(vehicle_id, organisation_name, emergency_type)
  values (p_vehicle, p_org, p_type) returning id into rid;
  return rid;
end $$;

create or replace function public.admin_set_emergency_status(p_id uuid, p_status text)
returns void language plpgsql security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'admin only'; end if;
  if p_status not in ('verified','rejected','revoked') then raise exception 'invalid status'; end if;
  update public.emergency_accounts
     set verification_status = p_status,
         verified_at = case when p_status = 'verified' then now() end,
         verified_by = auth.uid()
   where id = p_id;
end $$;
revoke all on function public.request_emergency_account(uuid,text,text) from public, anon;
revoke all on function public.admin_set_emergency_status(uuid,text) from public, anon;
grant execute on function public.request_emergency_account(uuid,text,text) to authenticated;
grant execute on function public.admin_set_emergency_status(uuid,text) to authenticated;

-- ---------- events & hazards (short-lived) ----------
create table public.vehicle_events (
  id uuid primary key default gen_random_uuid(),
  vehicle_id uuid not null references public.vehicles(id) on delete cascade,
  event_type text not null check (char_length(event_type) <= 40),
  event_metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default now() + interval '1 hour'
);
create index vehicle_events_vehicle_idx on public.vehicle_events(vehicle_id, expires_at);

create table public.road_hazards (
  id uuid primary key default gen_random_uuid(),
  reporter_id uuid not null references public.profiles(id) on delete cascade,
  event_type text not null check (event_type in
    ('accident','obstruction','broken_down_vehicle','road_damage','road_closure','other')),
  latitude double precision not null check (latitude between -90 and 90),
  longitude double precision not null check (longitude between -180 and 180),
  description text check (description is null or char_length(description) <= 500),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default now() + interval '6 hours',
  verification_status text not null default 'unverified' check (verification_status in ('unverified','verified','dismissed'))
);
create index road_hazards_geo_idx on public.road_hazards(latitude, longitude);
create index road_hazards_exp_idx on public.road_hazards(expires_at);

-- ---------- new-user bootstrap ----------
create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles(id, full_name) values (new.id, coalesce(new.raw_user_meta_data->>'full_name',''));
  insert into public.user_settings(user_id) values (new.id);
  return new;
end $$;
create trigger on_auth_user_created after insert on auth.users
  for each row execute function public.handle_new_user();

-- ---------- Row Level Security ----------
alter table public.profiles enable row level security;
alter table public.vehicles enable row level security;
alter table public.user_settings enable row level security;
alter table public.active_vehicle_sessions enable row level security;
alter table public.emergency_accounts enable row level security;
alter table public.vehicle_events enable row level security;
alter table public.road_hazards enable row level security;

create policy profiles_select on public.profiles for select to authenticated using (id = (select auth.uid()) or public.is_admin());
create policy profiles_update on public.profiles for update to authenticated using (id = (select auth.uid()))
  with check (id = (select auth.uid()) and account_status = 'active');

create policy vehicles_select on public.vehicles for select to authenticated using (owner_id = (select auth.uid()) or public.is_admin());
create policy vehicles_insert on public.vehicles for insert to authenticated with check (owner_id = (select auth.uid()));
create policy vehicles_update on public.vehicles for update to authenticated
  using (owner_id = (select auth.uid()) or public.is_admin()) with check (owner_id = (select auth.uid()) or public.is_admin());
create policy vehicles_delete on public.vehicles for delete to authenticated using (owner_id = (select auth.uid()));

create policy settings_all on public.user_settings for all to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

create policy sessions_select on public.active_vehicle_sessions for select to authenticated using (owner_id = (select auth.uid()));
create policy sessions_insert on public.active_vehicle_sessions for insert to authenticated with check (
  owner_id = (select auth.uid()) and exists (select 1 from public.vehicles v where v.id = vehicle_id and v.owner_id = (select auth.uid())));
create policy sessions_update on public.active_vehicle_sessions for update to authenticated
  using (owner_id = (select auth.uid())) with check (owner_id = (select auth.uid()));

-- emergency_accounts: read-only for owners/admins; writes only via request_emergency_account() / admin_set_emergency_status()
create policy emergency_select on public.emergency_accounts for select to authenticated using (
  public.is_admin() or exists (select 1 from public.vehicles v where v.id = vehicle_id and v.owner_id = (select auth.uid())));

create policy events_select on public.vehicle_events for select to authenticated using (
  exists (select 1 from public.vehicles v where v.id = vehicle_id and v.owner_id = (select auth.uid())));
create policy events_insert on public.vehicle_events for insert to authenticated with check (
  exists (select 1 from public.vehicles v where v.id = vehicle_id and v.owner_id = (select auth.uid())));

create policy hazards_select on public.road_hazards for select to authenticated
  using (expires_at > now() and verification_status <> 'dismissed');
create policy hazards_insert on public.road_hazards for insert to authenticated with check (reporter_id = (select auth.uid()));
create policy hazards_delete on public.road_hazards for delete to authenticated using (reporter_id = (select auth.uid()));
revoke update on public.road_hazards from authenticated, anon;  -- users cannot alter hazard verification
