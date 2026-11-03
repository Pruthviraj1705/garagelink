-- 01_seed.sql  (test data only)
INSERT INTO auth.users(id) VALUES
  ('11111111-1111-1111-1111-111111111111'),   -- U1: garage owner
  ('22222222-2222-2222-2222-222222222222'),   -- U2: driver
  ('33333333-3333-3333-3333-333333333333'),   -- U3: second garage owner (no garage yet)
  ('44444444-4444-4444-4444-444444444444');   -- U4: brand-new user (no profile yet)

INSERT INTO subscription_plans(id, name, target_role, price_inr, duration_days,
                               max_stock_items, max_invoice_scans, max_ai_messages, max_sale_scans) VALUES
  ('aaaaaaaa-0000-0000-0000-000000000001', 'Pro',  'garage_owner', 249, 30, 500, 50, 50, 50),
  ('aaaaaaaa-0000-0000-0000-000000000002', 'Plus', 'driver',        49, 30, NULL, NULL, 50, NULL);

INSERT INTO profiles(id, phone_number, role) VALUES
  ('11111111-1111-1111-1111-111111111111', '+911000000001', 'garage_owner'),
  ('22222222-2222-2222-2222-222222222222', '+911000000002', 'driver'),
  ('33333333-3333-3333-3333-333333333333', '+911000000003', 'garage_owner');

INSERT INTO garages(id, owner_id, shop_name, latitude, longitude) VALUES
  ('bbbbbbbb-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'Test Garage', 18.52, 73.85);

INSERT INTO inventory_items(id, garage_id, part_name, quantity, low_stock_threshold) VALUES
  ('cccccccc-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000001', 'Brake pad', 5, 5);
