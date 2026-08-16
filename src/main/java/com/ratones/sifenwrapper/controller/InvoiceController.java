package com.ratones.sifenwrapper.controller;

import com.ratones.sifenwrapper.dto.request.EmitirFacturaRequest;
import com.ratones.sifenwrapper.dto.request.EventoRequest;
import com.ratones.sifenwrapper.dto.request.KudeRequest;
import com.ratones.sifenwrapper.dto.response.*;
import com.ratones.sifenwrapper.service.EventoService;
import com.ratones.sifenwrapper.service.InvoiceEmailService;
import com.ratones.sifenwrapper.service.InvoiceService;
import com.ratones.sifenwrapper.service.KudeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Controlador REST para operaciones con Documentos Electrónicos SIFEN.
 *
 * Endpoints disponibles:
 *  POST /invoices/emit                    → Recepción DE Síncrona
 *  POST /invoices/emit/batch              → Recepción Lote DE Asíncrona
 *  POST /invoices/kude                    → Generar KUDE (PDF) de un DE
 *  GET  /invoices/{cdc}                   → Consulta DE por CDC
 *  GET  /invoices/batch/{nroLote}         → Consulta Estado Lote
 *  GET  /invoices/ruc/{ruc}               → Consulta RUC
 *  POST /invoices/events                  → Recepción Evento (cancelación, inutilización, etc.)
 *  GET  /invoices/{cdc}/events            → Historial de eventos de un CDC
 *  GET  /invoices/events                  → Listado paginado de eventos (filtros tipo/estado/fecha)
 *  POST /invoices/events/{id}/reconcile   → Reconciliación bajo demanda de un evento INDETERMINADO
 *  POST /invoices/{cdc}/resend            → Reenvía un DE RECHAZADO/ERROR con el mismo CDC
 *  POST /invoices/resend-rejected         → Reenvía todos los RECHAZADO por código SIFEN
 */
@Slf4j
@RestController
@RequestMapping("/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final EventoService eventoService;
    private final KudeService kudeService;

    /**
     * Emite un Documento Electrónico (factura, nota crédito/débito, etc.)
     * mediante el servicio síncrono de SIFEN.
     *
     * Tipos de documento soportados:
     *   1 = Factura Electrónica
     *   4 = Auto-factura Electrónica
     *   5 = Nota de Crédito Electrónica
     *   6 = Nota de Débito Electrónica
     *   7 = Nota de Remisión Electrónica
     */
    @PostMapping("/emit")
    public ResponseEntity<SifenApiResponse<EmisionDEResponse>> emitirFactura(
            @RequestBody EmitirFacturaRequest request) {

        log.info("POST /invoices/emit - tipoDocumento={}", request.getData().getTipoDocumento());
        EmisionDEResponse response = invoiceService.emitirFactura(request);
        return ResponseEntity.ok(SifenApiResponse.ok(response, "Documento electrónico enviado correctamente"));
    }

    /**
     * Envía un lote de Documentos Electrónicos (recepción asíncrona).
     * Devuelve un nroLote para consultar el estado posteriormente.
     */
    @PostMapping("/emit/batch")
    public ResponseEntity<SifenApiResponse<RecepcionLoteResponse>> emitirLote(
            @RequestBody List<EmitirFacturaRequest> requests) {

        log.info("POST /invoices/emit/batch - cantidad={}", requests.size());
        RecepcionLoteResponse response = invoiceService.enviarLote(requests);
        return ResponseEntity.ok(SifenApiResponse.ok(response, "Lote enviado correctamente"));
    }

    /**
     * Prepara un Documento Electrónico: genera XML firmado, calcula CDC y QR,
     * lo persiste localmente con estado PREPARADO, y retorna los datos necesarios
     * para impresión de ticket. NO envía a SIFEN (se envía después en lote).
     */
    @PostMapping("/prepare")
    public ResponseEntity<SifenApiResponse<PrepareInvoiceResponse>> prepararDE(
            @RequestBody EmitirFacturaRequest request) {

        log.info("POST /invoices/prepare - tipoDocumento={}", request.getData().getTipoDocumento());
        PrepareInvoiceResponse response = invoiceService.prepararDE(request);
        return ResponseEntity.ok(SifenApiResponse.ok(response, "Documento electrónico preparado correctamente"));
    }

    /**
     * Prepara múltiples Documentos Electrónicos en un solo request.
     * Cada uno se genera, firma y persiste localmente sin enviar a SIFEN.
     */
    @PostMapping("/prepare/batch")
    public ResponseEntity<SifenApiResponse<List<PrepareInvoiceResponse>>> prepararLote(
            @RequestBody List<EmitirFacturaRequest> requests) {

        log.info("POST /invoices/prepare/batch - cantidad={}", requests.size());
        List<PrepareInvoiceResponse> response = invoiceService.prepararLote(requests);
        return ResponseEntity.ok(SifenApiResponse.ok(response, "Lote preparado correctamente"));
    }

    /**
     * Consulta el estado de un DE desde la base de datos local.
     * Si el estado es final (APROBADO/RECHAZADO), retorna directamente sin consultar SIFEN.
     * Parámetro opcional: ?refresh=true para forzar consulta a SIFEN si el estado no es final.
     */
    @GetMapping("/{cdc}/status")
    public ResponseEntity<SifenApiResponse<DocumentStatusResponse>> consultarEstadoLocal(
            @PathVariable String cdc,
            @RequestParam(defaultValue = "false") boolean refresh) {

        log.info("GET /invoices/{}/status?refresh={}", cdc, refresh);
        DocumentStatusResponse response = invoiceService.consultarEstadoLocal(cdc, refresh);
        return ResponseEntity.ok(SifenApiResponse.ok(response));
    }

    /**
     * Lista las Notas de Crédito (tipoDocumento 5) emitidas por esta empresa que
     * referencian el CDC dado (grupo H / documentoAsociado del request de la NC).
     */
    @GetMapping("/{cdc}/credit-notes")
    public ResponseEntity<SifenApiResponse<List<NotaCreditoResumenDTO>>> listarNotasCredito(
            @PathVariable String cdc) {

        log.info("GET /invoices/{}/credit-notes", cdc);
        List<NotaCreditoResumenDTO> response = invoiceService.listarNotasCredito(cdc);
        return ResponseEntity.ok(SifenApiResponse.ok(response));
    }

    @PostMapping("/{cdc}/resend-email")
    public ResponseEntity<SifenApiResponse<Map<String, String>>> reenviarEmailFactura(
            @PathVariable String cdc) {

        log.info("POST /invoices/{}/resend-email", cdc);
        InvoiceEmailService.EmailDispatchResult result = invoiceService.reenviarEmailFacturaAprobada(cdc);

        Map<String, String> data = Map.of(
                "cdc", cdc,
                "sent", String.valueOf(result.sent()),
                "email", result.email() != null ? result.email() : "",
                "cc", result.cc() != null && !result.cc().isEmpty() ? String.join(",", result.cc()) : "",
                "reason", result.reason() != null ? result.reason() : "",
                "resendId", result.resendId() != null ? result.resendId() : ""
        );

        String message = result.sent()
                ? "Correo reenviado correctamente"
                : "No se pudo reenviar el correo";

        return ResponseEntity.ok(SifenApiResponse.ok(data, message));
    }

    /**
     * Reenvía un Documento Electrónico RECHAZADO o ERROR a SIFEN reutilizando el
     * mismo CDC (permitido por el Manual Técnico SIFEN mientras el ajuste no
     * modifique los campos que componen el CDC). El documento vuelve a estado
     * PREPARADO y el scheduler de envío lo re-firma y reenvía en el próximo ciclo.
     *
     * El body es opcional:
     *  - Sin body: reintenta con los datos ya almacenados (ej. código 1004, donde
     *    el problema es la hora de firma, no los datos del documento).
     *  - Con body (EmitirFacturaRequest): reemplaza los datos almacenados por el
     *    payload corregido; se exige que el CDC recalculado sea idéntico al original.
     *
     * ?force=true omite el bloqueo por clasificación NO_REENVIABLE (ver GET
     * /invoices/{cdc}/status → clasificacionReenvio) — el guard de igualdad de CDC
     * sigue aplicando siempre, sin excepción.
     */
    @PostMapping("/{cdc}/resend")
    public ResponseEntity<SifenApiResponse<DocumentStatusResponse>> reenviarDocumento(
            @PathVariable String cdc,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestBody(required = false) EmitirFacturaRequest payload) {

        log.info("POST /invoices/{}/resend?force={}", cdc, force);
        DocumentStatusResponse response = invoiceService.reenviarDocumento(cdc, payload, force);
        return ResponseEntity.ok(SifenApiResponse.ok(response,
                "Documento reencolado para reenvío con el mismo CDC"));
    }

    /**
     * Reenvía todos los documentos RECHAZADO de la empresa cuyo código de error SIFEN
     * coincida (por defecto 1004 — "La fecha y hora de la firma digital es adelantada").
     */
    @PostMapping("/resend-rejected")
    public ResponseEntity<SifenApiResponse<List<ResendResultDTO>>> reenviarRechazados(
            @RequestParam(required = false) String codigo) {

        log.info("POST /invoices/resend-rejected?codigo={}", codigo);
        List<ResendResultDTO> response = invoiceService.reenviarRechazados(codigo);
        return ResponseEntity.ok(SifenApiResponse.ok(response, "Reenvío de documentos rechazados procesado"));
    }

    /**
     * Consulta el estado de un Documento Electrónico por su CDC
     * (Código de Control del Documento Electrónico, 44 caracteres).
     * Consulta directamente a SIFEN (no usa la base local).
     */
    @GetMapping("/{cdc}")
    public ResponseEntity<SifenApiResponse<ConsultaDEResponse>> consultarDE(
            @PathVariable String cdc) {

        log.info("GET /invoices/{}", cdc);
        ConsultaDEResponse response = invoiceService.consultarDE(cdc);
        return ResponseEntity.ok(SifenApiResponse.ok(response));
    }

    /**
     * Consulta el estado de procesamiento de un lote enviado previamente.
     */
    @GetMapping("/batch/{nroLote}")
    public ResponseEntity<SifenApiResponse<ConsultaEstadoLoteResponse>> consultarLote(
            @PathVariable String nroLote) {

        log.info("GET /invoices/batch/{}", nroLote);
        ConsultaEstadoLoteResponse response = invoiceService.consultarEstadoLote(nroLote);
        return ResponseEntity.ok(SifenApiResponse.ok(response));
    }

    /**
     * Consulta información de un contribuyente por RUC.
     * El RUC puede incluir o no el dígito verificador (ej: "80089752" o "80089752-3").
     */
    @GetMapping("/ruc/{ruc}")
    public ResponseEntity<SifenApiResponse<ConsultaRucResponse>> consultarRuc(
            @PathVariable String ruc) {

        log.info("GET /invoices/ruc/{}", ruc);
        ConsultaRucResponse response = invoiceService.consultarRuc(ruc);
        return ResponseEntity.ok(SifenApiResponse.ok(response));
    }

    /**
     * Envía un evento relacionado a un DE (cancelación, inutilización, conformidad, etc.).
     *
     * Tipos de evento (tipoEvento):
     *   1 = Cancelación (requiere: cdc, motivo)
     *   2 = Inutilización (requiere: timbrado, establecimiento, puntoExpedicion, numeroDesde, numeroHasta, tipoDocumento, motivo)
     *   3 = Conformidad del receptor (requiere: cdc, tipoConformidad, fechaRecepcion)
     *   4 = Disconformidad del receptor (requiere: cdc, motivo)
     *   5 = Desconocimiento del receptor (requiere: cdc, motivo + datos receptor)
     *   6 = Notificación de recepción (requiere: cdc + datos receptor + totalGs)
     */
    @PostMapping("/events")
    public ResponseEntity<SifenApiResponse<RecepcionEventoResponse>> enviarEvento(
            @Valid @RequestBody EventoRequest request) {

        log.info("POST /invoices/events - tipoEvento={}, cdc={}", request.getTipoEvento(), request.getCdc());
        RecepcionEventoResponse response = eventoService.enviarEvento(request);
        return ResponseEntity.ok(SifenApiResponse.ok(response, "Evento enviado correctamente"));
    }

    /**
     * Historial de eventos registrados para un CDC (propios, si fue emitido por esta
     * empresa; o eventos de receptor registrados por esta empresa sobre un CDC ajeno).
     */
    @GetMapping("/{cdc}/events")
    public ResponseEntity<SifenApiResponse<List<EventoResumenDTO>>> listarEventosPorCdc(
            @PathVariable String cdc) {

        log.info("GET /invoices/{}/events", cdc);
        List<EventoResumenDTO> eventos = eventoService.listarPorCdc(cdc);
        return ResponseEntity.ok(SifenApiResponse.ok(eventos));
    }

    /**
     * Listado paginado de eventos de la empresa autenticada, con filtros opcionales.
     * Nota: coexiste sin ambigüedad con GET /invoices/{cdc} — Spring prioriza el
     * segmento literal "events" sobre el template {cdc}, igual que ya ocurre con
     * /invoices/prepare, /invoices/kude, /invoices/ruc/{ruc}, etc.
     */
    @GetMapping("/events")
    public ResponseEntity<SifenApiResponse<PageResponse<EventoResumenDTO>>> listarEventos(
            @RequestParam(required = false) Short tipoEvento,
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.info("GET /invoices/events - tipoEvento={}, estado={}, page={}, size={}", tipoEvento, estado, page, size);
        Pageable pageable = PageRequest.of(page, size);
        PageResponse<EventoResumenDTO> response = eventoService.listar(tipoEvento, estado, desde, hasta, pageable);
        return ResponseEntity.ok(SifenApiResponse.ok(response));
    }

    /**
     * Reconciliación bajo demanda de un evento (típicamente INDETERMINADO tras un
     * timeout). Solo tipo 1 (cancelación) puede confirmarse vía consultaDE; el resto
     * devuelve NO_CONCLUYENTE con guía para el ERP. Nunca reenvía automáticamente.
     */
    @PostMapping("/events/{id}/reconcile")
    public ResponseEntity<SifenApiResponse<EventoResumenDTO>> reconciliarEvento(@PathVariable Long id) {
        log.info("POST /invoices/events/{}/reconcile", id);
        EventoResumenDTO response = eventoService.reconciliar(id);
        return ResponseEntity.ok(SifenApiResponse.ok(response));
    }

    /**
     * Genera el KUDE (PDF) de un Documento Electrónico.
     * Recibe los datos del DE y de la respuesta SIFEN para generar la representación gráfica.
     *
     * Devuelve el PDF directamente como application/pdf con Content-Disposition: attachment.
     */
    @PostMapping("/kude")
    public ResponseEntity<byte[]> generarKude(@RequestBody KudeRequest request) {
        log.info("POST /invoices/kude - CDC: {}", request.getCdc());

        byte[] pdfBytes = kudeService.generarKude(request);

        String filename = (request.getCdc() != null ? request.getCdc() : "documento") + ".pdf";

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(pdfBytes.length))
                .body(pdfBytes);
    }

    /**
     * Genera el KUDE (PDF) como base64 string dentro de un JSON response.
     * Útil para integraciones que necesitan el PDF embebido en la respuesta JSON.
     */
    @PostMapping("/kude/base64")
    public ResponseEntity<SifenApiResponse<Map<String, String>>> generarKudeBase64(
            @RequestBody KudeRequest request) {
        log.info("POST /invoices/kude/base64 - CDC: {}", request.getCdc());

        String base64Pdf = kudeService.generarKudeBase64(request);

        Map<String, String> data = Map.of(
                "kude", base64Pdf,
                "cdc", request.getCdc() != null ? request.getCdc() : "",
                "contentType", "application/pdf"
        );

        return ResponseEntity.ok(SifenApiResponse.ok(data, "KUDE generado correctamente"));
    }
}
