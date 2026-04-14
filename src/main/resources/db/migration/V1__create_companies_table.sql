CREATE TABLE companies (
    id              BIGSERIAL       PRIMARY KEY,
    nombre          VARCHAR(200)    NOT NULL,
    ruc             VARCHAR(20)     NOT NULL UNIQUE,
    dv              VARCHAR(2)      NOT NULL,
    ambiente        VARCHAR(4)      NOT NULL DEFAULT 'DEV',
    certificado_pfx BYTEA,
    certificado_password VARCHAR(500),
    csc_id          VARCHAR(10),
    csc_valor       VARCHAR(500),
    habilitar_nt13  BOOLEAN         NOT NULL DEFAULT TRUE,
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);
