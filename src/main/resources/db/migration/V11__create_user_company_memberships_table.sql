CREATE TABLE user_company_memberships (
    id          BIGSERIAL       PRIMARY KEY,
    user_id     BIGINT          NOT NULL REFERENCES users(id),
    company_id  BIGINT          NOT NULL REFERENCES companies(id),
    role        VARCHAR(20)     NOT NULL DEFAULT 'USER',
    active      BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP       NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_company UNIQUE (user_id, company_id)
);

CREATE INDEX idx_memberships_user_id    ON user_company_memberships(user_id);
CREATE INDEX idx_memberships_company_id ON user_company_memberships(company_id);
