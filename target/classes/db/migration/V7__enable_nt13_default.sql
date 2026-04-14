-- Corrección: SIFEN test actualmente requiere dBasExe en gCamIVA (NT13).
-- Rehabilitar NT13 por defecto y para empresas existentes.
UPDATE companies SET habilitar_nt13 = true WHERE habilitar_nt13 = false;
ALTER TABLE companies ALTER COLUMN habilitar_nt13 SET DEFAULT true;
