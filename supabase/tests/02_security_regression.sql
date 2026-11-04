-- 02_security_regression.sql
-- Database security regression tests. Each block acts as a specific user through the same
-- API role Supabase uses (authenticated / anon) and asserts the outcome. Any surprise raises
-- an exception, which makes psql exit non-zero and fails the CI job.
--
-- Everything runs inside one transaction that is rolled back at the end.

BEGIN;

-- Helpers (live only for this session)
CREATE FUNCTION pg_temp.as_user(p_uid uuid) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  PERFORM set_config('request.jwt.claims',
    json_build_object('sub', p_uid, 'role', 'authenticated')::text, true);
  EXECUTE 'SET LOCAL ROLE authenticated';
END $$;

CREATE FUNCTION pg_temp.as_anon() RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  PERFORM set_config('request.jwt.claims', '', true);
  EXECUTE 'SET LOCAL ROLE anon';
END $$;

CREATE FUNCTION pg_temp.back_to_admin() RETURNS void LANGUAGE plpgsql AS $$
BEGIN EXECUTE 'RESET ROLE'; END $$;

CREATE FUNCTION pg_temp.assert(p_ok boolean, p_msg text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF p_ok IS NOT TRUE THEN RAISE EXCEPTION 'TEST FAILED: %', p_msg; END IF;
  RAISE NOTICE 'PASS: %', p_msg;
END $$;

-- ===========================================================================
-- Attacks that MUST be blocked
-- ===========================================================================

DO $$ BEGIN
  PERFORM pg_temp.as_user('11111111-1111-1111-1111-111111111111');
  BEGIN
    UPDATE public.garages
       SET is_verified = true, subscription_status = 'active',
           subscription_expiry = now() + interval '10 years'
     WHERE owner_id = '11111111-1111-1111-1111-111111111111';
    RAISE EXCEPTION 'TEST FAILED: owner self-verified and granted themselves a plan';
  EXCEPTION WHEN OTHERS THEN
    IF SQLERRM NOT LIKE 'protected_column%' THEN RAISE; END IF;
  END;
  PERFORM pg_temp.back_to_admin();
  PERFORM pg_temp.assert(true, 'owner cannot self-verify or grant a plan on garages');
END $$;

DO $$ BEGIN
  PERFORM pg_temp.as_user('11111111-1111-1111-1111-111111111111');
  BEGIN
    UPDATE public.garages SET current_plan_id = 'aaaaaaaa-0000-0000-0000-000000000001'
     WHERE owner_id = '11111111-1111-1111-1111-111111111111';
    RAISE EXCEPTION 'TEST FAILED: owner set current_plan_id directly';
  EXCEPTION WHEN OTHERS THEN
    IF SQLERRM NOT LIKE 'protected_column%' THEN RAISE; END IF;
  END;
  PERFORM pg_temp.back_to_admin();
  PERFORM pg_temp.assert(true, 'owner cannot set current_plan_id directly');
END $$;

DO $$ BEGIN
  PERFORM pg_temp.as_user('33333333-3333-3333-3333-333333333333');
  BEGIN
    INSERT INTO public.garages(owner_id, shop_name, latitude, longitude, is_verified)
    VALUES ('33333333-3333-3333-3333-333333333333', 'Pre-verified', 1, 1, true);
    RAISE EXCEPTION 'TEST FAILED: garage inserted already verified';
  EXCEPTION WHEN OTHERS THEN
    IF SQLERRM NOT LIKE 'protected_column%' THEN RAISE; END IF;
  END;
  PERFORM pg_temp.back_to_admin();
  PERFORM pg_temp.assert(true, 'a new garage cannot be created already verified');
END $$;

DO $$ BEGIN
  PERFORM pg_temp.as_user('22222222-2222-2222-2222-222222222222');
  BEGIN
    UPDATE public.profiles
       SET current_plan_id = 'aaaaaaaa-0000-0000-0000-000000000002',
           subscription_expiry = now() + interval '5 years'
     WHERE id = '22222222-2222-2222-2222-222222222222';
    RAISE EXCEPTION 'TEST FAILED: driver granted themselves a plan';
  EXCEPTION WHEN OTHERS THEN
    IF SQLERRM NOT LIKE 'protected_column%' THEN RAISE; END IF;
  END;
  PERFORM pg_temp.back_to_admin();
  PERFORM pg_temp.assert(true, 'driver cannot grant themselves a plan on profiles');
END $$;

DO $$ BEGIN
  PERFORM pg_temp.as_user('44444444-4444-4444-4444-444444444444');
  BEGIN
    INSERT INTO public.profiles(id, phone_number, role, current_plan_id)
    VALUES ('44444444-4444-4444-4444-444444444444', '+911000000004', 'driver',
            'aaaaaaaa-0000-0000-0000-000000000002');
    RAISE EXCEPTION 'TEST FAILED: profile created with a plan';
  EXCEPTION WHEN OTHERS THEN
    IF SQLERRM NOT LIKE 'protected_column%' THEN RAISE; END IF;
  END;
  PERFORM pg_temp.back_to_admin();
  PERFORM pg_temp.assert(true, 'a new profile cannot be created with a plan');
END $$;

DO $$ BEGIN
  PERFORM pg_temp.as_user('22222222-2222-2222-2222-222222222222');
  BEGIN
    UPDATE public.profiles SET referral_code = 'HACKED'
     WHERE id = '22222222-2222-2222-2222-222222222222';
    RAISE EXCEPTION 'TEST FAILED: user set their own referral code';
  EXCEPTION WHEN OTHERS THEN
    IF SQLERRM NOT LIKE 'protected_column%' THEN RAISE; END IF;
  END;
  PERFORM pg_temp.back_to_admin();
  PERFORM pg_temp.assert(true, 'user cannot set their own referral_code directly');
END $$;

DO $$ BEGIN
  PERFORM pg_temp.as_user('11111111-1111-1111-1111-111111111111');
  BEGIN
    PERFORM public.check_subscription_expiring();
    RAISE EXCEPTION 'TEST FAILED: client called the cron function';
  EXCEPTION WHEN OTHERS THEN
    IF SQLERRM NOT LIKE 'permission denied%' THEN RAISE; END IF;
  END;
  PERFORM pg_temp.back_to_admin();
  PERFORM pg_temp.assert(true, 'clients cannot call check_subscription_expiring()');
END $$;

DO $$
DECLARE n int;
BEGIN
  -- Another user must not see or change a garage they do not own.
  PERFORM pg_temp.as_user('22222222-2222-2222-2222-222222222222');
  UPDATE public.garages SET shop_name = 'Hijacked' WHERE id = 'bbbbbbbb-0000-0000-0000-000000000001';
  GET DIAGNOSTICS n = ROW_COUNT;
  PERFORM pg_temp.back_to_admin();
  PERFORM pg_temp.assert(n = 0, 'another user cannot edit someone else''s garage');
END $$;

-- ===========================================================================
-- Normal flows that MUST keep working
-- ===========================================================================

DO $$
DECLARE n int;
BEGIN
  PERFORM pg_temp.as_user('11111111-1111-1111-1111-111111111111');
  UPDATE public.garages SET shop_name = 'Renamed Garage' WHERE owner_id = '11111111-1111-1111-1111-111111111111';
  GET DIAGNOSTICS n = ROW_COUNT;
  PERFORM pg_temp.back_to_admin();
  PERFORM pg_temp.assert(n = 1, 'owner can edit shop details');
END $$;

DO $$
DECLARE v_verified boolean; v_status text;
BEGIN
  PERFORM pg_temp.as_user('33333333-3333-3333-3333-333333333333');
  INSERT INTO public.garages(owner_id, shop_name, latitude, longitude)
  VALUES ('33333333-3333-3333-3333-333333333333', 'Garage Three', 18.5, 73.8);
  PERFORM pg_temp.back_to_admin();
  SELECT is_verified, subscription_status::text INTO v_verified, v_status
    FROM public.garages WHERE owner_id = '33333333-3333-3333-3333-333333333333';
  PERFORM pg_temp.assert(v_verified = false AND v_status = 'trial',
    'garage setup (owner_id, shop_name, lat, lng) works and starts unverified on trial');
END $$;

DO $$
DECLARE n int;
BEGIN
  PERFORM pg_temp.as_user('44444444-4444-4444-4444-444444444444');
  INSERT INTO public.profiles(id, phone_number, role)
  VALUES ('44444444-4444-4444-4444-444444444444', '+911000000004', 'driver');
  PERFORM pg_temp.back_to_admin();
  SELECT count(*) INTO n FROM public.profiles WHERE id = '44444444-4444-4444-4444-444444444444';
  PERFORM pg_temp.assert(n = 1, 'profile creation (id, phone_number, role) works');
END $$;

DO $$
DECLARE n int;
BEGIN
  PERFORM pg_temp.as_user('22222222-2222-2222-2222-222222222222');
  UPDATE public.profiles SET full_name = 'Test Driver' WHERE id = '22222222-2222-2222-2222-222222222222';
  GET DIAGNOSTICS n = ROW_COUNT;
  PERFORM pg_temp.back_to_admin();
  PERFORM pg_temp.assert(n = 1, 'user can edit non-protected profile fields');
END $$;

DO $$
DECLARE r jsonb; v_status text; v_plan uuid;
BEGIN
  PERFORM pg_temp.as_user('11111111-1111-1111-1111-111111111111');
  r := public.purchase_subscription_manual('bbbbbbbb-0000-0000-0000-000000000001',
        'aaaaaaaa-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111');
  PERFORM pg_temp.back_to_admin();
  SELECT subscription_status::text, current_plan_id INTO v_status, v_plan
    FROM public.garages WHERE id = 'bbbbbbbb-0000-0000-0000-000000000001';
  PERFORM pg_temp.assert((r->>'success')::boolean AND v_status = 'active' AND v_plan IS NOT NULL,
    'garage purchase RPC still activates the plan (server-side update passes the guard)');
END $$;

DO $$
DECLARE r jsonb;
BEGIN
  PERFORM pg_temp.as_user('22222222-2222-2222-2222-222222222222');
  r := public.purchase_driver_subscription_manual('aaaaaaaa-0000-0000-0000-000000000002',
        '22222222-2222-2222-2222-222222222222');
  PERFORM pg_temp.back_to_admin();
  PERFORM pg_temp.assert((r->>'success')::boolean, 'driver purchase RPC still works');
END $$;

DO $$
DECLARE r jsonb;
BEGIN
  PERFORM pg_temp.as_user('11111111-1111-1111-1111-111111111111');
  r := public.purchase_subscription_manual('bbbbbbbb-0000-0000-0000-000000000001',
        'aaaaaaaa-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222');
  PERFORM pg_temp.back_to_admin();
  PERFORM pg_temp.assert(NOT (r->>'success')::boolean AND r->>'reason' = 'not_authorized',
    'purchase RPC refuses a mismatched user id');
END $$;

DO $$
DECLARE v_code text; r jsonb; v_before timestamptz; v_after timestamptz;
BEGIN
  SELECT subscription_expiry INTO v_before FROM public.garages WHERE id = 'bbbbbbbb-0000-0000-0000-000000000001';
  PERFORM pg_temp.as_user('11111111-1111-1111-1111-111111111111');
  v_code := public.get_or_create_referral_code('11111111-1111-1111-1111-111111111111');
  PERFORM pg_temp.as_user('22222222-2222-2222-2222-222222222222');
  r := public.redeem_referral_code(v_code, '22222222-2222-2222-2222-222222222222');
  PERFORM pg_temp.back_to_admin();
  SELECT subscription_expiry INTO v_after FROM public.garages WHERE id = 'bbbbbbbb-0000-0000-0000-000000000001';
  PERFORM pg_temp.assert((r->>'success')::boolean AND v_after > v_before,
    'referral flow works and rewards the referrer with extra days');
END $$;

DO $$
DECLARE r jsonb;
BEGIN
  INSERT INTO public.promo_codes(code, code_type, reward_duration_days, max_global_usage)
  VALUES ('TRIAL30', 'garage_trial', 30, 10);
  PERFORM pg_temp.as_user('11111111-1111-1111-1111-111111111111');
  r := public.redeem_promo_code('trial30', '11111111-1111-1111-1111-111111111111');
  PERFORM pg_temp.back_to_admin();
  PERFORM pg_temp.assert((r->>'success')::boolean, 'promo code redemption works');
END $$;

DO $$
DECLARE r1 jsonb; r2 jsonb;
BEGIN
  PERFORM pg_temp.as_user('11111111-1111-1111-1111-111111111111');
  r1 := public.check_and_log_invoice_scan('bbbbbbbb-0000-0000-0000-000000000001');
  PERFORM pg_temp.as_user('22222222-2222-2222-2222-222222222222');
  r2 := public.check_and_log_invoice_scan('bbbbbbbb-0000-0000-0000-000000000001');
  PERFORM pg_temp.back_to_admin();
  PERFORM pg_temp.assert((r1->>'success')::boolean AND r2->>'reason' = 'not_authorized',
    'scan quota check: the owner is allowed, anyone else is refused');
END $$;

DO $$
DECLARE n int;
BEGIN
  -- Dropping below the alert level notifies the owner exactly once.
  PERFORM pg_temp.as_user('11111111-1111-1111-1111-111111111111');
  UPDATE public.inventory_items SET quantity = 4 WHERE id = 'cccccccc-0000-0000-0000-000000000001';
  UPDATE public.inventory_items SET quantity = 3 WHERE id = 'cccccccc-0000-0000-0000-000000000001';
  PERFORM pg_temp.back_to_admin();
  SELECT count(*) INTO n FROM public.notifications
   WHERE related_item_id = 'cccccccc-0000-0000-0000-000000000001'
     AND user_id = '11111111-1111-1111-1111-111111111111' AND type = 'low_inventory';
  PERFORM pg_temp.assert(n = 1, 'low-stock alert is sent to the owner exactly once');

  -- A muted part does not notify again.
  UPDATE public.inventory_items SET quantity = 9 WHERE id = 'cccccccc-0000-0000-0000-000000000001';
  INSERT INTO public.notification_mutes(garage_id, inventory_item_id, muted_until)
  VALUES ('bbbbbbbb-0000-0000-0000-000000000001', 'cccccccc-0000-0000-0000-000000000001', now() + interval '1 day');
  UPDATE public.inventory_items SET quantity = 2 WHERE id = 'cccccccc-0000-0000-0000-000000000001';
  SELECT count(*) INTO n FROM public.notifications WHERE related_item_id = 'cccccccc-0000-0000-0000-000000000001';
  PERFORM pg_temp.assert(n = 1, 'a muted part does not produce another low-stock alert');
END $$;

DO $$
DECLARE n int;
BEGIN
  PERFORM pg_temp.as_anon();
  PERFORM public.log_search_demand('spark plug', 18.5, 73.8);
  PERFORM pg_temp.back_to_admin();
  SELECT count(*) INTO n FROM public.search_demand_log;
  PERFORM pg_temp.assert(n = 0, 'anonymous callers cannot write to the search demand log');

  PERFORM pg_temp.as_user('22222222-2222-2222-2222-222222222222');
  PERFORM public.log_search_demand('spark plug', 18.5, 73.8);
  PERFORM public.log_search_demand(repeat('x', 500), 18.5, 73.8);
  PERFORM pg_temp.back_to_admin();
  SELECT count(*) INTO n FROM public.search_demand_log;
  PERFORM pg_temp.assert(n = 1, 'logged-in search is recorded; oversized input is ignored');
END $$;

DO $$ BEGIN
  PERFORM public.check_subscription_expiring();
  PERFORM pg_temp.assert(true, 'the cron function still runs for the database owner');
END $$;

ROLLBACK;
