package com.ratones.sifenwrapper.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratones.sifenwrapper.config.ResendProperties;
import com.ratones.sifenwrapper.dto.request.EmitirFacturaRequest;
import com.ratones.sifenwrapper.dto.request.KudeRequest;
import com.ratones.sifenwrapper.dto.response.EmisionDEResponse;
import com.ratones.sifenwrapper.entity.ElectronicDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceEmailService {

    private static final String RESEND_BASE_URL = "https://api.resend.com";

    private final ObjectMapper objectMapper;
    private final ResendProperties resendProperties;
    private final RestClient.Builder restClientBuilder;
    private final KudeService kudeService;

    public EmailDispatchResult sendApprovedEmail(ElectronicDocument doc) {
        if (doc == null) {
            return EmailDispatchResult.notSent("Documento no disponible");
        }
        if (!isApprovedState(doc.getEstado())) {
            return EmailDispatchResult.notSent("El documento no está aprobado");
        }

        JsonNode root = parseRequestData(doc.getRequestData());
        if (root == null) {
            return EmailDispatchResult.notSent("No se pudo leer requestData para obtener email del cliente");
        }

        EmailRecipients recipients = resolveRecipientsFromRoot(root);
        if (recipients.toEmail() == null) {
            return EmailDispatchResult.notSent("El cliente no tiene email en el request");
        }

        String cliente = text(root.path("data").path("cliente").path("razonSocial"));
        String titulo = nombreDocumento(doc.getTipoDocumento());
        String subject = titulo + " aprobada - CDC " + doc.getCdc();
        String html = buildHtmlBody(
                titulo,
                cliente,
                doc.getCdc(),
                doc.getEstado(),
                doc.getSifenCodigo(),
                doc.getSifenMensaje(),
                doc.getQrUrl());
        String text = buildTextBody(titulo, doc.getCdc(), doc.getEstado(), doc.getSifenCodigo(), doc.getSifenMensaje(), doc.getQrUrl());

        byte[] kude = generarKudeSilencioso(root, doc.getCdc(), doc.getQrUrl(), doc.getEstado(),
                doc.getSifenCodigo(), doc.getSifenMensaje());
        return sendEmail(recipients.toEmail(), recipients.ccEmails(), subject, html, text, kude,
                "kude-" + doc.getCdc() + ".pdf");
    }

    public EmailDispatchResult sendApprovedEmailFromEmission(EmitirFacturaRequest request,
                                                             EmisionDEResponse response) {
        if (request == null || request.getData() == null || request.getData().getCliente() == null || response == null) {
            return EmailDispatchResult.notSent("Datos insuficientes para envío");
        }
        if (!isApprovedState(response.getEstado())) {
            return EmailDispatchResult.notSent("El documento no está aprobado");
        }

        EmailRecipients recipients = resolveRecipientsFromRequest(request);
        if (recipients.toEmail() == null) {
            return EmailDispatchResult.notSent("El cliente no tiene email en el request");
        }

        String cliente = request.getData().getCliente().getRazonSocial();
        String titulo = nombreDocumento((short) request.getData().getTipoDocumento());
        String subject = titulo + " aprobada - CDC " + response.getCdc();
        String html = buildHtmlBody(
                titulo,
                cliente,
                response.getCdc(),
                response.getEstado(),
                response.getCodigoEstado(),
                response.getDescripcionEstado(),
                response.getQrUrl());
        String text = buildTextBody(titulo, response.getCdc(), response.getEstado(), response.getCodigoEstado(),
                response.getDescripcionEstado(), response.getQrUrl());

        byte[] kude = generarKudeSilenciosoDesdeRequest(request, response);
        return sendEmail(recipients.toEmail(), recipients.ccEmails(), subject, html, text, kude,
                "kude-" + response.getCdc() + ".pdf");
    }

    private byte[] generarKudeSilencioso(JsonNode root, String cdc, String qrUrl,
                                          String estado, String codigoEstado, String descripcionEstado) {
        try {
            EmitirFacturaRequest req = objectMapper.treeToValue(root, EmitirFacturaRequest.class);
            KudeRequest kudeReq = new KudeRequest();
            kudeReq.setParams(req.getParams());
            kudeReq.setData(req.getData());
            kudeReq.setCdc(cdc);
            kudeReq.setQrUrl(qrUrl);
            kudeReq.setEstado(estado);
            kudeReq.setCodigoEstado(codigoEstado);
            kudeReq.setDescripcionEstado(descripcionEstado);
            return kudeService.generarKude(kudeReq);
        } catch (Exception e) {
            log.warn("[EMAIL] No se pudo generar KUDE para adjuntar (cdc={}): {}", cdc, e.getMessage());
            return null;
        }
    }

    private byte[] generarKudeSilenciosoDesdeRequest(EmitirFacturaRequest request,
                                                      EmisionDEResponse response) {
        try {
            KudeRequest kudeReq = new KudeRequest();
            kudeReq.setParams(request.getParams());
            kudeReq.setData(request.getData());
            kudeReq.setCdc(response.getCdc());
            kudeReq.setQrUrl(response.getQrUrl());
            kudeReq.setEstado(response.getEstado());
            kudeReq.setCodigoEstado(response.getCodigoEstado());
            kudeReq.setDescripcionEstado(response.getDescripcionEstado());
            return kudeService.generarKude(kudeReq);
        } catch (Exception e) {
            log.warn("[EMAIL] No se pudo generar KUDE para adjuntar (cdc={}): {}", response.getCdc(), e.getMessage());
            return null;
        }
    }

    private EmailDispatchResult sendEmail(String toEmail, List<String> ccEmails, String subject, String html,
                                          String text, byte[] attachmentBytes, String attachmentFilename) {
        if (resendProperties.getApiKey() == null || resendProperties.getApiKey().isBlank()) {
            log.warn("[EMAIL] RESEND_API_KEY no configurada. Se omite envío a {} (cc={})", toEmail,
                    ccEmails == null || ccEmails.isEmpty() ? "-" : String.join(",", ccEmails));
            return EmailDispatchResult.notSent("RESEND_API_KEY no configurada");
        }

        String from = buildFrom();
        RestClient client = restClientBuilder.baseUrl(RESEND_BASE_URL).build();

        List<ResendAttachment> attachments = null;
        if (attachmentBytes != null && attachmentFilename != null) {
            attachments = List.of(new ResendAttachment(
                    attachmentFilename,
                    Base64.getEncoder().encodeToString(attachmentBytes)
            ));
        }

        ResendSendEmailRequest payload = new ResendSendEmailRequest(
                from,
                List.of(toEmail),
                (ccEmails == null || ccEmails.isEmpty()) ? null : ccEmails,
                subject,
                html,
                text,
                attachments
        );

        try {
            ResendSendEmailResponse resendResponse = client.post()
                    .uri("/emails")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + resendProperties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(ResendSendEmailResponse.class);

            String resendId = resendResponse != null ? resendResponse.id() : null;
            log.info("[EMAIL] Factura enviada por email a {} (cc={}) (resendId={})",
                    toEmail, ccEmails == null || ccEmails.isEmpty() ? "-" : String.join(",", ccEmails), resendId);
            return EmailDispatchResult.sent(toEmail, ccEmails, resendId);
        } catch (RestClientResponseException ex) {
            log.error("[EMAIL] Error Resend (status={}): {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            return EmailDispatchResult.notSent("Resend rechazó la solicitud: " + ex.getStatusCode());
        } catch (Exception ex) {
            log.error("[EMAIL] Error enviando email con Resend: {}", ex.getMessage(), ex);
            return EmailDispatchResult.notSent("Error enviando email: " + ex.getMessage());
        }
    }

    private String buildFrom() {
        String name = resendProperties.getFromName() != null ? resendProperties.getFromName().trim() : "";
        String email = resendProperties.getFromEmail() != null ? resendProperties.getFromEmail().trim() : "";
        if (name.isBlank()) return email;
        return name + " <" + email + ">";
    }

    private boolean isApprovedState(String estado) {
        if (estado == null) return false;
        return "APROBADO".equalsIgnoreCase(estado)
                || "APROBADO_CON_OBSERVACION".equalsIgnoreCase(estado)
                || estado.toUpperCase().startsWith("APROBADO");
    }

    private JsonNode parseRequestData(String requestData) {
        if (requestData == null || requestData.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(requestData);
        } catch (Exception e) {
            log.warn("[EMAIL] No se pudo parsear requestData: {}", e.getMessage());
            return null;
        }
    }

    private String text(JsonNode node) {
        return (node == null || node.isNull()) ? null : node.asText(null);
    }

    private EmailRecipients resolveRecipientsFromRoot(JsonNode root) {
        String toEmail = text(root.path("data").path("cliente").path("email"));
        JsonNode ccNode = root.path("data").path("cliente").path("emailCc");
        List<String> ccEmails = extractCcEmails(ccNode);
        return resolveRecipients(toEmail, ccEmails);
    }

    private EmailRecipients resolveRecipientsFromRequest(EmitirFacturaRequest request) {
        String toEmail = request.getData().getCliente().getEmail();
        List<String> ccEmails = request.getData().getCliente().getEmailCc();
        return resolveRecipients(toEmail, ccEmails);
    }

    private EmailRecipients resolveRecipients(String toEmail, List<String> ccEmails) {
        String normalizedTo = normalizeEmail(toEmail);
        if (normalizedTo == null) {
            return new EmailRecipients(null, List.of());
        }
        List<String> normalizedCc = normalizeCcEmails(ccEmails, normalizedTo);
        return new EmailRecipients(normalizedTo, normalizedCc);
    }

    private List<String> extractCcEmails(JsonNode ccNode) {
        if (ccNode == null || ccNode.isNull() || ccNode.isMissingNode()) {
            return List.of();
        }
        List<String> emails = new ArrayList<>();
        if (ccNode.isArray()) {
            for (JsonNode child : ccNode) {
                if (child != null && !child.isNull()) {
                    emails.add(child.asText(null));
                }
            }
            return emails;
        }
        if (ccNode.isTextual()) {
            emails.add(ccNode.asText(null));
        }
        return emails;
    }

    private List<String> normalizeCcEmails(List<String> ccEmails, String toEmail) {
        if (ccEmails == null || ccEmails.isEmpty()) {
            return List.of();
        }
        String toKey = toEmail.toLowerCase(Locale.ROOT);
        Map<String, String> dedup = new LinkedHashMap<>();
        for (String candidate : ccEmails) {
            String normalized = normalizeEmail(candidate);
            if (normalized == null) continue;
            String key = normalized.toLowerCase(Locale.ROOT);
            if (toKey.equals(key)) continue;
            dedup.putIfAbsent(key, normalized);
        }
        return List.copyOf(dedup.values());
    }

    private String normalizeEmail(String email) {
        if (email == null) return null;
        String trimmed = email.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    /** 1=Factura, 4=Autofactura, 5=Nota de Crédito, 6=Nota de Débito, 7=Nota de Remisión. */
    private String nombreDocumento(Short tipoDocumento) {
        if (tipoDocumento == null) return "Factura";
        return switch (tipoDocumento.intValue()) {
            case 1 -> "Factura";
            case 4 -> "Autofactura";
            case 5 -> "Nota de Crédito";
            case 6 -> "Nota de Débito";
            case 7 -> "Nota de Remisión";
            default -> "Documento electrónico";
        };
    }

    private String buildHtmlBody(String tituloDocumento,
                                 String cliente,
                                 String cdc,
                                 String estado,
                                 String codigo,
                                 String mensaje,
                                 String qrUrl) {
        String nombre = (cliente == null || cliente.isBlank()) ? "cliente" : cliente;
        return """
                <html>
                  <body style=\"font-family: Arial, sans-serif; color: #111;\">
                    <h2>%s aprobada por SIFEN</h2>
                    <p>Hola %s,</p>
                    <p>Tu documento fue procesado correctamente.</p>
                    <ul>
                      <li><strong>CDC:</strong> %s</li>
                      <li><strong>Estado:</strong> %s</li>
                      <li><strong>Código SIFEN:</strong> %s</li>
                      <li><strong>Detalle:</strong> %s</li>
                    </ul>
                    <p><strong>QR:</strong> <a href=\"%s\">Ver comprobante</a></p>
                    <p>Este correo fue generado automáticamente por SYNCTEMA.</p>
                  </body>
                </html>
                """.formatted(
                escapeHtml(tituloDocumento),
                escapeHtml(nombre),
                safe(cdc),
                safe(estado),
                safe(codigo),
                safe(mensaje),
                safe(qrUrl)
        );
    }

    private String buildTextBody(String tituloDocumento, String cdc, String estado, String codigo, String mensaje, String qrUrl) {
        return tituloDocumento + " aprobada por SIFEN\n"
                + "CDC: " + safe(cdc) + "\n"
                + "Estado: " + safe(estado) + "\n"
                + "Codigo SIFEN: " + safe(codigo) + "\n"
                + "Detalle: " + safe(mensaje) + "\n"
                + "QR: " + safe(qrUrl);
    }

    private String safe(String value) {
        return value == null ? "-" : value;
    }

    private String escapeHtml(String value) {
        if (value == null) return "";
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public record EmailDispatchResult(boolean sent, String email, List<String> cc, String reason, String resendId) {
        public static EmailDispatchResult sent(String email, List<String> cc, String resendId) {
            return new EmailDispatchResult(true, email, cc == null ? List.of() : List.copyOf(cc), null, resendId);
        }

        public static EmailDispatchResult notSent(String reason) {
            return new EmailDispatchResult(false, null, List.of(), reason, null);
        }
    }

    private record ResendSendEmailRequest(String from,
                                          List<String> to,
                                          List<String> cc,
                                          String subject,
                                          String html,
                                          String text,
                                          List<ResendAttachment> attachments) {
    }

    private record EmailRecipients(String toEmail, List<String> ccEmails) {
    }

    private record ResendAttachment(String filename, String content) {
    }

    private record ResendSendEmailResponse(String id) {
    }
}
