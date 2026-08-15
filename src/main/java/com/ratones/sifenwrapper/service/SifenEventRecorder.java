package com.ratones.sifenwrapper.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratones.sifenwrapper.dto.request.EventoRequest;
import com.ratones.sifenwrapper.dto.response.MensajeSifenDTO;
import com.ratones.sifenwrapper.entity.ElectronicDocument;
import com.ratones.sifenwrapper.entity.SifenEvent;
import com.ratones.sifenwrapper.exception.EventoDuplicadoException;
import com.ratones.sifenwrapper.repository.ElectronicDocumentRepository;
import com.ratones.sifenwrapper.repository.SifenEventRepository;
import com.ratones.sifenwrapper.service.EventoValidator.EventoValidado;
import com.roshka.sifen.internal.util.SifenUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Único lugar del subdominio de eventos con {@code @Transactional}, en un bean
 * separado de {@link EventoService} a propósito: {@code BatchSenderService.enviarSublote}
 * está anotado {@code @Transactional} pero se auto-invoca desde el mismo bean, así que
 * el proxy de Spring se saltea y la anotación queda inerte. Aquí cada método se llama
 * siempre desde OTRO bean ({@link EventoService}), así que {@code REQUIRES_NEW} sí
 * abre una transacción real y committea de forma independiente de la llamada a SIFEN.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SifenEventRecorder {

    private final SifenEventRepository sifenEventRepository;
    private final ElectronicDocumentRepository electronicDocumentRepository;
    private final InvoiceService invoiceService;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SifenEvent registrarEnvio(Long companyId, EventoRequest request, EventoValidado validado, String eventoId) {
        SifenEvent.SifenEventBuilder builder = SifenEvent.builder()
                .companyId(companyId)
                .tipoEvento(validado.tipoEvento())
                .eventoId(eventoId)
                .cdc(validado.cdc())
                .motivo(request.getMotivo())
                .estado(SifenEvent.ENVIADO)
                .sentAt(LocalDateTime.now())
                .requestData(serializar(request));

        if (validado.documento() != null) {
            builder.electronicDocumentId(validado.documento().getId());
        }

        if (validado.tipoEvento() == 2) {
            builder.timbrado(request.getTimbrado())
                    .establecimiento(request.getEstablecimiento())
                    .puntoExpedicion(request.getPuntoExpedicion())
                    .numeroDesde(zeroPad(request.getNumeroDesde()))
                    .numeroHasta(zeroPad(request.getNumeroHasta()))
                    .tipoDocumento(request.getTipoDocumento().shortValue());
        }

        try {
            return sifenEventRepository.saveAndFlush(builder.build());
        } catch (DataIntegrityViolationException e) {
            throw new EventoDuplicadoException(
                    "Ya existe un evento vigente (enviado, indeterminado o aprobado) del mismo tipo para este " +
                    "CDC/rango. SIFEN bloquea el RUC entre 10 y 60 minutos ante eventos idénticos repetidos: " +
                    "no reintente sin verificar el estado del evento anterior.");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SifenEvent registrarResultado(Long eventoDbId, String estado, String codigo, String mensaje,
                                          String protocoloAutorizacion, LocalDateTime fechaProceso,
                                          List<MensajeSifenDTO> mensajes, String xmlEnviado, String xmlRespuesta) {
        SifenEvent evento = cargar(eventoDbId);

        evento.setEstado(estado);
        evento.setSifenCodigo(codigo);
        evento.setSifenMensaje(mensaje);
        evento.setProtocoloAutorizacion(protocoloAutorizacion);
        evento.setFechaProceso(fechaProceso);
        evento.setXmlEnviado(xmlEnviado);
        evento.setXmlRespuesta(xmlRespuesta);
        evento.setProcessedAt(LocalDateTime.now());
        evento.setResponseData(serializar(mensajes));

        if (SifenEvent.APROBADO.equals(estado)) {
            aplicarEfectoSobreDocumentos(evento, mensajes);
        }

        return sifenEventRepository.save(evento);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SifenEvent registrarIndeterminado(Long eventoDbId, String detalle) {
        SifenEvent evento = cargar(eventoDbId);
        evento.setEstado(SifenEvent.INDETERMINADO);
        evento.setSifenMensaje(detalle);
        evento.setProcessedAt(LocalDateTime.now());
        return sifenEventRepository.save(evento);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SifenEvent registrarError(Long eventoDbId, String estado, String detalle) {
        SifenEvent evento = cargar(eventoDbId);
        evento.setEstado(estado);
        evento.setSifenMensaje(detalle);
        evento.setProcessedAt(LocalDateTime.now());
        return sifenEventRepository.save(evento);
    }

    private SifenEvent cargar(Long eventoDbId) {
        return sifenEventRepository.findById(eventoDbId)
                .orElseThrow(() -> new IllegalStateException("Evento SIFEN no encontrado: id=" + eventoDbId));
    }

    /**
     * Efectos sobre electronic_documents cuando SIFEN aprueba el evento. Solo tipos 1 y 2
     * (actos de emisor); los eventos de receptor (3-6) no tocan documentos, porque el DE
     * pertenece a otra empresa. No toca doc.processedAt: es el ancla histórica de la
     * ventana de 48h de cancelación y no debe sobrescribirse.
     */
    private void aplicarEfectoSobreDocumentos(SifenEvent evento, List<MensajeSifenDTO> mensajes) {
        if (evento.getTipoEvento() == 1) {
            if (evento.getElectronicDocumentId() == null) {
                return;
            }
            electronicDocumentRepository.findById(evento.getElectronicDocumentId()).ifPresent(doc -> {
                invoiceService.registrarResultadoSifen(doc, "CANCELADO", mensajes, "EVENTO_CANCELACION");
                electronicDocumentRepository.save(doc);
            });
        } else if (evento.getTipoEvento() == 2) {
            List<ElectronicDocument> enRango = electronicDocumentRepository.findEnRango(
                    evento.getCompanyId(), evento.getEstablecimiento(), evento.getPuntoExpedicion(),
                    evento.getTipoDocumento(), evento.getNumeroDesde(), evento.getNumeroHasta());

            for (ElectronicDocument doc : enRango) {
                boolean yaAprobado = "APROBADO".equals(doc.getEstado()) || "APROBADO_CON_OBSERVACION".equals(doc.getEstado());
                if (!yaAprobado) {
                    invoiceService.registrarResultadoSifen(doc, "INUTILIZADO", mensajes, "EVENTO_INUTILIZACION");
                    electronicDocumentRepository.save(doc);
                }
            }
        }
    }

    private String serializar(Object valor) {
        try {
            return objectMapper.writeValueAsString(valor);
        } catch (Exception e) {
            log.warn("No se pudo serializar dato de evento SIFEN: {}", e.getMessage());
            return null;
        }
    }

    private static String zeroPad(String valor) {
        return valor == null ? null : SifenUtil.leftPad(valor, '0', 7);
    }
}
