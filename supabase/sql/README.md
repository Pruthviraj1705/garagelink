# Database (Supabase / PostgreSQL)

An export of the live backend, split by topic. It documents what is deployed; it is not an
auto-generated migration history.

| File | Contents |
|---|---|
| `01_schema.sql` | Extensions, enum types, and all 24 tables with keys, defaults and checks |
| `02_rls_policies.sql` | Row Level Security enabled on every table, plus every policy |
| `03_functions.sql` | All RPCs and trigger functions (quota checks, search, purchases, referrals, notifications) |
| `04_triggers.sql` | Triggers on `inventory_items`, and notes on the push webhook and the cron job |
| `05_indexes.sql` | All explicit indexes (trigram, geo, partial) |
| `06_security_fixes.sql` | Hardening migration: protects billing/verification columns from client writes |

Apply them in numeric order on an empty Supabase project. Create the push webhook and the
cron job from the notes in `04_triggers.sql`, then set the Edge Function secrets.

## How the security model works

- The Android app talks to Supabase with the public **anon key** plus the signed-in user's JWT.
  The anon key is public by design; every table has Row Level Security on, so the JWT decides
  what a user can read or write.
- Functions that take a user id or a garage id never trust it. They compare it with
  `auth.uid()` (the id inside the verified JWT) before doing anything.
- Plan limits (stock items, scans per month, AI messages per day, searches per day) are enforced
  in the database and in the Edge Functions, not in the app.
- Secrets (Gemini key, Firebase service account, webhook secret) live only in Supabase's secret
  store. Nothing sensitive is committed to this repository.

See `docs/security-audit.md` for findings and their status.

## Tests

`supabase/tests/` builds the database from these files on a throwaway PostgreSQL and runs
security regression tests against it:

```bash
# needs a local PostgreSQL 16 and psql; uses the usual PG* environment variables
bash supabase/tests/run_tests.sh
```

The same script runs in GitHub Actions on every push.
