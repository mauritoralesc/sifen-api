-- El CDC es único dentro de una empresa, pero dos empresas distintas (ej. DEV y PROD)
-- con el mismo RUC pueden generar documentos con el mismo CDC.
-- Se reemplaza el UNIQUE global por un UNIQUE compuesto (company_id, cdc).
ALTER TABLE electronic_documents DROP CONSTRAINT electronic_documents_cdc_key;
ALTER TABLE electronic_documents ADD CONSTRAINT uq_ed_company_cdc UNIQUE (company_id, cdc);
