-- Deshabilitar NT13 por defecto: el campo dBasExe que agrega no está
-- en el XSD v150 de SIFEN y causa error 0160 (XML Mal Formado).
UPDATE companies SET habilitar_nt13 = false WHERE habilitar_nt13 = true;
ALTER TABLE companies ALTER COLUMN habilitar_nt13 SET DEFAULT false;
