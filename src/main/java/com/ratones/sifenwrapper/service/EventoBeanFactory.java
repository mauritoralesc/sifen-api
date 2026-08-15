package com.ratones.sifenwrapper.service;

import com.ratones.sifenwrapper.dto.request.EventoRequest;
import com.ratones.sifenwrapper.service.EventoValidator.EventoValidado;
import com.roshka.sifen.core.fields.request.event.*;
import com.roshka.sifen.core.types.TiNatRec;
import com.roshka.sifen.core.types.TiTipConf;
import com.roshka.sifen.core.types.TiTipDocRec;
import com.roshka.sifen.core.types.TTiDE;
import lombok.experimental.UtilityClass;

/**
 * Construye el árbol de beans {@link TgGroupTiEvt} de rshk-jsifenlib a partir de un
 * {@link EventoRequest} ya validado. Puro: sin I/O, sin Spring. Las fechas y demás
 * campos ya resueltos vienen de {@link EventoValidado} para no re-parsear ni
 * redivalidar lo que {@link EventoValidator} ya garantizó.
 */
@UtilityClass
public class EventoBeanFactory {

    public static TgGroupTiEvt build(EventoRequest request, EventoValidado validado) {
        TgGroupTiEvt grupo = new TgGroupTiEvt();

        switch (validado.tipoEvento()) {
            case 1 -> { // Cancelación
                TrGeVeCan cancelacion = new TrGeVeCan();
                cancelacion.setId(validado.cdc());
                cancelacion.setmOtEve(request.getMotivo());
                grupo.setrGeVeCan(cancelacion);
            }
            case 2 -> { // Inutilización
                TrGeVeInu inutilizacion = new TrGeVeInu();
                inutilizacion.setdNumTim(request.getTimbrado());
                inutilizacion.setdEst(request.getEstablecimiento());
                inutilizacion.setdPunExp(request.getPuntoExpedicion());
                inutilizacion.setdNumIn(request.getNumeroDesde());
                inutilizacion.setdNumFin(request.getNumeroHasta());
                inutilizacion.setiTiDE(TTiDE.getByVal(request.getTipoDocumento().shortValue()));
                inutilizacion.setmOtEve(request.getMotivo());
                grupo.setrGeVeInu(inutilizacion);
            }
            case 3 -> { // Conformidad del receptor
                TrGeVeConf conformidad = new TrGeVeConf();
                conformidad.setId(validado.cdc());
                conformidad.setiTipConf(TiTipConf.getByVal(request.getTipoConformidad().shortValue()));
                conformidad.setdFecRecep(validado.fechaRecepcion());
                grupo.setrGeVeConf(conformidad);
            }
            case 4 -> { // Disconformidad del receptor
                TrGeVeDisconf disconformidad = new TrGeVeDisconf();
                disconformidad.setId(validado.cdc());
                disconformidad.setmOtEve(request.getMotivo());
                grupo.setrGeVeDisconf(disconformidad);
            }
            case 5 -> { // Desconocimiento del receptor
                TrGeVeDescon desconocimiento = new TrGeVeDescon();
                desconocimiento.setId(validado.cdc());
                desconocimiento.setdFecEmi(validado.fechaEmision());
                desconocimiento.setdFecRecep(validado.fechaRecepcion());
                if (request.getReceptorContribuyente() != null) {
                    desconocimiento.setiTipRec(request.getReceptorContribuyente()
                            ? TiNatRec.CONTRIBUYENTE : TiNatRec.NO_CONTRIBUYENTE);
                }
                desconocimiento.setdNomRec(request.getNombreReceptor());
                desconocimiento.setdRucRec(validado.rucReceptor());
                desconocimiento.setdDVRec(validado.dvReceptor());
                if (request.getTipoDocIdentidad() != null) {
                    desconocimiento.setdTipIDRec(TiTipDocRec.getByVal(request.getTipoDocIdentidad().shortValue()));
                }
                desconocimiento.setdNumID(request.getNumeroDocIdentidad());
                desconocimiento.setmOtEve(request.getMotivo());
                grupo.setrGeVeDescon(desconocimiento);
            }
            case 6 -> { // Notificación de recepción
                TrGeVeNotRec notificacion = new TrGeVeNotRec();
                notificacion.setId(validado.cdc());
                notificacion.setdFecEmi(validado.fechaEmision());
                notificacion.setdFecRecep(validado.fechaRecepcion());
                if (request.getReceptorContribuyente() != null) {
                    notificacion.setiTipRec(request.getReceptorContribuyente()
                            ? TiNatRec.CONTRIBUYENTE : TiNatRec.NO_CONTRIBUYENTE);
                }
                notificacion.setdNomRec(request.getNombreReceptor());
                notificacion.setdRucRec(validado.rucReceptor());
                notificacion.setdDVRec(validado.dvReceptor());
                if (request.getTipoDocIdentidad() != null) {
                    notificacion.setdTipIDRec(TiTipDocRec.getByVal(request.getTipoDocIdentidad().shortValue()));
                }
                notificacion.setdNumID(request.getNumeroDocIdentidad());
                notificacion.setdTotalGs(request.getTotalGs());
                grupo.setrGeVeNotRec(notificacion);
            }
            default -> throw new IllegalArgumentException("Tipo de evento no soportado: " + validado.tipoEvento());
        }

        return grupo;
    }
}
