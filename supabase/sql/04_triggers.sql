-- 04_triggers.sql
-- Triggers on application tables, exported from pg_trigger.

-- ---------------------------------------------------------------------------
-- inventory_items
-- ---------------------------------------------------------------------------

-- Enforce the plan's stock-item limit before a new part is added.
DROP TRIGGER IF EXISTS trg_check_stock_item_limit ON public.inventory_items;
CREATE TRIGGER trg_check_stock_item_limit
  BEFORE INSERT ON public.inventory_items
  FOR EACH ROW EXECUTE FUNCTION check_stock_item_limit();

-- Record every stock change (feeds the analytics screen).
DROP TRIGGER IF EXISTS trg_log_inventory_movement ON public.inventory_items;
CREATE TRIGGER trg_log_inventory_movement
  AFTER INSERT OR UPDATE OF quantity ON public.inventory_items
  FOR EACH ROW EXECUTE FUNCTION log_inventory_movement();

-- Notify the owner when an item's stock drops below its alert level.
DROP TRIGGER IF EXISTS trg_notify_low_inventory ON public.inventory_items;
CREATE TRIGGER trg_notify_low_inventory
  AFTER INSERT OR UPDATE OF quantity ON public.inventory_items
  FOR EACH ROW EXECUTE FUNCTION notify_low_inventory();

-- ---------------------------------------------------------------------------
-- notifications -> push notification
-- ---------------------------------------------------------------------------
-- This one is created from the Supabase dashboard (Integrations -> Database Webhooks,
-- "push_on_notification_insert"), which generates the trigger below. The header value is a
-- secret and is deliberately NOT stored in this repository:
--   * set the same random value as the Edge Function secret PUSH_WEBHOOK_SECRET
--   * set it as the "x-webhook-secret" header on the webhook
--
-- CREATE TRIGGER push_on_notification_insert
--   AFTER INSERT ON public.notifications
--   FOR EACH ROW EXECUTE FUNCTION supabase_functions.http_request(
--     'https://<PROJECT_REF>.supabase.co/functions/v1/send-push',
--     'POST',
--     '{"Content-type":"application/json","x-webhook-secret":"<PUSH_WEBHOOK_SECRET>"}',
--     '{}',
--     '5000');

-- ---------------------------------------------------------------------------
-- Scheduled job
-- ---------------------------------------------------------------------------
-- check_subscription_expiring() is run on a schedule by pg_cron. The exact schedule is
-- not part of this export; see `select jobname, schedule, command from cron.job;`.
