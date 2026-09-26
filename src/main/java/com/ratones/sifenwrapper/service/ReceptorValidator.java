package com.ratones.sifenwrapper.service;

import com.ratones.sifenwrapper.dto.request.ClienteDTO;
import com.ratones.sifenwrapper.dto.request.DataDTO;
import com.roshka.sifen.internal.util.SifenUtil;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Validaciones del grupo receptor (gDatRec, D200-D299) del Manual Técnico SIFEN v150
 * y la Nota Técnica 23, aplicadas antes de preparar/emitir el DE. Mismo patrón que
 * {@link NotaCreditoValidator} y {@link EventoValidator}: lanza
 * {@link IllegalArgumentException} (400 INVALID_REQUEST vía GlobalExceptionHandler).
 *
 * Solo rechaza combinaciones que SIFEN también rechazaría, o que hoy terminan en un
 * NullPointerException dentro de rshk-jsifenlib al serializar el XML. Los campos que
 * TgDatRec no serializa según la naturaleza del receptor (p. ej. tipoContribuyente en
 * un no contribuyente, como en el ejemplo de innominado del README) se toleran, para
 * no romper a los ERPs que ya los envían.
 */
@Component
public class ReceptorValidator {

    static final BigDecimal MONTO_MAX_INNOMINADO = new BigDecimal("60000000");

    private static final int OPERACION_B2B = 1;
    private static final int OPERACION_B2C = 2;
    private static final int OPERACION_B2G = 3;
    private static final int OPERACION_B2F = 4;

    private static final int TIPO_DOCUMENTO_AUTOFACTURA = 4;
    /** NC, ND y Nota de Remisión: no admiten receptor innominado (regla 1331, NT 23). */
    private static final Set<Integer> TIPOS_DOCUMENTO_SIN_INNOMINADO = Set.of(5, 6, 7);

    private static final int DOC_IDENTIDAD_INNOMINADO = 5;
    private static final Set<Integer> TIPOS_DOC_IDENTIDAD = Set.of(1, 2, 3, 4, 5, 6, 9);

    public void validar(DataDTO data) {
        if (data == null || data.getCliente() == null) {
            return;
        }

        ClienteDTO cliente = data.getCliente();
        int tipoOperacion = cliente.getTipoOperacion();

        if (tipoOperacion < OPERACION_B2B || tipoOperacion > OPERACION_B2F) {
            throw new IllegalArgumentException(
                    "cliente.tipoOperacion inválido (" + tipoOperacion + "): use 1=B2B, 2=B2C, 3=B2G o 4=B2F");
        }

        if (cliente.isContribuyente()) {
            validarContribuyente(cliente, tipoOperacion);
        } else {
            validarNoContribuyente(data, cliente, tipoOperacion);
        }
    }

    /** iNatRec = 1: reglas 1300 (B2F), 1302, 1304 y 1309. */
    private void validarContribuyente(ClienteDTO cliente, int tipoOperacion) {
        if (tipoOperacion == OPERACION_B2F) {
            throw new IllegalArgumentException(
                    "La operación B2F requiere un receptor no contribuyente (cliente.contribuyente=false) (regla SIFEN 1300)");
        }

        int tipoContribuyente = cliente.getTipoContribuyente();
        if (tipoContribuyente != 1 && tipoContribuyente != 2) {
            throw new IllegalArgumentException(
                    "Para receptor contribuyente debe informarse cliente.tipoContribuyente: 1=Persona Física o 2=Persona Jurídica (regla SIFEN 1302)");
        }

        String ruc = cliente.getRuc();
        if (ruc == null || ruc.isBlank()) {
            throw new IllegalArgumentException(
                    "Para receptor contribuyente debe informarse cliente.ruc con su dígito verificador (regla SIFEN 1304)");
        }

        String[] partes = ruc.trim().split("-", 2);
        String base = partes[0].replaceAll("[^0-9]", "");
        if (base.length() < 3 || base.length() > 8) {
            throw new IllegalArgumentException(
                    "RUC del receptor inválido (" + ruc + "): la parte numérica debe tener entre 3 y 8 dígitos");
        }

        String dv = partes.length > 1 ? partes[1].replaceAll("[^0-9]", "") : "";
        if (!dv.isEmpty()) {
            String dvCalculado = SifenUtil.generateDv(base);
            if (!dv.equals(dvCalculado)) {
                throw new IllegalArgumentException(
                        "El dígito verificador del RUC del receptor es incorrecto (" + ruc + "); corresponde "
                                + base + "-" + dvCalculado + " (regla SIFEN 1309)");
            }
        }
    }

    /** iNatRec = 2: reglas 1300 (B2B/B2G), 1305, 1310, 1319/1333, 1321 y 1331. */
    private void validarNoContribuyente(DataDTO data, ClienteDTO cliente, int tipoOperacion) {
        boolean autofactura = data.getTipoDocumento() == TIPO_DOCUMENTO_AUTOFACTURA;
        if (!autofactura && (tipoOperacion == OPERACION_B2B || tipoOperacion == OPERACION_B2G)) {
            throw new IllegalArgumentException(
                    "Receptor no contribuyente: cliente.tipoOperacion debe ser 2 (B2C). Si el receptor tiene RUC, "
                            + "envíe cliente.contribuyente=true con ruc y tipoContribuyente (regla SIFEN 1300)");
        }

        if (cliente.getRuc() != null && !cliente.getRuc().isBlank()) {
            throw new IllegalArgumentException("Para no contribuyente no debe informarse RUC del receptor (regla SIFEN 1305)");
        }

        if (tipoOperacion == OPERACION_B2F) {
            return;
        }

        Integer tipoDocumento = tipoDocumentoReceptor(cliente);
        if (tipoDocumento == null) {
            throw new IllegalArgumentException(
                    "Para no contribuyente debe informarse el tipo de documento de identidad del receptor "
                            + "(cliente.iTipIDRec o cliente.documentoTipo) (regla SIFEN 1310)");
        }

        if (!TIPOS_DOC_IDENTIDAD.contains(tipoDocumento)) {
            throw new IllegalArgumentException(
                    "Tipo de documento de identidad del receptor inválido (" + tipoDocumento + "): use 1, 2, 3, 4, 5, 6 o 9");
        }

        if (tipoDocumento != DOC_IDENTIDAD_INNOMINADO) {
            return;
        }

        if (tipoOperacion != OPERACION_B2C) {
            throw new IllegalArgumentException(
                    "El receptor innominado solo se permite en operaciones B2C (cliente.tipoOperacion=2) (regla SIFEN 1333)");
        }

        if (TIPOS_DOCUMENTO_SIN_INNOMINADO.contains(data.getTipoDocumento())) {
            throw new IllegalArgumentException(
                    "Las notas de crédito, notas de débito y notas de remisión no pueden emitirse a un receptor innominado (regla SIFEN 1331)");
        }

        BigDecimal total = InvoiceService.calcularTotalOperacion(data.getItems());
        if (total.compareTo(MONTO_MAX_INNOMINADO) >= 0) {
            throw new IllegalArgumentException(
                    "Factura innominada no permitida para montos >= 60.000.000 Gs (regla SIFEN 1321)");
        }
    }

    /** Misma precedencia que SifenMapper/KudeService: iTipIDRec → tipoDocumentoIdentidad → tipoDocumento → documentoTipo. */
    static Integer tipoDocumentoReceptor(ClienteDTO cliente) {
        if (cliente.getITipIDRec() != null) {
            return cliente.getITipIDRec();
        }
        if (cliente.getTipoDocumentoIdentidad() != null) {
            return cliente.getTipoDocumentoIdentidad();
        }
        if (cliente.getTipoDocumento() != null) {
            return cliente.getTipoDocumento();
        }
        return cliente.getDocumentoTipo();
    }
}
