ALTER TABLE companies DROP CONSTRAINT IF EXISTS companies_ruc_key;

CREATE UNIQUE INDEX IF NOT EXISTS ux_companies_ruc_operational_profile_active
    ON companies (ruc, dv, ambiente, nombre)
    WHERE active = true;

CREATE INDEX IF NOT EXISTS idx_companies_ruc_active
    ON companies (ruc, active);
