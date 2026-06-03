-- Corrección de documento marcado incorrectamente como RECHAZADO
-- Causa: el lote 15660711722233539454 fue procesado por SIFEN con código 0260 (Aprobado),
-- pero la consulta individual por CDC devolvió un resultado erróneo antes del fix de
-- consistencia en consultarEstadoLocal (issue: consultaDE se llamaba después de 0362,
-- SIFEN devolvía latencia/código invertido → RECHAZADO falso).
-- Verificado vía /invoices/batch/15660711722233539454 que retorna estado Aprobado / 0260.

UPDATE electronic_documents
SET estado           = 'APROBADO',
    sifen_codigo     = '0260',
    sifen_mensaje    = 'Aprobado',
    processed_at     = NOW()
WHERE cdc = '01005324815001001000003222026050510000000320'
  AND estado = 'RECHAZADO';
