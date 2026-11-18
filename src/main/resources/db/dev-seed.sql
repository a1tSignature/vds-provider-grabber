-- Apply once to an empty, migrated dev catalog. The bootstrap-admin configuration creates the first user.
-- Fixtures include sold-out and inactive tariffs, an RSD price with no FX rate, and a closed price followed by a current price.

BEGIN;


INSERT INTO provider (slug, name, website_url, description, status) VALUES
    ('hostzilla', 'HostZilla B.V.', 'https://www.hostzilla.example',
     'European KVM VPS provider based in Amsterdam with a secondary PoP in Frankfurt.', 'ACTIVE'),
    ('cloudanchor', 'CloudAnchor Inc.', 'https://www.cloudanchor.example',
     'US-based cloud VPS with NVMe storage and East Coast / South Central peering.', 'ACTIVE'),
    ('nordvds', 'NordVDS Oy', 'https://www.nordvds.example',
     'Finnish VPS provider running Nordic green-energy datacenters.', 'ACTIVE'),
    ('baltichost', 'BalticHost UAB', 'https://www.baltichost.example',
     'Budget VPS hosting out of Vilnius with a Central European PoP in Warsaw.', 'ACTIVE'),
    ('redlineservers', 'RedLine Servers GmbH', 'https://www.redlineservers.example',
     'German KVM VPS provider with Frankfurt and Paris datacenters.', 'ACTIVE'),
    ('msklabs', 'MSK Labs LLC', 'https://www.msklabs.example',
     'Russian VPS hosting with Moscow and Saint Petersburg datacenters.', 'ACTIVE');


INSERT INTO provider_legal_info
    (provider_id, legal_entity_name, registration_country, registration_number, legal_address,
     data_source, confidence, source_url, verified_at)
VALUES
    ((SELECT id FROM provider WHERE slug = 'hostzilla'), 'HostZilla B.V.', 'NL', 'NL123456789B01',
     'Keizersgracht 123, 1015 CJ Amsterdam, Netherlands', 'MANUAL', 'HIGH',
     'https://www.hostzilla.example/legal', now() - interval '30 days'),
    ((SELECT id FROM provider WHERE slug = 'cloudanchor'), 'CloudAnchor Inc.', 'US', 'DE-7001234',
     '1201 N Market St, Wilmington, DE 19801, USA', 'MANUAL', 'MEDIUM',
     'https://www.cloudanchor.example/legal', now() - interval '45 days'),
    ((SELECT id FROM provider WHERE slug = 'nordvds'), 'NordVDS Oy', 'FI', 'FI23456789',
     'Mannerheimintie 10, 00100 Helsinki, Finland', 'MANUAL', 'HIGH',
     'https://www.nordvds.example/legal', now() - interval '60 days'),
    ((SELECT id FROM provider WHERE slug = 'baltichost'), 'BalticHost UAB', 'LT', '304567890',
     'Gedimino pr. 20, 01103 Vilnius, Lithuania', 'CRAWLER', 'MEDIUM', NULL, NULL),
    ((SELECT id FROM provider WHERE slug = 'redlineservers'), 'RedLine Servers GmbH', 'DE',
     'HRB98765', 'Mainzer Landstr. 50, 60325 Frankfurt am Main, Germany', 'MANUAL', 'HIGH',
     'https://www.redlineservers.example/legal', now() - interval '20 days'),
    ((SELECT id FROM provider WHERE slug = 'msklabs'), 'OOO MSK Labs', 'RU', '1157746123456',
     'Prospekt Mira 50, 129344 Moscow, Russia', 'MANUAL', 'MEDIUM',
     'https://www.msklabs.example/legal', now() - interval '90 days');


INSERT INTO provider_payment_method (provider_id, method, note) VALUES
    ((SELECT id FROM provider WHERE slug = 'hostzilla'), 'VISA_MASTERCARD', NULL),
    ((SELECT id FROM provider WHERE slug = 'hostzilla'), 'PAYPAL', NULL),
    ((SELECT id FROM provider WHERE slug = 'hostzilla'), 'CRYPTO_BTC', NULL),
    ((SELECT id FROM provider WHERE slug = 'hostzilla'), 'BANK_WIRE', NULL),

    ((SELECT id FROM provider WHERE slug = 'cloudanchor'), 'VISA_MASTERCARD', NULL),
    ((SELECT id FROM provider WHERE slug = 'cloudanchor'), 'AMERICAN_EXPRESS', NULL),
    ((SELECT id FROM provider WHERE slug = 'cloudanchor'), 'PAYPAL', NULL),
    ((SELECT id FROM provider WHERE slug = 'cloudanchor'), 'APPLE_PAY', NULL),
    ((SELECT id FROM provider WHERE slug = 'cloudanchor'), 'GOOGLE_PAY', NULL),

    ((SELECT id FROM provider WHERE slug = 'nordvds'), 'VISA_MASTERCARD', NULL),
    ((SELECT id FROM provider WHERE slug = 'nordvds'), 'BANK_WIRE', NULL),
    ((SELECT id FROM provider WHERE slug = 'nordvds'), 'CRYPTO_BTC', NULL),
    ((SELECT id FROM provider WHERE slug = 'nordvds'), 'CRYPTO_ETH', NULL),

    ((SELECT id FROM provider WHERE slug = 'baltichost'), 'VISA_MASTERCARD', NULL),
    ((SELECT id FROM provider WHERE slug = 'baltichost'), 'MIR', NULL),
    ((SELECT id FROM provider WHERE slug = 'baltichost'), 'CRYPTO_USDT', 'preferred for annual plans'),
    ((SELECT id FROM provider WHERE slug = 'baltichost'), 'WEBMONEY', NULL),

    ((SELECT id FROM provider WHERE slug = 'redlineservers'), 'VISA_MASTERCARD', NULL),
    ((SELECT id FROM provider WHERE slug = 'redlineservers'), 'BANK_WIRE', NULL),
    ((SELECT id FROM provider WHERE slug = 'redlineservers'), 'PAYPAL', NULL),
    ((SELECT id FROM provider WHERE slug = 'redlineservers'), 'CRYPTO_BTC', NULL),

    ((SELECT id FROM provider WHERE slug = 'msklabs'), 'MIR', NULL),
    ((SELECT id FROM provider WHERE slug = 'msklabs'), 'SBP', NULL),
    ((SELECT id FROM provider WHERE slug = 'msklabs'), 'RU_BANK_TRANSFER', NULL),
    ((SELECT id FROM provider WHERE slug = 'msklabs'), 'CRYPTO_USDT', NULL);


INSERT INTO location (provider_id, country_code, region, city, datacenter_name, latitude, longitude) VALUES
    ((SELECT id FROM provider WHERE slug = 'hostzilla'), 'NL', 'North Holland', 'Amsterdam', 'AMS-1', 52.370216, 4.895168),
    ((SELECT id FROM provider WHERE slug = 'hostzilla'), 'DE', 'Hesse', 'Frankfurt', 'FRA-2', 50.110924, 8.682127),

    ((SELECT id FROM provider WHERE slug = 'cloudanchor'), 'US', 'New York', 'New York', 'NYC-3', 40.712776, -74.005974),
    ((SELECT id FROM provider WHERE slug = 'cloudanchor'), 'US', 'Texas', 'Dallas', 'DFW-1', 32.776665, -96.796989),

    ((SELECT id FROM provider WHERE slug = 'nordvds'), 'FI', 'Uusimaa', 'Helsinki', 'HEL-1', 60.169857, 24.938379),
    ((SELECT id FROM provider WHERE slug = 'nordvds'), 'SE', 'Stockholm County', 'Stockholm', 'STO-1', 59.329323, 18.068581),

    ((SELECT id FROM provider WHERE slug = 'baltichost'), 'LT', 'Vilnius County', 'Vilnius', 'VNO-1', 54.687157, 25.279652),
    ((SELECT id FROM provider WHERE slug = 'baltichost'), 'PL', 'Masovian', 'Warsaw', 'WAW-2', 52.229675, 21.012230),

    ((SELECT id FROM provider WHERE slug = 'redlineservers'), 'DE', 'Hesse', 'Frankfurt', 'FRA-1', 50.110924, 8.682127),
    ((SELECT id FROM provider WHERE slug = 'redlineservers'), 'FR', 'Ile-de-France', 'Paris', 'PAR-1', 48.856613, 2.352222),

    ((SELECT id FROM provider WHERE slug = 'msklabs'), 'RU', 'Moscow', 'Moscow', 'MOW-1', 55.755825, 37.617298),
    ((SELECT id FROM provider WHERE slug = 'msklabs'), 'RU', 'Leningrad Oblast', 'Saint Petersburg', 'LED-1', 59.934280, 30.335099);


INSERT INTO tariff
    (provider_id, external_id, name, url, vcpu_count, cpu_model, ram_mb, disk_gb, disk_type,
     bandwidth_mbps, traffic_limit_gb, ipv4_count, ipv6_supported, virtualization, is_active, in_stock)
VALUES
    ((SELECT id FROM provider WHERE slug = 'hostzilla'), NULL, 'VPS Starter',
     'https://www.hostzilla.example/plans/starter', 1, NULL, 1024, 20, 'NVME', 100, 1000, 1, NULL,
     'KVM', TRUE, FALSE),
    ((SELECT id FROM provider WHERE slug = 'hostzilla'), NULL, 'VPS Basic',
     'https://www.hostzilla.example/plans/basic', 2, NULL, 2048, 40, 'NVME', 200, 2000, 1, TRUE,
     'KVM', TRUE, TRUE),
    ((SELECT id FROM provider WHERE slug = 'hostzilla'), NULL, 'VPS Plus',
     'https://www.hostzilla.example/plans/plus', 4, NULL, 4096, 80, 'NVME', 400, NULL, 1, TRUE,
     'KVM', TRUE, TRUE),
    ((SELECT id FROM provider WHERE slug = 'hostzilla'), NULL, 'VPS Pro',
     'https://www.hostzilla.example/plans/pro', 8, NULL, 8192, 160, 'NVME', 1000, NULL, 2, TRUE,
     'KVM', TRUE, TRUE),

    ((SELECT id FROM provider WHERE slug = 'cloudanchor'), NULL, 'Cloud Nano',
     'https://www.cloudanchor.example/plans/nano', 1, 'Intel Xeon Gold 6338', 1024, 25, 'SSD', 100,
     1000, 1, TRUE, 'KVM', TRUE, TRUE),
    ((SELECT id FROM provider WHERE slug = 'cloudanchor'), NULL, 'Cloud Micro',
     'https://www.cloudanchor.example/plans/micro', 2, 'Intel Xeon Gold 6338', 2048, 50, 'SSD', 300,
     3000, 1, TRUE, 'KVM', TRUE, TRUE),
    ((SELECT id FROM provider WHERE slug = 'cloudanchor'), NULL, 'Cloud Standard',
     'https://www.cloudanchor.example/plans/standard', 4, 'Intel Xeon Gold 6338', 8192, 100, 'NVME',
     500, NULL, 1, TRUE, 'KVM', TRUE, TRUE),
    ((SELECT id FROM provider WHERE slug = 'cloudanchor'), NULL, 'Cloud Business UK',
     'https://www.cloudanchor.example/plans/business-uk', 6, 'Intel Xeon Gold 6338', 12288, 200,
     'NVME', 1000, NULL, 2, TRUE, 'KVM', TRUE, TRUE),

    ((SELECT id FROM provider WHERE slug = 'nordvds'), NULL, 'Nord Lite',
     'https://www.nordvds.example/plans/lite', 1, NULL, 1024, 20, 'SSD', 100, 1000, 1, TRUE, 'KVM',
     TRUE, TRUE),
    ((SELECT id FROM provider WHERE slug = 'nordvds'), NULL, 'Nord Standard',
     'https://www.nordvds.example/plans/standard', 2, NULL, 4096, 60, 'NVME', 300, 3000, 1, TRUE,
     'KVM', TRUE, TRUE),
    ((SELECT id FROM provider WHERE slug = 'nordvds'), NULL, 'Nord Advanced',
     'https://www.nordvds.example/plans/advanced', 4, NULL, 8192, 120, 'NVME', 500, NULL, 1, TRUE,
     'KVM', FALSE, TRUE),
    ((SELECT id FROM provider WHERE slug = 'nordvds'), NULL, 'Nord Ultra',
     'https://www.nordvds.example/plans/ultra', 8, NULL, 16384, 300, 'NVME', 1000, NULL, 2, TRUE,
     'KVM', TRUE, TRUE),

    ((SELECT id FROM provider WHERE slug = 'baltichost'), NULL, 'Baltic Mini',
     'https://www.baltichost.example/plans/mini', 1, NULL, 512, 10, 'HDD', 50, 500, 1, FALSE,
     'OPENVZ', TRUE, TRUE),
    ((SELECT id FROM provider WHERE slug = 'baltichost'), NULL, 'Baltic Basic',
     'https://www.baltichost.example/plans/basic', 2, NULL, 2048, 30, 'SSD', 150, 1500, 1, FALSE,
     'OPENVZ', TRUE, TRUE),
    ((SELECT id FROM provider WHERE slug = 'baltichost'), NULL, 'Baltic Flex',
     'https://www.baltichost.example/plans/flex', 4, NULL, 4096, 80, 'SSD', 300, NULL, 1, FALSE,
     'KVM', TRUE, TRUE),
    ((SELECT id FROM provider WHERE slug = 'baltichost'), NULL, 'Baltic Max',
     'https://www.baltichost.example/plans/max', 8, NULL, 8192, 160, 'NVME', 600, NULL, 2, FALSE,
     'KVM', TRUE, TRUE),

    ((SELECT id FROM provider WHERE slug = 'redlineservers'), NULL, 'RedLine Basic',
     'https://www.redlineservers.example/plans/basic', 1, 'AMD EPYC 7443P', 1024, 20, 'NVME', 200,
     2000, 1, TRUE, 'KVM', TRUE, TRUE),
    ((SELECT id FROM provider WHERE slug = 'redlineservers'), NULL, 'RedLine Pro',
     'https://www.redlineservers.example/plans/pro', 2, 'AMD EPYC 7443P', 4096, 60, 'NVME', 500,
     5000, 1, TRUE, 'KVM', TRUE, TRUE),
    ((SELECT id FROM provider WHERE slug = 'redlineservers'), NULL, 'RedLine Business',
     'https://www.redlineservers.example/plans/business', 4, 'AMD EPYC 7443P', 8192, 160, 'NVME',
     1000, NULL, 2, TRUE, 'KVM', TRUE, TRUE),
    ((SELECT id FROM provider WHERE slug = 'redlineservers'), NULL, 'RedLine Enterprise',
     'https://www.redlineservers.example/plans/enterprise', 16, 'AMD EPYC 7443P', 32768, 640, 'NVME',
     2000, NULL, 4, TRUE, 'KVM', TRUE, TRUE),

    ((SELECT id FROM provider WHERE slug = 'msklabs'), NULL, 'MSK Ekonom',
     'https://www.msklabs.example/plans/ekonom', 1, NULL, 1024, 20, 'SSD', 100, 1000, 1, FALSE,
     'KVM', TRUE, TRUE),
    ((SELECT id FROM provider WHERE slug = 'msklabs'), NULL, 'MSK Standart',
     'https://www.msklabs.example/plans/standart', 2, NULL, 4096, 60, 'NVME', 300, 3000, 1, FALSE,
     'KVM', TRUE, TRUE),
    ((SELECT id FROM provider WHERE slug = 'msklabs'), NULL, 'MSK Biznes',
     'https://www.msklabs.example/plans/biznes', 4, NULL, 8192, 120, 'NVME', 500, NULL, 1, FALSE,
     'KVM', TRUE, TRUE),
    ((SELECT id FROM provider WHERE slug = 'msklabs'), NULL, 'MSK Maksimum',
     'https://www.msklabs.example/plans/maksimum', 8, NULL, 16384, 300, 'NVME', 1000, NULL, 2, FALSE,
     'KVM', TRUE, TRUE);


INSERT INTO tariff_location (tariff_id, location_id, provider_id)
SELECT t.id, l.id, t.provider_id
FROM tariff t
JOIN location l ON l.provider_id = t.provider_id
WHERE
    (t.provider_id = (SELECT id FROM provider WHERE slug = 'hostzilla') AND t.name IN ('VPS Starter', 'VPS Basic') AND l.datacenter_name = 'AMS-1')
    OR (t.provider_id = (SELECT id FROM provider WHERE slug = 'hostzilla') AND t.name IN ('VPS Plus', 'VPS Pro'))

    OR (t.provider_id = (SELECT id FROM provider WHERE slug = 'cloudanchor') AND t.name IN ('Cloud Nano', 'Cloud Micro') AND l.datacenter_name = 'NYC-3')
    OR (t.provider_id = (SELECT id FROM provider WHERE slug = 'cloudanchor') AND t.name IN ('Cloud Standard', 'Cloud Business UK'))

    OR (t.provider_id = (SELECT id FROM provider WHERE slug = 'nordvds') AND t.name IN ('Nord Lite', 'Nord Standard') AND l.datacenter_name = 'HEL-1')
    OR (t.provider_id = (SELECT id FROM provider WHERE slug = 'nordvds') AND t.name IN ('Nord Advanced', 'Nord Ultra'))

    OR (t.provider_id = (SELECT id FROM provider WHERE slug = 'baltichost') AND t.name IN ('Baltic Mini', 'Baltic Basic') AND l.datacenter_name = 'VNO-1')
    OR (t.provider_id = (SELECT id FROM provider WHERE slug = 'baltichost') AND t.name IN ('Baltic Flex', 'Baltic Max'))

    OR (t.provider_id = (SELECT id FROM provider WHERE slug = 'redlineservers') AND t.name IN ('RedLine Basic', 'RedLine Pro') AND l.datacenter_name = 'FRA-1')
    OR (t.provider_id = (SELECT id FROM provider WHERE slug = 'redlineservers') AND t.name IN ('RedLine Business', 'RedLine Enterprise'))

    OR (t.provider_id = (SELECT id FROM provider WHERE slug = 'msklabs') AND t.name IN ('MSK Ekonom', 'MSK Standart') AND l.datacenter_name = 'MOW-1')
    OR (t.provider_id = (SELECT id FROM provider WHERE slug = 'msklabs') AND t.name IN ('MSK Biznes', 'MSK Maksimum'));

-- Stored USD amounts use illustrative seed rates (EUR 1.07, GBP 1.25, RUB 0.0108).

-- A closed row followed by the current row gives VPS Starter a price history.
INSERT INTO tariff_price
    (tariff_id, billing_period, amount, currency, amount_usd, usd_rate, usd_rate_at,
     effective_from, effective_to, data_source, recorded_by)
VALUES
    ((SELECT id FROM tariff WHERE name = 'VPS Starter' AND provider_id = (SELECT id FROM provider WHERE slug = 'hostzilla')),
     'MONTHLY', 4.49, 'EUR', 4.8043, 1.07, now() - interval '120 days',
     now() - interval '120 days', now() - interval '30 days', 'MANUAL', NULL),
    ((SELECT id FROM tariff WHERE name = 'VPS Starter' AND provider_id = (SELECT id FROM provider WHERE slug = 'hostzilla')),
     'MONTHLY', 4.99, 'EUR', 5.3393, 1.07, now() - interval '30 days',
     now() - interval '30 days', NULL, 'MANUAL', NULL);

INSERT INTO tariff_price
    (tariff_id, billing_period, amount, currency, amount_usd, usd_rate, usd_rate_at,
     effective_from, effective_to, data_source, recorded_by)
VALUES
    ((SELECT id FROM tariff WHERE name = 'VPS Basic' AND provider_id = (SELECT id FROM provider WHERE slug = 'hostzilla')),
     'MONTHLY', 8.99, 'EUR', 9.6193, 1.07, now() - interval '45 days', now() - interval '45 days', NULL, 'MANUAL', NULL),
    ((SELECT id FROM tariff WHERE name = 'VPS Plus' AND provider_id = (SELECT id FROM provider WHERE slug = 'hostzilla')),
     'MONTHLY', 16.99, 'EUR', 18.1793, 1.07, now() - interval '45 days', now() - interval '45 days', NULL, 'MANUAL', NULL),
    ((SELECT id FROM tariff WHERE name = 'VPS Pro' AND provider_id = (SELECT id FROM provider WHERE slug = 'hostzilla')),
     'MONTHLY', 32.99, 'EUR', 35.2993, 1.07, now() - interval '45 days', now() - interval '45 days', NULL, 'MANUAL', NULL),

    ((SELECT id FROM tariff WHERE name = 'Cloud Nano' AND provider_id = (SELECT id FROM provider WHERE slug = 'cloudanchor')),
     'MONTHLY', 5.00, 'USD', 5.0000, 1.00, now() - interval '20 days', now() - interval '20 days', NULL, 'MANUAL', NULL),
    ((SELECT id FROM tariff WHERE name = 'Cloud Micro' AND provider_id = (SELECT id FROM provider WHERE slug = 'cloudanchor')),
     'MONTHLY', 10.00, 'USD', 10.0000, 1.00, now() - interval '20 days', now() - interval '20 days', NULL, 'MANUAL', NULL),
    ((SELECT id FROM tariff WHERE name = 'Cloud Standard' AND provider_id = (SELECT id FROM provider WHERE slug = 'cloudanchor')),
     'MONTHLY', 24.00, 'USD', 24.0000, 1.00, now() - interval '20 days', now() - interval '20 days', NULL, 'MANUAL', NULL),
    ((SELECT id FROM tariff WHERE name = 'Cloud Business UK' AND provider_id = (SELECT id FROM provider WHERE slug = 'cloudanchor')),
     'MONTHLY', 42.50, 'GBP', 53.1250, 1.25, now() - interval '20 days', now() - interval '20 days', NULL, 'MANUAL', NULL),

    ((SELECT id FROM tariff WHERE name = 'Nord Lite' AND provider_id = (SELECT id FROM provider WHERE slug = 'nordvds')),
     'MONTHLY', 4.50, 'EUR', 4.8150, 1.07, now() - interval '60 days', now() - interval '60 days', NULL, 'MANUAL', NULL),
    ((SELECT id FROM tariff WHERE name = 'Nord Standard' AND provider_id = (SELECT id FROM provider WHERE slug = 'nordvds')),
     'MONTHLY', 11.00, 'EUR', 11.7700, 1.07, now() - interval '60 days', now() - interval '60 days', NULL, 'MANUAL', NULL),
    ((SELECT id FROM tariff WHERE name = 'Nord Advanced' AND provider_id = (SELECT id FROM provider WHERE slug = 'nordvds')),
     'MONTHLY', 21.00, 'EUR', 22.4700, 1.07, now() - interval '60 days', now() - interval '60 days', NULL, 'MANUAL', NULL),
    ((SELECT id FROM tariff WHERE name = 'Nord Ultra' AND provider_id = (SELECT id FROM provider WHERE slug = 'nordvds')),
     'MONTHLY', 39.00, 'EUR', 41.7300, 1.07, now() - interval '60 days', now() - interval '60 days', NULL, 'MANUAL', NULL),

    -- Baltic Flex has no FX rate on record.
    ((SELECT id FROM tariff WHERE name = 'Baltic Mini' AND provider_id = (SELECT id FROM provider WHERE slug = 'baltichost')),
     'MONTHLY', 2.99, 'EUR', 3.1993, 1.07, now() - interval '15 days', now() - interval '15 days', NULL, 'CRAWLER', NULL),
    ((SELECT id FROM tariff WHERE name = 'Baltic Basic' AND provider_id = (SELECT id FROM provider WHERE slug = 'baltichost')),
     'MONTHLY', 5.99, 'EUR', 6.4093, 1.07, now() - interval '15 days', now() - interval '15 days', NULL, 'CRAWLER', NULL),
    ((SELECT id FROM tariff WHERE name = 'Baltic Flex' AND provider_id = (SELECT id FROM provider WHERE slug = 'baltichost')),
     'MONTHLY', 1399.00, 'RSD', NULL, NULL, NULL, now() - interval '15 days', NULL, 'CRAWLER', NULL),
    ((SELECT id FROM tariff WHERE name = 'Baltic Max' AND provider_id = (SELECT id FROM provider WHERE slug = 'baltichost')),
     'MONTHLY', 18.99, 'EUR', 20.3193, 1.07, now() - interval '15 days', now() - interval '15 days', NULL, 'CRAWLER', NULL),

    ((SELECT id FROM tariff WHERE name = 'RedLine Basic' AND provider_id = (SELECT id FROM provider WHERE slug = 'redlineservers')),
     'MONTHLY', 5.49, 'EUR', 5.8743, 1.07, now() - interval '10 days', now() - interval '10 days', NULL, 'MANUAL', NULL),
    ((SELECT id FROM tariff WHERE name = 'RedLine Pro' AND provider_id = (SELECT id FROM provider WHERE slug = 'redlineservers')),
     'MONTHLY', 12.49, 'EUR', 13.3643, 1.07, now() - interval '10 days', now() - interval '10 days', NULL, 'MANUAL', NULL),
    ((SELECT id FROM tariff WHERE name = 'RedLine Business' AND provider_id = (SELECT id FROM provider WHERE slug = 'redlineservers')),
     'MONTHLY', 24.99, 'EUR', 26.7393, 1.07, now() - interval '10 days', now() - interval '10 days', NULL, 'MANUAL', NULL),
    ((SELECT id FROM tariff WHERE name = 'RedLine Enterprise' AND provider_id = (SELECT id FROM provider WHERE slug = 'redlineservers')),
     'MONTHLY', 89.99, 'EUR', 96.2893, 1.07, now() - interval '10 days', now() - interval '10 days', NULL, 'MANUAL', NULL),

    ((SELECT id FROM tariff WHERE name = 'MSK Ekonom' AND provider_id = (SELECT id FROM provider WHERE slug = 'msklabs')),
     'MONTHLY', 349.00, 'RUB', 3.7692, 0.0108, now() - interval '5 days', now() - interval '5 days', NULL, 'MANUAL', NULL),
    ((SELECT id FROM tariff WHERE name = 'MSK Standart' AND provider_id = (SELECT id FROM provider WHERE slug = 'msklabs')),
     'MONTHLY', 699.00, 'RUB', 7.5492, 0.0108, now() - interval '5 days', now() - interval '5 days', NULL, 'MANUAL', NULL),
    ((SELECT id FROM tariff WHERE name = 'MSK Biznes' AND provider_id = (SELECT id FROM provider WHERE slug = 'msklabs')),
     'MONTHLY', 1399.00, 'RUB', 15.1092, 0.0108, now() - interval '5 days', now() - interval '5 days', NULL, 'MANUAL', NULL),
    ((SELECT id FROM tariff WHERE name = 'MSK Maksimum' AND provider_id = (SELECT id FROM provider WHERE slug = 'msklabs')),
     'MONTHLY', 2799.00, 'RUB', 30.2292, 0.0108, now() - interval '5 days', now() - interval '5 days', NULL, 'MANUAL', NULL);

COMMIT;
