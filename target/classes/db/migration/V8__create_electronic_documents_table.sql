CREATE TABLE electronic_documents (
    id              BIGSERIAL PRIMARY KEY,
    company_id      BIGINT NOT NULL REFERENCES companies(id),
    cdc             VARCHAR(44) NOT NULL UNIQUE,
    tipo_documento  SMALLINT NOT NULL,
    numero          VARCHAR(7) NOT NULL,
    establecimiento VARCHAR(3) NOT NULL,
    punto           VARCHAR(3) NOT NULL,
    estado          VARCHAR(30) NOT NULL DEFAULT 'PREPARADO',
    xml_firmado     TEXT NOT NULL,
    qr_url          VARCHAR(512),
    nro_lote        VARCHAR(30),
    sifen_codigo    VARCHAR(10),
    sifen_mensaje   TEXT,
    request_data    JSONB,
    response_data   JSONB,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    sent_at         TIMESTAMP,
    processed_at    TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ed_company_estado ON electronic_documents(company_id, estado);
CREATE INDEX idx_ed_nro_lote ON electronic_documents(nro_lote);
CREATE INDEX idx_ed_cdc ON electronic_documents(cdc);
CREATE INDEX idx_ed_estado_sent ON electronic_documents(estado, sent_at);
