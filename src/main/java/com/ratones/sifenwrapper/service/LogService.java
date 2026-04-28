package com.ratones.sifenwrapper.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratones.sifenwrapper.dto.response.AuditLogDTO;
import com.ratones.sifenwrapper.dto.response.ElectronicDocumentLogDTO;
import com.ratones.sifenwrapper.dto.response.PageResponse;
import com.ratones.sifenwrapper.entity.AuditLog;
import com.ratones.sifenwrapper.entity.ElectronicDocument;
import com.ratones.sifenwrapper.repository.AuditLogRepository;
import com.ratones.sifenwrapper.repository.ElectronicDocumentRepository;
import com.ratones.sifenwrapper.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogService {

    private final ElectronicDocumentRepository documentRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public PageResponse<ElectronicDocumentLogDTO> getTransactionLogs(Long companyId, String estado, Pageable pageable) {
        Long filterCompanyId = resolveCompanyFilter(companyId);

        Page<ElectronicDocument> page;
        if (filterCompanyId != null && estado != null) {
            page = documentRepository.findAllByCompanyIdAndEstadoOrderByCreatedAtDesc(filterCompanyId, estado, pageable);
        } else if (filterCompanyId != null) {
            page = documentRepository.findAllByCompanyIdOrderByCreatedAtDesc(filterCompanyId, pageable);
        } else if (estado != null) {
            page = documentRepository.findAllByEstadoOrderByCreatedAtDesc(estado, pageable);
        } else {
            page = documentRepository.findAllByOrderByCreatedAtDesc(pageable);
        }

        List<ElectronicDocumentLogDTO> content = page.getContent().stream()
                .map(this::mapToTransactionDto)
                .toList();

        return PageResponse.of(page, content);
    }

    public PageResponse<AuditLogDTO> getAuditLogs(Long companyId, Pageable pageable) {
        Long filterCompanyId = resolveCompanyFilter(companyId);

        Page<AuditLog> page;
        if (filterCompanyId != null) {
            page = auditLogRepository.findAllByCompanyIdOrderByCreatedAtDesc(filterCompanyId, pageable);
        } else {
            page = auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
        }

        List<AuditLogDTO> content = page.getContent().stream()
                .map(this::mapToAuditDto)
                .toList();

        return PageResponse.of(page, content);
    }

    private Long resolveCompanyFilter(Long requestedCompanyId) {
        Long tenantCompanyId = TenantContext.get();
        return tenantCompanyId != null ? tenantCompanyId : requestedCompanyId;
    }

    private ElectronicDocumentLogDTO mapToTransactionDto(ElectronicDocument entity) {
        return ElectronicDocumentLogDTO.builder()
                .id(entity.getId())
                .companyId(entity.getCompanyId())
                .cdc(entity.getCdc())
                .tipoDocumento(entity.getTipoDocumento())
                .numero(entity.getNumero())
                .establecimiento(entity.getEstablecimiento())
                .punto(entity.getPunto())
                .estado(entity.getEstado())
                .nroLote(entity.getNroLote())
                .sifenCodigo(entity.getSifenCodigo())
                .sifenMensaje(entity.getSifenMensaje())
                .requestData(parseJson(entity.getRequestData()))
                .responseData(parseJson(entity.getResponseData()))
                .createdAt(entity.getCreatedAt())
                .sentAt(entity.getSentAt())
                .processedAt(entity.getProcessedAt())
                .build();
    }

    private AuditLogDTO mapToAuditDto(AuditLog entity) {
        return AuditLogDTO.builder()
                .id(entity.getId())
                .companyId(entity.getCompanyId())
                .userId(entity.getUserId())
                .action(entity.getAction())
                .resource(entity.getResource())
                .detail(entity.getDetail())
                .ip(entity.getIp())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private Object parseJson(String jsonStr) {
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readTree(jsonStr);
        } catch (JsonProcessingException e) {
            log.warn("Error parsing JSON for log view", e);
            return jsonStr;
        }
    }
}
