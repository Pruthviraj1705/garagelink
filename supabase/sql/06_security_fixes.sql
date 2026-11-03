-- 06_security_fixes.sql
-- Hardening migration. Review it, run it once in the Supabase SQL Editor, then run the
-- verification block at the bottom.
--
-- Background: the policies garages_owner_all and profiles_update_own / profiles_insert_own let a
-- logged-in user write ANY column of their own row straight through the REST API. That includes
-- is_verified, subscription_status, subscription_expiry and current_plan_id, i.e. a user could
-- verify their own garage or grant themselves a paid plan without any purchase.
--
-- Fix: BEFORE triggers that reject those columns when the statement comes from a client role.
-- Inside SECURITY DEFINER functions (purchase_*_manual, redeem_*) current_user is the function
-- owner, not 'authenticated', so the server-side flows keep working.

-- ---------------------------------------------------------------------------
-- 1. garages: owners may edit shop details, never verification or billing state
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.protect_garage_privileged_columns()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO 'public'
AS $function$
begin
  -- Only restrict direct client calls. SECURITY DEFINER RPCs and the service role pass through.
  if current_user not in ('authenticated', 'anon') then
    return new;
  end if;

  if tg_op = 'INSERT' then
    if new.is_verified is distinct from false
       or new.current_plan_id is not null
       or new.subscription_expiry is not null
       or new.subscription_status is distinct from 'trial'::subscription_status then
      raise exception 'protected_column: verification and billing fields cannot be set by clients';
    end if;
  else
    if new.is_verified is distinct from old.is_verified
       or new.current_plan_id is distinct from old.current_plan_id
       or new.subscription_expiry is distinct from old.subscription_expiry
       or new.subscription_status is distinct from old.subscription_status then
      raise exception 'protected_column: verification and billing fields cannot be changed by clients';
    end if;
  end if;

  return new;
end;
$function$;

DROP TRIGGER IF EXISTS trg_protect_garage_privileged_columns ON public.garages;
CREATE TRIGGER trg_protect_garage_privileged_columns
  BEFORE INSERT OR UPDATE ON public.garages
  FOR EACH ROW EXECUTE FUNCTION public.protect_garage_privileged_columns();

-- ---------------------------------------------------------------------------
-- 2. profiles: users may not set their own plan, expiry, status or referral code
--    (the app only ever inserts id, phone_number and role)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.protect_profile_privileged_columns()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO 'public'
AS $function$
begin
  if current_user not in ('authenticated', 'anon') then
    return new;
  end if;

  if tg_op = 'INSERT' then
    if new.current_plan_id is not null or new.subscription_expiry is not null then
      raise exception 'protected_column: billing fields cannot be set by clients';
    end if;
  else
    if new.current_plan_id is distinct from old.current_plan_id
       or new.subscription_expiry is distinct from old.subscription_expiry
       or new.subscription_status is distinct from old.subscription_status
       or new.referral_code is distinct from old.referral_code then
      raise exception 'protected_column: billing and referral fields cannot be changed by clients';
    end if;
  end if;

  return new;
end;
$function$;

DROP TRIGGER IF EXISTS trg_protect_profile_privileged_columns ON public.profiles;
CREATE TRIGGER trg_protect_profile_privileged_columns
  BEFORE INSERT OR UPDATE ON public.profiles
  FOR EACH ROW EXECUTE FUNCTION public.protect_profile_privileged_columns();

-- ---------------------------------------------------------------------------
-- 3. check_subscription_expiring() is a cron job, not an API: remove client access.
--    pg_cron runs it as the database owner, so the schedule keeps working.
-- ---------------------------------------------------------------------------

REVOKE EXECUTE ON FUNCTION public.check_subscription_expiring() FROM PUBLIC, anon, authenticated;

-- ---------------------------------------------------------------------------
-- 4. log_search_demand(): require a logged-in caller and bound the input, so the demand
--    table cannot be spammed anonymously with arbitrary text.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.log_search_demand(p_query text, p_lat double precision, p_lng double precision)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if auth.uid() is null then
    return;
  end if;
  if p_query is null or length(trim(p_query)) = 0 or length(p_query) > 100 then
    return;
  end if;
  insert into search_demand_log (part_query, user_lat, user_lng)
  values (left(trim(p_query), 100), p_lat, p_lng);
end;
$function$;

-- ---------------------------------------------------------------------------
-- OPTIONAL hardening (commented out; enable only after testing the driver flows).
-- The four discovery functions below are callable with just the public anon key, i.e. without
-- logging in. That is acceptable for a marketplace, but you can require a login:
-- ---------------------------------------------------------------------------
-- REVOKE EXECUTE ON FUNCTION public.list_nearby_garages(double precision, double precision, double precision) FROM PUBLIC, anon;
-- REVOKE EXECUTE ON FUNCTION public.list_top_garages() FROM PUBLIC, anon;
-- REVOKE EXECUTE ON FUNCTION public.search_nearby_garages_with_part(text, double precision, double precision, double precision) FROM PUBLIC, anon;
-- REVOKE EXECUTE ON FUNCTION public.search_part_name_suggestions(text, integer) FROM PUBLIC, anon;
-- GRANT  EXECUTE ON FUNCTION public.list_nearby_garages(double precision, double precision, double precision) TO authenticated;
-- GRANT  EXECUTE ON FUNCTION public.list_top_garages() TO authenticated;
-- GRANT  EXECUTE ON FUNCTION public.search_nearby_garages_with_part(text, double precision, double precision, double precision) TO authenticated;
-- GRANT  EXECUTE ON FUNCTION public.search_part_name_suggestions(text, integer) TO authenticated;

-- OPTIONAL cleanup of redundant objects (the app's search queries use part_name_normalized):
-- DROP INDEX IF EXISTS public.idx_inventory_part_name_trgm;
-- ALTER TABLE public.referrals   DROP CONSTRAINT IF EXISTS referrals_referred_id_unique;
-- ALTER TABLE public.promo_codes DROP CONSTRAINT IF EXISTS uq_promo_code;

-- ---------------------------------------------------------------------------
-- VERIFICATION (run each block separately in the SQL Editor; everything is rolled back).
-- Replace the UUID with the owner's user id. Run block A BEFORE this migration to see the
-- hole (it returns a row), and again AFTER to see it closed (it raises protected_column).
-- ---------------------------------------------------------------------------
-- A. A garage owner tries to verify their own garage and grant themselves a plan:
--
--   begin;
--   set local role authenticated;
--   select set_config('request.jwt.claims',
--     '{"sub":"<OWNER-USER-UUID>","role":"authenticated"}', true);
--   update public.garages
--      set is_verified = true, subscription_status = 'active',
--          subscription_expiry = now() + interval '10 years'
--    where owner_id = '<OWNER-USER-UUID>'
--    returning id, is_verified, subscription_status, subscription_expiry;
--   rollback;
--
-- B. Normal edits still work (shop name), and the server-side purchase flow is unaffected:
--
--   begin;
--   set local role authenticated;
--   select set_config('request.jwt.claims',
--     '{"sub":"<OWNER-USER-UUID>","role":"authenticated"}', true);
--   update public.garages set shop_name = shop_name where owner_id = '<OWNER-USER-UUID>';
--   rollback;
