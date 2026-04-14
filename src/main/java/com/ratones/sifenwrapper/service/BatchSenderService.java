package com.ratones.sifenwrapper.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratones.sifenwrapper.config.BatchProperties;
import com.ratones.sifenwrapper.dto.request.EmitirFacturaRequest;
import com.ratones.sifenwrapper.entity.ElectronicDocument;
import com.ratones.sifenwrapper.mapper.SifenMapper;
import com.ratones.sifenwrapper.repository.ElectronicDocumentRepository;
import com.roshka.sifen.Sifen;
import com.roshka.sifen.core.SifenConfig;
import com.roshka.sifen.core.beans.DocumentoElectronico;
import com.roshka.sifen.core.beans.response.RespuestaRecepcionLoteDE;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Scheduler que toma DEs con estado PREPARADO, los agrupa por empresa y tipo de documento,
 * y los envía a SIFEN en lotes de hasta 50 (según buenas prácticas SIFEN).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchSenderService {

    private final ElectronicDocumentRepository documentRepository;
    private final SifenConfigFactory sifenConfigFactory;
    private final BatchProperties batchProperties;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${sifen.batch.send-interval:60000}")
    public void enviarLotesPendientes() {
        if (!batchProperties.isEnabled()) return;

        List<Long> companyIds = documentRepository.findDistinctCompanyIdsByEstado("PREPARADO");
        if (companyIds.isEmpty()) return;

        log.info("[BATCH-SEND] Empresas con DEs pendientes: {}", companyIds.size());

        for (Long companyId : companyIds) {
            try {
                enviarLotesDeEmpresa(companyId);
            } catch (Exception e) {
                log.error("[BATCH-SEND] Error procesando empresa {}: {}", companyId, e.getMessage(), e);
            }
        }
    }

    private void enviarLotesDeEmpresa(Long companyId) {
        List<Short> tipos = documentRepository.findDistinctTipoDocumentoByCompanyIdAndEstado(
                companyId, "PREPARADO");

        for (Short tipo : tipos) {
            List<ElectronicDocument> docs = documentRepository
                    .findByCompanyIdAndEstadoAndTipoDocumento(companyId, "PREPARADO", tipo);

            if (docs.isEmpty()) continue;

            // Partir en sublotes de máx maxPerLote
            int maxPerLote = batchProperties.getMaxPerLote();
            for (int i = 0; i < docs.size(); i += maxPerLote) {
                List<ElectronicDocument> sublote = docs.subList(i, Math.min(i + maxPerLote, docs.size()));
                enviarSublote(companyId, sublote);
            }
        }
    }

    @Transactional
    void enviarSublote(Long companyId, List<ElectronicDocument> docs) {
        try {
            log.info("[BATCH-SEND] Enviando lote: empresa={}, cantidad={}, tipo={}",
                    companyId, docs.size(), docs.get(0).getTipoDocumento());

            SifenConfig config = sifenConfigFactory.getConfigForCompany(companyId);
            Sifen.setSifenConfig(config);

            // Re-crear DocumentoElectronico desde los request almacenados
            List<DocumentoElectronico> documentos = new ArrayList<>();
            List<ElectronicDocument> docsValidos = new ArrayList<>();

            for (ElectronicDocument doc : docs) {
                try {
                    EmitirFacturaRequest request = objectMapper.readValue(
                            doc.getRequestData(), EmitirFacturaRequest.class);
                    documentos.add(SifenMapper.toDocumentoElectronico(request));
                    docsValidos.add(doc);
                } catch (Exception e) {
                    log.error("[BATCH-SEND] Error reconstruyendo DE CDC={}: {}",
                            doc.getCdc(), e.getMessage());
                    doc.setEstado("ERROR");
                    doc.setSifenMensaje("Error al reconstruir DE: " + e.getMessage());
                    documentRepository.save(doc);
                }
            }

            if (documentos.isEmpty()) return;

            RespuestaRecepcionLoteDE respuesta = Sifen.recepcionLoteDE(documentos);

            if (respuesta == null) {
                log.error("[BATCH-SEND] Respuesta nula de SIFEN para lote empresa={}", companyId);
                return;
            }

            String codRes = respuesta.getdCodRes();
            String nroLote = respuesta.getdProtConsLote();
            LocalDateTime ahora = LocalDateTime.now();

            if ("0300".equals(codRes)) {
                // Lote recibido con éxito
                log.info("[BATCH-SEND] Lote recibido OK: nroLote={}, DEs={}", nroLote, docsValidos.size());
                for (ElectronicDocument doc : docsValidos) {
                    doc.setEstado("ENVIADO");
                    doc.setNroLote(nroLote);
                    doc.setSentAt(ahora);
                    doc.setSifenCodigo(codRes);
                    doc.setSifenMensaje(respuesta.getdMsgRes());
                    documentRepository.save(doc);
                }
            } else {
                // Lote rechazado (0301) u otro error
                log.warn("[BATCH-SEND] Lote rechazado: código={}, mensaje={}", codRes, respuesta.getdMsgRes());
                for (ElectronicDocument doc : docsValidos) {
                    doc.setEstado("ERROR");
                    doc.setSifenCodigo(codRes);
                    doc.setSifenMensaje(respuesta.getdMsgRes());
                    documentRepository.save(doc);
                }
            }

        } catch (Exception e) {
            log.error("[BATCH-SEND] Error enviando lote empresa={}: {}", companyId, e.getMessage(), e);
            // Los documentos quedan en PREPARADO para reintentar en el siguiente ciclo
        }
    }
}
