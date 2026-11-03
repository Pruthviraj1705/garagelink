-- 05_indexes.sql
-- Extension and explicit indexes as deployed. Primary-key and unique-constraint indexes are
-- created together with their tables and are not repeated here.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Garages: geo lookups, owner lookups, and the expiry cron query.
CREATE INDEX IF NOT EXISTS idx_garages_geo        ON public.garages USING btree (latitude, longitude);
CREATE INDEX IF NOT EXISTS idx_garages_owner      ON public.garages USING btree (owner_id);
CREATE INDEX IF NOT EXISTS idx_garages_sub_expiry ON public.garages USING btree (subscription_expiry);

-- Inventory: per-garage listing, in-stock filter, and trigram indexes for typo-tolerant search.
CREATE INDEX IF NOT EXISTS idx_inventory_garage   ON public.inventory_items USING btree (garage_id);
CREATE INDEX IF NOT EXISTS idx_inventory_in_stock ON public.inventory_items USING btree (garage_id) WHERE (quantity > 0);
CREATE INDEX IF NOT EXISTS idx_inventory_name_trgm
  ON public.inventory_items USING gin (part_name_normalized gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_inventory_part_name_trgm
  ON public.inventory_items USING gin (part_name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_inv_txn_garage ON public.inventory_transactions USING btree (garage_id);

-- Push tokens by user.
CREATE INDEX IF NOT EXISTS device_tokens_user_id_idx ON public.device_tokens USING btree (user_id);

-- Promo codes: fast lookup of active codes, and one free trial per user.
CREATE INDEX IF NOT EXISTS idx_promo_code_lookup ON public.promo_codes USING btree (code) WHERE (is_active = true);
CREATE UNIQUE INDEX IF NOT EXISTS idx_one_trial_per_user
  ON public.promo_code_redemptions USING btree (user_id) WHERE (code_type = 'garage_trial'::promo_code_type);
