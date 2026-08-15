package com.ratones.sifenwrapper.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DataIntegrityViolationException;
import com.roshka.sifen.Sifen;
import com.roshka.sifen.core.SifenConfig;
import com.roshka.sifen.core.beans.DocumentoElectronico;
import com.roshka.sifen.core.beans.response.*;
import com.roshka.sifen.core.exceptions.SifenException;
import com.roshka.sifen.core.fields.request.de.*;
import com.roshka.sifen.core.fields.response.TgResProc;
import com.roshka.sifen.core.fields.response.batch.TgResProcLote;
import com.roshka.sifen.core.fields.response.de.TxContenDE;
import com.roshka.sifen.core.types.*;
import com.roshka.sifen.internal.ctx.GenerationCtx;
import com.roshka.sifen.internal.util.SifenUtil;
import com.ratones.sifenwrapper.dto.request.ClienteDTO;
import com.ratones.sifenwrapper.dto.request.EmitirFacturaRequest;
import com.ratones.sifenwrapper.dto.request.ItemDTO;
import com.ratones.sifenwrapper.dto.request.KudeRequest;
import com.ratones.sifenwrapper.dto.request.ParamsDTO;
import com.ratones.sifenwrapper.dto.response.*;
import com.ratones.sifenwrapper.dto.response.consulta.*;
import com.ratones.sifenwrapper.entity.ElectronicDocument;
import com.ratones.sifenwrapper.entity.SifenEvent;
import com.ratones.sifenwrapper.mapper.SifenMapper;
import com.ratones.sifenwrapper.repository.ElectronicDocumentRepository;
import com.ratones.sifenwrapper.repository.SifenEventRepository;
import com.ratones.sifenwrapper.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private static final ZoneId PY_ZONE = ZoneId.of("America/Asuncion");
    private static final BigDecimal MONTO_MAX_INNOMINADO = new BigDecimal("60000000");
    private static final String DEFAULT_RESEND_SIFEN_CODIGO = "1004";
    private static final List<String> ESTADOS_REENVIABLES = List.of("RECHAZADO", "ERROR");

    private final SifenConfigFactory sifenConfigFactory;
    private final CompanyService companyService;
    private final KudeService kudeService;
    private final InvoiceEmailService invoiceEmailService;
    private final ElectronicDocumentRepository electronicDocumentRepository;
    private final SifenEventRepository sifenEventRepository;
    private final ObjectMapper objectMapper;

    // ─── Emisión DE (Recepción Síncrona) ──────────────────────────────────────

    public EmisionDEResponse emitirFactura(EmitirFacturaRequest request) {
        try {
            // Auto-fill params desde la config de la empresa si no vienen en el request
            request.setParams(resolveParams(request.getParams()));
            validarReglasReceptor(request);

            log.info("Iniciando emisión de DE - Establecimiento: {}, Punto: {}, Número: {}",
                    request.getData().getEstablecimiento(),
                    request.getData().getPunto(),
                    request.getData().getNumero());

            // Obtener config del tenant actual
            SifenConfig sifenConfig = sifenConfigFactory.getConfigForCurrentTenant();

            // Actualizar CSC si viene en el request (override del config de la empresa)
            if (request.getQr() != null && request.getQr().getCsc() != null) {
                sifenConfig.setIdCSC(request.getQr().getIdCSC());
                sifenConfig.setCSC(request.getQr().getCsc());
            }

            // Mapear DTO → DocumentoElectronico (sin lock: no usa el config estático)
            DocumentoElectronico de = SifenMapper.toDocumentoElectronico(request);

            // Llamar al servicio de recepción síncrona de SIFEN dentro del lock global
            RespuestaRecepcionDE respuesta = sifenConfigFactory.withSifenConfig(sifenConfig,
                    () -> Sifen.recepcionDE(de));

            return buildEmisionResponse(request, de, respuesta, request.isIncludeKude());

        } catch (SifenException e) {
            log.error("Error SIFEN al emitir factura: {}", e.getMessage(), e);
            throw new com.ratones.sifenwrapper.exception.SifenServiceException(
                    "Error al emitir documento electrónico", e);
        }
    }

    // ─── Preparación DE (sin envío a SIFEN) ───────────────────────────────────

    public PrepareInvoiceResponse prepararDE(EmitirFacturaRequest request) {
        try {
            request.setParams(resolveParams(request.getParams()));
            validarReglasReceptor(request);

            log.info("[PREPARE] Preparando DE - Establecimiento: {}, Punto: {}, Número: {}",
                    request.getData().getEstablecimiento(),
                    request.getData().getPunto(),
                    request.getData().getNumero());

            SifenConfig sifenConfig = sifenConfigFactory.getConfigForCurrentTenant();

            if (request.getQr() != null && request.getQr().getCsc() != null) {
                sifenConfig.setIdCSC(request.getQr().getIdCSC());
                sifenConfig.setCSC(request.getQr().getCsc());
            }

            DocumentoElectronico de = SifenMapper.toDocumentoElectronico(request);

            // Generar XML firmado + CDC + QR sin enviar a SIFEN
            // Se usa el lock global porque la librería puede leer el config estático internamente
            final String[] xmlResult = new String[1];
            sifenConfigFactory.withSifenConfig(sifenConfig, () -> {
                GenerationCtx ctx = GenerationCtx.getDefaultFromConfig(sifenConfig);
                xmlResult[0] = de.generarXml(ctx, sifenConfig);
                return null;
            });
            String xmlFirmado = xmlResult[0];
            String cdc = de.getId();
            String qrUrl = de.getEnlaceQR();

            log.info("[PREPARE] DE generado - CDC: {}", cdc);

            // Persistir en base de datos
            Long companyId = TenantContext.get();
            String requestJson;
            try {
                requestJson = objectMapper.writeValueAsString(request);
            } catch (JsonProcessingException e) {
                log.warn("No se pudo serializar el request a JSON: {}", e.getMessage());
                requestJson = "{}";
            }

            ElectronicDocument doc = ElectronicDocument.builder()
                    .companyId(companyId)
                    .cdc(cdc)
                    .tipoDocumento((short) request.getData().getTipoDocumento())
                    .numero(request.getData().getNumero())
                    .establecimiento(request.getData().getEstablecimiento())
                    .punto(request.getData().getPunto())
                    .estado("PREPARADO")
                    .xmlFirmado(xmlFirmado)
                    .qrUrl(qrUrl)
                    .requestData(requestJson)
                    .build();
            try {
                electronicDocumentRepository.save(doc);
            } catch (DataIntegrityViolationException e) {
                throw new IllegalArgumentException(
                        "Ya existe un documento con CDC " + cdc + " para esta empresa");
            }

            log.info("[PREPARE] DE persistido - CDC: {}, estado: PREPARADO", cdc);

            // Generar KUDE si se solicitó
            PrepareInvoiceResponse.PrepareInvoiceResponseBuilder responseBuilder =
                    PrepareInvoiceResponse.builder()
                            .cdc(cdc)
                            .qrUrl(qrUrl)
                            .estado("PREPARADO")
                            .numero(request.getData().getNumero())
                            .establecimiento(request.getData().getEstablecimiento())
                            .punto(request.getData().getPunto());

            if (request.isIncludeKude()) {
                try {
                    KudeRequest kudeReq = new KudeRequest();
                    kudeReq.setParams(request.getParams());
                    kudeReq.setData(request.getData());
                    kudeReq.setCdc(cdc);
                    kudeReq.setQrUrl(qrUrl);
                    kudeReq.setEstado("PREPARADO");
                    String kudeBase64 = kudeService.generarKudeBase64(kudeReq);
                    responseBuilder.kude(kudeBase64);
                } catch (Exception e) {
                    log.warn("No se pudo generar KUDE en prepare: {}", e.getMessage());
                }
            }

            return responseBuilder.build();

        } catch (SifenException e) {
            log.error("Error SIFEN al preparar DE: {}", e.getMessage(), e);
            throw new com.ratones.sifenwrapper.exception.SifenServiceException(
                    "Error al preparar documento electrónico", e);
        }
    }

    public List<PrepareInvoiceResponse> prepararLote(List<EmitirFacturaRequest> requests) {
        List<PrepareInvoiceResponse> resultados = new ArrayList<>();
        for (EmitirFacturaRequest req : requests) {
            resultados.add(prepararDE(req));
        }
        return resultados;
    }

    // ─── Consulta estado local por CDC ────────────────────────────────────────

    public DocumentStatusResponse consultarEstadoLocal(String cdc, boolean refresh) {
        Long tenantId = TenantContext.get();
        Optional<ElectronicDocument> optDoc = tenantId != null
                ? electronicDocumentRepository.findByCompanyIdAndCdc(tenantId, cdc)
                : electronicDocumentRepository.findByCdc(cdc);

        if (optDoc.isEmpty()) {
            // No existe localmente — intentar consulta directa a SIFEN
            if (refresh) {
                try {
                    ConsultaDEResponse sifenResp = consultarDE(cdc);
                    return enriquecerConEventos(enriquecerConClasificacion(DocumentStatusResponse.builder()
                            .cdc(cdc)
                            .estado(sifenResp.getEstado())
                            .codigoEstado(sifenResp.getCodigoEstado())
                            .descripcionEstado(sifenResp.getDescripcionEstado())
                            .qrUrl(sifenResp.getQrUrl()),
                            sifenResp.getEstado(), sifenResp.getCodigoEstado(),
                            sifenResp.getDescripcionEstado(), null), cdc).build();
                } catch (Exception e) {
                    log.warn("No se pudo consultar SIFEN para CDC {}: {}", cdc, e.getMessage());
                }
            }
            // Un CDC ajeno (emitido por otra empresa) no tiene ElectronicDocument local,
            // pero esta empresa puede haber registrado eventos de receptor (tipos 3-6) sobre él.
            return enriquecerConEventos(DocumentStatusResponse.builder()
                    .cdc(cdc)
                    .estado("NO_ENCONTRADO")
                    .descripcionEstado("No se encontró el documento con CDC: " + cdc)
                    .mensajes(List.of())
                    .reenviable(false), cdc).build();
        }

        ElectronicDocument doc = optDoc.get();

        // Si refresh=true y el estado es ENVIADO, consultar a SIFEN para obtener el estado final.
        // NO consultar si está PREPARADO (aún no fue enviado, SIFEN respondería "No Existe").
        // IMPORTANTE: antes de consultar por CDC individual, verificar el estado del lote.
        // Si el lote sigue en procesamiento (0361), la consulta CDC devuelve 0422
        // ("No Existe o Rechazado") aunque el documento sea válido — falso negativo.
        if (refresh && "ENVIADO".equals(doc.getEstado())) {
            try {
                SifenConfig sifenConfig = sifenConfigFactory.getConfigForCompany(doc.getCompanyId());

                // Verificar estado del lote si el documento fue enviado en batch
                if (doc.getNroLote() != null) {
                    RespuestaConsultaLoteDE respuestaLote = sifenConfigFactory.withSifenConfig(sifenConfig,
                            () -> Sifen.consultaLoteDE(doc.getNroLote()));

                    String codResLot = respuestaLote != null
                            ? (respuestaLote.getdCodResLot() != null
                                    ? respuestaLote.getdCodResLot()
                                    : respuestaLote.getdCodRes())
                            : null;

                    if ("0361".equals(codResLot)) {
                        // Lote todavía en procesamiento — no consultar por CDC, es falso negativo
                        log.debug("[REFRESH] Lote {} en procesamiento, omitiendo consulta CDC para {}",
                                doc.getNroLote(), cdc);
                        return DocumentStatusResponse.builder()
                                .cdc(doc.getCdc())
                                .estado(doc.getEstado())
                                .codigoEstado("0361")
                                .descripcionEstado("Lote en procesamiento — resultado pendiente")
                                .nroLote(doc.getNroLote())
                                .qrUrl(doc.getQrUrl())
                                .createdAt(doc.getCreatedAt())
                                .sentAt(doc.getSentAt())
                                .processedAt(doc.getProcessedAt())
                                .mensajes(List.of())
                                .reenviable(false)
                                .build();
                    }

                    if ("0362".equals(codResLot)) {
                        // Lote concluido — leer resultado individual del CDC desde la respuesta del lote.
                        // NO llamar consultaDE: SIFEN puede devolver 0422 por latencia o el código
                        // inverted (0420) si el cliente está usando una versión anterior del wrapper.
                        java.util.List<TgResProcLote> resultados = respuestaLote.getgResProcLoteList();
                        if (resultados != null) {
                            for (TgResProcLote res : resultados) {
                                if (!cdc.equals(res.getId())) {
                                    continue;
                                }
                                // dEstRes ("Aprobado"/"Rechazado") es la fuente de verdad del estado,
                                // independiente de si gResProc trae detalle (puede venir vacío).
                                String nuevoEstado = mapearEstadoLote(res.getdEstRes());
                                if (!"DESCONOCIDO".equals(nuevoEstado)) {
                                    String estadoAnterior = doc.getEstado();
                                    List<MensajeSifenDTO> mensajes = mapearMensajes(res.getgResProc());
                                    registrarResultadoSifen(doc, nuevoEstado, mensajes, "consultaLoteDE");
                                    doc.setProcessedAt(LocalDateTime.now());
                                    electronicDocumentRepository.save(doc);
                                    log.info("[STATUS-UPDATE] CDC: {} → {} (resultado de lote)", cdc, nuevoEstado);

                                    if (!estadoAnterior.equals(nuevoEstado) && isApprovedState(nuevoEstado)) {
                                        InvoiceEmailService.EmailDispatchResult emailResult =
                                                invoiceEmailService.sendApprovedEmail(doc);
                                        if (!emailResult.sent()) {
                                            log.warn("[EMAIL] No se envió correo para CDC {}: {}",
                                                    cdc, emailResult.reason());
                                        }
                                    }
                                }
                                break;
                            }
                        }
                        // Retornar estado actualizado
                        ElectronicDocument updatedDoc = electronicDocumentRepository
                                .findById(doc.getId()).orElse(doc);
                        return enriquecerConEventos(enriquecerConClasificacion(DocumentStatusResponse.builder()
                                .cdc(updatedDoc.getCdc())
                                .estado(updatedDoc.getEstado())
                                .codigoEstado(updatedDoc.getSifenCodigo())
                                .descripcionEstado(updatedDoc.getSifenMensaje())
                                .nroLote(updatedDoc.getNroLote())
                                .qrUrl(updatedDoc.getQrUrl())
                                .createdAt(updatedDoc.getCreatedAt())
                                .sentAt(updatedDoc.getSentAt())
                                .processedAt(updatedDoc.getProcessedAt()),
                                updatedDoc.getEstado(), updatedDoc.getSifenCodigo(),
                                updatedDoc.getSifenMensaje(), updatedDoc.getResponseData()), updatedDoc.getCdc()).build();
                    }
                }

                // Lote extemporáneo (0364), sin nroLote o código desconocido → consultar por CDC individual
                RespuestaConsultaDE respuesta = sifenConfigFactory.withSifenConfig(sifenConfig,
                        () -> Sifen.consultaDE(cdc));

                if (respuesta != null && respuesta.getdCodRes() != null) {
                    String estadoAnterior = doc.getEstado();
                    String nuevoEstado = resolverEstadoDocumento(respuesta.getdCodRes());
                    if (!"DESCONOCIDO".equals(nuevoEstado)) {
                        List<MensajeSifenDTO> mensajes = List.of(MensajeSifenDTO.builder()
                                .codigo(respuesta.getdCodRes())
                                .descripcion(respuesta.getdMsgRes())
                                .build());
                        registrarResultadoSifen(doc, nuevoEstado, mensajes, "consultaDE");
                        doc.setProcessedAt(LocalDateTime.now());
                        electronicDocumentRepository.save(doc);
                        log.info("[STATUS-UPDATE] CDC: {} → {}", cdc, nuevoEstado);

                        if (!estadoAnterior.equals(nuevoEstado) && isApprovedState(nuevoEstado)) {
                            InvoiceEmailService.EmailDispatchResult emailResult =
                                    invoiceEmailService.sendApprovedEmail(doc);
                            if (!emailResult.sent()) {
                                log.warn("[EMAIL] No se envió correo para CDC {}: {}",
                                        cdc, emailResult.reason());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Error al refrescar estado por CDC {}: {}", cdc, e.getMessage());
            }
        }

        return enriquecerConEventos(enriquecerConClasificacion(DocumentStatusResponse.builder()
                .cdc(doc.getCdc())
                .estado(doc.getEstado())
                .codigoEstado(doc.getSifenCodigo())
                .descripcionEstado(doc.getSifenMensaje())
                .nroLote(doc.getNroLote())
                .qrUrl(doc.getQrUrl())
                .createdAt(doc.getCreatedAt())
                .sentAt(doc.getSentAt())
                .processedAt(doc.getProcessedAt()),
                doc.getEstado(), doc.getSifenCodigo(), doc.getSifenMensaje(), doc.getResponseData()), doc.getCdc()).build();
    }

    // ─── Recepción Lote (Asíncrono) ───────────────────────────────────────────

    public RecepcionLoteResponse enviarLote(List<EmitirFacturaRequest> requests) {
        try {
            log.info("Enviando lote de {} documentos", requests.size());

            SifenConfig config = sifenConfigFactory.getConfigForCurrentTenant();

            List<DocumentoElectronico> documentos = new ArrayList<>();
            for (EmitirFacturaRequest req : requests) {
                req.setParams(resolveParams(req.getParams()));
                validarReglasReceptor(req);
                documentos.add(SifenMapper.toDocumentoElectronico(req));
            }

            RespuestaRecepcionLoteDE respuesta = sifenConfigFactory.withSifenConfig(config,
                    () -> Sifen.recepcionLoteDE(documentos));
            return buildLoteResponse(respuesta);

        } catch (SifenException e) {
            log.error("Error SIFEN al enviar lote: {}", e.getMessage(), e);
            throw new com.ratones.sifenwrapper.exception.SifenServiceException(
                    "Error al enviar lote de documentos", e);
        }
    }

    // ─── Consulta Estado Lote ─────────────────────────────────────────────────

    public ConsultaEstadoLoteResponse consultarEstadoLote(String nroLote) {
        try {
            log.info("Consultando estado del lote: {}", nroLote);
            SifenConfig config = sifenConfigFactory.getConfigForCurrentTenant();
            RespuestaConsultaLoteDE respuesta = sifenConfigFactory.withSifenConfig(config,
                    () -> Sifen.consultaLoteDE(nroLote));
            return buildConsultaLoteResponse(nroLote, respuesta);
        } catch (SifenException e) {
            log.error("Error SIFEN al consultar lote {}: {}", nroLote, e.getMessage(), e);
            throw new com.ratones.sifenwrapper.exception.SifenServiceException(
                    "Error al consultar lote " + nroLote, e);
        }
    }

    // ─── Consulta DE por CDC ──────────────────────────────────────────────────

    public ConsultaDEResponse consultarDE(String cdc) {
        try {
            log.info("Consultando DE con CDC: {}", cdc);
            SifenConfig config = sifenConfigFactory.getConfigForCurrentTenant();
            RespuestaConsultaDE respuesta = sifenConfigFactory.withSifenConfig(config,
                    () -> Sifen.consultaDE(cdc));
            return buildConsultaDEResponse(cdc, respuesta);
        } catch (SifenException e) {
            log.error("Error SIFEN al consultar DE {}: {}", cdc, e.getMessage(), e);
            throw new com.ratones.sifenwrapper.exception.SifenServiceException(
                    "Error al consultar DE con CDC: " + cdc, e);
        }
    }

    // ─── Consulta RUC ─────────────────────────────────────────────────────────

    public ConsultaRucResponse consultarRuc(String ruc) {
        try {
            // El RUC se pasa sin dígito verificador
            String rucSinDV = ruc.contains("-") ? ruc.split("-")[0] : ruc;
            log.info("Consultando RUC: {}", rucSinDV);

            SifenConfig config = sifenConfigFactory.getConfigForCurrentTenant();
            RespuestaConsultaRUC respuesta = sifenConfigFactory.withSifenConfig(config,
                    () -> Sifen.consultaRUC(rucSinDV));
            return buildConsultaRucResponse(ruc, respuesta);
        } catch (SifenException e) {
            log.error("Error SIFEN al consultar RUC {}: {}", ruc, e.getMessage(), e);
            throw new com.ratones.sifenwrapper.exception.SifenServiceException(
                    "Error al consultar RUC: " + ruc, e);
        }
    }

    // ─── Builders de respuesta ────────────────────────────────────────────────

    private EmisionDEResponse buildEmisionResponse(EmitirFacturaRequest request,
                                                    DocumentoElectronico de,
                                                    RespuestaRecepcionDE respuesta,
                                                    boolean includeKude) {
        EmisionDEResponse.EmisionDEResponseBuilder builder = EmisionDEResponse.builder();

        if (respuesta != null) {
            if (esRespuestaHtml(respuesta.getRespuestaBruta())) {
                builder.estado("ERROR_CONEXION")
                        .codigoEstado("CONN_ERR")
                        .descripcionEstado(MSG_ERROR_CONEXION);
                log.error("SIFEN devolvió HTML en lugar de XML al emitir DE. HTTP status={}",
                        respuesta.getCodigoEstado());
            } else if (respuesta.getxProtDE() != null) {
                String estadoSifen = respuesta.getxProtDE().getdEstRes(); // "Aprobado", "Rechazado", etc.
                builder.estado(estadoSifen != null ? estadoSifen.toUpperCase() : "DESCONOCIDO");

                // Usar código y descripción del primer resultado de procesamiento
                if (respuesta.getxProtDE().getgResProc() != null
                        && !respuesta.getxProtDE().getgResProc().isEmpty()) {
                    TgResProc primerResultado = respuesta.getxProtDE().getgResProc().get(0);
                    builder.codigoEstado(primerResultado.getdCodRes())
                            .descripcionEstado(primerResultado.getdMsgRes());

                    // Mapear todos los mensajes
                    List<MensajeSifenDTO> mensajes = new ArrayList<>();
                    for (TgResProc msg : respuesta.getxProtDE().getgResProc()) {
                        mensajes.add(MensajeSifenDTO.builder()
                                .codigo(msg.getdCodRes())
                                .descripcion(msg.getdMsgRes())
                                .build());
                    }
                    builder.mensajes(mensajes);
                } else {
                    builder.codigoEstado(respuesta.getdCodRes())
                            .descripcionEstado(respuesta.getdMsgRes());
                }
            } else {
                // Sin xProtDE: usar datos de BaseResponse
                builder.codigoEstado(respuesta.getdCodRes())
                        .descripcionEstado(respuesta.getdMsgRes())
                        .estado(resolverEstado(respuesta.getdCodRes()));
            }

            // Respuesta SIFEN detallada
            builder.respuestaSifen(RespuestaSifenDTO.builder()
                    .codigoRespuesta(respuesta.getCodigoEstado())
                    .descripcionRespuesta(respuesta.getdMsgRes())
                    .xmlRespuesta(respuesta.getRespuestaBruta())
                    .xmlEnviado(respuesta.getRequestSent())
                    .build());
        }

        // CDC del documento
        if (de != null && de.getId() != null) {
            builder.cdc(de.getId());
        }

        // QR URL
        if (de != null && de.getEnlaceQR() != null) {
            builder.qrUrl(de.getEnlaceQR());
        }

        // XML crudo de la respuesta
        if (respuesta != null && respuesta.getRespuestaBruta() != null) {
            builder.xml(respuesta.getRespuestaBruta());
        }

        EmisionDEResponse response = builder.build();

        if (isApprovedState(response.getEstado())) {
            InvoiceEmailService.EmailDispatchResult emailResult =
                invoiceEmailService.sendApprovedEmailFromEmission(request, response);
            if (!emailResult.sent()) {
            log.info("[EMAIL] Emisión aprobada sin correo enviado (cdc={}): {}",
                response.getCdc(), emailResult.reason());
            }
        }

        // Generar KUDE si fue solicitado
        if (includeKude) {
            try {
                KudeRequest kudeReq = buildKudeRequestFromEmision(request, response);
                String kudeBase64 = kudeService.generarKudeBase64(kudeReq);
                response.setKude(kudeBase64);
            } catch (Exception e) {
                log.warn("No se pudo generar KUDE automático: {}", e.getMessage());
            }
        }

        return response;
    }

    private RecepcionLoteResponse buildLoteResponse(RespuestaRecepcionLoteDE respuesta) {
        RecepcionLoteResponse.RecepcionLoteResponseBuilder builder = RecepcionLoteResponse.builder();
        if (respuesta != null) {
            if (esRespuestaHtml(respuesta.getRespuestaBruta())) {
                builder.estado("ERROR_CONEXION").codigoEstado("CONN_ERR").descripcionEstado(MSG_ERROR_CONEXION);
                log.error("SIFEN devolvió HTML en lugar de XML al enviar lote.");
            } else {
                builder.nroLote(respuesta.getdProtConsLote())
                        .codigoEstado(respuesta.getdCodRes())
                        .descripcionEstado(respuesta.getdMsgRes())
                        .estado(resolverEstado(respuesta.getdCodRes()));
            }
        }
        return builder.build();
    }

    private ConsultaEstadoLoteResponse buildConsultaLoteResponse(String nroLoteSolicitado,
                                                                 RespuestaConsultaLoteDE respuesta) {
        ConsultaEstadoLoteResponse.ConsultaEstadoLoteResponseBuilder builder =
                ConsultaEstadoLoteResponse.builder();
        if (respuesta != null) {
            if (esRespuestaHtml(respuesta.getRespuestaBruta())) {
                builder.estado("ERROR_CONEXION").codigoEstado("CONN_ERR").descripcionEstado(MSG_ERROR_CONEXION);
                log.error("SIFEN devolvió HTML en lugar de XML al consultar lote.");
            } else {
                String codigoEstado = respuesta.getdCodResLot() != null
                        ? respuesta.getdCodResLot()
                        : respuesta.getdCodRes();
                String descripcionEstado = respuesta.getdMsgResLot() != null
                        ? respuesta.getdMsgResLot()
                        : respuesta.getdMsgRes();

                builder.nroLote(nroLoteSolicitado)
                        .codigoEstado(codigoEstado)
                        .descripcionEstado(descripcionEstado)
                        .estado(resolverEstado(codigoEstado));

                if (codigoEstado != null || descripcionEstado != null) {
                    builder.mensajes(List.of(MensajeSifenDTO.builder()
                            .codigo(codigoEstado)
                            .descripcion(descripcionEstado)
                            .build()));
                }

                if (respuesta.getgResProcLoteList() != null && !respuesta.getgResProcLoteList().isEmpty()) {
                    List<ResultadoLoteItemDTO> resultados = new ArrayList<>();
                    for (TgResProcLote item : respuesta.getgResProcLoteList()) {
                        String codigoItem = null;
                        String descripcionItem = null;
                        if (item.getgResProc() != null && !item.getgResProc().isEmpty()) {
                            codigoItem = item.getgResProc().get(0).getdCodRes();
                            descripcionItem = item.getgResProc().get(0).getdMsgRes();
                        }
                        String estadoLocalItem = mapearEstadoLote(item.getdEstRes());
                        SifenRechazoClassifier.ClasificacionRechazo clasificacionItem =
                                SifenRechazoClassifier.clasificar(estadoLocalItem, codigoItem);
                        resultados.add(ResultadoLoteItemDTO.builder()
                                .cdc(item.getId())
                                .estado(item.getdEstRes())
                                .descripcion(descripcionItem)
                                .codigo(codigoItem)
                                .reenviable(clasificacionItem.reenviable())
                                .clasificacionReenvio(clasificacionItem.clasificacion().name())
                                .build());
                    }
                    builder.resultados(resultados);
                }
            }
            builder.respuestaSifen(buildRespuestaSifenDTO(respuesta));
        }
        return builder.build();
    }

    private ConsultaDEResponse buildConsultaDEResponse(String cdc, RespuestaConsultaDE respuesta) {
        ConsultaDEResponse.ConsultaDEResponseBuilder builder = ConsultaDEResponse.builder().cdc(cdc);
        if (respuesta != null) {
            if (esRespuestaHtml(respuesta.getRespuestaBruta())) {
                builder.estado("ERROR_CONEXION").codigoEstado("CONN_ERR").descripcionEstado(MSG_ERROR_CONEXION);
                log.error("SIFEN devolvió HTML en lugar de XML al consultar DE cdc={}.", cdc);
            } else {
                builder.codigoEstado(respuesta.getdCodRes())
                        .descripcionEstado(respuesta.getdMsgRes())
                        .estado(resolverEstado(respuesta.getdCodRes()));
            }

            // Fecha de procesamiento
            if (respuesta.getdFecProc() != null) {
                builder.fechaProcesamiento(respuesta.getdFecProc());
            }

            // Extraer datos del DE parseado
            TxContenDE contenDE = respuesta.getxContenDE();
            if (contenDE != null) {
                builder.protocoloAutorizacion(contenDE.getdProtAut());

                DocumentoElectronico de = contenDE.getDE();
                if (de != null) {
                    if (de.getId() != null) {
                        builder.cdc(de.getId());
                    }
                    if (de.getEnlaceQR() != null) {
                        builder.qrUrl(de.getEnlaceQR());
                    }
                    builder.documento(mapDocumentoElectronico(de));
                }
            }

            builder.respuestaSifen(buildRespuestaSifenDTO(respuesta));
        }
        return builder.build();
    }

    // ─── Mapeo DE → DTOs de consulta ──────────────────────────────────────────

    private ConsultaDocumentoDTO mapDocumentoElectronico(DocumentoElectronico de) {
        ConsultaDocumentoDTO.ConsultaDocumentoDTOBuilder doc = ConsultaDocumentoDTO.builder()
                .cdc(de.getId())
                .fechaFirma(de.getdFecFirma())
                .qrUrl(de.getEnlaceQR());

        // Operación DE (tipo emisión, código seguridad)
        if (de.getgOpeDE() != null) {
            TgOpeDE ope = de.getgOpeDE();
            if (ope.getiTipEmi() != null) {
                doc.tipoEmision((int) ope.getiTipEmi().getVal())
                   .tipoEmisionDescripcion(ope.getiTipEmi().getDescripcion());
            }
            doc.codigoSeguridad(ope.getdCodSeg());
        }

        // Timbrado
        if (de.getgTimb() != null) {
            doc.timbrado(mapTimbrado(de.getgTimb()));
        }

        // Datos generales (emisor, receptor, operación comercial)
        if (de.getgDatGralOpe() != null) {
            TdDatGralOpe datGral = de.getgDatGralOpe();
            doc.fechaEmision(datGral.getdFeEmiDE());

            if (datGral.getgEmis() != null) {
                doc.emisor(mapEmisor(datGral.getgEmis()));
            }
            if (datGral.getgDatRec() != null) {
                doc.receptor(mapReceptor(datGral.getgDatRec()));
            }
            if (datGral.getgOpeCom() != null) {
                TgOpeCom ope = datGral.getgOpeCom();
                if (ope.getiTipTra() != null) {
                    doc.tipoTransaccion((int) ope.getiTipTra().getVal())
                       .tipoTransaccionDescripcion(ope.getiTipTra().getDescripcion());
                }
                if (ope.getiTImp() != null) {
                    doc.tipoImpuesto((int) ope.getiTImp().getVal())
                       .tipoImpuestoDescripcion(ope.getiTImp().getDescripcion());
                }
                if (ope.getcMoneOpe() != null) {
                    doc.moneda(ope.getcMoneOpe().name());
                }
            }
        }

        // Detalle por tipo DE (condición, ítems)
        if (de.getgDtipDE() != null) {
            TgDtipDE dtip = de.getgDtipDE();
            if (dtip.getgCamCond() != null) {
                doc.condicion(mapCondicion(dtip.getgCamCond()));
            }
            if (dtip.getgCamItemList() != null) {
                doc.items(dtip.getgCamItemList().stream().map(this::mapItem).toList());
            }
        }

        // Totales
        if (de.getgTotSub() != null) {
            doc.totales(mapTotales(de.getgTotSub()));
        }

        return doc.build();
    }

    private ConsultaTimbradoDTO mapTimbrado(TgTimb timb) {
        ConsultaTimbradoDTO.ConsultaTimbradoDTOBuilder b = ConsultaTimbradoDTO.builder()
                .numero(timb.getdNumTim())
                .establecimiento(timb.getdEst())
                .puntoExpedicion(timb.getdPunExp())
                .numeroDocumento(timb.getdNumDoc())
                .fechaInicioVigencia(timb.getdFeIniT());
        if (timb.getiTiDE() != null) {
            b.tipoDocumento((int) timb.getiTiDE().getVal())
             .tipoDocumentoDescripcion(timb.getiTiDE().getDescripcion());
        }
        return b.build();
    }

    private ConsultaEmisorDTO mapEmisor(TgEmis emis) {
        ConsultaEmisorDTO.ConsultaEmisorDTOBuilder b = ConsultaEmisorDTO.builder()
                .ruc(emis.getdRucEm())
                .dv(emis.getdDVEmi())
                .razonSocial(emis.getdNomEmi())
                .nombreFantasia(emis.getdNomFanEmi())
                .direccion(emis.getdDirEmi())
                .numeroCasa(emis.getdNumCas())
                .distrito((int) emis.getcDisEmi())
                .distritoDescripcion(emis.getdDesDisEmi())
                .ciudad(emis.getcCiuEmi())
                .ciudadDescripcion(emis.getdDesCiuEmi())
                .telefono(emis.getdTelEmi())
                .email(emis.getdEmailE())
                .denominacionSucursal(emis.getdDenSuc());
        if (emis.getiTipCont() != null) {
            b.tipoContribuyente((int) emis.getiTipCont().getVal())
             .tipoContribuyenteDescripcion(emis.getiTipCont().getDescripcion());
        }
        if (emis.getcTipReg() != null) {
            b.tipoRegimen((int) emis.getcTipReg().getVal())
             .tipoRegimenDescripcion(emis.getcTipReg().getDescripcion());
        }
        if (emis.getcDepEmi() != null) {
            b.departamento((int) emis.getcDepEmi().getVal())
             .departamentoDescripcion(emis.getcDepEmi().getDescripcion());
        }
        if (emis.getgActEcoList() != null) {
            b.actividadesEconomicas(emis.getgActEcoList().stream()
                    .map(act -> ConsultaActividadEconomicaDTO.builder()
                            .codigo(act.getcActEco())
                            .descripcion(act.getdDesActEco())
                            .build())
                    .toList());
        }
        return b.build();
    }

    private ConsultaReceptorDTO mapReceptor(TgDatRec rec) {
        ConsultaReceptorDTO.ConsultaReceptorDTOBuilder b = ConsultaReceptorDTO.builder()
                .ruc(rec.getdRucRec())
                .dv((int) rec.getdDVRec())
                .numeroDocumento(rec.getdNumIDRec())
                .razonSocial(rec.getdNomRec())
                .nombreFantasia(rec.getdNomFanRec())
                .direccion(rec.getdDirRec())
                .numeroCasa(rec.getdNumCasRec())
                .distrito((int) rec.getcDisRec())
                .distritoDescripcion(rec.getdDesDisRec())
                .ciudad(rec.getcCiuRec())
                .ciudadDescripcion(rec.getdDesCiuRec())
                .telefono(rec.getdTelRec())
                .celular(rec.getdCelRec())
                .email(rec.getdEmailRec())
                .codigoCliente(rec.getdCodCliente());
        if (rec.getiTiOpe() != null) {
            b.tipoOperacion((int) rec.getiTiOpe().getVal())
             .tipoOperacionDescripcion(rec.getiTiOpe().getDescripcion());
        }
        if (rec.getiTiContRec() != null) {
            b.tipoContribuyente((int) rec.getiTiContRec().getVal())
             .tipoContribuyenteDescripcion(rec.getiTiContRec().getDescripcion());
        }
        if (rec.getiTipIDRec() != null) {
            b.tipoDocumentoIdentidad((int) rec.getiTipIDRec().getVal())
             .descripcionTipoDocumento(rec.getdDTipIDRec());
        }
        if (rec.getcDepRec() != null) {
            b.departamento((int) rec.getcDepRec().getVal())
             .departamentoDescripcion(rec.getcDepRec().getDescripcion());
        }
        return b.build();
    }

    private ConsultaCondicionDTO mapCondicion(TgCamCond cond) {
        ConsultaCondicionDTO.ConsultaCondicionDTOBuilder b = ConsultaCondicionDTO.builder();
        if (cond.getiCondOpe() != null) {
            b.tipo((int) cond.getiCondOpe().getVal())
             .tipoDescripcion(cond.getiCondOpe().getDescripcion());
        }
        if (cond.getgPaConEIniList() != null) {
            b.entregas(cond.getgPaConEIniList().stream().map(e -> {
                ConsultaEntregaDTO.ConsultaEntregaDTOBuilder eb = ConsultaEntregaDTO.builder()
                        .monto(e.getdMonTiPag());
                if (e.getiTiPago() != null) {
                    eb.tipoPago((int) e.getiTiPago().getVal())
                      .tipoPagoDescripcion(e.getiTiPago().getDescripcion());
                }
                if (e.getcMoneTiPag() != null) {
                    eb.moneda(e.getcMoneTiPag().name());
                }
                return eb.build();
            }).toList());
        }
        return b.build();
    }

    private ConsultaItemDTO mapItem(TgCamItem item) {
        ConsultaItemDTO.ConsultaItemDTOBuilder b = ConsultaItemDTO.builder()
                .codigo(item.getdCodInt())
                .descripcion(item.getdDesProSer())
                .cantidad(item.getdCantProSer());
        if (item.getcUniMed() != null) {
            b.unidadMedida((int) item.getcUniMed().getVal())
             .unidadMedidaDescripcion(item.getcUniMed().getDescripcion());
        }
        if (item.getgValorItem() != null) {
            b.precioUnitario(item.getgValorItem().getdPUniProSer())
             .totalBruto(item.getgValorItem().getdTotBruOpeItem());
        }
        if (item.getgCamIVA() != null) {
            TgCamIVA iva = item.getgCamIVA();
            if (iva.getiAfecIVA() != null) {
                b.afectacionIva((int) iva.getiAfecIVA().getVal())
                 .afectacionIvaDescripcion(iva.getiAfecIVA().getDescripcion());
            }
            b.proporcionIva(iva.getdPropIVA())
             .tasaIva(iva.getdTasaIVA())
             .baseGravadaIva(iva.getdBasGravIVA())
             .liquidacionIva(iva.getdLiqIVAItem())
             .baseExenta(iva.getdBasExe());
        }
        return b.build();
    }

    private ConsultaTotalesDTO mapTotales(TgTotSub tot) {
        return ConsultaTotalesDTO.builder()
                .subtotalExenta(tot.getdSubExe())
                .subtotalExonerada(tot.getdSubExo())
                .subtotalIva5(tot.getdSub5())
                .subtotalIva10(tot.getdSub10())
                .totalOperacion(tot.getdTotOpe())
                .totalDescuento(tot.getdTotDesc())
                .totalAnticipo(tot.getdTotAnt())
                .redondeo(tot.getdRedon())
                .totalGeneral(tot.getdTotGralOpe())
                .liquidacionIva5(tot.getdLiqTotIVA5())
                .liquidacionIva10(tot.getdLiqTotIVA10())
                .totalIva(tot.getdTotIVA())
                .baseGravada5(tot.getdBaseGrav5())
                .baseGravada10(tot.getdBaseGrav10())
                .totalBaseGravada(tot.getdTBasGraIVA())
                .totalGuaranies(tot.getdTotalGs())
                .build();
    }

    private void validarReglasReceptor(EmitirFacturaRequest request) {
        if (request == null || request.getData() == null) {
            return;
        }

        validarItemsDocumento(request.getData().getItems());

        if (request.getData().getCliente() == null) {
            return;
        }

        ClienteDTO cliente = request.getData().getCliente();
        Integer tipoDocReceptor = resolveTipoDocumentoReceptor(cliente);

        // iNatRec = 2 (No Contribuyente): no debe enviarse RUC del receptor.
        if (!cliente.isContribuyente() && cliente.getRuc() != null && !cliente.getRuc().isBlank()) {
            throw new IllegalArgumentException("Para no contribuyente no debe informarse RUC del receptor");
        }

        // Innominado (iTipIDRec=5): permitido solo para total < 60.000.000.
        if (tipoDocReceptor != null && tipoDocReceptor == 5) {
            BigDecimal total = calcularTotalOperacion(request.getData().getItems());
            if (total.compareTo(MONTO_MAX_INNOMINADO) >= 0) {
                throw new IllegalArgumentException(
                        "Factura innominada no permitida para montos >= 60.000.000 Gs (regla SIFEN 1321)");
            }
        }
    }

    private void validarItemsDocumento(List<ItemDTO> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException(
                    "El documento debe incluir al menos un item en data.items.");
        }
    }

    private Integer resolveTipoDocumentoReceptor(ClienteDTO cliente) {
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

    private BigDecimal calcularTotalOperacion(List<ItemDTO> items) {
        if (items == null || items.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal total = BigDecimal.ZERO;
        for (ItemDTO item : items) {
            BigDecimal cantidad = item.getCantidad() != null ? item.getCantidad() : BigDecimal.ONE;
            BigDecimal precio = item.getPrecioUnitario() != null ? item.getPrecioUnitario() : BigDecimal.ZERO;
            BigDecimal descuento = item.getDescuento() != null ? item.getDescuento() : BigDecimal.ZERO;
            total = total.add(cantidad.multiply(precio).subtract(descuento));
        }
        return total;
    }

    private ConsultaRucResponse buildConsultaRucResponse(String ruc, RespuestaConsultaRUC respuesta) {
        String rucSolo = ruc.contains("-") ? ruc.split("-")[0] : ruc;
        String dvCalculado = SifenUtil.generateDv(rucSolo);
        ConsultaRucResponse.ConsultaRucResponseBuilder builder = ConsultaRucResponse.builder().ruc(rucSolo).dv(dvCalculado);
        if (respuesta != null) {
            if (esRespuestaHtml(respuesta.getRespuestaBruta())) {
                builder.estado("ERROR_CONEXION").codigoEstado("CONN_ERR").descripcionEstado(MSG_ERROR_CONEXION);
                log.error("SIFEN devolvió HTML en lugar de XML al consultar RUC={}.", ruc);
            } else {
                builder.codigoEstado(respuesta.getdCodRes())
                        .descripcionEstado(respuesta.getdMsgRes())
                        .estado(resolverEstado(respuesta.getdCodRes()));
                if (respuesta.getxContRUC() != null) {
                    builder.razonSocial(respuesta.getxContRUC().getdRazCons());
                }
            }
        }
        return builder.build();
    }

    static final String MSG_ERROR_CONEXION =
            "SIFEN devolvió una página HTML en lugar de XML. " +
            "Posible sesión SSL expirada o problema de red/infraestructura del servidor SIFEN. " +
            "Reinicie la aplicación e intente nuevamente.";

    boolean esRespuestaHtml(String raw) {
        if (raw == null) return false;
        String trimmed = raw.trim().toLowerCase();
        return trimmed.startsWith("<html") || trimmed.startsWith("<!doctype html");
    }

    RespuestaSifenDTO buildRespuestaSifenDTO(com.roshka.sifen.internal.response.BaseResponse respuesta) {
        return RespuestaSifenDTO.builder()
                .codigoRespuesta(respuesta.getCodigoEstado())
                .descripcionRespuesta(respuesta.getdMsgRes())
                .xmlRespuesta(respuesta.getRespuestaBruta())
                .xmlEnviado(respuesta.getRequestSent())
                .build();
    }

    /**
     * Extrae el contenido de un tag XML simple del raw string.
     * Busca tanto con namespace (ns2:tag) como sin él.
     */
    String extraerTagXml(String xml, String tagName) {
        if (xml == null) return null;
        Pattern p = Pattern.compile("<(?:[\\w]+:)?" + tagName + "[^>]*>([^<]+)</");
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * Mapea códigos de estado SIFEN a descripciones legibles.
     * Ver Manual Técnico SIFEN v150 - Tabla de códigos de respuesta.
     */
    String resolverEstado(String codigoEstado) {
        if (codigoEstado == null) return "DESCONOCIDO";
        switch (codigoEstado) {
            case "0260": return "APROBADO";
            case "0261": return "APROBADO_CON_OBSERVACION";
            case "0262": return "RECHAZADO";
            case "0263": return "CANCELADO";
            case "0264": return "INUTILIZADO";
            case "0160": return "XML_MALFORMADO";
            case "0300": return "LOTE_RECIBIDO";
            case "0301": return "LOTE_RECHAZADO";
            case "0361": return "LOTE_EN_PROCESAMIENTO";
            case "0362": return "LOTE_CONCLUIDO";
            default: return "CODIGO_" + codigoEstado;
        }
    }

    /**
     * Mapea códigos de estado SIFEN de consulta DE a estados del documento local.
     * Usado por consultas CDC individuales (no lotes).
     * Ver Manual Técnico SIFEN v150:
     *   0420 = Documento Aprobado (consultaDE)
     *   0422 = Documento No Existe en SIFEN o ha sido Rechazado (consultaDE)
     */
    String resolverEstadoDocumento(String codigoEstado) {
        if (codigoEstado == null) return "DESCONOCIDO";
        switch (codigoEstado) {
            case "0260": return "APROBADO";
            case "0261": return "APROBADO_CON_OBSERVACION";
            case "0262": return "RECHAZADO";
            case "0420": return "APROBADO";      // consultaDE: Documento Aprobado
            case "0422": return "RECHAZADO";     // consultaDE: No Existe o Rechazado
            default: return "DESCONOCIDO";
        }
    }

    /**
     * Mapea el estado textual de un DE dentro de un resultado de lote ("Aprobado",
     * "Rechazado", etc. — campo dEstRes) al estado local. Usado tanto por el poller
     * de lotes como por la consulta de estado con refresh=true, para no depender de
     * que gResProc venga poblado (puede llegar vacío en un rechazo).
     */
    String mapearEstadoLote(String estadoSifen) {
        if (estadoSifen == null) return "DESCONOCIDO";
        switch (estadoSifen.toLowerCase()) {
            case "aprobado": return "APROBADO";
            case "aprobado con observación":
            case "aprobado con observacion": return "APROBADO_CON_OBSERVACION";
            case "rechazado": return "RECHAZADO";
            default: return "DESCONOCIDO";
        }
    }

    /** Convierte la lista de mensajes de procesamiento SIFEN (dCodRes/dMsgRes) al DTO expuesto al ERP. */
    List<MensajeSifenDTO> mapearMensajes(List<TgResProc> gResProc) {
        if (gResProc == null || gResProc.isEmpty()) {
            return List.of();
        }
        return gResProc.stream()
                .map(m -> MensajeSifenDTO.builder().codigo(m.getdCodRes()).descripcion(m.getdMsgRes()).build())
                .toList();
    }

    /**
     * Persiste el resultado de una consulta SIFEN (lote o individual) en el documento:
     * el nuevo estado; el primer mensaje en sifenCodigo/sifenMensaje (compatibilidad con
     * el resto del sistema y con los filtros de reenvío masivo); y el detalle completo
     * (todos los mensajes + origen de la consulta) en responseData, para que el ERP y
     * GET /logs/transactions puedan ver el motivo completo, no solo el primero.
     *
     * Si el resultado es RECHAZADO sin mensajes (gResProc vacío — puede ocurrir según el
     * Manual Técnico), no deja los valores del envío anterior (ej. "0300 - Lote recibido
     * con éxito"): registra explícitamente que SIFEN no detalló el motivo.
     */
    void registrarResultadoSifen(ElectronicDocument doc, String nuevoEstado,
                                  List<MensajeSifenDTO> mensajes, String fuente) {
        doc.setEstado(nuevoEstado);
        if (!mensajes.isEmpty()) {
            doc.setSifenCodigo(mensajes.get(0).getCodigo());
            doc.setSifenMensaje(mensajes.get(0).getDescripcion());
        } else if ("RECHAZADO".equals(nuevoEstado)) {
            doc.setSifenCodigo("SIN_DETALLE");
            doc.setSifenMensaje("SIFEN rechazó el documento sin detallar el motivo por esta vía ("
                    + fuente + ")."
                    + (doc.getNroLote() != null ? " Lote: " + doc.getNroLote() + "." : ""));
        }
        doc.setResponseData(serializarResponseData(nuevoEstado, mensajes, fuente));
    }

    private String serializarResponseData(String estado, List<MensajeSifenDTO> mensajes, String fuente) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("estado", estado);
            root.put("fuente", fuente);
            root.put("consultadoEn", LocalDateTime.now(PY_ZONE).toString());
            root.set("mensajes", objectMapper.valueToTree(mensajes));
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("No se pudo serializar responseData: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Agrega los campos orientados al ERP (mensajes, reenviable, clasificación, acción
     * sugerida) a un DocumentStatusResponse en construcción. mensajes se toma de
     * responseData si está disponible (todos los mensajes SIFEN); si no, cae al par
     * sifenCodigo/sifenMensaje persistido.
     */
    private DocumentStatusResponse.DocumentStatusResponseBuilder enriquecerConClasificacion(
            DocumentStatusResponse.DocumentStatusResponseBuilder builder,
            String estado, String sifenCodigo, String sifenMensaje, String responseDataJson) {
        List<MensajeSifenDTO> mensajes = extraerMensajes(sifenCodigo, sifenMensaje, responseDataJson);
        SifenRechazoClassifier.ClasificacionRechazo clasificacion =
                SifenRechazoClassifier.clasificar(estado, sifenCodigo);
        return builder.mensajes(mensajes)
                .reenviable(clasificacion.reenviable())
                .clasificacionReenvio(clasificacion.clasificacion().name())
                .accionSugerida(clasificacion.accionSugerida());
    }

    /**
     * Agrega el resumen de eventos SIFEN al estado local. Se consulta también cuando
     * no hay ElectronicDocument local (NO_ENCONTRADO): un CDC ajeno puede tener eventos
     * de receptor (tipos 3-6) registrados por esta empresa aunque el DE lo haya emitido
     * otra. No se enriquece en el early-return de lote en procesamiento (0361): un
     * documento aún no concluido no puede tener eventos.
     */
    private DocumentStatusResponse.DocumentStatusResponseBuilder enriquecerConEventos(
            DocumentStatusResponse.DocumentStatusResponseBuilder builder, String cdc) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            return builder;
        }
        List<SifenEvent> eventos = sifenEventRepository.findByCompanyIdAndCdcOrderByCreatedAtDesc(tenantId, cdc);
        if (eventos.isEmpty()) {
            return builder.cancelado(false);
        }
        boolean cancelado = eventos.stream()
                .anyMatch(e -> e.getTipoEvento() == 1 && SifenEvent.APROBADO.equals(e.getEstado()));
        SifenEvent ultimo = eventos.get(0);
        return builder.cancelado(cancelado)
                .ultimoEvento(EventoResumenDTO.builder()
                        .id(ultimo.getId())
                        .eventoId(ultimo.getEventoId())
                        .tipoEvento(ultimo.getTipoEvento())
                        .cdc(ultimo.getCdc())
                        .estado(ultimo.getEstado())
                        .sifenCodigo(ultimo.getSifenCodigo())
                        .sifenMensaje(ultimo.getSifenMensaje())
                        .protocoloAutorizacion(ultimo.getProtocoloAutorizacion())
                        .createdAt(ultimo.getCreatedAt())
                        .sentAt(ultimo.getSentAt())
                        .processedAt(ultimo.getProcessedAt())
                        .build());
    }

    private List<MensajeSifenDTO> extraerMensajes(String sifenCodigo, String sifenMensaje, String responseDataJson) {
        if (responseDataJson != null && !responseDataJson.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(responseDataJson);
                JsonNode mensajesNode = root.get("mensajes");
                if (mensajesNode != null && mensajesNode.isArray() && !mensajesNode.isEmpty()) {
                    List<MensajeSifenDTO> mensajes = new ArrayList<>();
                    for (JsonNode m : mensajesNode) {
                        mensajes.add(MensajeSifenDTO.builder()
                                .codigo(m.path("codigo").asText(null))
                                .descripcion(m.path("descripcion").asText(null))
                                .build());
                    }
                    return mensajes;
                }
            } catch (Exception e) {
                log.debug("No se pudo parsear responseData para extraer mensajes: {}", e.getMessage());
            }
        }
        if (sifenCodigo != null || sifenMensaje != null) {
            return List.of(MensajeSifenDTO.builder().codigo(sifenCodigo).descripcion(sifenMensaje).build());
        }
        return List.of();
    }

    /**
     * Archiva el rechazo actual del documento (estado, código, mensaje, lote) en
     * responseData.historial antes de reencolarlo, para no perder el motivo original
     * cuando /resend limpia sifenCodigo/sifenMensaje.
     */
    private String archivarRechazoEnHistorial(ElectronicDocument doc) {
        try {
            ObjectNode root = doc.getResponseData() != null && !doc.getResponseData().isBlank()
                    ? (ObjectNode) objectMapper.readTree(doc.getResponseData())
                    : objectMapper.createObjectNode();
            ArrayNode historial = root.has("historial") && root.get("historial").isArray()
                    ? (ArrayNode) root.get("historial")
                    : root.putArray("historial");
            ObjectNode entrada = objectMapper.createObjectNode();
            entrada.put("estado", doc.getEstado());
            entrada.put("sifenCodigo", doc.getSifenCodigo());
            entrada.put("sifenMensaje", doc.getSifenMensaje());
            entrada.put("nroLote", doc.getNroLote());
            entrada.put("archivadoEn", LocalDateTime.now(PY_ZONE).toString());
            historial.add(entrada);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("No se pudo archivar el historial de rechazo para CDC {}: {}", doc.getCdc(), e.getMessage());
            return doc.getResponseData();
        }
    }

    /**
     * Resuelve los parámetros del emisor: si no vienen en el request, los carga
     * desde la configuración de la empresa (emisorConfig).
     */
    private ParamsDTO resolveParams(ParamsDTO params) {
        ParamsDTO resolved = params;
        if (resolved == null || resolved.getRuc() == null || resolved.getRuc().isBlank()) {
            Long companyId = TenantContext.get();
            ParamsDTO stored = companyService.getEmisorConfig(companyId);
            if (stored == null) {
                throw new IllegalArgumentException(
                        "No se enviaron parámetros del emisor (params) y la empresa no tiene configuración de emisor. " +
                        "Configure la empresa con PUT /companies/" + companyId + "/emisor o envíe params en el request.");
            }
            log.info("Usando configuración de emisor almacenada para empresa {}", companyId);
            resolved = stored;
        }

        validateTenantRucMatch(resolved);
        validateEmisorEstablecimiento(resolved);
        return resolved;
    }

    private void validateEmisorEstablecimiento(ParamsDTO params) {
        if (params.getEstablecimientos() == null || params.getEstablecimientos().isEmpty()) {
            throw new IllegalArgumentException(
                    "El emisor debe incluir al menos un establecimiento en params.establecimientos.");
        }

        var est = params.getEstablecimientos().get(0);
        if (est.getDistritoDescripcion() == null || est.getDistritoDescripcion().isBlank()) {
            throw new IllegalArgumentException(
                    "El campo params.establecimientos[0].distritoDescripcion es obligatorio para SIFEN.");
        }
        if (est.getCiudadDescripcion() == null || est.getCiudadDescripcion().isBlank()) {
            throw new IllegalArgumentException(
                    "El campo params.establecimientos[0].ciudadDescripcion es obligatorio para SIFEN.");
        }
    }

    private void validateTenantRucMatch(ParamsDTO params) {
        Long companyId = TenantContext.get();
        if (companyId == null) {
            throw new IllegalStateException("No hay tenant configurado para la request actual");
        }

        String rucRecibido = params != null ? params.getRuc() : null;
        if (rucRecibido == null || rucRecibido.isBlank()) {
            throw new IllegalArgumentException(
                    "El campo params.ruc es obligatorio y debe coincidir con la empresa autenticada.");
        }

        var company = companyService.getActiveCompanyOrThrow(companyId);
        String esperadoNormalizado = normalizeRuc(company.getRuc() + company.getDv());
        String recibidoNormalizado = normalizeRuc(rucRecibido);

        if (!esperadoNormalizado.equals(recibidoNormalizado)) {
            String esperado = company.getRuc() + "-" + company.getDv();
            throw new IllegalArgumentException(
                    "El RUC del emisor no coincide con la empresa del tenant. " +
                    "Esperado: " + esperado + ", recibido: " + rucRecibido + ".");
        }
    }

    private String normalizeRuc(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
    }

    public InvoiceEmailService.EmailDispatchResult reenviarEmailFacturaAprobada(String cdc) {
        Long companyId = TenantContext.get();
        if (companyId == null) {
            throw new IllegalStateException("No hay tenant configurado para la request actual");
        }

        ElectronicDocument doc = electronicDocumentRepository.findByCompanyIdAndCdc(companyId, cdc)
                .orElseThrow(() -> new IllegalArgumentException("No se encontró documento con CDC: " + cdc));

        return invoiceEmailService.sendApprovedEmail(doc);
    }

    // ─── Reenvío de documentos rechazados (mismo CDC) ─────────────────────────

    /**
     * Reencola un documento RECHAZADO o ERROR para reenvío a SIFEN reutilizando el
     * mismo CDC. Válido según el Manual Técnico SIFEN §6.5: si el ajuste no altera los
     * campos que componen el CDC, el documento corregido puede volver a someterse a
     * validación con el mismo CDC original, cuantas veces sea necesario.
     *
     * Sin payloadCorregido: reintenta con los datos ya almacenados (útil para 1004 —
     * firma adelantada — donde el problema es la hora de firma, no los datos).
     * Con payloadCorregido: reemplaza el requestData almacenado; se valida como un
     * prepare normal (resolveParams + validarReglasReceptor) y se exige que el CDC
     * recalculado sea idéntico al original.
     *
     * Por defecto se rechaza el reenvío de documentos clasificados NO_REENVIABLE
     * (SifenRechazoClassifier — el código afecta un campo del CDC); force=true omite
     * esa verificación orientativa, pero el guard de igualdad de CDC sigue aplicando
     * siempre, sin excepción.
     *
     * El documento vuelve a estado PREPARADO; BatchSenderService lo reconstruye desde
     * requestData, lo re-firma con la hora corregida (ver SifenMapper.FIRMA_BACKDATE_SECONDS)
     * y lo reenvía en el próximo ciclo del scheduler.
     */
    public DocumentStatusResponse reenviarDocumento(String cdc, EmitirFacturaRequest payloadCorregido, boolean force) {
        Long companyId = TenantContext.get();
        if (companyId == null) {
            throw new IllegalStateException("No hay tenant configurado para la request actual");
        }

        ElectronicDocument doc = electronicDocumentRepository.findByCompanyIdAndCdc(companyId, cdc)
                .orElseThrow(() -> new IllegalArgumentException("No se encontró documento con CDC: " + cdc));

        validarReenvio(doc);

        if (!force) {
            SifenRechazoClassifier.ClasificacionRechazo clasificacion =
                    SifenRechazoClassifier.clasificar(doc.getEstado(), doc.getSifenCodigo());
            if (clasificacion.clasificacion() == SifenRechazoClassifier.ClasificacionReenvio.NO_REENVIABLE) {
                throw new IllegalArgumentException(
                        "El documento con CDC " + cdc + " no puede reenviarse con el mismo CDC: "
                                + clasificacion.accionSugerida()
                                + " Si de todas formas desea intentarlo, use ?force=true.");
            }
        }

        EmitirFacturaRequest request;
        if (payloadCorregido != null) {
            request = payloadCorregido;
            request.setParams(resolveParams(request.getParams()));
            validarReglasReceptor(request);
        } else {
            try {
                request = objectMapper.readValue(doc.getRequestData(), EmitirFacturaRequest.class);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(
                        "No se pudieron leer los datos originales del documento para reenviarlo: "
                                + e.getMessage());
            }
        }

        // Verificar que el request (almacenado o corregido) regenere exactamente el
        // mismo CDC antes de reencolar. Es el requisito legal para reutilizar el CDC,
        // y la salvaguarda real — independiente de la clasificación orientativa de arriba.
        String cdcRecalculado;
        try {
            DocumentoElectronico de = SifenMapper.toDocumentoElectronico(request);
            cdcRecalculado = de.obtenerCDC();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "No se pudo regenerar el documento electrónico para reenviarlo: " + e.getMessage());
        }

        if (!doc.getCdc().equals(cdcRecalculado)) {
            throw new IllegalArgumentException(
                    (payloadCorregido != null
                            ? "El payload corregido regeneraría un CDC distinto ("
                            : "Los datos almacenados del documento regenerarían un CDC distinto (")
                            + cdcRecalculado + ") al original (" + doc.getCdc()
                            + "). No se puede reenviar reutilizando el mismo CDC.");
        }

        String requestJsonNuevo = null;
        if (payloadCorregido != null) {
            try {
                requestJsonNuevo = objectMapper.writeValueAsString(payloadCorregido);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("No se pudo serializar el payload corregido: " + e.getMessage());
            }
        }

        String estadoAnterior = doc.getEstado();
        doc.setResponseData(archivarRechazoEnHistorial(doc));
        if (requestJsonNuevo != null) {
            doc.setRequestData(requestJsonNuevo);
        }
        doc.setEstado("PREPARADO");
        doc.setNroLote(null);
        doc.setSifenCodigo(null);
        doc.setSifenMensaje(null);
        doc.setSentAt(null);
        doc.setProcessedAt(null);
        electronicDocumentRepository.save(doc);

        log.info("[RESEND] CDC: {} — {} → PREPARADO (reencolado para reenvío con mismo CDC{})",
                cdc, estadoAnterior, payloadCorregido != null ? ", con payload corregido" : "");

        return DocumentStatusResponse.builder()
                .cdc(doc.getCdc())
                .estado(doc.getEstado())
                .qrUrl(doc.getQrUrl())
                .createdAt(doc.getCreatedAt())
                .build();
    }

    /**
     * Reencola todos los documentos RECHAZADO del tenant actual cuyo código de error
     * SIFEN coincida (por defecto 1004 — firma adelantada). Devuelve el resultado por
     * documento; los errores individuales no interrumpen el procesamiento del resto.
     */
    public List<ResendResultDTO> reenviarRechazados(String sifenCodigo) {
        Long companyId = TenantContext.get();
        if (companyId == null) {
            throw new IllegalStateException("No hay tenant configurado para la request actual");
        }

        String codigo = (sifenCodigo == null || sifenCodigo.isBlank())
                ? DEFAULT_RESEND_SIFEN_CODIGO : sifenCodigo;

        List<ElectronicDocument> docs = electronicDocumentRepository
                .findByCompanyIdAndEstadoAndSifenCodigo(companyId, "RECHAZADO", codigo);

        List<ResendResultDTO> resultados = new ArrayList<>();
        for (ElectronicDocument doc : docs) {
            try {
                reenviarDocumento(doc.getCdc(), null, false);
                resultados.add(ResendResultDTO.builder()
                        .cdc(doc.getCdc())
                        .reenviado(true)
                        .estadoAnterior("RECHAZADO")
                        .detalle("Reencolado como PREPARADO para reenvío")
                        .build());
            } catch (Exception e) {
                log.warn("[RESEND] No se pudo reencolar CDC {}: {}", doc.getCdc(), e.getMessage());
                resultados.add(ResendResultDTO.builder()
                        .cdc(doc.getCdc())
                        .reenviado(false)
                        .estadoAnterior("RECHAZADO")
                        .detalle(e.getMessage())
                        .build());
            }
        }
        return resultados;
    }

    private void validarReenvio(ElectronicDocument doc) {
        String estado = doc.getEstado();
        if (ESTADOS_REENVIABLES.contains(estado)) {
            return;
        }
        switch (estado) {
            case "PREPARADO":
                throw new IllegalArgumentException(
                        "El documento con CDC " + doc.getCdc()
                                + " ya está en cola de envío (PREPARADO). No es necesario reenviarlo.");
            case "ENVIADO":
                throw new IllegalArgumentException(
                        "El documento con CDC " + doc.getCdc()
                                + " ya fue transmitido a SIFEN y está en curso (ENVIADO). "
                                + "Espere el resultado del lote antes de reenviarlo, para evitar duplicados.");
            default:
                throw new IllegalArgumentException(
                        "El documento con CDC " + doc.getCdc() + " está en estado " + estado
                                + " y no puede reenviarse.");
        }
    }

    private boolean isApprovedState(String estado) {
        if (estado == null) return false;
        return "APROBADO".equalsIgnoreCase(estado)
                || "APROBADO_CON_OBSERVACION".equalsIgnoreCase(estado)
                || estado.toUpperCase().startsWith("APROBADO");
    }

    /**
     * Construye un KudeRequest a partir de los datos de emisión y la respuesta.
     */
    private KudeRequest buildKudeRequestFromEmision(EmitirFacturaRequest emitRequest,
                                                      EmisionDEResponse emisionResponse) {
        KudeRequest kudeReq = new KudeRequest();
        kudeReq.setParams(emitRequest.getParams());
        kudeReq.setData(emitRequest.getData());
        kudeReq.setCdc(emisionResponse.getCdc());
        kudeReq.setQrUrl(emisionResponse.getQrUrl());
        kudeReq.setEstado(emisionResponse.getEstado());
        kudeReq.setCodigoEstado(emisionResponse.getCodigoEstado());
        kudeReq.setDescripcionEstado(emisionResponse.getDescripcionEstado());
        return kudeReq;
    }
}
