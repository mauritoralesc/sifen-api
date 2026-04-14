CREATE TABLE audit_log (
    id              BIGSERIAL       PRIMARY KEY,
    company_id      BIGINT          REFERENCES companies(id),
    user_id         BIGINT          REFERENCES users(id),
    action          VARCHAR(50)     NOT NULL,
    resource        VARCHAR(200),
    detail          TEXT,
    ip              VARCHAR(45),
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_company_id ON audit_log(company_id);
CREATE INDEX idx_audit_log_created_at ON audit_log(created_at);
