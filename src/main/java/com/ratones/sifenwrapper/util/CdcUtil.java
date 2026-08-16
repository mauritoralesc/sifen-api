package com.ratones.sifenwrapper.util;

import com.roshka.sifen.internal.util.SifenUtil;
import lombok.experimental.UtilityClass;

/**
 * Helpers de CDC compartidos entre validadores de negocio (eventos, notas de
 * crédito/débito). El CDC de 44 dígitos codifica, entre otros campos, el tipo
 * de documento (posiciones 0-1) y el RUC del emisor sin DV (posiciones 2-9).
 */
@UtilityClass
public class CdcUtil {

    /**
     * Verifica longitud, que sea numérico y que el dígito verificador (última
     * posición) sea consistente con las primeras 43. Mismo mensaje que usaba
     * EventoValidator.validarCdcEstructural para no romper integraciones existentes.
     */
    public static void validarEstructural(String cdc) {
        if (cdc == null || cdc.length() != 44 || !cdc.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(
                    "El CDC debe tener 44 dígitos numéricos (recibido: '" + cdc + "').");
        }
        String base = cdc.substring(0, 43);
        String dvEsperado = SifenUtil.generateDv(base);
        String dvRecibido = cdc.substring(43);
        if (!dvEsperado.equals(dvRecibido)) {
            throw new IllegalArgumentException(
                    "El dígito verificador del CDC es inválido (recibido " + dvRecibido +
                    ", esperado " + dvEsperado + "). Verifique que el CDC esté completo y sin errores de transcripción.");
        }
    }

    /** Tipo de documento electrónico codificado en las posiciones 0-1 del CDC (01=FE, 04=AFE, 05=NC, ...). */
    public static short tipoDocumentoDe(String cdc) {
        return Short.parseShort(cdc.substring(0, 2));
    }

    /** RUC del emisor (sin DV), zero-padded a 8 dígitos, codificado en las posiciones 2-9 del CDC. */
    public static String rucDe(String cdc) {
        return cdc.substring(2, 10);
    }

    /** true si el RUC codificado en el CDC coincide con el RUC de la empresa (normalizado y zero-padded a 8). */
    public static boolean perteneceAEmisor(String cdc, String rucEmpresa) {
        String rucEsperado = SifenUtil.leftPad(soloDigitos(rucEmpresa), '0', 8);
        return rucDe(cdc).equals(rucEsperado);
    }

    private static String soloDigitos(String valor) {
        return valor == null ? "" : valor.replaceAll("[^0-9]", "");
    }
}
