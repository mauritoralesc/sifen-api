package com.ratones.sifenwrapper.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratones.sifenwrapper.dto.request.EventoRequest;
import com.ratones.sifenwrapper.dto.response.*;
import com.ratones.sifenwrapper.entity.SifenEvent;
import com.ratones.sifenwrapper.exception.EventoDuplicadoException;
import com.ratones.sifenwrapper.exception.SifenServiceException;
import com.ratones.sifenwrapper.mapper.SifenMapper;
import com.ratones.sifenwrapper.repository.SifenEventRepository;
import com.ratones.sifenwrapper.security.TenantContext;
import com.ratones.sifenwrapper.service.EventoValidator.EventoValidado;
import com.roshka.sifen.Sifen;
import com.roshka.sifen.core.SifenConfig;
import com.roshka.sifen.core.beans.EventosDE;
import com.roshka.sifen.core.beans.response.RespuestaRecepcionEvento;
import com.roshka.sifen.core.exceptions.SifenException;
import com.roshka.sifen.core.fields.request.event.TgGroupTiEvt;
import com.roshka.sifen.core.fields.request.event.TrGesEve;
import com.roshka.sifen.core.fields.response.TgResProc;
import com.roshka.sifen.core.fields.response.event.TgResProcEVe;
import com.roshka.sifen.internal.util.SifenUtil;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Dueño del subdominio de eventos SIFEN: valida, persiste, llama a
 * {@code Sifen.recepcionEvento} y clasifica el resultado. Deliberadamente
 * SIN {@code @Transactional} — una transacción que abarcara la llamada
 * síncrona a SIFEN (hasta ~45s de read-timeout) retendría una conexión de
 * HikariCP mientras además sostiene {@code SIFEN_GLOBAL_LOCK}, que serializa
 * a todos los tenants. Los límites transaccionales viven en {@link SifenEventRecorder}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventoService {

    private static final ZoneId PY_ZONE = ZoneId.of("America/Asuncion");
    private static final long VENTANA_DUPLICADO_SEGUNDOS = 60;
    private static final Set<String> ESTADOS_VIGENTES =
            Set.of(SifenEvent.ENVIADO, SifenEvent.INDETERMINADO, SifenEvent.APROBADO);

    private final SifenConfigFactory sifenConfigFactory;
    private final EventoValidator eventoValidator;
    private final SifenEventRecorder sifenEventRecorder;
    private final SifenEventRepository sifenEventRepository;
    private final InvoiceService invoiceService;
    private final ObjectMapper objectMapper;

    public RecepcionEventoResponse enviarEvento(EventoRequest request) {
        Long companyId = requireTenant();
        log.info("Enviando evento tipo {} para CDC/rango: {}", request.getTipoEvento(), request.getCdc());

        EventoValidado validado = eventoValidator.validar(request, companyId);
        TgGroupTiEvt grupo = EventoBeanFactory.build(request, validado);

        verificarDuplicado(companyId, request, validado);

        String eventoId = String.valueOf(sifenEventRepository.nextEventoId());
        SifenEvent evento = sifenEventRecorder.registrarEnvio(companyId, request, validado, eventoId);

        SifenConfig sifenConfig = sifenConfigFactory.getConfigForCurrentTenant();

        TrGesEve gesEve = new TrGesEve();
        gesEve.setId(eventoId);
        // Margen de seguridad hacia atrás para evitar rechazo SIFEN 1004 (firma
        // adelantada) ante desfases de reloj del servidor.
        gesEve.setdFecFirma(LocalDateTime.now(PY_ZONE).minusSeconds(SifenMapper.FIRMA_BACKDATE_SECONDS));
        gesEve.setgGroupTiEvt(grupo);

        EventosDE eventosDE = new EventosDE();
        eventosDE.setrGesEveList(List.of(gesEve));

        try {
            RespuestaRecepcionEvento respuesta = sifenConfigFactory.withSifenConfig(sifenConfig,
                    () -> Sifen.recepcionEvento(eventosDE));
            return procesarRespuesta(evento, respuesta);
        } catch (SifenException e) {
            log.error("Error SIFEN al enviar evento tipo {} (evento id={}): {}",
                    validado.tipoEvento(), evento.getId(), e.getMessage(), e);
            if (esTimeoutOReadFailure(e)) {
                sifenEventRecorder.registrarIndeterminado(evento.getId(),
                        "Error de E/S al llamar a SIFEN (posible timeout): " + e.getMessage());
                throw new SifenServiceException(
                        "SIFEN no respondió a tiempo. El evento quedó en estado INDETERMINADO (id=" +
                        evento.getId() + "): puede o no haber sido procesado. NO reenvíe — verifique con " +
                        "POST /invoices/events/" + evento.getId() + "/reconcile o en el portal e-Kuatia. " +
                        "Un reenvío idéntico puede bloquear el RUC entre 10 y 60 minutos.");
            }
            sifenEventRecorder.registrarError(evento.getId(), SifenEvent.ERROR, e.getMessage());
            throw new SifenServiceException("Error al enviar evento", e);
        } catch (RuntimeException e) {
            sifenEventRecorder.registrarError(evento.getId(), SifenEvent.ERROR, e.getMessage());
            throw e;
        }
    }

    public List<EventoResumenDTO> listarPorCdc(String cdc) {
        Long companyId = requireTenant();
        return sifenEventRepository.findByCompanyIdAndCdcOrderByCreatedAtDesc(companyId, cdc).stream()
                .map(this::toResumen)
                .toList();
    }

    public PageResponse<EventoResumenDTO> listar(Short tipoEvento, String estado,
                                                  LocalDateTime desde, LocalDateTime hasta, Pageable pageable) {
        Long companyId = requireTenant();
        Specification<SifenEvent> spec = construirFiltro(companyId, tipoEvento, estado, desde, hasta);
        Page<SifenEvent> page = sifenEventRepository.findAll(spec, pageable);
        List<EventoResumenDTO> content = page.getContent().stream().map(this::toResumen).toList();
        return PageResponse.of(page, content);
    }

    /**
     * Filtros opcionales como predicados construidos solo cuando el valor no es nulo —
     * a diferencia de un JPQL con "(:param IS NULL OR ...)", esto nunca envía a Postgres
     * un bind parameter cuyo único uso es una comparación IS NULL sin más contexto de
     * tipo, que el driver JDBC de Postgres no puede inferir ("could not determine data
     * type of parameter").
     */
    private Specification<SifenEvent> construirFiltro(Long companyId, Short tipoEvento, String estado,
                                                        LocalDateTime desde, LocalDateTime hasta) {
        return (root, query, cb) -> {
            List<Predicate> predicados = new java.util.ArrayList<>();
            predicados.add(cb.equal(root.get("companyId"), companyId));
            if (tipoEvento != null) {
                predicados.add(cb.equal(root.get("tipoEvento"), tipoEvento));
            }
            if (estado != null) {
                predicados.add(cb.equal(root.get("estado"), estado));
            }
            if (desde != null) {
                predicados.add(cb.greaterThanOrEqualTo(root.get("createdAt"), desde));
            }
            if (hasta != null) {
                predicados.add(cb.lessThanOrEqualTo(root.get("createdAt"), hasta));
            }
            return cb.and(predicados.toArray(new Predicate[0]));
        };
    }

    /**
     * Reconciliación bajo demanda de un evento INDETERMINADO (o cualquier otro), nunca
     * automática. No existe un WS de consulta de eventos en SIFEN: solo el tipo 1
     * (cancelación) deja rastro consultable vía consultaDE, y únicamente el código 0263
     * cuenta como confirmación positiva. Cualquier otra cosa —incluido un 0422— NO
     * concluye que el evento no se procesó (ver docs/erp-polling-rechazado-falso.md:
     * consultaDE puede devolver falsos negativos mientras SIFEN converge). Nunca reenvía.
     */
    public EventoResumenDTO reconciliar(Long eventoId) {
        Long companyId = requireTenant();
        SifenEvent evento = sifenEventRepository.findByIdAndCompanyId(eventoId, companyId)
                .orElseThrow(() -> new IllegalArgumentException("Evento no encontrado: " + eventoId));

        if (evento.getTipoEvento() == 1 && evento.getCdc() != null) {
            try {
                ConsultaDEResponse consulta = invoiceService.consultarDE(evento.getCdc());
                if ("0263".equals(consulta.getCodigoEstado())) {
                    List<MensajeSifenDTO> mensajes = List.of(MensajeSifenDTO.builder()
                            .codigo("0263")
                            .descripcion("Cancelado (confirmado por reconciliación vía consultaDE)")
                            .build());
                    SifenEvent actualizado = sifenEventRecorder.registrarResultado(
                            evento.getId(), SifenEvent.APROBADO, "0263",
                            "Confirmado por consultaDE en reconciliación: documento cancelado.",
                            consulta.getProtocoloAutorizacion(), consulta.getFechaProcesamiento(),
                            mensajes, null, null);
                    return toResumen(actualizado);
                }
                return toResumenConNota(evento, "NO_CONCLUYENTE",
                        "consultaDE devolvió " + consulta.getCodigoEstado() + " (" + consulta.getDescripcionEstado() +
                        "). Esto NO confirma ni descarta la cancelación: consultaDE puede devolver 0422 mientras " +
                        "SIFEN converge. Verifique en el portal e-Kuatia antes de reintentar.");
            } catch (SifenServiceException e) {
                return toResumenConNota(evento, "NO_CONCLUYENTE",
                        "No se pudo consultar el DE para reconciliar: " + e.getMessage());
            }
        }

        return toResumenConNota(evento, "NO_CONCLUYENTE",
                "SIFEN no expone un servicio de consulta de eventos para el tipo " + evento.getTipoEvento() +
                ". Verifique en el portal e-Kuatia; un reintento idéntico puede bloquear el RUC entre 10 y 60 minutos.");
    }

    // ─── Duplicados ─────────────────────────────────────────────────────────

    /**
     * Chequeo de app para dar un mensaje claro en el caso común (doble click, reintento
     * inmediato). El guard real, a prueba de carreras, son los índices únicos parciales
     * de V16 — DataIntegrityViolationException se traduce a EventoDuplicadoException en
     * SifenEventRecorder.registrarEnvio. Un ENVIADO/INDETERMINADO más viejo que la
     * ventana no se bloquea acá (el envío es síncrono; un registro tan viejo en ese
     * estado es anómalo y de todas formas topará con el índice si de verdad hay otro
     * intento en curso).
     */
    private void verificarDuplicado(Long companyId, EventoRequest request, EventoValidado validado) {
        Optional<SifenEvent> existente = (validado.tipoEvento() == 2)
                ? buscarDuplicadoInutilizacion(companyId, request, validado)
                : sifenEventRepository.findFirstByCompanyIdAndCdcAndTipoEventoAndEstadoInOrderByCreatedAtDesc(
                        companyId, validado.cdc(), validado.tipoEvento(), ESTADOS_VIGENTES);

        existente.ifPresent(e -> {
            if (SifenEvent.APROBADO.equals(e.getEstado())) {
                throw new EventoDuplicadoException(
                        "El evento ya fue aprobado por SIFEN el " + e.getProcessedAt() +
                        (e.getProtocoloAutorizacion() != null ? " (protocolo " + e.getProtocoloAutorizacion() + ")" : "") +
                        ". No corresponde reenviarlo.");
            }
            LocalDateTime referencia = e.getSentAt() != null ? e.getSentAt() : e.getCreatedAt();
            long segundos = Duration.between(referencia, LocalDateTime.now()).getSeconds();
            if (segundos < VENTANA_DUPLICADO_SEGUNDOS) {
                throw new EventoDuplicadoException(
                        "Ya hay un evento del mismo tipo en curso para este CDC/rango (id=" + e.getId() +
                        ", enviado hace " + segundos + "s). SIFEN bloquea el RUC entre 10 y 60 minutos ante " +
                        "eventos idénticos repetidos.");
            }
        });
    }

    private Optional<SifenEvent> buscarDuplicadoInutilizacion(Long companyId, EventoRequest request, EventoValidado validado) {
        String desde = SifenUtil.leftPad(request.getNumeroDesde(), '0', 7);
        String hasta = SifenUtil.leftPad(request.getNumeroHasta(), '0', 7);
        return sifenEventRepository.findFirstByCompanyIdAndTipoEventoAndTimbradoAndEstablecimientoAndPuntoExpedicionAndNumeroDesdeAndNumeroHastaAndEstadoInOrderByCreatedAtDesc(
                companyId, validado.tipoEvento(), request.getTimbrado(), request.getEstablecimiento(),
                request.getPuntoExpedicion(), desde, hasta, ESTADOS_VIGENTES);
    }

    // ─── Parseo de la respuesta SIFEN ───────────────────────────────────────

    private RecepcionEventoResponse procesarRespuesta(SifenEvent evento, RespuestaRecepcionEvento respuesta) {
        String estado = null;
        String codigoEstado = null;
        String descripcionEstado = null;
        String protocoloAutorizacion = null;
        LocalDateTime fechaProceso = null;
        List<MensajeSifenDTO> mensajes = List.of();

        if (respuesta != null) {
            fechaProceso = respuesta.getdFecProc();

            if (respuesta.getgResProcEVe() != null && !respuesta.getgResProcEVe().isEmpty()) {
                TgResProcEVe primerRes = respuesta.getgResProcEVe().get(0);
                estado = primerRes.getdEstRes() != null ? primerRes.getdEstRes().toUpperCase() : null;
                protocoloAutorizacion = primerRes.getdProtAut();

                if (primerRes.getgResProc() != null && !primerRes.getgResProc().isEmpty()) {
                    TgResProc primerProc = primerRes.getgResProc().get(0);
                    codigoEstado = primerProc.getdCodRes();
                    descripcionEstado = primerProc.getdMsgRes();
                    mensajes = primerRes.getgResProc().stream()
                            .map(m -> MensajeSifenDTO.builder().codigo(m.getdCodRes()).descripcion(m.getdMsgRes()).build())
                            .toList();
                } else {
                    codigoEstado = respuesta.getdCodRes();
                    descripcionEstado = respuesta.getdMsgRes();
                }
            } else if (respuesta.getdCodRes() != null) {
                codigoEstado = respuesta.getdCodRes();
                descripcionEstado = respuesta.getdMsgRes();
                estado = invoiceService.resolverEstado(codigoEstado);
            } else if (respuesta.getRespuestaBruta() != null) {
                String raw = respuesta.getRespuestaBruta();
                if (invoiceService.esRespuestaHtml(raw)) {
                    estado = "ERROR_CONEXION";
                    codigoEstado = "CONN_ERR";
                    descripcionEstado = InvoiceService.MSG_ERROR_CONEXION;
                    log.error("SIFEN devolvió HTML en lugar de XML al enviar evento. HTTP status={}",
                            respuesta.getCodigoEstado());
                } else {
                    String estRes = invoiceService.extraerTagXml(raw, "dEstRes");
                    codigoEstado = invoiceService.extraerTagXml(raw, "dCodRes");
                    descripcionEstado = invoiceService.extraerTagXml(raw, "dMsgRes");
                    estado = estRes != null ? estRes.toUpperCase() : null;
                    if (codigoEstado != null) {
                        mensajes = List.of(MensajeSifenDTO.builder().codigo(codigoEstado).descripcion(descripcionEstado).build());
                    }
                    log.warn("Respuesta SIFEN con wrapper inesperado al enviar evento. Estado={}, Código={}, Mensaje={}",
                            estRes, codigoEstado, descripcionEstado);
                }
            }
        }

        // Sin señal interpretable (respuesta null, o sin gResProcEVe/dCodRes/XML parseable):
        // no hay evidencia de qué pasó. INDETERMINADO, nunca "DESCONOCIDO" (eso invitaba a
        // tratarlo como un estado terminal cuando en realidad no se sabe si SIFEN procesó el evento).
        String estadoFinal = (estado == null || estado.isBlank()) ? SifenEvent.INDETERMINADO : estado;

        SifenEvent actualizado = sifenEventRecorder.registrarResultado(
                evento.getId(), estadoFinal, codigoEstado, descripcionEstado, protocoloAutorizacion,
                fechaProceso, mensajes,
                respuesta != null ? respuesta.getRequestSent() : null,
                respuesta != null ? respuesta.getRespuestaBruta() : null);

        SifenEventoClassifier.ClasificacionEvento clasificacion =
                SifenEventoClassifier.clasificar(actualizado.getTipoEvento(), estadoFinal, codigoEstado);

        return RecepcionEventoResponse.builder()
                .id(actualizado.getId())
                .eventoId(actualizado.getEventoId())
                .tipoEvento(actualizado.getTipoEvento())
                .cdc(actualizado.getCdc())
                .estado(estadoFinal)
                .codigoEstado(codigoEstado)
                .descripcionEstado(descripcionEstado)
                .mensajes(mensajes)
                .protocoloAutorizacion(protocoloAutorizacion)
                .fechaProceso(fechaProceso)
                .clasificacion(clasificacion.resultado().name())
                .reintentable(clasificacion.reintentable())
                .accionSugerida(clasificacion.accionSugerida())
                .respuestaSifen(respuesta != null ? invoiceService.buildRespuestaSifenDTO(respuesta) : null)
                .build();
    }

    /**
     * La librería colapsa fallos de escritura ("seguro no se envió") y timeouts de
     * lectura ("pudo procesarse") en el mismo SifenException. No se pueden distinguir
     * de forma confiable → se asume siempre el caso pesimista (INDETERMINADO) ante
     * cualquier IOException en la cadena de causas.
     */
    private boolean esTimeoutOReadFailure(Throwable t) {
        Throwable actual = t;
        for (int i = 0; i < 10 && actual != null; i++) {
            if (actual instanceof IOException) {
                return true;
            }
            actual = actual.getCause();
        }
        return false;
    }

    // ─── Mapeo a DTO ────────────────────────────────────────────────────────

    private EventoResumenDTO toResumen(SifenEvent e) {
        List<MensajeSifenDTO> mensajes = deserializarMensajes(e.getResponseData());
        SifenEventoClassifier.ClasificacionEvento clasificacion =
                SifenEventoClassifier.clasificar(e.getTipoEvento(), e.getEstado(), e.getSifenCodigo());
        return EventoResumenDTO.builder()
                .id(e.getId())
                .eventoId(e.getEventoId())
                .tipoEvento(e.getTipoEvento())
                .tipoEventoDescripcion(descripcionTipoEvento(e.getTipoEvento()))
                .cdc(e.getCdc())
                .estado(e.getEstado())
                .sifenCodigo(e.getSifenCodigo())
                .sifenMensaje(e.getSifenMensaje())
                .protocoloAutorizacion(e.getProtocoloAutorizacion())
                .motivo(e.getMotivo())
                .timbrado(e.getTimbrado())
                .establecimiento(e.getEstablecimiento())
                .puntoExpedicion(e.getPuntoExpedicion())
                .numeroDesde(e.getNumeroDesde())
                .numeroHasta(e.getNumeroHasta())
                .tipoDocumento(e.getTipoDocumento())
                .mensajes(mensajes)
                .clasificacion(clasificacion.resultado().name())
                .reintentable(clasificacion.reintentable())
                .accionSugerida(clasificacion.accionSugerida())
                .createdAt(e.getCreatedAt())
                .sentAt(e.getSentAt())
                .processedAt(e.getProcessedAt())
                .build();
    }

    private EventoResumenDTO toResumenConNota(SifenEvent evento, String nota, String accion) {
        EventoResumenDTO dto = toResumen(evento);
        dto.setClasificacion(nota);
        dto.setAccionSugerida(accion);
        return dto;
    }

    private List<MensajeSifenDTO> deserializarMensajes(String responseDataJson) {
        if (responseDataJson == null || responseDataJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(responseDataJson, new TypeReference<List<MensajeSifenDTO>>() {});
        } catch (Exception e) {
            log.warn("No se pudo deserializar responseData de evento: {}", e.getMessage());
            return List.of();
        }
    }

    private static String descripcionTipoEvento(Short tipoEvento) {
        if (tipoEvento == null) return null;
        return switch (tipoEvento) {
            case 1 -> "Cancelación";
            case 2 -> "Inutilización";
            case 3 -> "Conformidad del receptor";
            case 4 -> "Disconformidad del receptor";
            case 5 -> "Desconocimiento del receptor";
            case 6 -> "Notificación de recepción";
            default -> "Desconocido";
        };
    }

    private Long requireTenant() {
        Long companyId = TenantContext.get();
        if (companyId == null) {
            throw new IllegalStateException("No hay tenant configurado para la request actual");
        }
        return companyId;
    }
}
