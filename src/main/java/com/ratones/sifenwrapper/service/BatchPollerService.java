package com.ratones.sifenwrapper.service;

import com.ratones.sifenwrapper.config.BatchProperties;
import com.ratones.sifenwrapper.entity.ElectronicDocument;
import com.ratones.sifenwrapper.repository.ElectronicDocumentRepository;
import com.roshka.sifen.Sifen;
import com.roshka.sifen.core.SifenConfig;
import com.roshka.sifen.core.beans.response.RespuestaConsultaDE;
import com.roshka.sifen.core.beans.response.RespuestaConsultaLoteDE;
import com.roshka.sifen.core.fields.response.batch.TgResProcLote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Scheduler que consulta el estado de lotes enviados a SIFEN y actualiza
 * los documentos electrónicos con el resultado final.
 *
 * Respeta las buenas prácticas SIFEN:
 * - No consulta lotes con menos de 10 minutos desde el envío.
 * - Lotes con más de 48h se consultan por CDC individual.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchPollerService {

    private final ElectronicDocumentRepository documentRepository;
    private final SifenConfigFactory sifenConfigFactory;
    private final InvoiceService invoiceService;
    private final InvoiceEmailService invoiceEmailService;
    private final BatchProperties batchProperties;

    @Scheduled(fixedDelayString = "${sifen.batch.poll-interval:600000}")
    public void consultarLotesPendientes() {
        if (!batchProperties.isEnabled()) return;

        LocalDateTime cutoff = LocalDateTime.now()
                .minusSeconds(batchProperties.getMinWaitBeforePoll());

        List<String> nroLotes = documentRepository.findDistinctNroLoteForPolling(cutoff);
        if (nroLotes.isEmpty()) return;

        log.info("[BATCH-POLL] Lotes pendientes de consulta: {}", nroLotes.size());

        for (String nroLote : nroLotes) {
            try {
                consultarLote(nroLote);
            } catch (Exception e) {
                log.error("[BATCH-POLL] Error consultando lote {}: {}", nroLote, e.getMessage(), e);
            }
        }

        // Fallback: DEs enviados hace más de maxPollAgeHours sin resultado → consulta por CDC
        consultarDocumentosExtemporaneos();
    }

    @Transactional
    void consultarLote(String nroLote) {
        List<ElectronicDocument> docs = documentRepository.findByNroLote(nroLote);
        if (docs.isEmpty()) return;

        // Todos los docs de un lote pertenecen a la misma empresa
        Long companyId = docs.get(0).getCompanyId();

        try {
            SifenConfig config = sifenConfigFactory.getConfigForCompany(companyId);
            Sifen.setSifenConfig(config);
            RespuestaConsultaLoteDE respuesta = Sifen.consultaLoteDE(nroLote);

            if (respuesta == null) {
                log.warn("[BATCH-POLL] Respuesta nula para lote {}", nroLote);
                return;
            }

            String codResLot = respuesta.getdCodResLot() != null
                    ? respuesta.getdCodResLot() : respuesta.getdCodRes();

            log.info("[BATCH-POLL] Lote {}: código={}, mensaje={}",
                    nroLote, codResLot, respuesta.getdMsgResLot());

            switch (codResLot != null ? codResLot : "") {
                case "0362": // LOTE_CONCLUIDO
                    procesarResultadoLote(docs, respuesta);
                    break;

                case "0361": // EN PROCESAMIENTO
                    log.info("[BATCH-POLL] Lote {} aún en procesamiento", nroLote);
                    break;

                case "0360": // LOTE INEXISTENTE
                    log.warn("[BATCH-POLL] Lote {} inexistente en SIFEN", nroLote);
                    for (ElectronicDocument doc : docs) {
                        doc.setEstado("ERROR");
                        doc.setSifenCodigo(codResLot);
                        doc.setSifenMensaje("Lote inexistente en SIFEN: " + nroLote);
                        documentRepository.save(doc);
                    }
                    break;

                case "0364": // CONSULTA EXTEMPORÁNEA (>48h)
                    log.warn("[BATCH-POLL] Consulta extemporánea lote {}. Pasando a consulta individual por CDC.", nroLote);
                    consultarPorCdcIndividual(docs, companyId);
                    break;

                default:
                    log.warn("[BATCH-POLL] Código no esperado para lote {}: {}", nroLote, codResLot);
                    break;
            }

        } catch (Exception e) {
            log.error("[BATCH-POLL] Error SIFEN consultando lote {}: {}", nroLote, e.getMessage(), e);
        }
    }

    private void procesarResultadoLote(List<ElectronicDocument> docs,
                                       RespuestaConsultaLoteDE respuesta) {
        if (respuesta.getgResProcLoteList() == null || respuesta.getgResProcLoteList().isEmpty()) {
            log.warn("[BATCH-POLL] Lote concluido pero sin resultados por CDC");
            return;
        }

        // Indexar resultados por CDC
        Map<String, TgResProcLote> resultadosPorCdc = respuesta.getgResProcLoteList().stream()
                .filter(r -> r.getId() != null)
                .collect(Collectors.toMap(TgResProcLote::getId, r -> r, (a, b) -> b));

        LocalDateTime ahora = LocalDateTime.now();

        for (ElectronicDocument doc : docs) {
            TgResProcLote resultado = resultadosPorCdc.get(doc.getCdc());
            if (resultado == null) {
                log.warn("[BATCH-POLL] CDC {} no encontrado en resultados del lote", doc.getCdc());
                continue;
            }

            String estadoSifen = resultado.getdEstRes(); // "Aprobado", "Rechazado", etc.
            String nuevoEstado = mapearEstadoLote(estadoSifen);
            String estadoAnterior = doc.getEstado();

            doc.setEstado(nuevoEstado);
            doc.setProcessedAt(ahora);

            if (resultado.getgResProc() != null && !resultado.getgResProc().isEmpty()) {
                doc.setSifenCodigo(resultado.getgResProc().get(0).getdCodRes());
                doc.setSifenMensaje(resultado.getgResProc().get(0).getdMsgRes());
            }

            documentRepository.save(doc);
            log.info("[STATUS-UPDATE] CDC: {} — {} → {}", doc.getCdc(), estadoAnterior, nuevoEstado);

            if (!estadoAnterior.equals(nuevoEstado) && isApprovedState(nuevoEstado)) {
                InvoiceEmailService.EmailDispatchResult emailResult =
                        invoiceEmailService.sendApprovedEmail(doc);
                if (!emailResult.sent()) {
                    log.warn("[EMAIL] No se envió correo para CDC {}: {}", doc.getCdc(), emailResult.reason());
                }
            }
        }
    }

    private void consultarDocumentosExtemporaneos() {
        LocalDateTime cutoff = LocalDateTime.now()
                .minusHours(batchProperties.getMaxPollAgeHours());

        List<ElectronicDocument> docsViejos = documentRepository.findSentDocumentsOlderThan(cutoff);
        if (docsViejos.isEmpty()) return;

        log.info("[BATCH-POLL] DEs extemporáneos (>{}h) para consulta individual: {}",
                batchProperties.getMaxPollAgeHours(), docsViejos.size());

        // Agrupar por empresa
        Map<Long, List<ElectronicDocument>> porEmpresa = docsViejos.stream()
                .collect(Collectors.groupingBy(ElectronicDocument::getCompanyId));

        for (Map.Entry<Long, List<ElectronicDocument>> entry : porEmpresa.entrySet()) {
            consultarPorCdcIndividual(entry.getValue(), entry.getKey());
        }
    }

    private void consultarPorCdcIndividual(List<ElectronicDocument> docs, Long companyId) {
        try {
            SifenConfig config = sifenConfigFactory.getConfigForCompany(companyId);
            Sifen.setSifenConfig(config);

            for (ElectronicDocument doc : docs) {
                try {
                    RespuestaConsultaDE respuesta = Sifen.consultaDE(doc.getCdc());
                    if (respuesta != null && respuesta.getdCodRes() != null) {
                        String nuevoEstado = invoiceService.resolverEstadoDocumento(respuesta.getdCodRes());
                        if (!"DESCONOCIDO".equals(nuevoEstado)) {
                            String estadoAnterior = doc.getEstado();
                            doc.setEstado(nuevoEstado);
                            doc.setSifenCodigo(respuesta.getdCodRes());
                            doc.setSifenMensaje(respuesta.getdMsgRes());
                            doc.setProcessedAt(LocalDateTime.now());
                            documentRepository.save(doc);
                            log.info("[STATUS-UPDATE] CDC: {} — {} → {} (consulta individual)",
                                    doc.getCdc(), estadoAnterior, nuevoEstado);

                            if (!estadoAnterior.equals(nuevoEstado) && isApprovedState(nuevoEstado)) {
                                InvoiceEmailService.EmailDispatchResult emailResult =
                                        invoiceEmailService.sendApprovedEmail(doc);
                                if (!emailResult.sent()) {
                                    log.warn("[EMAIL] No se envió correo para CDC {}: {}",
                                            doc.getCdc(), emailResult.reason());
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("[BATCH-POLL] Error consultando CDC {}: {}", doc.getCdc(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[BATCH-POLL] Error obteniendo config empresa {}: {}", companyId, e.getMessage());
        }
    }

    private String mapearEstadoLote(String estadoSifen) {
        if (estadoSifen == null) return "DESCONOCIDO";
        switch (estadoSifen.toLowerCase()) {
            case "aprobado": return "APROBADO";
            case "aprobado con observación":
            case "aprobado con observacion": return "APROBADO_CON_OBSERVACION";
            case "rechazado": return "RECHAZADO";
            default: return "DESCONOCIDO";
        }
    }

    private boolean isApprovedState(String estado) {
        return "APROBADO".equalsIgnoreCase(estado)
                || "APROBADO_CON_OBSERVACION".equalsIgnoreCase(estado);
    }
}
