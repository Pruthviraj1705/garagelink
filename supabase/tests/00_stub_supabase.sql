-- 00_stub_supabase.sql
-- Minimal stand-ins for the parts of Supabase that the schema depends on, so the SQL in
-- supabase/sql can be applied and tested on a plain PostgreSQL 16 (locally or in CI).
-- Do NOT run this against a real Supabase project.

-- Roles belong to the whole server, so create them only if they are missing (re-runnable).
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon')          THEN CREATE ROLE anon          NOLOGIN; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN CREATE ROLE authenticated NOLOGIN; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'service_role')  THEN CREATE ROLE service_role  NOLOGIN BYPASSRLS; END IF;
END $$;

CREATE SCHEMA auth;
CREATE TABLE auth.users (id uuid PRIMARY KEY);

-- Same contract as Supabase's auth.uid(): the "sub" claim of the request's JWT.
CREATE FUNCTION auth.uid() RETURNS uuid
  LANGUAGE sql STABLE AS $$
  SELECT nullif(
    coalesce(current_setting('request.jwt.claim.sub', true),
             (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'sub')),
    '')::uuid
$$;

GRANT USAGE ON SCHEMA public, auth TO anon, authenticated, service_role;
GRANT SELECT ON auth.users TO anon, authenticated, service_role;

-- Supabase gives the API roles table privileges by default; RLS is what restricts them.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO anon, authenticated, service_role;
