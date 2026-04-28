package com.ratones.sifenwrapper.controller;

import com.ratones.sifenwrapper.dto.response.AuditLogDTO;
import com.ratones.sifenwrapper.dto.response.ElectronicDocumentLogDTO;
import com.ratones.sifenwrapper.dto.response.PageResponse;
import com.ratones.sifenwrapper.dto.response.SifenApiResponse;
import com.ratones.sifenwrapper.service.LogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/logs")
@RequiredArgsConstructor
public class LogController {

    private final LogService logService;

    @GetMapping("/transactions")
    public ResponseEntity<SifenApiResponse<PageResponse<ElectronicDocumentLogDTO>>> getTransactionLogs(
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) String estado,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.info("GET /logs/transactions - companyId={}, estado={}, page={}, size={}", companyId, estado, page, size);

        Pageable pageable = PageRequest.of(page, size);
        PageResponse<ElectronicDocumentLogDTO> response = logService.getTransactionLogs(companyId, estado, pageable);
        return ResponseEntity.ok(SifenApiResponse.ok(response));
    }

    @GetMapping("/audit")
    public ResponseEntity<SifenApiResponse<PageResponse<AuditLogDTO>>> getAuditLogs(
            @RequestParam(required = false) Long companyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.info("GET /logs/audit - companyId={}, page={}, size={}", companyId, page, size);

        Pageable pageable = PageRequest.of(page, size);
        PageResponse<AuditLogDTO> response = logService.getAuditLogs(companyId, pageable);
        return ResponseEntity.ok(SifenApiResponse.ok(response));
    }
}
