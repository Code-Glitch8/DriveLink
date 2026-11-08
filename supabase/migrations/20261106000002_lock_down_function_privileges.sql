-- Trigger functions never need to be callable through the API.
revoke all on function public.handle_new_user() from public, anon, authenticated;
revoke all on function public.vehicles_guard() from public, anon, authenticated;
revoke all on function public.user_settings_guard() from public, anon, authenticated;
revoke all on function public.is_admin() from public, anon;
grant execute on function public.is_admin() to authenticated;
revoke all on function public.admin_set_emergency_status(uuid,text) from public, anon;
revoke all on function public.request_emergency_account(uuid,text,text) from public, anon;
-- Internal tables: explicit deny-all policies for signed-in clients.
create policy admin_users_no_access on public.admin_users for select to authenticated using (false);
create policy counters_no_access on public.vehicle_id_counters for select to authenticated using (false);
