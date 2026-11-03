-- 01_schema.sql
-- Extensions, enum types and tables (columns, defaults, keys, checks), reconstructed from the
-- deployed database. Apply this first; the other files build on it.
--
-- Notes:
--   * The live database also contains two redundant duplicate unique constraints
--     (promo_codes.uq_promo_code and referrals.referrals_referred_id_unique); they are not
--     repeated here. See docs/security-audit.md (F7).
--   * Secondary indexes are in 05_indexes.sql, RLS in 02_rls_policies.sql.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------------
-- Enum types
-- ---------------------------------------------------------------------------

CREATE TYPE public.user_role           AS ENUM ('garage_owner', 'driver');
CREATE TYPE public.subscription_status AS ENUM ('trial', 'active', 'expired', 'cancelled');
CREATE TYPE public.notification_type   AS ENUM ('subscription_expiring', 'low_inventory', 'general');
CREATE TYPE public.payment_gateway     AS ENUM ('razorpay', 'phonepe', 'manual');
CREATE TYPE public.payment_status      AS ENUM ('pending', 'success', 'failed', 'refunded');
CREATE TYPE public.promo_code_type     AS ENUM ('garage_trial', 'driver_referral', 'admin_manual');
CREATE TYPE public.txn_type            AS ENUM ('stock_in', 'stock_out');

-- ---------------------------------------------------------------------------
-- Plans and people
-- ---------------------------------------------------------------------------

-- A NULL max_* column means "unlimited" on that plan.
CREATE TABLE public.subscription_plans (
  id                uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  name              text NOT NULL,
  target_role       public.user_role NOT NULL,
  price_inr         numeric(10,2) NOT NULL,
  price_usd         numeric(10,2),
  duration_days     integer NOT NULL,
  features          jsonb NOT NULL DEFAULT '{}'::jsonb,
  is_active         boolean NOT NULL DEFAULT true,
  max_stock_items   integer,
  max_invoice_scans integer,
  max_ai_messages   integer,
  max_sale_scans    integer
);

CREATE TABLE public.profiles (
  id                  uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  phone_number        text NOT NULL UNIQUE,
  full_name           text,
  role                public.user_role NOT NULL,
  preferred_language  text NOT NULL DEFAULT 'en',
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  current_plan_id     uuid REFERENCES public.subscription_plans(id),
  subscription_status public.subscription_status NOT NULL DEFAULT 'trial',
  subscription_expiry timestamptz,
  referral_code       text UNIQUE
);

CREATE TABLE public.garages (
  id                  uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  owner_id            uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  shop_name           text NOT NULL,
  address             text,
  latitude            double precision NOT NULL,
  longitude           double precision NOT NULL,
  is_verified         boolean NOT NULL DEFAULT false,
  subscription_status public.subscription_status NOT NULL DEFAULT 'trial',
  subscription_expiry timestamptz,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  current_plan_id     uuid REFERENCES public.subscription_plans(id)
);

-- ---------------------------------------------------------------------------
-- Inventory
-- ---------------------------------------------------------------------------

CREATE TABLE public.inventory_items (
  id                   uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  garage_id            uuid NOT NULL REFERENCES public.garages(id) ON DELETE CASCADE,
  part_name            text NOT NULL,
  -- Lower-cased, trimmed copy of part_name used by the fuzzy search (similarity()).
  part_name_normalized text GENERATED ALWAYS AS (lower(trim(BOTH FROM part_name))) STORED,
  serial_number        text,
  category             text,
  quantity             integer NOT NULL DEFAULT 0 CONSTRAINT inventory_items_quantity_check CHECK (quantity >= 0),
  unit_price           numeric(10,2),
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  low_stock_threshold  integer NOT NULL DEFAULT 5,
  UNIQUE (garage_id, serial_number)
);

CREATE TABLE public.inventory_transactions (
  id                uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  inventory_item_id uuid NOT NULL REFERENCES public.inventory_items(id) ON DELETE CASCADE,
  garage_id         uuid NOT NULL REFERENCES public.garages(id) ON DELETE CASCADE,
  txn_type          public.txn_type NOT NULL,
  quantity_change   integer NOT NULL,
  source            text NOT NULL,
  reference_id      uuid,
  created_by        uuid REFERENCES public.profiles(id),
  created_at        timestamptz NOT NULL DEFAULT now()
);

-- Written by the trg_log_inventory_movement trigger; feeds the analytics screen.
CREATE TABLE public.inventory_movement_log (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  garage_id         uuid NOT NULL REFERENCES public.garages(id),
  inventory_item_id uuid,
  part_name         text NOT NULL,
  quantity_before   integer NOT NULL,
  quantity_after    integer NOT NULL,
  changed_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.invoices (
  id             uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  garage_id      uuid NOT NULL REFERENCES public.garages(id) ON DELETE CASCADE,
  customer_name  text,
  customer_phone text,
  total_amount   numeric(10,2) NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.invoice_line_items (
  id                uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  invoice_id        uuid NOT NULL REFERENCES public.invoices(id) ON DELETE CASCADE,
  inventory_item_id uuid REFERENCES public.inventory_items(id),
  part_name         text NOT NULL,
  quantity          integer NOT NULL CONSTRAINT invoice_line_items_quantity_check CHECK (quantity > 0),
  unit_price        numeric(10,2) NOT NULL
);

CREATE TABLE public.ocr_scans (
  id           uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  garage_id    uuid NOT NULL REFERENCES public.garages(id) ON DELETE CASCADE,
  scan_type    text NOT NULL,
  raw_text     text,
  parsed_items jsonb,
  status       text NOT NULL DEFAULT 'pending_review',
  created_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ocr_scans_scan_type_check CHECK (scan_type = ANY (ARRAY['supplier_invoice'::text, 'customer_bill'::text])),
  CONSTRAINT ocr_scans_status_check    CHECK (status    = ANY (ARRAY['pending_review'::text, 'confirmed'::text, 'rejected'::text]))
);

-- ---------------------------------------------------------------------------
-- Notifications and push
-- ---------------------------------------------------------------------------

CREATE TABLE public.notifications (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         uuid NOT NULL REFERENCES public.profiles(id),
  garage_id       uuid REFERENCES public.garages(id),
  type            public.notification_type NOT NULL,
  title           text NOT NULL,
  body            text NOT NULL,
  related_item_id uuid REFERENCES public.inventory_items(id),
  is_read         boolean NOT NULL DEFAULT false,
  created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.notification_mutes (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  garage_id         uuid NOT NULL REFERENCES public.garages(id),
  inventory_item_id uuid NOT NULL REFERENCES public.inventory_items(id),
  muted_until       timestamptz NOT NULL,
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (garage_id, inventory_item_id)
);

CREATE TABLE public.device_tokens (
  token      text PRIMARY KEY,
  user_id    uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  platform   text NOT NULL DEFAULT 'android',
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Payments, promo codes, referrals
-- ---------------------------------------------------------------------------

CREATE TABLE public.payments (
  id                     uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id                uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  garage_id              uuid REFERENCES public.garages(id),
  amount                 numeric(10,2) NOT NULL,
  currency               text NOT NULL DEFAULT 'INR',
  gateway                public.payment_gateway NOT NULL,
  gateway_transaction_id text,
  status                 public.payment_status NOT NULL DEFAULT 'pending',
  created_at             timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.promo_codes (
  id                   uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  code                 text NOT NULL UNIQUE,
  code_type            public.promo_code_type NOT NULL,
  reward_description   text,
  reward_duration_days integer,
  max_global_usage     integer NOT NULL DEFAULT 1,
  current_usage_count  integer NOT NULL DEFAULT 0,
  is_active            boolean NOT NULL DEFAULT true,
  expiry_date          timestamptz,
  created_by           uuid REFERENCES public.profiles(id),
  created_at           timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT chk_usage_within_max CHECK (current_usage_count <= max_global_usage)
);

CREATE TABLE public.promo_code_redemptions (
  id            uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  promo_code_id uuid NOT NULL REFERENCES public.promo_codes(id) ON DELETE CASCADE,
  user_id       uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  code_type     public.promo_code_type NOT NULL,
  redeemed_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (promo_code_id, user_id)
);

CREATE TABLE public.referrals (
  id          uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  referrer_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  referred_id uuid NOT NULL UNIQUE REFERENCES public.profiles(id) ON DELETE CASCADE,
  status      text NOT NULL DEFAULT 'pending',
  created_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT referrals_status_check CHECK (status = ANY (ARRAY['pending'::text, 'completed'::text]))
);

-- ---------------------------------------------------------------------------
-- Usage logs (one row per metered action; counted by the check_and_log_* functions)
-- ---------------------------------------------------------------------------

CREATE TABLE public.invoice_scan_log (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  garage_id  uuid NOT NULL REFERENCES public.garages(id),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.sale_scan_log (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  garage_id  uuid NOT NULL REFERENCES public.garages(id),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.ai_assistant_usage_log (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    uuid NOT NULL REFERENCES public.profiles(id),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.search_usage_log (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    uuid NOT NULL REFERENCES public.profiles(id),
  created_at timestamptz NOT NULL DEFAULT now()
);

-- What drivers search for, by location (feeds garage analytics).
CREATE TABLE public.search_demand_log (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  part_query  text NOT NULL,
  user_lat    double precision NOT NULL,
  user_lng    double precision NOT NULL,
  searched_at timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Legacy tables (superseded by the usage logs above; still present in the database)
-- ---------------------------------------------------------------------------

CREATE TABLE public.ai_conversations (
  id                   uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id              uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  question             text NOT NULL,
  response             text,
  was_offtopic_blocked boolean NOT NULL DEFAULT false,
  created_at           timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.ai_usage_tracking (
  id             uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id        uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  month_year     text NOT NULL,
  question_count integer NOT NULL DEFAULT 0,
  UNIQUE (user_id, month_year)
);

-- ---------------------------------------------------------------------------
-- Reference data
-- ---------------------------------------------------------------------------

CREATE TABLE public.emergency_contacts (
  id           uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  category     text NOT NULL,
  name         text NOT NULL,
  phone_number text NOT NULL,
  region       text NOT NULL DEFAULT 'Pune',
  is_active    boolean NOT NULL DEFAULT true
);
