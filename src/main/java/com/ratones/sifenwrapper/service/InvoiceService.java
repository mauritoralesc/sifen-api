package com.ratones.sifenwrapper.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roshka.sifen.Sifen;
import com.roshka.sifen.core.SifenConfig;
import com.roshka.sifen.core.beans.DocumentoElectronico;
import com.roshka.sifen.core.beans.EventosDE;
import com.roshka.sifen.core.beans.response.*;
import com.roshka.sifen.core.exceptions.SifenException;
import com.roshka.sifen.core.fields.request.de.*;
import com.roshka.sifen.core.fields.request.event.*;
import com.roshka.sifen.core.fields.response.TgResProc;
import com.roshka.sifen.core.fields.response.batch.TgResProcLote;
import com.roshka.sifen.core.fields.response.de.TxContenDE;
import com.roshka.sifen.core.fields.response.event.TgResProcEVe;
import com.roshka.sifen.core.types.*;
import com.roshka.sifen.internal.ctx.GenerationCtx;
import com.ratones.sifenwrapper.dto.request.EmitirFacturaRequest;
import com.ratones.sifenwrapper.dto.request.EventoRequest;
import com.ratones.sifenwrapper.dto.request.KudeRequest;
import com.ratones.sifenwrapper.dto.request.ParamsDTO;
import com.ratones.sifenwrapper.dto.response.*;
import com.ratones.sifenwrapper.dto.response.consulta.*;
import com.ratones.sifenwrapper.entity.ElectronicDocument;
import com.ratones.sifenwrapper.mapper.SifenMapper;
import com.ratones.sifenwrapper.repository.ElectronicDocumentRepository;
import com.ratones.sifenwrapper.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    private final SifenConfigFactory sifenConfigFactory;
    private final CompanyService companyService;
    private final KudeService kudeService;
    private final ElectronicDocumentRepository electronicDocumentRepository;
    private final ObjectMapper objectMapper;

    // ─── Emisión DE (Recepción Síncrona) ──────────────────────────────────────

    public EmisionDEResponse emitirFactura(EmitirFacturaRequest request) {
        try {
            // Auto-fill params desde la config de la empresa si no vienen en el request
            request.setParams(resolveParams(request.getParams()));

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

            Sifen.setSifenConfig(sifenConfig);

            // Mapear DTO → DocumentoElectronico
            DocumentoElectronico de = SifenMapper.toDocumentoElectronico(request);

            // Llamar al servicio de recepción síncrona de SIFEN
            RespuestaRecepcionDE respuesta = Sifen.recepcionDE(de);

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

            log.info("[PREPARE] Preparando DE - Establecimiento: {}, Punto: {}, Número: {}",
                    request.getData().getEstablecimiento(),
                    request.getData().getPunto(),
                    request.getData().getNumero());

            SifenConfig sifenConfig = sifenConfigFactory.getConfigForCurrentTenant();

            if (request.getQr() != null && request.getQr().getCsc() != null) {
                sifenConfig.setIdCSC(request.getQr().getIdCSC());
                sifenConfig.setCSC(request.getQr().getCsc());
            }

            Sifen.setSifenConfig(sifenConfig);

            DocumentoElectronico de = SifenMapper.toDocumentoElectronico(request);

            // Generar XML firmado + CDC + QR sin enviar a SIFEN
            GenerationCtx ctx = GenerationCtx.getDefaultFromConfig(sifenConfig);
            String xmlFirmado = de.generarXml(ctx, sifenConfig);
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
            electronicDocumentRepository.save(doc);

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
        Optional<ElectronicDocument> optDoc = electronicDocumentRepository.findByCdc(cdc);

        if (optDoc.isEmpty()) {
            // No existe localmente — intentar consulta directa a SIFEN
            if (refresh) {
                try {
                    ConsultaDEResponse sifenResp = consultarDE(cdc);
                    return DocumentStatusResponse.builder()
                            .cdc(cdc)
                            .estado(sifenResp.getEstado())
                            .codigoEstado(sifenResp.getCodigoEstado())
                            .descripcionEstado(sifenResp.getDescripcionEstado())
                            .qrUrl(sifenResp.getQrUrl())
                            .build();
                } catch (Exception e) {
                    log.warn("No se pudo consultar SIFEN para CDC {}: {}", cdc, e.getMessage());
                }
            }
            return DocumentStatusResponse.builder()
                    .cdc(cdc)
                    .estado("NO_ENCONTRADO")
                    .descripcionEstado("No se encontró el documento con CDC: " + cdc)
                    .build();
        }

        ElectronicDocument doc = optDoc.get();

        // Si refresh=true y el estado es ENVIADO (ya está en SIFEN pero sin resultado),
        // consultar a SIFEN para obtener el estado final.
        // NO consultar si está PREPARADO (aún no fue enviado, SIFEN respondería "No Existe").
        if (refresh && "ENVIADO".equals(doc.getEstado())) {
            try {
                // Configurar tenant para la consulta
                SifenConfig sifenConfig = sifenConfigFactory.getConfigForCompany(doc.getCompanyId());
                Sifen.setSifenConfig(sifenConfig);
                RespuestaConsultaDE respuesta = Sifen.consultaDE(cdc);

                if (respuesta != null && respuesta.getdCodRes() != null) {
                    String nuevoEstado = resolverEstadoDocumento(respuesta.getdCodRes());
                    if (!"DESCONOCIDO".equals(nuevoEstado) && !nuevoEstado.startsWith("CODIGO_")) {
                        doc.setEstado(nuevoEstado);
                        doc.setSifenCodigo(respuesta.getdCodRes());
                        doc.setSifenMensaje(respuesta.getdMsgRes());
                        doc.setProcessedAt(LocalDateTime.now());
                        electronicDocumentRepository.save(doc);
                        log.info("[STATUS-UPDATE] CDC: {} → {}", cdc, nuevoEstado);
                    }
                }
            } catch (Exception e) {
                log.warn("Error al refrescar estado por CDC {}: {}", cdc, e.getMessage());
            }
        }

        return DocumentStatusResponse.builder()
                .cdc(doc.getCdc())
                .estado(doc.getEstado())
                .codigoEstado(doc.getSifenCodigo())
                .descripcionEstado(doc.getSifenMensaje())
                .nroLote(doc.getNroLote())
                .qrUrl(doc.getQrUrl())
                .createdAt(doc.getCreatedAt())
                .sentAt(doc.getSentAt())
                .processedAt(doc.getProcessedAt())
                .build();
    }

    // ─── Recepción Lote (Asíncrono) ───────────────────────────────────────────

    public RecepcionLoteResponse enviarLote(List<EmitirFacturaRequest> requests) {
        try {
            log.info("Enviando lote de {} documentos", requests.size());

            Sifen.setSifenConfig(sifenConfigFactory.getConfigForCurrentTenant());

            List<DocumentoElectronico> documentos = new ArrayList<>();
            for (EmitirFacturaRequest req : requests) {
                req.setParams(resolveParams(req.getParams()));
                documentos.add(SifenMapper.toDocumentoElectronico(req));
            }

            RespuestaRecepcionLoteDE respuesta = Sifen.recepcionLoteDE(documentos);
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
            Sifen.setSifenConfig(sifenConfigFactory.getConfigForCurrentTenant());
            RespuestaConsultaLoteDE respuesta = Sifen.consultaLoteDE(nroLote);
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
            Sifen.setSifenConfig(sifenConfigFactory.getConfigForCurrentTenant());
            RespuestaConsultaDE respuesta = Sifen.consultaDE(cdc);
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

            Sifen.setSifenConfig(sifenConfigFactory.getConfigForCurrentTenant());
            RespuestaConsultaRUC respuesta = Sifen.consultaRUC(rucSinDV);
            return buildConsultaRucResponse(ruc, respuesta);
        } catch (SifenException e) {
            log.error("Error SIFEN al consultar RUC {}: {}", ruc, e.getMessage(), e);
            throw new com.ratones.sifenwrapper.exception.SifenServiceException(
                    "Error al consultar RUC: " + ruc, e);
        }
    }

    // ─── Recepción Evento ─────────────────────────────────────────────────────

    public RecepcionEventoResponse enviarEvento(EventoRequest request) {
        try {
            log.info("Enviando evento tipo {} para CDC: {}", request.getTipoEvento(), request.getCdc());

            Sifen.setSifenConfig(sifenConfigFactory.getConfigForCurrentTenant());

            EventosDE eventosDE = new EventosDE();
            TgGroupTiEvt grupo = new TgGroupTiEvt();

            switch (request.getTipoEvento()) {
                case 1: { // Cancelación
                    TrGeVeCan cancelacion = new TrGeVeCan();
                    cancelacion.setId(request.getCdc());
                    cancelacion.setmOtEve(request.getMotivo());
                    grupo.setrGeVeCan(cancelacion);
                    break;
                }
                case 2: { // Inutilización
                    TrGeVeInu inutilizacion = new TrGeVeInu();
                    inutilizacion.setdNumTim(request.getTimbrado());
                    inutilizacion.setdEst(request.getEstablecimiento());
                    inutilizacion.setdPunExp(request.getPuntoExpedicion());
                    inutilizacion.setdNumIn(request.getNumeroDesde());
                    inutilizacion.setdNumFin(request.getNumeroHasta());
                    inutilizacion.setiTiDE(TTiDE.getByVal(request.getTipoDocumento().shortValue()));
                    inutilizacion.setmOtEve(request.getMotivo());
                    grupo.setrGeVeInu(inutilizacion);
                    break;
                }
                case 3: { // Conformidad del receptor
                    TrGeVeConf conformidad = new TrGeVeConf();
                    conformidad.setId(request.getCdc());
                    conformidad.setiTipConf(TiTipConf.getByVal(request.getTipoConformidad().shortValue()));
                    conformidad.setdFecRecep(LocalDateTime.parse(request.getFechaRecepcion()));
                    grupo.setrGeVeConf(conformidad);
                    break;
                }
                case 4: { // Disconformidad del receptor
                    TrGeVeDisconf disconformidad = new TrGeVeDisconf();
                    disconformidad.setId(request.getCdc());
                    disconformidad.setmOtEve(request.getMotivo());
                    grupo.setrGeVeDisconf(disconformidad);
                    break;
                }
                case 5: { // Desconocimiento del receptor
                    TrGeVeDescon desconocimiento = new TrGeVeDescon();
                    desconocimiento.setId(request.getCdc());
                    if (request.getFechaEmision() != null) {
                        desconocimiento.setdFecEmi(LocalDateTime.parse(request.getFechaEmision()));
                    }
                    if (request.getFechaRecepcion() != null) {
                        desconocimiento.setdFecRecep(LocalDateTime.parse(request.getFechaRecepcion()));
                    }
                    if (request.getReceptorContribuyente() != null) {
                        desconocimiento.setiTipRec(request.getReceptorContribuyente()
                                ? TiNatRec.CONTRIBUYENTE : TiNatRec.NO_CONTRIBUYENTE);
                    }
                    desconocimiento.setdNomRec(request.getNombreReceptor());
                    desconocimiento.setdRucRec(request.getRucReceptor());
                    desconocimiento.setdDVRec(request.getDvReceptor());
                    if (request.getTipoDocIdentidad() != null) {
                        desconocimiento.setdTipIDRec(TiTipDocRec.getByVal(request.getTipoDocIdentidad().shortValue()));
                    }
                    desconocimiento.setdNumID(request.getNumeroDocIdentidad());
                    desconocimiento.setmOtEve(request.getMotivo());
                    grupo.setrGeVeDescon(desconocimiento);
                    break;
                }
                case 6: { // Notificación de recepción
                    TrGeVeNotRec notificacion = new TrGeVeNotRec();
                    notificacion.setId(request.getCdc());
                    if (request.getFechaEmision() != null) {
                        notificacion.setdFecEmi(LocalDateTime.parse(request.getFechaEmision()));
                    }
                    if (request.getFechaRecepcion() != null) {
                        notificacion.setdFecRecep(LocalDateTime.parse(request.getFechaRecepcion()));
                    }
                    if (request.getReceptorContribuyente() != null) {
                        notificacion.setiTipRec(request.getReceptorContribuyente()
                                ? TiNatRec.CONTRIBUYENTE : TiNatRec.NO_CONTRIBUYENTE);
                    }
                    notificacion.setdNomRec(request.getNombreReceptor());
                    notificacion.setdRucRec(request.getRucReceptor());
                    notificacion.setdDVRec(request.getDvReceptor());
                    if (request.getTipoDocIdentidad() != null) {
                        notificacion.setdTipIDRec(TiTipDocRec.getByVal(request.getTipoDocIdentidad().shortValue()));
                    }
                    notificacion.setdNumID(request.getNumeroDocIdentidad());
                    notificacion.setdTotalGs(request.getTotalGs());
                    grupo.setrGeVeNotRec(notificacion);
                    break;
                }
                default:
                    throw new IllegalArgumentException("Tipo de evento no soportado: " + request.getTipoEvento());
            }

            TrGesEve gesEve = new TrGesEve();
            gesEve.setId("1");
            gesEve.setdFecFirma(LocalDateTime.now(PY_ZONE));
            gesEve.setgGroupTiEvt(grupo);

            eventosDE.setrGesEveList(List.of(gesEve));

            RespuestaRecepcionEvento respuesta = Sifen.recepcionEvento(eventosDE);
            return buildEventoResponse(request.getCdc(), respuesta);

        } catch (SifenException e) {
            log.error("Error SIFEN al enviar evento: {}", e.getMessage(), e);
            throw new com.ratones.sifenwrapper.exception.SifenServiceException(
                    "Error al enviar evento", e);
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
                        String descripcionItem = null;
                        if (item.getgResProc() != null && !item.getgResProc().isEmpty()) {
                            descripcionItem = item.getgResProc().get(0).getdMsgRes();
                        }
                        resultados.add(ResultadoLoteItemDTO.builder()
                                .cdc(item.getId())
                                .estado(item.getdEstRes())
                                .descripcion(descripcionItem)
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

    private ConsultaRucResponse buildConsultaRucResponse(String ruc, RespuestaConsultaRUC respuesta) {
        ConsultaRucResponse.ConsultaRucResponseBuilder builder = ConsultaRucResponse.builder().ruc(ruc);
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
            builder.respuestaSifen(buildRespuestaSifenDTO(respuesta));
        }
        return builder.build();
    }

    private RecepcionEventoResponse buildEventoResponse(String cdc, RespuestaRecepcionEvento respuesta) {
        RecepcionEventoResponse.RecepcionEventoResponseBuilder builder =
                RecepcionEventoResponse.builder().cdc(cdc);
        if (respuesta != null) {
            // Extraer estado del resultado de procesamiento del evento
            if (respuesta.getgResProcEVe() != null && !respuesta.getgResProcEVe().isEmpty()) {
                TgResProcEVe primerRes = respuesta.getgResProcEVe().get(0);
                String estadoEvento = primerRes.getdEstRes();
                builder.estado(estadoEvento != null ? estadoEvento.toUpperCase() : "DESCONOCIDO");

                if (primerRes.getgResProc() != null && !primerRes.getgResProc().isEmpty()) {
                    TgResProc primerProc = primerRes.getgResProc().get(0);
                    builder.codigoEstado(primerProc.getdCodRes())
                            .descripcionEstado(primerProc.getdMsgRes());

                    List<MensajeSifenDTO> mensajes = new ArrayList<>();
                    for (TgResProc msg : primerRes.getgResProc()) {
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
            } else if (respuesta.getdCodRes() != null) {
                // Fallback a BaseResponse (datos parseados por la librería)
                builder.codigoEstado(respuesta.getdCodRes())
                        .descripcionEstado(respuesta.getdMsgRes())
                        .estado(resolverEstado(respuesta.getdCodRes()));
            } else if (respuesta.getRespuestaBruta() != null) {
                String raw = respuesta.getRespuestaBruta();

                // Detectar respuestas no-XML (ej. HTML de BIG-IP/F5 por sesión expirada)
                if (raw.trim().startsWith("<html") || raw.trim().startsWith("<!DOCTYPE html")) {
                    builder.estado("ERROR_CONEXION")
                            .codigoEstado("CONN_ERR")
                            .descripcionEstado("SIFEN devolvió una página HTML en lugar de XML. " +
                                    "Posible sesión SSL expirada o problema de red. Reinicie la aplicación e intente nuevamente.");
                    log.error("SIFEN devolvió HTML en lugar de XML. Posible problema de sesión SSL/red. HTTP status={}",
                            respuesta.getCodigoEstado());
                } else {
                    // Parsear XML bruto para extraer estado/error
                    // Esto ocurre cuando SIFEN responde con un wrapper inesperado (ej. rRetEnviDe)
                    String estRes = extraerTagXml(raw, "dEstRes");
                    String codRes = extraerTagXml(raw, "dCodRes");
                    String msgRes = extraerTagXml(raw, "dMsgRes");

                    if (estRes != null) {
                        builder.estado(estRes.toUpperCase());
                    } else {
                        builder.estado("DESCONOCIDO");
                    }
                    builder.codigoEstado(codRes).descripcionEstado(msgRes);

                    if (codRes != null) {
                        builder.mensajes(List.of(MensajeSifenDTO.builder()
                                .codigo(codRes).descripcion(msgRes).build()));
                    }
                    log.warn("Respuesta SIFEN con wrapper inesperado. Estado={}, Código={}, Mensaje={}",
                            estRes, codRes, msgRes);
                }
            } else {
                builder.estado("DESCONOCIDO");
            }

            builder.respuestaSifen(RespuestaSifenDTO.builder()
                    .codigoRespuesta(respuesta.getCodigoEstado())
                    .descripcionRespuesta(respuesta.getdMsgRes())
                    .xmlRespuesta(respuesta.getRespuestaBruta())
                    .xmlEnviado(respuesta.getRequestSent())
                    .build());
        }
        return builder.build();
    }

    private static final String MSG_ERROR_CONEXION =
            "SIFEN devolvió una página HTML en lugar de XML. " +
            "Posible sesión SSL expirada o problema de red/infraestructura del servidor SIFEN. " +
            "Reinicie la aplicación e intente nuevamente.";

    private boolean esRespuestaHtml(String raw) {
        if (raw == null) return false;
        String trimmed = raw.trim().toLowerCase();
        return trimmed.startsWith("<html") || trimmed.startsWith("<!doctype html");
    }

    private RespuestaSifenDTO buildRespuestaSifenDTO(com.roshka.sifen.internal.response.BaseResponse respuesta) {
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
    private String extraerTagXml(String xml, String tagName) {
        if (xml == null) return null;
        Pattern p = Pattern.compile("<(?:[\\w]+:)?" + tagName + "[^>]*>([^<]+)</");
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * Mapea códigos de estado SIFEN a descripciones legibles.
     * Ver Manual Técnico SIFEN v150 - Tabla de códigos de respuesta.
     */
    private String resolverEstado(String codigoEstado) {
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
     */
    String resolverEstadoDocumento(String codigoEstado) {
        if (codigoEstado == null) return "DESCONOCIDO";
        switch (codigoEstado) {
            case "0260": return "APROBADO";
            case "0261": return "APROBADO_CON_OBSERVACION";
            case "0262": return "RECHAZADO";
            case "0422": return "APROBADO";      // Consulta CDC: aprobado
            case "0420": return "RECHAZADO";     // Consulta CDC: rechazado
            default: return "DESCONOCIDO";
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
        return resolved;
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
