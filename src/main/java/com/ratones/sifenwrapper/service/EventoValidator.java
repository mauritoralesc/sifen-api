package com.ratones.sifenwrapper.service;

import com.ratones.sifenwrapper.dto.request.EstablecimientoDTO;
import com.ratones.sifenwrapper.dto.request.EventoRequest;
import com.ratones.sifenwrapper.dto.request.ParamsDTO;
import com.ratones.sifenwrapper.entity.Company;
import com.ratones.sifenwrapper.entity.ElectronicDocument;
import com.ratones.sifenwrapper.entity.SifenEvent;
import com.ratones.sifenwrapper.repository.ElectronicDocumentRepository;
import com.ratones.sifenwrapper.repository.SifenEventRepository;
import com.ratones.sifenwrapper.util.CdcUtil;
import com.roshka.sifen.internal.util.SifenUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

/**
 * Validaciones de negocio (capa 2) para {@link EventoRequest}, más allá del formato
 * que ya cubre Bean Validation. Lanza {@link IllegalArgumentException} (mapeada a
 * 400 INVALID_REQUEST por GlobalExceptionHandler) con mensajes en español orientados
 * al ERP que integra.
 */
@Component
@RequiredArgsConstructor
public class EventoValidator {

    /** Plazo legal fijo (no configurable) para cancelar una FE desde su aprobación. */
    public static final int HORAS_LIMITE_CANCELACION = 48;
    public static final int MOTIVO_MIN_LENGTH = 15;
    public static final int MAX_RANGO_INUTILIZACION = 1000;

    private static final Set<String> ESTADOS_CANCELABLES =
            Set.of("APROBADO", "APROBADO_CON_OBSERVACION");

    private final ElectronicDocumentRepository electronicDocumentRepository;
    private final SifenEventRepository sifenEventRepository;
    private final CompanyService companyService;

    public record EventoValidado(
            short tipoEvento,
            String cdc,
            ElectronicDocument documento,
            LocalDateTime fechaRecepcion,
            LocalDateTime fechaEmision,
            String rucReceptor,
            String dvReceptor,
            Integer numeroDesde,
            Integer numeroHasta
    ) {}

    public EventoValidado validar(EventoRequest request, Long companyId) {
        short tipo = (short) request.getTipoEvento();
        Company company = companyService.getActiveCompanyOrThrow(companyId);

        return switch (tipo) {
            case 1 -> validarCancelacion(request, companyId, company);
            case 2 -> validarInutilizacion(request, companyId, company);
            case 3 -> validarConformidad(request, companyId, company);
            case 4 -> validarDisconformidad(request, companyId, company);
            case 5 -> validarDesconocimiento(request, companyId, company);
            case 6 -> validarNotificacionRecepcion(request, companyId, company);
            default -> throw new IllegalArgumentException("Tipo de evento no soportado: " + request.getTipoEvento());
        };
    }

    // ─── Tipo 1: Cancelación ────────────────────────────────────────────────

    private EventoValidado validarCancelacion(EventoRequest request, Long companyId, Company company) {
        String cdc = requerido(request.getCdc(), "cdc");
        String motivo = requerirMotivo(request.getMotivo());

        validarCdcPerteneceAEmisor(cdc, company);

        ElectronicDocument doc = electronicDocumentRepository.findByCompanyIdAndCdc(companyId, cdc)
                .orElseThrow(() -> new IllegalArgumentException(
                        "El CDC no pertenece a esta empresa o no fue emitido a través de este wrapper. " +
                        "Una cancelación solo puede solicitarla el emisor."));

        if ("CANCELADO".equals(doc.getEstado())) {
            throw new IllegalArgumentException("El documento ya fue cancelado.");
        }
        if (!ESTADOS_CANCELABLES.contains(doc.getEstado())) {
            throw new IllegalArgumentException(
                    "Solo se puede cancelar un documento APROBADO o APROBADO_CON_OBSERVACION. " +
                    "Estado actual: " + doc.getEstado() + ".");
        }

        validarVentanaCancelacion(doc);

        boolean conConformidadPrevia = sifenEventRepository.existsByCompanyIdAndCdcAndTipoEventoAndEstado(
                companyId, cdc, (short) 3, SifenEvent.APROBADO);
        if (conConformidadPrevia) {
            throw new IllegalArgumentException(
                    "No corresponde cancelar: el receptor ya registró conformidad para este CDC " +
                    "(evento visible a través de este wrapper). Si la conformidad fue registrada por otra vía, " +
                    "SIFEN rechazará la cancelación con el código correspondiente.");
        }

        return new EventoValidado((short) 1, cdc, doc, null, null, null, null, null, null);
    }

    /**
     * Ventana de 48h calculada con el mismo reloj (zona por defecto del sistema) con el
     * que se escribe processedAt/sentAt/createdAt en BatchPollerService/InvoiceService.
     * NO cambiar a LocalDateTime.now(PY_ZONE): en un servidor en UTC eso correría el
     * cálculo 3-4 horas respecto de la referencia real usada al persistir esos campos.
     */
    private void validarVentanaCancelacion(ElectronicDocument doc) {
        LocalDateTime referencia;
        String origen;
        if (doc.getProcessedAt() != null) {
            referencia = doc.getProcessedAt();
            origen = "processedAt";
        } else if (doc.getSentAt() != null) {
            referencia = doc.getSentAt();
            origen = "sentAt";
        } else {
            referencia = doc.getCreatedAt();
            origen = "createdAt";
        }

        long horasTranscurridas = Duration.between(referencia, LocalDateTime.now()).toHours();
        if (horasTranscurridas > HORAS_LIMITE_CANCELACION) {
            throw new IllegalArgumentException(
                    "El plazo de " + HORAS_LIMITE_CANCELACION + " horas para cancelar venció (" +
                    horasTranscurridas + "h desde " + origen + " = " + referencia + "). " +
                    "El documento debe inutilizarse y reemitirse con un nuevo correlativo.");
        }
    }

    private void validarCdcPerteneceAEmisor(String cdc, Company company) {
        if (!CdcUtil.perteneceAEmisor(cdc, company.getRuc())) {
            throw new IllegalArgumentException(
                    "El CDC no pertenece a esta empresa o no fue emitido a través de este wrapper. " +
                    "Una cancelación solo puede solicitarla el emisor.");
        }
    }

    // ─── Tipo 2: Inutilización ──────────────────────────────────────────────

    private EventoValidado validarInutilizacion(EventoRequest request, Long companyId, Company company) {
        Integer timbrado = requerido(request.getTimbrado(), "timbrado");
        String establecimiento = requerido(request.getEstablecimiento(), "establecimiento");
        String puntoExpedicion = requerido(request.getPuntoExpedicion(), "puntoExpedicion");
        String numeroDesdeStr = requerido(request.getNumeroDesde(), "numeroDesde");
        String numeroHastaStr = requerido(request.getNumeroHasta(), "numeroHasta");
        Integer tipoDocumento = requerido(request.getTipoDocumento(), "tipoDocumento");
        requerirMotivo(request.getMotivo());

        int desde = Integer.parseInt(numeroDesdeStr);
        int hasta = Integer.parseInt(numeroHastaStr);
        if (hasta < desde) {
            throw new IllegalArgumentException("numeroHasta (" + hasta + ") debe ser mayor o igual a numeroDesde (" + desde + ").");
        }
        int cantidad = hasta - desde + 1;
        if (cantidad > MAX_RANGO_INUTILIZACION) {
            throw new IllegalArgumentException(
                    "El rango de inutilización tiene " + cantidad + " números; el máximo permitido por evento es " +
                    MAX_RANGO_INUTILIZACION + ". Divida el rango en varios eventos.");
        }

        ParamsDTO emisorConfig = company.getEmisorConfig();
        if (emisorConfig != null) {
            List<EstablecimientoDTO> establecimientos = emisorConfig.getEstablecimientos();
            if (establecimientos != null && !establecimientos.isEmpty()) {
                boolean coincide = establecimientos.stream()
                        .anyMatch(e -> establecimiento.equals(e.getCodigo()));
                if (!coincide) {
                    throw new IllegalArgumentException(
                            "El establecimiento " + establecimiento + " no está configurado para esta empresa.");
                }
            }
            String timbradoConfigurado = emisorConfig.getTimbradoNumero();
            if (timbradoConfigurado != null && !timbradoConfigurado.isBlank()) {
                try {
                    if (Integer.parseInt(timbradoConfigurado.trim()) != timbrado) {
                        throw new IllegalArgumentException(
                                "El timbrado " + timbrado + " no coincide con el timbrado configurado para esta empresa (" +
                                timbradoConfigurado + ").");
                    }
                } catch (NumberFormatException ignored) {
                    // timbradoNumero configurado con formato no numérico: no se puede comparar, se omite el chequeo.
                }
            }
        }

        String numeroDesdePadded = zeroPad(numeroDesdeStr, 7);
        String numeroHastaPadded = zeroPad(numeroHastaStr, 7);
        List<ElectronicDocument> enRango = electronicDocumentRepository.findEnRango(
                companyId, establecimiento, puntoExpedicion, tipoDocumento.shortValue(),
                numeroDesdePadded, numeroHastaPadded);

        List<String> aprobadosEnRango = enRango.stream()
                .filter(d -> "APROBADO".equals(d.getEstado()) || "APROBADO_CON_OBSERVACION".equals(d.getEstado()))
                .map(ElectronicDocument::getCdc)
                .toList();
        if (!aprobadosEnRango.isEmpty()) {
            throw new IllegalArgumentException(
                    "No se puede inutilizar: el rango contiene documentos ya aprobados por SIFEN (" +
                    String.join(", ", aprobadosEnRango) + "). Solo se inutilizan números no utilizados.");
        }

        return new EventoValidado((short) 2, null, null, null, null, null, null, desde, hasta);
    }

    // ─── Tipo 3: Conformidad ────────────────────────────────────────────────

    private EventoValidado validarConformidad(EventoRequest request, Long companyId, Company company) {
        String cdc = requerido(request.getCdc(), "cdc");
        validarCdcEstructural(cdc);
        requerido(request.getTipoConformidad(), "tipoConformidad");
        LocalDateTime fechaRecepcion = parsearFecha(requerido(request.getFechaRecepcion(), "fechaRecepcion"), "fechaRecepcion");
        validarNoFutura(fechaRecepcion, "fechaRecepcion");

        ElectronicDocument doc = electronicDocumentRepository.findByCompanyIdAndCdc(companyId, cdc).orElse(null);
        return new EventoValidado((short) 3, cdc, doc, fechaRecepcion, null, null, null, null, null);
    }

    // ─── Tipo 4: Disconformidad ─────────────────────────────────────────────

    private EventoValidado validarDisconformidad(EventoRequest request, Long companyId, Company company) {
        String cdc = requerido(request.getCdc(), "cdc");
        validarCdcEstructural(cdc);
        requerirMotivo(request.getMotivo());

        ElectronicDocument doc = electronicDocumentRepository.findByCompanyIdAndCdc(companyId, cdc).orElse(null);
        return new EventoValidado((short) 4, cdc, doc, null, null, null, null, null, null);
    }

    // ─── Tipo 5: Desconocimiento ────────────────────────────────────────────

    private EventoValidado validarDesconocimiento(EventoRequest request, Long companyId, Company company) {
        String cdc = requerido(request.getCdc(), "cdc");
        validarCdcEstructural(cdc);
        requerirMotivo(request.getMotivo());
        LocalDateTime fechaEmision = parsearFecha(requerido(request.getFechaEmision(), "fechaEmision"), "fechaEmision");
        LocalDateTime fechaRecepcion = parsearFecha(requerido(request.getFechaRecepcion(), "fechaRecepcion"), "fechaRecepcion");
        requerido(request.getNombreReceptor(), "nombreReceptor");
        Boolean receptorContribuyente = requerido(request.getReceptorContribuyente(), "receptorContribuyente");
        validarNoFutura(fechaRecepcion, "fechaRecepcion");
        if (fechaEmision.isAfter(fechaRecepcion)) {
            throw new IllegalArgumentException("fechaEmision no puede ser posterior a fechaRecepcion.");
        }
        if (!receptorContribuyente) {
            requerido(request.getTipoDocIdentidad(), "tipoDocIdentidad");
            requerido(request.getNumeroDocIdentidad(), "numeroDocIdentidad");
        }

        String[] rucDv = resolverRucReceptor(request, company);
        ElectronicDocument doc = electronicDocumentRepository.findByCompanyIdAndCdc(companyId, cdc).orElse(null);
        return new EventoValidado((short) 5, cdc, doc, fechaRecepcion, fechaEmision, rucDv[0], rucDv[1], null, null);
    }

    // ─── Tipo 6: Notificación de recepción ──────────────────────────────────

    private EventoValidado validarNotificacionRecepcion(EventoRequest request, Long companyId, Company company) {
        String cdc = requerido(request.getCdc(), "cdc");
        validarCdcEstructural(cdc);
        LocalDateTime fechaEmision = parsearFecha(requerido(request.getFechaEmision(), "fechaEmision"), "fechaEmision");
        LocalDateTime fechaRecepcion = parsearFecha(requerido(request.getFechaRecepcion(), "fechaRecepcion"), "fechaRecepcion");
        requerido(request.getNombreReceptor(), "nombreReceptor");
        requerido(request.getReceptorContribuyente(), "receptorContribuyente");
        requerido(request.getTotalGs(), "totalGs");
        validarNoFutura(fechaRecepcion, "fechaRecepcion");
        if (fechaEmision.isAfter(fechaRecepcion)) {
            throw new IllegalArgumentException("fechaEmision no puede ser posterior a fechaRecepcion.");
        }

        String[] rucDv = resolverRucReceptor(request, company);
        ElectronicDocument doc = electronicDocumentRepository.findByCompanyIdAndCdc(companyId, cdc).orElse(null);
        return new EventoValidado((short) 6, cdc, doc, fechaRecepcion, fechaEmision, rucDv[0], rucDv[1], null, null);
    }

    /**
     * Los eventos de receptor (3-6) actúan sobre un DE emitido por OTRA empresa: el CDC
     * legítimamente puede no existir en electronic_documents. El control de tenant real
     * es que rucReceptor (cuando el payload lo trae) coincida con el RUC de la empresa
     * autenticada; si se omite, se autocompleta desde la empresa. Esto evita que el
     * tenant A registre un evento de receptor a nombre del tenant B.
     */
    private String[] resolverRucReceptor(EventoRequest request, Company company) {
        String rucReceptor = request.getRucReceptor();
        String dvReceptor = request.getDvReceptor();
        if (rucReceptor == null || rucReceptor.isBlank()) {
            return new String[]{company.getRuc(), company.getDv()};
        }
        String rucNormalizado = soloDigitos(rucReceptor);
        String rucEmpresaNormalizado = soloDigitos(company.getRuc());
        if (!rucNormalizado.equals(rucEmpresaNormalizado)) {
            throw new IllegalArgumentException(
                    "rucReceptor debe ser el RUC de la empresa autenticada (" + company.getRuc() + "): " +
                    "un evento de receptor solo puede registrarse en nombre propio.");
        }
        return new String[]{rucReceptor, dvReceptor != null ? dvReceptor : company.getDv()};
    }

    private void validarCdcEstructural(String cdc) {
        CdcUtil.validarEstructural(cdc);
    }

    private void validarNoFutura(LocalDateTime fecha, String campo) {
        if (fecha.isAfter(LocalDateTime.now().plusMinutes(5))) {
            throw new IllegalArgumentException("El campo " + campo + " no puede ser una fecha futura.");
        }
    }

    private LocalDateTime parsearFecha(String valor, String campo) {
        try {
            return LocalDateTime.parse(valor);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(valor).atStartOfDay();
            } catch (DateTimeParseException e2) {
                throw new IllegalArgumentException(
                        "El campo " + campo + " debe tener formato ISO yyyy-MM-ddTHH:mm:ss (recibido: '" + valor + "').");
            }
        }
    }

    private String requerirMotivo(String motivo) {
        if (motivo == null || motivo.isBlank()) {
            throw new IllegalArgumentException("motivo es obligatorio para este tipo de evento.");
        }
        if (motivo.trim().length() < MOTIVO_MIN_LENGTH) {
            throw new IllegalArgumentException(
                    "motivo debe tener al menos " + MOTIVO_MIN_LENGTH + " caracteres (regla SIFEN).");
        }
        return motivo;
    }

    private <T> T requerido(T valor, String campo) {
        if (valor == null || (valor instanceof String s && s.isBlank())) {
            throw new IllegalArgumentException(campo + " es obligatorio para este tipo de evento.");
        }
        return valor;
    }

    private static String soloDigitos(String valor) {
        return valor == null ? "" : valor.replaceAll("[^0-9]", "");
    }

    private static String zeroPad(String valor, int length) {
        return SifenUtil.leftPad(valor, '0', length);
    }
}
