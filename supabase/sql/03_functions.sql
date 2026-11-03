-- 03_functions.sql
-- Application functions exactly as deployed (exported with pg_get_functiondef).
-- Excludes the functions that belong to the pg_trgm extension.
--
-- Conventions used throughout:
--   * Every SECURITY DEFINER function pins search_path to 'public'.
--   * Functions that accept a user id or garage id never trust it: the id is checked against
--     auth.uid() (the identity in the caller's verified JWT) before anything happens.
--   * Quota checks and their log inserts happen in one function, so they cannot drift apart.

-- ---------------------------------------------------------------------------
-- Usage limits (called by the Edge Functions and the app)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.check_and_log_ai_message(p_user_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_limit integer;
  v_has_plan boolean;
  v_today_count integer;
  v_owned_garage_id uuid;
begin
  if p_user_id is distinct from auth.uid() then
    return jsonb_build_object('success', false, 'reason', 'not_authorized');
  end if;

  select id into v_owned_garage_id from garages where owner_id = auth.uid() limit 1;

  if v_owned_garage_id is not null then
    select sp.max_ai_messages, (g.current_plan_id is not null) into v_limit, v_has_plan
    from garages g
    left join subscription_plans sp on sp.id = g.current_plan_id
    where g.id = v_owned_garage_id;
  else
    select sp.max_ai_messages, (p.current_plan_id is not null)
    into v_limit, v_has_plan
    from profiles p
    left join subscription_plans sp on sp.id = p.current_plan_id
    where p.id = auth.uid();
  end if;

  if not v_has_plan then
    v_limit := 5;
  end if;

  if v_limit is not null then
    select count(*) into v_today_count
    from ai_assistant_usage_log
    where user_id = auth.uid()
      and created_at >= date_trunc('day', now());

    if v_today_count >= v_limit then
      return jsonb_build_object('success', false, 'reason', 'ai_limit_reached', 'limit', v_limit);
    end if;
  end if;

  insert into ai_assistant_usage_log (user_id) values (auth.uid());
  return jsonb_build_object('success', true, 'limit', v_limit);
end;
$function$;

CREATE OR REPLACE FUNCTION public.check_and_log_invoice_scan(p_garage_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_limit integer;
  v_has_plan boolean;
  v_this_month_count integer;
  v_is_owner boolean;
begin
  select exists(
    select 1 from garages where id = p_garage_id and owner_id = auth.uid()
  ) into v_is_owner;

  if not v_is_owner then
    return jsonb_build_object('success', false, 'reason', 'not_authorized');
  end if;

  select sp.max_invoice_scans, (g.current_plan_id is not null) into v_limit, v_has_plan
  from garages g
  left join subscription_plans sp on sp.id = g.current_plan_id
  where g.id = p_garage_id;

  if not v_has_plan then
    v_limit := 5;
  end if;

  if v_limit is not null then
    select count(*) into v_this_month_count
    from invoice_scan_log
    where garage_id = p_garage_id
      and created_at >= date_trunc('month', now());

    if v_this_month_count >= v_limit then
      return jsonb_build_object('success', false, 'reason', 'scan_limit_reached', 'limit', v_limit);
    end if;
  end if;

  insert into invoice_scan_log (garage_id) values (p_garage_id);
  return jsonb_build_object('success', true, 'limit', v_limit);
end;
$function$;

CREATE OR REPLACE FUNCTION public.check_and_log_sale_scan(p_garage_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_limit integer;
  v_has_plan boolean;
  v_this_month_count integer;
  v_is_owner boolean;
begin
  select exists(
    select 1 from garages where id = p_garage_id and owner_id = auth.uid()
  ) into v_is_owner;

  if not v_is_owner then
    return jsonb_build_object('success', false, 'reason', 'not_authorized');
  end if;

  select sp.max_sale_scans, (g.current_plan_id is not null) into v_limit, v_has_plan
  from garages g
  left join subscription_plans sp on sp.id = g.current_plan_id
  where g.id = p_garage_id;

  if not v_has_plan then
    v_limit := 5;
  end if;

  if v_limit is not null then
    select count(*) into v_this_month_count
    from sale_scan_log
    where garage_id = p_garage_id
      and created_at >= date_trunc('month', now());

    if v_this_month_count >= v_limit then
      return jsonb_build_object('success', false, 'reason', 'scan_limit_reached', 'limit', v_limit);
    end if;
  end if;

  insert into sale_scan_log (garage_id) values (p_garage_id);
  return jsonb_build_object('success', true, 'limit', v_limit);
end;
$function$;

CREATE OR REPLACE FUNCTION public.check_and_log_search(p_user_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_plan_price numeric;
  v_today_count integer;
  v_limit integer := 10;
begin
  if p_user_id is distinct from auth.uid() then
    return jsonb_build_object('success', false, 'reason', 'not_authorized');
  end if;

  select sp.price_inr into v_plan_price
  from profiles p
  left join subscription_plans sp on sp.id = p.current_plan_id
  where p.id = auth.uid();

  if v_plan_price is not null and v_plan_price > 0 then
    insert into search_usage_log (user_id) values (auth.uid());
    return jsonb_build_object('success', true, 'unlimited', true);
  end if;

  select count(*) into v_today_count
  from search_usage_log
  where user_id = auth.uid()
    and created_at >= date_trunc('day', now());

  if v_today_count >= v_limit then
    return jsonb_build_object('success', false, 'reason', 'search_limit_reached', 'limit', v_limit);
  end if;

  insert into search_usage_log (user_id) values (auth.uid());
  return jsonb_build_object('success', true, 'unlimited', false, 'remaining', v_limit - v_today_count - 1);
end;
$function$;

CREATE OR REPLACE FUNCTION public.get_ai_message_usage(p_user_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_limit integer;
  v_has_plan boolean;
  v_used integer;
  v_owned_garage_id uuid;
begin
  if p_user_id is distinct from auth.uid() then
    return jsonb_build_object('limit', null, 'used', 0, 'unlimited', false);
  end if;

  -- Garage owners' subscription lives on garages.current_plan_id, not
  -- profiles.current_plan_id (which only ever gets set for driver purchases).
  select id into v_owned_garage_id from garages where owner_id = auth.uid() limit 1;

  if v_owned_garage_id is not null then
    select sp.max_ai_messages, (g.current_plan_id is not null) into v_limit, v_has_plan
    from garages g
    left join subscription_plans sp on sp.id = g.current_plan_id
    where g.id = v_owned_garage_id;
  else
    select sp.max_ai_messages, (p.current_plan_id is not null)
    into v_limit, v_has_plan
    from profiles p
    left join subscription_plans sp on sp.id = p.current_plan_id
    where p.id = auth.uid();
  end if;

  if not v_has_plan then
    v_limit := 5;
  end if;

  select count(*) into v_used
  from ai_assistant_usage_log
  where user_id = auth.uid()
    and created_at >= date_trunc('day', now());

  return jsonb_build_object('limit', v_limit, 'used', v_used, 'unlimited', v_limit is null);
end;
$function$;

CREATE OR REPLACE FUNCTION public.get_invoice_scan_usage(p_garage_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_limit integer;
  v_has_plan boolean;
  v_used integer;
  v_is_owner boolean;
begin
  select exists(
    select 1 from garages where id = p_garage_id and owner_id = auth.uid()
  ) into v_is_owner;

  if not v_is_owner then
    return jsonb_build_object('limit', null, 'used', 0, 'unlimited', false);
  end if;

  select sp.max_invoice_scans, (g.current_plan_id is not null) into v_limit, v_has_plan
  from garages g
  left join subscription_plans sp on sp.id = g.current_plan_id
  where g.id = p_garage_id;

  if not v_has_plan then
    v_limit := 5;
  end if;

  select count(*) into v_used
  from invoice_scan_log
  where garage_id = p_garage_id
    and created_at >= date_trunc('month', now());

  return jsonb_build_object('limit', v_limit, 'used', v_used, 'unlimited', v_limit is null);
end;
$function$;

CREATE OR REPLACE FUNCTION public.get_sale_scan_usage(p_garage_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_limit integer;
  v_has_plan boolean;
  v_used integer;
  v_is_owner boolean;
begin
  select exists(
    select 1 from garages where id = p_garage_id and owner_id = auth.uid()
  ) into v_is_owner;

  if not v_is_owner then
    return jsonb_build_object('limit', null, 'used', 0, 'unlimited', false);
  end if;

  select sp.max_sale_scans, (g.current_plan_id is not null) into v_limit, v_has_plan
  from garages g
  left join subscription_plans sp on sp.id = g.current_plan_id
  where g.id = p_garage_id;

  if not v_has_plan then
    v_limit := 5;
  end if;

  select count(*) into v_used
  from sale_scan_log
  where garage_id = p_garage_id
    and created_at >= date_trunc('month', now());

  return jsonb_build_object('limit', v_limit, 'used', v_used, 'unlimited', v_limit is null);
end;
$function$;

-- ---------------------------------------------------------------------------
-- Plan limits enforced inside the database (trigger functions)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.check_stock_item_limit()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_limit integer;
  v_current_count integer;
begin
  select sp.max_stock_items into v_limit
  from garages g
  left join subscription_plans sp on sp.id = g.current_plan_id
  where g.id = NEW.garage_id;

  -- No plan at all -> Free tier default of 100
  if v_limit is null and not exists (
    select 1 from garages g where g.id = NEW.garage_id and g.current_plan_id is not null
  ) then
    v_limit := 100;
  end if;

  -- v_limit still null here means an actual unlimited plan (Enterprise) -> allow
  if v_limit is not null then
    select count(*) into v_current_count from inventory_items where garage_id = NEW.garage_id;
    if v_current_count >= v_limit then
      raise exception 'stock_limit_reached: % items allowed on your current plan', v_limit;
    end if;
  end if;

  return NEW;
end;
$function$;

CREATE OR REPLACE FUNCTION public.log_inventory_movement()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if TG_OP = 'UPDATE' and OLD.quantity is distinct from NEW.quantity then
    insert into inventory_movement_log (garage_id, inventory_item_id, part_name, quantity_before, quantity_after)
    values (NEW.garage_id, NEW.id, NEW.part_name, OLD.quantity, NEW.quantity);
  elsif TG_OP = 'INSERT' then
    insert into inventory_movement_log (garage_id, inventory_item_id, part_name, quantity_before, quantity_after)
    values (NEW.garage_id, NEW.id, NEW.part_name, 0, NEW.quantity);
  end if;
  return NEW;
end;
$function$;

-- ---------------------------------------------------------------------------
-- Notifications
-- ---------------------------------------------------------------------------

-- Fires when an item's stock drops below its alert level. Notifies the garage owner,
-- respects the per-part mute, and never blocks the stock update if the notification fails.
CREATE OR REPLACE FUNCTION public.notify_low_inventory()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_owner_id uuid;
begin
  -- Not low: nothing to do.
  if new.quantity >= new.low_stock_threshold then
    return new;
  end if;

  -- Was already low before this update: don't notify again.
  if tg_op = 'UPDATE' and old.quantity < new.low_stock_threshold then
    return new;
  end if;

  -- A notification problem must never block a stock update (e.g. a sale-bill deduction).
  begin
    if exists (
      select 1 from notification_mutes m
      where m.garage_id = new.garage_id
        and m.inventory_item_id = new.id
        and m.muted_until > now()
    ) then
      return new;
    end if;

    select owner_id into v_owner_id from garages where id = new.garage_id;
    if v_owner_id is null then
      return new;
    end if;

    insert into notifications (id, user_id, garage_id, type, title, body, related_item_id, is_read, created_at)
    values (
      gen_random_uuid(), v_owner_id, new.garage_id, 'low_inventory',
      'Low stock: ' || new.part_name,
      new.part_name || ' is down to ' || new.quantity || ' (alert level ' || new.low_stock_threshold || ').',
      new.id, false, now()
    );
  exception when others then
    raise warning 'notify_low_inventory failed: %', sqlerrm;
  end;

  return new;
end;
$function$;

-- Scheduled (pg_cron). Notifies owners whose subscription expires within 3 days, once per 3 days.
CREATE OR REPLACE FUNCTION public.check_subscription_expiring()
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  insert into notifications (user_id, garage_id, type, title, body)
  select g.owner_id, g.id, 'subscription_expiring',
         'Subscription expiring soon',
         'Your subscription expires on ' || to_char(g.subscription_expiry, 'DD Mon YYYY') || '. Renew to avoid interruption.'
  from garages g
  where g.subscription_status = 'active'
    and g.subscription_expiry between now() and now() + interval '3 days'
    and not exists (
      select 1 from notifications n
      where n.garage_id = g.id and n.type = 'subscription_expiring'
        and n.created_at > now() - interval '3 days'
    );
end;
$function$;

CREATE OR REPLACE FUNCTION public.register_device_token(p_token text, p_platform text DEFAULT 'android'::text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if auth.uid() is null then
    raise exception 'not authenticated';
  end if;
  if p_token is null or length(p_token) < 20 then
    raise exception 'invalid token';
  end if;

  insert into public.device_tokens (token, user_id, platform, updated_at)
  values (p_token, auth.uid(), coalesce(p_platform, 'android'), now())
  on conflict (token) do update
    set user_id    = excluded.user_id,
        platform   = excluded.platform,
        updated_at = now();
end;
$function$;

CREATE OR REPLACE FUNCTION public.unregister_device_token(p_token text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if auth.uid() is null then
    raise exception 'not authenticated';
  end if;
  delete from public.device_tokens
  where token = p_token and user_id = auth.uid();
end;
$function$;

-- ---------------------------------------------------------------------------
-- Subscriptions, promo codes, referrals
-- NOTE: the *_manual purchase functions simulate billing (no payment gateway yet).
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.purchase_driver_subscription_manual(p_plan_id uuid, p_user_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_plan subscription_plans%rowtype;
  v_current_expiry timestamptz;
  v_new_expiry timestamptz;
begin
  -- The caller must be who they claim to be
  if p_user_id is distinct from auth.uid() then
    return jsonb_build_object('success', false, 'reason', 'not_authorized');
  end if;

  select * into v_plan from subscription_plans where id = p_plan_id and is_active = true and target_role = 'driver';
  if not found then
    return jsonb_build_object('success', false, 'reason', 'invalid_plan');
  end if;

  select subscription_expiry into v_current_expiry from profiles where id = p_user_id;

  v_new_expiry := greatest(coalesce(v_current_expiry, now()), now()) + (v_plan.duration_days || ' days')::interval;

  insert into payments (id, user_id, garage_id, amount, currency, gateway, gateway_transaction_id, status, created_at)
  values (gen_random_uuid(), auth.uid(), null, v_plan.price_inr, 'INR', 'manual', null, 'success', now());

  update profiles
  set subscription_status = 'active',
      subscription_expiry = v_new_expiry,
      current_plan_id = p_plan_id
  where id = auth.uid();

  return jsonb_build_object('success', true, 'new_expiry', v_new_expiry, 'plan_name', v_plan.name);
end;
$function$;

CREATE OR REPLACE FUNCTION public.purchase_subscription_manual(p_garage_id uuid, p_plan_id uuid, p_user_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_plan subscription_plans%rowtype;
  v_current_expiry timestamptz;
  v_new_expiry timestamptz;
  v_owner_id uuid;
begin
  -- The caller must be who they claim to be
  if p_user_id is distinct from auth.uid() then
    return jsonb_build_object('success', false, 'reason', 'not_authorized');
  end if;

  -- The caller must actually own this garage
  select owner_id into v_owner_id from garages where id = p_garage_id;
  if v_owner_id is null or v_owner_id <> auth.uid() then
    return jsonb_build_object('success', false, 'reason', 'not_authorized');
  end if;

  select * into v_plan from subscription_plans where id = p_plan_id and is_active = true;
  if not found then
    return jsonb_build_object('success', false, 'reason', 'invalid_plan');
  end if;

  select subscription_expiry into v_current_expiry from garages where id = p_garage_id;

  v_new_expiry := greatest(coalesce(v_current_expiry, now()), now()) + (v_plan.duration_days || ' days')::interval;

  insert into payments (id, user_id, garage_id, amount, currency, gateway, gateway_transaction_id, status, created_at)
  values (gen_random_uuid(), auth.uid(), p_garage_id, v_plan.price_inr, 'INR', 'manual', null, 'success', now());

  update garages
  set subscription_status = 'active',
      subscription_expiry = v_new_expiry,
      current_plan_id = p_plan_id
  where id = p_garage_id;

  return jsonb_build_object('success', true, 'new_expiry', v_new_expiry, 'plan_name', v_plan.name);
end;
$function$;

CREATE OR REPLACE FUNCTION public.redeem_promo_code(p_code text, p_user_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_promo record;
  v_already_redeemed boolean;
  v_trial_used boolean;
begin
  if p_user_id is distinct from auth.uid() then
    return jsonb_build_object('success', false, 'reason', 'not_authorized');
  end if;

  select * into v_promo
  from promo_codes
  where code = upper(trim(p_code)) and is_active = true
  for update;

  if not found then
    return jsonb_build_object('success', false, 'reason', 'invalid_code');
  end if;

  if v_promo.expiry_date is not null and v_promo.expiry_date < now() then
    return jsonb_build_object('success', false, 'reason', 'expired');
  end if;

  if v_promo.current_usage_count >= v_promo.max_global_usage then
    return jsonb_build_object('success', false, 'reason', 'max_usage_reached');
  end if;

  select exists(
    select 1 from promo_code_redemptions
    where promo_code_id = v_promo.id and user_id = auth.uid()
  ) into v_already_redeemed;

  if v_already_redeemed then
    return jsonb_build_object('success', false, 'reason', 'already_redeemed_by_user');
  end if;

  if v_promo.code_type = 'garage_trial' then
    select exists(
      select 1 from promo_code_redemptions
      where user_id = auth.uid() and code_type = 'garage_trial'
    ) into v_trial_used;

    if v_trial_used then
      return jsonb_build_object('success', false, 'reason', 'trial_already_used');
    end if;
  end if;

  insert into promo_code_redemptions (promo_code_id, user_id, code_type)
  values (v_promo.id, auth.uid(), v_promo.code_type);

  update promo_codes
  set current_usage_count = current_usage_count + 1
  where id = v_promo.id;

  if v_promo.code_type = 'garage_trial' then
    update garages
    set subscription_status = 'trial',
        subscription_expiry = greatest(coalesce(subscription_expiry, now()), now())
                               + make_interval(days => coalesce(v_promo.reward_duration_days, 30))
    where owner_id = auth.uid();
  end if;

  return jsonb_build_object(
    'success', true,
    'code_type', v_promo.code_type,
    'reward_duration_days', v_promo.reward_duration_days
  );
end;
$function$;

CREATE OR REPLACE FUNCTION public.get_or_create_referral_code(p_user_id uuid)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_existing text;
  v_new_code text;
begin
  if p_user_id is distinct from auth.uid() then
    return null;
  end if;

  select referral_code into v_existing from profiles where id = auth.uid();
  if v_existing is not null then
    return v_existing;
  end if;

  loop
    v_new_code := upper(substr(md5(random()::text), 1, 6));
    begin
      update profiles set referral_code = v_new_code where id = auth.uid();
      exit;
    exception when unique_violation then
      -- extremely rare collision, loop retries with a new code
    end;
  end loop;

  return v_new_code;
end;
$function$;

CREATE OR REPLACE FUNCTION public.redeem_referral_code(p_code text, p_referred_user_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_referrer_id uuid;
  v_referrer_role text;
  v_reward_days integer := 7;
begin
  if p_referred_user_id is distinct from auth.uid() then
    return jsonb_build_object('success', false, 'reason', 'not_authorized');
  end if;

  select id, role::text into v_referrer_id, v_referrer_role
  from profiles where referral_code = upper(trim(p_code));

  if v_referrer_id is null then
    return jsonb_build_object('success', false, 'reason', 'invalid_code');
  end if;

  if v_referrer_id = auth.uid() then
    return jsonb_build_object('success', false, 'reason', 'self_referral');
  end if;

  if exists (select 1 from referrals where referred_id = auth.uid()) then
    return jsonb_build_object('success', false, 'reason', 'already_referred');
  end if;

  insert into referrals (id, referrer_id, referred_id, status, created_at)
  values (gen_random_uuid(), v_referrer_id, auth.uid(), 'completed', now());

  if v_referrer_role = 'garage_owner' then
    update garages
    set subscription_expiry = greatest(coalesce(subscription_expiry, now()), now()) + (v_reward_days || ' days')::interval
    where owner_id = v_referrer_id;
  else
    update profiles
    set subscription_expiry = greatest(coalesce(subscription_expiry, now()), now()) + (v_reward_days || ' days')::interval,
        subscription_status = 'active'
    where id = v_referrer_id;
  end if;

  return jsonb_build_object('success', true, 'reward_days', v_reward_days);
end;
$function$;

-- ---------------------------------------------------------------------------
-- Search and discovery (drivers)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.search_nearby_garages_with_part(p_part_query text, p_user_lat double precision, p_user_lng double precision, p_radius_km double precision DEFAULT 15)
 RETURNS TABLE(garage_id uuid, shop_name text, address text, phone_number text, latitude double precision, longitude double precision, matched_part_name text, quantity integer, distance_km double precision)
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select garage_id, shop_name, address, phone_number, latitude, longitude, matched_part_name, quantity, distance_km
  from (
    select distinct on (sub.garage_id)
      sub.garage_id,
      sub.shop_name,
      sub.address,
      sub.phone_number,
      sub.latitude,
      sub.longitude,
      sub.matched_part_name,
      sub.quantity,
      sub.distance_km
    from (
      select
        g.id as garage_id,
        g.shop_name,
        g.address,
        p.phone_number,
        g.latitude,
        g.longitude,
        i.part_name as matched_part_name,
        i.quantity,
        similarity(i.part_name_normalized, lower(trim(p_part_query))) as sim,
        6371 * acos(
          least(1.0, greatest(-1.0,
            cos(radians(p_user_lat)) * cos(radians(g.latitude)) *
            cos(radians(g.longitude) - radians(p_user_lng)) +
            sin(radians(p_user_lat)) * sin(radians(g.latitude))
          ))
        ) as distance_km
      from garages g
      join profiles p on p.id = g.owner_id
      join inventory_items i on i.garage_id = g.id
      where g.is_verified = true
        and i.quantity > 0
        and similarity(i.part_name_normalized, lower(trim(p_part_query))) > 0.3
    ) sub
    where sub.distance_km <= p_radius_km
    order by sub.garage_id, sub.sim desc, sub.distance_km asc
  ) deduped
  order by distance_km asc;
$function$;

CREATE OR REPLACE FUNCTION public.search_part_name_suggestions(p_query text, p_limit integer DEFAULT 8)
 RETURNS TABLE(part_name text)
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select distinct on (part_name_normalized) part_name
  from inventory_items
  where similarity(part_name_normalized, lower(trim(p_query))) > 0.2
    and length(trim(p_query)) >= 2
  order by part_name_normalized, similarity(part_name_normalized, lower(trim(p_query))) desc
  limit p_limit;
$function$;

CREATE OR REPLACE FUNCTION public.list_nearby_garages(p_user_lat double precision, p_user_lng double precision, p_radius_km double precision DEFAULT 50)
 RETURNS TABLE(garage_id uuid, shop_name text, address text, phone_number text, latitude double precision, longitude double precision, is_verified boolean, distance_km double precision)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  return query
  select * from (
    select
      g.id as garage_id,
      g.shop_name,
      g.address,
      p.phone_number,
      g.latitude,
      g.longitude,
      g.is_verified,
      (
        6371 * acos(
          cos(radians(p_user_lat)) * cos(radians(g.latitude)) *
          cos(radians(g.longitude) - radians(p_user_lng)) +
          sin(radians(p_user_lat)) * sin(radians(g.latitude))
        )
      ) as distance_km
    from garages g
    join profiles p on p.id = g.owner_id
    where g.subscription_status in ('trial', 'active')
  ) sub
  where sub.distance_km <= p_radius_km
  order by sub.distance_km asc;
end;
$function$;

CREATE OR REPLACE FUNCTION public.list_top_garages()
 RETURNS TABLE(garage_id uuid, shop_name text, address text, phone_number text, latitude double precision, longitude double precision, is_verified boolean, plan_name text, plan_price_inr numeric)
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select
    g.id,
    g.shop_name,
    g.address,
    p.phone_number,
    g.latitude,
    g.longitude,
    g.is_verified,
    sp.name,
    coalesce(sp.price_inr, 0)
  from garages g
  join profiles p on p.id = g.owner_id
  left join subscription_plans sp on sp.id = g.current_plan_id
  where g.subscription_status in ('trial', 'active')
  order by g.is_verified desc, coalesce(sp.price_inr, 0) desc, g.shop_name asc;
$function$;

CREATE OR REPLACE FUNCTION public.log_search_demand(p_query text, p_lat double precision, p_lng double precision)
 RETURNS void
 LANGUAGE sql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  insert into search_demand_log (part_query, user_lat, user_lng) values (p_query, p_lat, p_lng);
$function$;

-- ---------------------------------------------------------------------------
-- Analytics (garage owners)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.get_garage_analytics(p_garage_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_lat double precision;
  v_lng double precision;
  v_top_searches jsonb;
  v_movement jsonb;
  v_is_owner boolean;
begin
  select exists(
    select 1 from garages where id = p_garage_id and owner_id = auth.uid()
  ) into v_is_owner;

  if not v_is_owner then
    return jsonb_build_object('top_searches_nearby', '[]'::jsonb, 'inventory_movement', '[]'::jsonb);
  end if;

  select latitude, longitude into v_lat, v_lng from garages where id = p_garage_id;

  -- Group by the normalized (lowercased, trimmed) query so "Spark Plug" and
  -- "spark plug" count as one search term instead of two separate rows.
  -- initcap() on the grouped key gives a consistent display form regardless
  -- of how any individual driver happened to type it.
  select jsonb_agg(row_to_json(t)) into v_top_searches
  from (
    select
      initcap(lower(trim(part_query))) as part_query,
      count(*) as search_count
    from search_demand_log
    where searched_at >= now() - interval '30 days'
      and (
        6371 * acos(
          least(1.0, greatest(-1.0,
            cos(radians(v_lat)) * cos(radians(user_lat)) *
            cos(radians(user_lng) - radians(v_lng)) +
            sin(radians(v_lat)) * sin(radians(user_lat))
          ))
        )
      ) <= 10
    group by lower(trim(part_query))
    order by search_count desc
    limit 10
  ) t;

  select jsonb_agg(row_to_json(m)) into v_movement
  from (
    select part_name, sum(quantity_after - quantity_before) as net_change, count(*) as change_count
    from inventory_movement_log
    where garage_id = p_garage_id
      and changed_at >= now() - interval '30 days'
    group by part_name
    order by change_count desc
    limit 10
  ) m;

  return jsonb_build_object(
    'top_searches_nearby', coalesce(v_top_searches, '[]'::jsonb),
    'inventory_movement', coalesce(v_movement, '[]'::jsonb)
  );
end;
$function$;
