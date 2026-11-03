# Database tests

Applies `supabase/sql/01..06` to an empty PostgreSQL 16 and runs `02_security_regression.sql`,
which acts as different users through the `authenticated` / `anon` roles and asserts what each
may and may not do.

```bash
bash supabase/tests/run_tests.sh
```

- `00_stub_supabase.sql` stands in for the Supabase pieces the schema needs (the API roles,
  `auth.users`, `auth.uid()`). It is for test databases only; never run it on a real project.
- `01_seed.sql` creates a few users, a plan, a garage and one inventory item.
- A failing assertion raises `TEST FAILED: ...` and the script exits non-zero.
