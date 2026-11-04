#!/usr/bin/env bash
# Builds the database from supabase/sql on a throwaway PostgreSQL and runs the security tests.
# Needs: a running PostgreSQL 16 (with contrib extensions) and psql. Connection settings come
# from the usual PG* environment variables (PGHOST, PGUSER, PGPASSWORD, ...).
set -euo pipefail

DB="${DB:-garagelink_test}"
HERE="$(cd "$(dirname "$0")" && pwd)"
SQL="$HERE/../sql"

psql -v ON_ERROR_STOP=1 -q -d postgres -c "DROP DATABASE IF EXISTS $DB" -c "CREATE DATABASE $DB"
run() { psql -v ON_ERROR_STOP=1 -q -d "$DB" -f "$1"; }

run "$HERE/00_stub_supabase.sql"
for f in 01_schema 02_rls_policies 03_functions 04_triggers 05_indexes 06_security_fixes; do
  echo "applying $f.sql"
  run "$SQL/$f.sql" 2> >(grep -v 'NOTICE' >&2 || true)
done
run "$HERE/01_seed.sql"

echo "running security tests"
run "$HERE/02_security_regression.sql"
echo "ALL DATABASE TESTS PASSED"
