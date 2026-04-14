DROP INDEX IF EXISTS ux_companies_ruc_operational_profile_active;

CREATE INDEX IF NOT EXISTS idx_companies_ruc_dv_ambiente_active
    ON companies (ruc, dv, ambiente, active);
