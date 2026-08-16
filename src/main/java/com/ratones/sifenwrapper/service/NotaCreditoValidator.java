package com.ratones.sifenwrapper.service;

import com.ratones.sifenwrapper.dto.request.DataDTO;
import com.ratones.sifenwrapper.dto.request.DocumentoAsociadoDTO;
import com.ratones.sifenwrapper.dto.request.EmitirFacturaRequest;
import com.ratones.sifenwrapper.entity.Company;
import com.ratones.sifenwrapper.entity.ElectronicDocument;
import com.ratones.sifenwrapper.entity.SifenEvent;
import com.ratones.sifenwrapper.repository.ElectronicDocumentRepository;
import com.ratones.sifenwrapper.repository.SifenEventRepository;
import com.ratones.sifenwrapper.util.CdcUtil;
import com.roshka.sifen.core.types.TiMotEmi;
import com.roshka.sifen.core.types.TiTIpoDoc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;

/**
 * Validaciones de negocio (capa 2) para Notas de Crédito/Débito (tipoDocumento 5/6),
 * más allá del formato que ya cubre Bean Validation. Mismo patrón que
 * {@link EventoValidator}: lanza {@link IllegalArgumentException} (400 INVALID_REQUEST
 * vía GlobalExceptionHandler) con mensajes en español orientados al ERP que integra.
 *
 * Pre-valida localmente lo que SIFEN valida como reglas 2404/2417/2438/2415 del
 * Manual Técnico v150, pero solo cuando el documento asociado existe en la base local:
 * si el CDC referenciado es de una factura histórica o emitida fuera del wrapper, se
 * omite esa parte y SIFEN es la autoridad final (ver docs/notas-de-credito.md).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotaCreditoValidator {

    private static final Set<String> ESTADOS_ASOCIABLES = Set.of("APROBADO", "APROBADO_CON_OBSERVACION");

    /**
     * Tipos de DE (prefijo del CDC) a los que una NC/ND puede asociarse por CDC:
     * 1=Factura Electrónica, 4=Autofactura Electrónica. Si un caso real requiere
     * asociar a otro tipo, relajar esta constante.
     */
    private static final Set<Short> TIPOS_ASOCIABLES_ELECTRONICO = Set.of((short) 1, (short) 4);

    private final ElectronicDocumentRepository electronicDocumentRepository;
    private final SifenEventRepository sifenEventRepository;
    private final CompanyService companyService;

    public record NotaCreditoValidada(
            short motivo,
            DocumentoAsociadoDTO asociado,
            ElectronicDocument documentoAsociadoLocal,
            boolean validadoLocalmente
    ) {}

    /** Aplica a tipoDocumento 5 (Nota de Crédito) y 6 (Nota de Débito): comparten los grupos E5/H. */
    public NotaCreditoValidada validar(EmitirFacturaRequest request, Long companyId) {
        DataDTO data = request.getData();
        Company company = companyService.getActiveCompanyOrThrow(companyId);

        short motivo = validarMotivo(data);
        DocumentoAsociadoDTO asociado = validarGrupoH(data);

        if (asociado.getTipoDocumentoAsociado() == 1) {
            return validarAsociacionElectronica(data, companyId, company, motivo, asociado);
        }
        validarAsociacionImpresa(asociado);
        return new NotaCreditoValidada(motivo, asociado, null, false);
    }

    // ─── Sección A: motivo (E401) ──────────────────────────────────────────

    private short validarMotivo(DataDTO data) {
        if (data.getNotaCreditoDebito() == null || data.getNotaCreditoDebito().getMotivo() == 0) {
            throw new IllegalArgumentException(
                    "notaCreditoDebito.motivo es obligatorio para Nota de Crédito/Débito (E401). " +
                    "Valores: 1=Devolución y ajuste de precios, 2=Devolución, 3=Descuento, 4=Bonificación, " +
                    "5=Crédito incobrable, 6=Recupero de costo, 7=Recupero de gasto, 8=Ajuste de precio.");
        }
        int motivo = data.getNotaCreditoDebito().getMotivo();
        if (TiMotEmi.getByVal((short) motivo) == null) {
            throw new IllegalArgumentException(
                    "notaCreditoDebito.motivo inválido: " + motivo + ". Valores permitidos: 1..8 (E401).");
        }
        return (short) motivo;
    }

    // ─── Sección B: presencia y forma del grupo H ──────────────────────────

    private DocumentoAsociadoDTO validarGrupoH(DataDTO data) {
        DocumentoAsociadoDTO asociado = data.getDocumentoAsociado();
        if (asociado == null) {
            throw new IllegalArgumentException(
                    "documentoAsociado es obligatorio para Nota de Crédito: una NC ajusta exactamente un " +
                    "documento (grupo H, validación SIFEN 2415). Envíe documentoAsociado.tipoDocumentoAsociado=1 " +
                    "con cdcAsociado, o =2 con los datos del comprobante impreso.");
        }
        Integer tipo = asociado.getTipoDocumentoAsociado();
        if (tipo == null || (tipo != 1 && tipo != 2)) {
            throw new IllegalArgumentException(
                    "documentoAsociado.tipoDocumentoAsociado debe ser 1 (documento electrónico, por CDC) o " +
                    "2 (documento impreso). La asociación por constancia (3) no aplica a Notas de Crédito.");
        }

        if (tipo == 1 && tieneAlgunCampoImpreso(asociado)) {
            throw new IllegalArgumentException(
                    "Para tipoDocumentoAsociado=1 solo debe informarse cdcAsociado; los campos de documento " +
                    "impreso (timbradoAsociado, establecimientoAsociado, puntoAsociado, numeroAsociado, " +
                    "tipoComprobanteAsociado, fechaEmisionAsociado) no corresponden.");
        }
        if (tipo == 2 && notBlank(asociado.getCdcAsociado())) {
            throw new IllegalArgumentException(
                    "Para tipoDocumentoAsociado=2 no debe informarse cdcAsociado; use los campos del documento impreso.");
        }
        return asociado;
    }

    private boolean tieneAlgunCampoImpreso(DocumentoAsociadoDTO a) {
        return notBlank(a.getTimbradoAsociado()) || notBlank(a.getEstablecimientoAsociado())
                || notBlank(a.getPuntoAsociado()) || notBlank(a.getNumeroAsociado())
                || a.getTipoComprobanteAsociado() != null || notBlank(a.getFechaEmisionAsociado());
    }

    // ─── Sección C: asociación electrónica (tipo 1) ────────────────────────

    private NotaCreditoValidada validarAsociacionElectronica(
            DataDTO data, Long companyId, Company company, short motivo, DocumentoAsociadoDTO asociado) {

        String cdc = requerido(asociado.getCdcAsociado(), "documentoAsociado.cdcAsociado");
        CdcUtil.validarEstructural(cdc);

        short tipoDocDelCdc = CdcUtil.tipoDocumentoDe(cdc);
        if (!TIPOS_ASOCIABLES_ELECTRONICO.contains(tipoDocDelCdc)) {
            throw new IllegalArgumentException(
                    "El CDC asociado corresponde a un documento tipo " + tipoDocDelCdc + "; una Nota de Crédito " +
                    "solo puede asociarse a una Factura Electrónica (01) o Autofactura (04).");
        }
        if (!CdcUtil.perteneceAEmisor(cdc, company.getRuc())) {
            throw new IllegalArgumentException(
                    "El CDC asociado no pertenece al RUC de la empresa autenticada (" + company.getRuc() + "): " +
                    "una NC solo puede ajustar documentos emitidos por el propio emisor.");
        }

        Optional<ElectronicDocument> docOpt = electronicDocumentRepository.findByCompanyIdAndCdc(companyId, cdc);
        if (docOpt.isEmpty()) {
            log.warn("[NC] CDC asociado {} no existe localmente (empresa {}); se omiten las validaciones " +
                    "2404/2438/2417 locales, SIFEN las aplicará al procesar el lote.", cdc, companyId);
            return new NotaCreditoValidada(motivo, asociado, null, false);
        }

        ElectronicDocument doc = docOpt.get();
        validarEstadoAsociable(doc);
        validarMoneda(data, doc);
        validarMontoAcumulado(data, companyId, cdc, doc);

        return new NotaCreditoValidada(motivo, asociado, doc, true);
    }

    private void validarEstadoAsociable(ElectronicDocument doc) {
        if (!ESTADOS_ASOCIABLES.contains(doc.getEstado())) {
            throw new IllegalArgumentException(
                    "No se puede asociar la NC: el documento " + doc.getCdc() + " no está aprobado por SIFEN " +
                    "(estado actual: " + doc.getEstado() + ", regla SIFEN 2404). Espere la aprobación o corrija el CDC.");
        }
        boolean cancelado = sifenEventRepository.existsByCompanyIdAndCdcAndTipoEventoAndEstado(
                doc.getCompanyId(), doc.getCdc(), (short) 1, SifenEvent.APROBADO);
        if (cancelado) {
            throw new IllegalArgumentException(
                    "No se puede asociar la NC: el documento " + doc.getCdc() + " fue cancelado (regla SIFEN 2404).");
        }
    }

    private void validarMoneda(DataDTO data, ElectronicDocument doc) {
        String monedaNC = notBlank(data.getMoneda()) ? data.getMoneda() : "PYG";
        String monedaAsociado = doc.getMoneda();
        if (!notBlank(monedaAsociado)) {
            log.warn("[NC] Moneda del documento asociado {} no determinable localmente; se omite la " +
                    "validación 2438 (SIFEN la aplicará).", doc.getCdc());
            return;
        }
        if (!monedaNC.equalsIgnoreCase(monedaAsociado)) {
            throw new IllegalArgumentException(
                    "La moneda de la NC (" + monedaNC + ") debe coincidir con la del documento asociado (" +
                    monedaAsociado + ") (regla SIFEN 2438).");
        }
    }

    private void validarMontoAcumulado(DataDTO data, Long companyId, String cdc, ElectronicDocument doc) {
        if (doc.getMontoTotal() == null) {
            log.warn("[NC] Monto total del documento asociado {} no disponible localmente (registro anterior " +
                    "a la migración V17); se omite la validación 2417 (SIFEN la aplicará).", cdc);
            return;
        }
        BigDecimal previas = electronicDocumentRepository.sumMontoAprobadoNotasCredito(companyId, cdc);
        BigDecimal montoNC = InvoiceService.calcularTotalOperacion(data.getItems());
        BigDecimal acumulado = previas.add(montoNC);
        if (acumulado.compareTo(doc.getMontoTotal()) > 0) {
            throw new IllegalArgumentException(
                    "La suma de las Notas de Crédito asociadas a la factura " + cdc + " (" + previas +
                    " ya emitidas + " + montoNC + " de esta NC = " + acumulado +
                    ") supera el monto total de la factura (" + doc.getMontoTotal() + ") (regla SIFEN 2417).");
        }
    }

    // ─── Sección D: asociación impresa (tipo 2) ────────────────────────────

    private void validarAsociacionImpresa(DocumentoAsociadoDTO asociado) {
        String timbrado = requerido(asociado.getTimbradoAsociado(), "documentoAsociado.timbradoAsociado");
        if (!timbrado.matches("\\d{1,8}")) {
            throw new IllegalArgumentException(
                    "documentoAsociado.timbradoAsociado debe ser el número de timbrado de hasta 8 dígitos " +
                    "del comprobante impreso (H005).");
        }
        requerirNumerico(asociado.getEstablecimientoAsociado(), "documentoAsociado.establecimientoAsociado");
        requerirNumerico(asociado.getPuntoAsociado(), "documentoAsociado.puntoAsociado");
        requerirNumerico(asociado.getNumeroAsociado(), "documentoAsociado.numeroAsociado");

        Integer tipoComprobante = requerido(asociado.getTipoComprobanteAsociado(), "documentoAsociado.tipoComprobanteAsociado");
        if (TiTIpoDoc.getByVal(tipoComprobante.shortValue()) == null) {
            throw new IllegalArgumentException(
                    "documentoAsociado.tipoComprobanteAsociado inválido. Valores: 1=Factura, 2=Nota de crédito, " +
                    "3=Nota de débito, 4=Nota de remisión, 5=Comprobante de retención (H009).");
        }

        String fechaStr = requerido(asociado.getFechaEmisionAsociado(), "documentoAsociado.fechaEmisionAsociado");
        LocalDate fecha;
        try {
            fecha = LocalDate.parse(fechaStr);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "documentoAsociado.fechaEmisionAsociado debe tener formato yyyy-MM-dd (recibido: '" + fechaStr + "').");
        }
        if (fecha.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("documentoAsociado.fechaEmisionAsociado no puede ser una fecha futura.");
        }
    }

    private void requerirNumerico(String valor, String campo) {
        String v = requerido(valor, campo);
        if (!v.matches("\\d+")) {
            throw new IllegalArgumentException(campo + " debe ser numérico (recibido: '" + v + "').");
        }
    }

    private <T> T requerido(T valor, String campo) {
        if (valor == null || (valor instanceof String s && s.isBlank())) {
            throw new IllegalArgumentException(campo + " es obligatorio para este tipo de documentoAsociado.");
        }
        return valor;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
