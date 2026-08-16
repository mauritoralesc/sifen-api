-- Soporte para Notas de Crédito/Débito (grupo H, gCamDEAsoc): documento asociado,
-- moneda y monto total quedan disponibles para pre-validar localmente las reglas
-- SIFEN 2404 (estado del asociado), 2438 (misma moneda) y 2417 (suma de NC <= monto
-- de la factura) antes de enviar el lote. Se agregan para todos los tipos de
-- documento (no solo NC) porque moneda/monto_total son útiles en general.
ALTER TABLE electronic_documents
    ADD COLUMN cdc_asociado   VARCHAR(44),
    ADD COLUMN moneda         VARCHAR(3),
    ADD COLUMN monto_total    NUMERIC(23, 8),
    ADD COLUMN motivo_emision SMALLINT;

-- Consulta "NC de una factura" (regla 2417) y el endpoint GET /invoices/{cdc}/credit-notes.
CREATE INDEX idx_ed_company_cdc_asociado
    ON electronic_documents (company_id, cdc_asociado)
    WHERE cdc_asociado IS NOT NULL;

-- Backfill best-effort de moneda desde el request persistido. monto_total no se
-- backfillea (requeriría recalcular ítems); las filas legacy quedan NULL y las
-- validaciones que dependen de ese campo se omiten para ellas (ver NotaCreditoValidator).
UPDATE electronic_documents
SET moneda = COALESCE(request_data -> 'data' ->> 'moneda', 'PYG')
WHERE moneda IS NULL
  AND request_data IS NOT NULL;
