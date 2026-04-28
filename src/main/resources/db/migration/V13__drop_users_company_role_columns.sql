ALTER TABLE users
    DROP COLUMN company_id,
    DROP COLUMN role;

DROP INDEX IF EXISTS idx_users_company_id;
