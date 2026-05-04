package com.ratones.sifenwrapper.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratones.sifenwrapper.config.ResendProperties;
import com.ratones.sifenwrapper.dto.request.EmitirFacturaRequest;
import com.ratones.sifenwrapper.dto.response.EmisionDEResponse;
import com.ratones.sifenwrapper.entity.ElectronicDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceEmailService {

    private static final String RESEND_BASE_URL = "https://api.resend.com";

    private final ObjectMapper objectMapper;
    private final ResendProperties resendProperties;
    private final RestClient.Builder restClientBuilder;

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

        String toEmail = text(root.path("data").path("cliente").path("email"));
        if (toEmail == null || toEmail.isBlank()) {
            return EmailDispatchResult.notSent("El cliente no tiene email en el request");
        }

        String cliente = text(root.path("data").path("cliente").path("razonSocial"));
        String subject = "Factura aprobada - CDC " + doc.getCdc();
        String html = buildHtmlBody(
                cliente,
                doc.getCdc(),
                doc.getEstado(),
                doc.getSifenCodigo(),
                doc.getSifenMensaje(),
                doc.getQrUrl());
        String text = buildTextBody(doc.getCdc(), doc.getEstado(), doc.getSifenCodigo(), doc.getSifenMensaje(), doc.getQrUrl());

        return sendEmail(toEmail, subject, html, text);
    }

    public EmailDispatchResult sendApprovedEmailFromEmission(EmitirFacturaRequest request,
                                                             EmisionDEResponse response) {
        if (request == null || request.getData() == null || request.getData().getCliente() == null || response == null) {
            return EmailDispatchResult.notSent("Datos insuficientes para envío");
        }
        if (!isApprovedState(response.getEstado())) {
            return EmailDispatchResult.notSent("El documento no está aprobado");
        }

        String toEmail = request.getData().getCliente().getEmail();
        if (toEmail == null || toEmail.isBlank()) {
            return EmailDispatchResult.notSent("El cliente no tiene email en el request");
        }

        String cliente = request.getData().getCliente().getRazonSocial();
        String subject = "Factura aprobada - CDC " + response.getCdc();
        String html = buildHtmlBody(
                cliente,
                response.getCdc(),
                response.getEstado(),
                response.getCodigoEstado(),
                response.getDescripcionEstado(),
                response.getQrUrl());
        String text = buildTextBody(response.getCdc(), response.getEstado(), response.getCodigoEstado(),
                response.getDescripcionEstado(), response.getQrUrl());

        return sendEmail(toEmail, subject, html, text);
    }

    private EmailDispatchResult sendEmail(String toEmail, String subject, String html, String text) {
        if (resendProperties.getApiKey() == null || resendProperties.getApiKey().isBlank()) {
            log.warn("[EMAIL] RESEND_API_KEY no configurada. Se omite envío a {}", toEmail);
            return EmailDispatchResult.notSent("RESEND_API_KEY no configurada");
        }

        String from = buildFrom();
        RestClient client = restClientBuilder.baseUrl(RESEND_BASE_URL).build();

        ResendSendEmailRequest payload = new ResendSendEmailRequest(
                from,
                List.of(toEmail),
                subject,
                html,
                text
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
            log.info("[EMAIL] Factura enviada por email a {} (resendId={})", toEmail, resendId);
            return EmailDispatchResult.sent(toEmail, resendId);
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

    private String buildHtmlBody(String cliente,
                                 String cdc,
                                 String estado,
                                 String codigo,
                                 String mensaje,
                                 String qrUrl) {
        String nombre = (cliente == null || cliente.isBlank()) ? "cliente" : cliente;
        return """
                <html>
                  <body style=\"font-family: Arial, sans-serif; color: #111;\"> 
                    <h2>Factura aprobada por SIFEN</h2>
                    <p>Hola %s,</p>
                    <p>Tu factura fue procesada correctamente.</p>
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
                escapeHtml(nombre),
                safe(cdc),
                safe(estado),
                safe(codigo),
                safe(mensaje),
                safe(qrUrl)
        );
    }

    private String buildTextBody(String cdc, String estado, String codigo, String mensaje, String qrUrl) {
        return "Factura aprobada por SIFEN\n"
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

    public record EmailDispatchResult(boolean sent, String email, String reason, String resendId) {
        public static EmailDispatchResult sent(String email, String resendId) {
            return new EmailDispatchResult(true, email, null, resendId);
        }

        public static EmailDispatchResult notSent(String reason) {
            return new EmailDispatchResult(false, null, reason, null);
        }
    }

    private record ResendSendEmailRequest(String from,
                                          List<String> to,
                                          String subject,
                                          String html,
                                          String text) {
    }

    private record ResendSendEmailResponse(String id) {
    }
}
