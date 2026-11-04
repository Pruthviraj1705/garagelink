-- 02_rls_policies.sql
-- Row Level Security as deployed: RLS is enabled on every table in the public schema,
-- and each policy below is exported from pg_policies.
--
-- Tables with RLS on and NO policy are completely closed to API clients; they are only
-- reachable through SECURITY DEFINER functions (see 03_functions.sql):
--   device_tokens, promo_codes, sale_scan_log, search_demand_log
--
-- No client can INSERT into notifications or payments: those rows are written only by
-- database triggers and RPCs.

ALTER TABLE public.ai_assistant_usage_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ai_conversations       ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ai_usage_tracking      ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.device_tokens          ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.emergency_contacts     ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.garages                ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.inventory_items        ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.inventory_movement_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.inventory_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.invoice_line_items     ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.invoice_scan_log       ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.invoices               ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.notification_mutes     ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.notifications          ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ocr_scans              ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.payments               ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profiles               ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.promo_code_redemptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.promo_codes            ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.referrals              ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sale_scan_log          ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.search_demand_log      ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.search_usage_log       ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.subscription_plans     ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- Per-user data: a user sees only their own rows
-- ---------------------------------------------------------------------------

CREATE POLICY "Users view own AI usage log" ON public.ai_assistant_usage_log
  FOR SELECT USING (user_id = auth.uid());

CREATE POLICY ai_conv_own ON public.ai_conversations
  FOR ALL USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);

CREATE POLICY ai_usage_own ON public.ai_usage_tracking
  FOR SELECT USING (auth.uid() = user_id);

CREATE POLICY "Users view own search log" ON public.search_usage_log
  FOR SELECT USING (user_id = auth.uid());

CREATE POLICY payments_select_own ON public.payments
  FOR SELECT USING (auth.uid() = user_id);

CREATE POLICY redemptions_select_own ON public.promo_code_redemptions
  FOR SELECT USING (auth.uid() = user_id);

CREATE POLICY referrals_involved_select ON public.referrals
  FOR SELECT USING ((auth.uid() = referrer_id) OR (auth.uid() = referred_id));

-- ---------------------------------------------------------------------------
-- Profiles
-- ---------------------------------------------------------------------------

CREATE POLICY profiles_insert_own ON public.profiles
  FOR INSERT WITH CHECK (auth.uid() = id);

CREATE POLICY profiles_select_own ON public.profiles
  FOR SELECT USING (auth.uid() = id);

CREATE POLICY profiles_update_own ON public.profiles
  FOR UPDATE USING (auth.uid() = id);

-- ---------------------------------------------------------------------------
-- Garages and everything that belongs to a garage: only the owner's garage
-- ---------------------------------------------------------------------------

CREATE POLICY garages_owner_all ON public.garages
  FOR ALL USING (auth.uid() = owner_id) WITH CHECK (auth.uid() = owner_id);

CREATE POLICY garages_public_read_verified ON public.garages
  FOR SELECT USING (is_verified = true);

CREATE POLICY inventory_owner_all ON public.inventory_items
  FOR ALL
  USING (garage_id IN (SELECT id FROM public.garages WHERE owner_id = auth.uid()))
  WITH CHECK (garage_id IN (SELECT id FROM public.garages WHERE owner_id = auth.uid()));

CREATE POLICY "Owners view own movement log" ON public.inventory_movement_log
  FOR SELECT USING (garage_id IN (SELECT id FROM public.garages WHERE owner_id = auth.uid()));

CREATE POLICY inv_txn_owner_all ON public.inventory_transactions
  FOR ALL
  USING (garage_id IN (SELECT id FROM public.garages WHERE owner_id = auth.uid()))
  WITH CHECK (garage_id IN (SELECT id FROM public.garages WHERE owner_id = auth.uid()));

CREATE POLICY invoices_owner_all ON public.invoices
  FOR ALL
  USING (garage_id IN (SELECT id FROM public.garages WHERE owner_id = auth.uid()))
  WITH CHECK (garage_id IN (SELECT id FROM public.garages WHERE owner_id = auth.uid()));

CREATE POLICY invoice_line_items_owner_all ON public.invoice_line_items
  FOR ALL
  USING (invoice_id IN (
    SELECT i.id FROM public.invoices i
    JOIN public.garages g ON g.id = i.garage_id
    WHERE g.owner_id = auth.uid()))
  WITH CHECK (invoice_id IN (
    SELECT i.id FROM public.invoices i
    JOIN public.garages g ON g.id = i.garage_id
    WHERE g.owner_id = auth.uid()));

CREATE POLICY "Owners view own scan log" ON public.invoice_scan_log
  FOR SELECT USING (garage_id IN (SELECT id FROM public.garages WHERE owner_id = auth.uid()));

CREATE POLICY ocr_scans_owner_all ON public.ocr_scans
  FOR ALL
  USING (garage_id IN (SELECT id FROM public.garages WHERE owner_id = auth.uid()))
  WITH CHECK (garage_id IN (SELECT id FROM public.garages WHERE owner_id = auth.uid()));

CREATE POLICY "Owners view own mutes" ON public.notification_mutes
  FOR SELECT USING (garage_id IN (SELECT id FROM public.garages WHERE owner_id = auth.uid()));

CREATE POLICY "Owners insert own mutes" ON public.notification_mutes
  FOR INSERT WITH CHECK (garage_id IN (SELECT id FROM public.garages WHERE owner_id = auth.uid()));

CREATE POLICY "Owners update own mutes" ON public.notification_mutes
  FOR UPDATE
  USING (garage_id IN (SELECT id FROM public.garages WHERE owner_id = auth.uid()))
  WITH CHECK (garage_id IN (SELECT id FROM public.garages WHERE owner_id = auth.uid()));

CREATE POLICY "Owners delete own mutes" ON public.notification_mutes
  FOR DELETE USING (garage_id IN (SELECT id FROM public.garages WHERE owner_id = auth.uid()));

-- ---------------------------------------------------------------------------
-- Notifications: read, mark-as-read and delete your own. No client INSERT policy.
-- ---------------------------------------------------------------------------

CREATE POLICY "Users view own notifications" ON public.notifications
  FOR SELECT USING (user_id = auth.uid());

CREATE POLICY "Users update own notifications" ON public.notifications
  FOR UPDATE USING (user_id = auth.uid());

CREATE POLICY "Users delete own notifications" ON public.notifications
  FOR DELETE USING (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- Public reference data
-- ---------------------------------------------------------------------------

CREATE POLICY emergency_read_all ON public.emergency_contacts
  FOR SELECT USING (true);

CREATE POLICY plans_read_all ON public.subscription_plans
  FOR SELECT USING (true);
