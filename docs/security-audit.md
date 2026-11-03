# Security notes

How GarageLink protects its data, what was found when the backend was reviewed, and what
is still open. Written from the deployed database (`supabase/sql/`) and the Edge Functions
(`supabase/functions/`).

## Threat model in one paragraph

The Android app ships with a public Supabase **anon key**, so anyone can extract it and call the
REST API and the RPCs directly with their own account. The design therefore assumes the client
is untrusted: the database decides what a user may read or write, and the Edge Functions decide
what a user may spend.

## Controls in place

| Control | Where | How it works |
|---|---|---|
| Row Level Security on every table | `02_rls_policies.sql` | All 24 tables have RLS on. Four tables (`device_tokens`, `promo_codes`, `sale_scan_log`, `search_demand_log`) have no client policy and are reachable only through `SECURITY DEFINER` functions. |
| Identity never trusted from the client | `03_functions.sql` | RPCs that take a user id (`check_and_log_search`, `check_and_log_ai_message`, `redeem_promo_code`, `redeem_referral_code`, `get_or_create_referral_code`, `get_ai_message_usage`, both `purchase_*_manual`) compare it with `auth.uid()` and refuse if it differs. |
| Ownership checks | `03_functions.sql` | RPCs that take a garage id (`check_and_log_invoice_scan`, `check_and_log_sale_scan`, `get_*_scan_usage`, `get_garage_analytics`, `purchase_subscription_manual`) verify `garages.owner_id = auth.uid()`. |
| Pinned search path | `03_functions.sql` | Every `SECURITY DEFINER` function sets `search_path` to `public`. |
| No client writes to notifications or payments | `02_rls_policies.sql` | These tables have no INSERT policy; rows are written only by triggers and RPCs. |
| Quotas enforced server-side | Edge Functions + RPCs | The scan and AI functions call the quota RPC as the verified user before contacting the AI provider. |
| Authenticated Edge Functions | `scan-invoice`, `scan-sale-bill`, `bright-action` | Each verifies the caller's JWT with `auth.getUser()`, validates input types and size, and returns generic errors; provider errors are logged, never forwarded. |
| Authenticated webhook | `send-push` | Only accepts requests carrying a shared secret in `x-webhook-secret` (constant-time comparison). |
| Secrets management | Supabase secrets | Gemini key, Firebase service account and webhook secret are never in source control. The webhook secret has been rotated. |

## Findings

| ID | Severity | Finding | Status |
|---|---|---|---|
| F1 | High | `garages_owner_all` allows an owner to update any column of their garage through the REST API, including `is_verified`, `subscription_status`, `subscription_expiry` and `current_plan_id`. A user could verify their own garage or grant themselves a paid plan. | Fixed by trigger in `06_security_fixes.sql` |
| F2 | High | `profiles_insert_own` / `profiles_update_own` allow a user to set their own `current_plan_id`, `subscription_expiry`, `subscription_status` and `referral_code`. | Fixed by trigger in `06_security_fixes.sql` |
| F3 | Medium | `purchase_subscription_manual` and `purchase_driver_subscription_manual` simulate billing: they grant the plan and record a `success` payment with no payment verification. Any logged-in user can obtain any plan. | Known limitation (no payment gateway integrated). Must be replaced by server-side verification of a real payment (for example a gateway webhook) before any production launch. |
| F4 | Low | Discovery RPCs (`list_nearby_garages`, `list_top_garages`, `search_nearby_garages_with_part`, `search_part_name_suggestions`) and the `garages_public_read_verified` policy can be called with the anon key alone and expose shop names, addresses and phone numbers (and, for the policy, all columns of verified garages such as `owner_id` and subscription fields). | Accepted for a public marketplace. Optional hardening is in `06_security_fixes.sql` (require login; expose a view instead of the table). |
| F5 | Low | `log_search_demand` accepted unauthenticated, unbounded input. | Fixed in `06_security_fixes.sql` |
| F6 | Low | `check_subscription_expiring()` is a cron job but was executable by client roles. | Fixed in `06_security_fixes.sql` |
| F7 | Info | Redundant objects: `idx_inventory_part_name_trgm` (queries use `part_name_normalized`), duplicate unique constraints on `referrals.referred_id` and `promo_codes.code`. | Optional cleanup in `06_security_fixes.sql` |

## How F1 and F2 were verified

The SQL in `supabase/sql/` is applied to an empty PostgreSQL 16 and exercised through the same
API roles Supabase uses (`authenticated`, `anon`), with the caller's identity set the way a JWT
would set it. `supabase/tests/02_security_regression.sql` contains 23 assertions:

- Attacks that must fail: self-verifying a garage, granting yourself a plan through `garages` or
  `profiles`, creating a pre-verified garage or a profile with a plan, setting your own referral
  code, calling the cron function, editing someone else's garage.
- Flows that must keep working: shop edits, garage and profile creation exactly as the app does
  it, both purchase RPCs, referral and promo-code redemption, quota checks, low-stock alerts
  (including mutes) and the search demand log.

Before the fix the first attack succeeds (the test fails); after it, every attack is blocked and
every normal flow still passes. The tests run on every push (`.github/workflows/database.yml`).

## Known limitations

- A scan is counted against the monthly quota before the AI provider is called, so a failed scan
  still uses one unit.
- Authentication uses Supabase test phone numbers (no production SMS provider).
- Ads use Google's test ad unit IDs.
- The database tests run against plain PostgreSQL with a stand-in for `auth.uid()`; they cover the
  rules above, not every table and policy.
