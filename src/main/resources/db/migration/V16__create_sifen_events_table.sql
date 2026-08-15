-- Secuencia para rEve/@Id (tdIdEve): numérico, único, máximo 10 dígitos.
-- MAXVALUE hace que el límite de SIFEN sea un invariante de la base, no un if() en Java.
-- Es no transaccional: los rollbacks dejan huecos. SIFEN exige unicidad, no contigüidad.
CREATE SEQUENCE sifen_event_id_seq
    AS BIGINT START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 9999999999 NO CYCLE;

CREATE TABLE sifen_events (
    id                      BIGSERIAL PRIMARY KEY,
    company_id              BIGINT       NOT NULL REFERENCES companies(id),
    -- NULL para eventos de receptor (3-6): el DE fue emitido por otra empresa
    -- y nunca existe en electronic_documents. Ver docs/eventos-sifen.md.
    electronic_document_id  BIGINT       REFERENCES electronic_documents(id),

    tipo_evento             SMALLINT     NOT NULL,
    evento_id               VARCHAR(10)  NOT NULL,
    cdc                     VARCHAR(44),
    motivo                  TEXT,

    -- Rango de inutilización (tipo 2); NULL en el resto de los tipos.
    timbrado                INTEGER,
    establecimiento         VARCHAR(3),
    punto_expedicion        VARCHAR(3),
    numero_desde            VARCHAR(7),
    numero_hasta            VARCHAR(7),
    tipo_documento          SMALLINT,

    estado                  VARCHAR(20)  NOT NULL DEFAULT 'ENVIADO',
    sifen_codigo            VARCHAR(10),
    sifen_mensaje           TEXT,
    protocolo_autorizacion  VARCHAR(30),
    fecha_proceso           TIMESTAMP,

    request_data            JSONB,
    response_data           JSONB,
    xml_enviado             TEXT,
    xml_respuesta           TEXT,

    created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    sent_at                 TIMESTAMP,
    processed_at            TIMESTAMP,
    updated_at              TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_se_tipo_evento CHECK (tipo_evento BETWEEN 1 AND 6),
    CONSTRAINT ck_se_evento_id_numerico CHECK (evento_id ~ '^[0-9]{1,10}$'),
    CONSTRAINT ck_se_estado CHECK (estado IN (
        'ENVIADO','APROBADO','RECHAZADO','ERROR','ERROR_CONEXION','INDETERMINADO')),
    CONSTRAINT ck_se_cdc_por_tipo CHECK (
        (tipo_evento = 2 AND cdc IS NULL) OR (tipo_evento <> 2 AND cdc IS NOT NULL)),
    CONSTRAINT ck_se_rango_inutilizacion CHECK (
        tipo_evento <> 2 OR (timbrado IS NOT NULL AND establecimiento IS NOT NULL
                         AND punto_expedicion IS NOT NULL AND numero_desde IS NOT NULL
                         AND numero_hasta IS NOT NULL AND tipo_documento IS NOT NULL))
);

CREATE UNIQUE INDEX uq_se_company_evento_id ON sifen_events (company_id, evento_id);
CREATE INDEX idx_se_company_cdc            ON sifen_events (company_id, cdc);
CREATE INDEX idx_se_company_estado_created ON sifen_events (company_id, estado, created_at DESC);
CREATE INDEX idx_se_company_tipo_created   ON sifen_events (company_id, tipo_evento, created_at DESC);
CREATE INDEX idx_se_indeterminados         ON sifen_events (sent_at) WHERE estado = 'INDETERMINADO';

-- Guard anti-duplicado a nivel base: es la única capa a prueba de carreras
-- (un doble-click concurrente pasa cualquier chequeo SELECT-then-INSERT).
-- APROBADO está incluido a propósito: un evento fiscal aprobado nunca se repite.
CREATE UNIQUE INDEX uq_se_evento_vigente_cdc
    ON sifen_events (company_id, tipo_evento, cdc)
    WHERE cdc IS NOT NULL
      AND estado IN ('ENVIADO', 'INDETERMINADO', 'APROBADO');

CREATE UNIQUE INDEX uq_se_evento_vigente_inutilizacion
    ON sifen_events (company_id, timbrado, establecimiento, punto_expedicion, numero_desde, numero_hasta)
    WHERE tipo_evento = 2
      AND estado IN ('ENVIADO', 'INDETERMINADO', 'APROBADO');
