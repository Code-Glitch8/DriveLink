-- Behavioural tests for 20261109000001_shared_hazards.sql. Run against a SCRATCH database only:
--   psql -v ON_ERROR_STOP=1 -d scratch -f supabase/tests/hazards_test.sql
-- Requires the three migrations applied and Supabase-like roles (anon, authenticated) plus auth.uid().
begin;
do $$
declare
  u1 uuid := gen_random_uuid(); u2 uuid := gen_random_uuid(); u3 uuid := gen_random_uuid(); u4 uuid := gen_random_uuid();
  h jsonb; h2 jsonb; hid uuid; n int; failed boolean;
begin
  insert into auth.users(id) values (u1),(u2),(u3),(u4);   -- trigger creates profiles
  set local role authenticated;

  -- 1. report creates an active hazard with a server-side expiry from the category TTL
  perform set_config('request.jwt.claim.sub', u1::text, true);
  h := public.report_hazard('accident', 28.6139, 77.2090, '  Two cars  ', 8);
  hid := (h->>'id')::uuid;
  assert h->>'status' = 'active' and h->>'confidence' = 'low' and (h->>'confirmations')::int = 1, 'report: ' || h;
  assert (h->>'expires_at')::timestamptz between now() + interval '59 minutes' and now() + interval '61 minutes', 'accident ttl 60m';
  assert h->>'description' = 'Two cars', 'description trimmed';
  assert not (h ? 'reporter_id'), 'reporter id must not leak';

  -- 2. duplicate report nearby (same category, ~20 m) merges as a confirmation from another user
  perform set_config('request.jwt.claim.sub', u2::text, true);
  h2 := public.report_hazard('accident', 28.61400, 77.20905, null, 10);
  assert h2->>'id' = h->>'id' and (h2->>'confirmations')::int = 2 and h2->>'confidence' = 'medium', 'dedupe: ' || h2;
  -- same user repeating does not inflate the count
  h2 := public.report_hazard('accident', 28.6140, 77.2090, null, 10);
  assert (h2->>'confirmations')::int = 2, 'no double counting';
  -- a different category at the same place is a separate hazard
  h2 := public.report_hazard('pothole', 28.6139, 77.2090, null, 10);
  assert h2->>'id' <> h->>'id', 'different category not merged';
  -- far away is a separate hazard
  h2 := public.report_hazard('accident', 28.7000, 77.2090, null, 10);
  assert h2->>'id' <> h->>'id', 'far apart not merged';

  -- 3. validation
  failed := false; begin perform public.report_hazard('nonsense', 28.6, 77.2); exception when others then failed := true; end;
  assert failed, 'invalid category rejected';
  failed := false; begin perform public.report_hazard('accident', 91, 77.2); exception when others then failed := true; end;
  assert failed, 'invalid latitude rejected';
  failed := false; begin perform public.report_hazard('accident', 0, 0); exception when others then failed := true; end;
  assert failed, 'null island rejected';
  failed := false; begin perform public.report_hazard('accident', 28.6, 77.2, repeat('x', 400)); exception when others then failed := true; end;
  assert failed, 'long description rejected';
  failed := false; begin perform public.report_hazard('accident', 28.6, 77.2, null, 500); exception when others then failed := true; end;
  assert failed, 'poor accuracy rejected';

  -- 4. bbox read: only active, only inside the box, includes my vote, no reporter id
  perform set_config('request.jwt.claim.sub', u3::text, true);
  select count(*) into n from public.hazards_in_bbox(28.60, 77.19, 28.63, 77.22);
  assert n = 2, 'bbox returns the two hazards near Delhi centre, got ' || n;
  select count(*) into n from public.hazards_in_bbox(28.60, 77.19, 28.63, 77.22) x where x ? 'reporter_id';
  assert n = 0, 'no reporter id in bbox';
  failed := false; begin perform public.hazards_in_bbox(0, 0, 20, 20); exception when others then failed := true; end;
  assert failed, 'huge bbox rejected';

  -- 5. confirm extends expiry but never beyond the max lifetime
  h2 := public.vote_hazard(hid, 'confirm', 28.6139, 77.2090);
  assert (h2->>'confirmations')::int = 3 and h2->>'confidence' = 'high', 'third confirmation => high: ' || h2;
  assert (h2->>'expires_at')::timestamptz <= now() + interval '4 hours' + interval '1 minute', 'lifetime capped';

  -- 6. too far to vote
  failed := false; begin perform public.vote_hazard(hid, 'clear', 40.0, 77.2090); exception when others then failed := true; end;
  assert failed, 'far voter rejected';

  -- 7. a single clear vote does NOT clear a confirmed hazard; two do (never treat one unverified report as fact)
  perform set_config('request.jwt.claim.sub', u4::text, true);
  h2 := public.vote_hazard(hid, 'clear', 28.6139, 77.2090);
  assert h2->>'status' = 'active' and (h2->>'clear_votes')::int = 1, 'one clear vote keeps it active: ' || h2;
  perform set_config('request.jwt.claim.sub', u2::text, true);
  h2 := public.vote_hazard(hid, 'clear', 28.6139, 77.2090);
  assert h2->>'status' = 'cleared', 'two independent clear votes clear it: ' || h2;
  perform set_config('request.jwt.claim.sub', u3::text, true);
  select count(*) into n from public.hazards_in_bbox(28.60, 77.19, 28.63, 77.22) x where x->>'id' = hid::text;
  assert n = 0, 'cleared hazard no longer returned';

  -- 8. direct table writes are blocked; reporter id column unreadable
  failed := false; begin insert into public.road_hazards(reporter_id, event_type, latitude, longitude) values (u3, 'accident', 1, 1); exception when others then failed := true; end;
  assert failed, 'direct insert blocked';
  failed := false; begin perform reporter_id from public.road_hazards limit 1; exception when others then failed := true; end;
  assert failed, 'reporter_id not readable';
  failed := false; begin perform public.purge_expired_hazards(); exception when others then failed := true; end;
  assert failed, 'purge not callable by clients';

  -- 9. expired hazards are invisible even before the purge job runs
  reset role;
  update public.road_hazards set expires_at = now() - interval '1 minute' where event_type = 'pothole';
  set local role authenticated; perform set_config('request.jwt.claim.sub', u3::text, true);
  select count(*) into n from public.hazards_in_bbox(28.60, 77.19, 28.63, 77.22) x where x->>'category' = 'pothole';
  assert n = 0, 'expired hidden';
  select count(*) into n from public.road_hazards where event_type = 'pothole';
  assert n = 0, 'expired hidden from direct reads too';

  -- 10. purge marks expired and removes old history
  reset role;
  assert public.purge_expired_hazards() >= 1, 'purge marks expired';
  assert (select status from public.road_hazards where event_type = 'pothole') = 'expired', 'status expired';
  update public.road_hazards set updated_at = now() - interval '8 days' where status in ('expired','cleared');
  perform public.purge_expired_hazards();
  assert (select count(*) from public.road_hazards where status <> 'active') = 0, 'old history removed';

  -- 11. rate limit
  perform set_config('request.jwt.claim.sub', u1::text, true);
  set local role authenticated;
  failed := false;
  begin
    for i in 1..12 loop perform public.report_hazard('construction', 28.5 + i * 0.01, 77.1); end loop;
  exception when others then failed := true; end;
  assert failed, 'rate limit kicks in';
  raise notice 'ALL HAZARD TESTS PASSED';
end $$;
rollback;
