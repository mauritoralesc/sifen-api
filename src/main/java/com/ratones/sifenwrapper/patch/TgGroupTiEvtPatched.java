package com.ratones.sifenwrapper.patch;

import com.roshka.sifen.core.fields.request.event.TgGroupTiEvt;

import javax.xml.soap.SOAPElement;
import javax.xml.soap.SOAPException;

/**
 * Parche para TgGroupTiEvt de rshk-jsifenlib 0.2.4 que NO genera el campo
 * obligatorio {@code <dTiGDE>} (tipo de evento) dentro de {@code <rEve>}.
 *
 * <p>Sin este campo, SIFEN rechaza el XML con error 0160 "XML Mal Formado".</p>
 *
 * <p>Según el Manual Técnico v150, {@code <dTiGDE>} debe ser hijo directo de
 * {@code <rEve>}, ubicado después de {@code <dVerFor>} y antes de
 * {@code <gGroupTiEvt>}.</p>
 *
 * <p>Estructura XML resultante:</p>
 * <pre>{@code
 * <rEve Id="...">
 *   <dFecFirma>...</dFecFirma>
 *   <dVerFor>150</dVerFor>
 *   <dTiGDE>1</dTiGDE>                   <!-- INYECTADO por este parche -->
 *   <gGroupTiEvt>
 *     <rGeVeCan>                          <!-- Generado por la librería -->
 *       <Id>CDC...</Id>
 *       <mOtEve>Motivo...</mOtEve>
 *     </rGeVeCan>
 *   </gGroupTiEvt>
 * </rEve>
 * }</pre>
 *
 * @see <a href="https://ekuatia.set.gov.py/">Manual Técnico SIFEN v150 - Eventos</a>
 */
public class TgGroupTiEvtPatched extends TgGroupTiEvt {

    private final int tipoEvento;

    /**
     * @param tipoEvento código del tipo de evento SIFEN:
     *                   1=Cancelación, 2=Inutilización, 3=Conformidad,
     *                   4=Disconformidad, 5=Desconocimiento, 6=Notificación
     */
    public TgGroupTiEvtPatched(int tipoEvento) {
        this.tipoEvento = tipoEvento;
    }

    @Override
    public void setupSOAPElements(SOAPElement parent) throws SOAPException {
        // parent = rEve (pasado por TrGesEve que ya agregó dFecFirma y dVerFor)

        // 1. Inyectar dTiGDE como hijo directo de rEve, ANTES de gGroupTiEvt
        parent.addChildElement("dTiGDE").setTextContent(String.valueOf(tipoEvento));

        // 2. Crear gGroupTiEvt y delegar al tipo de evento específico
        SOAPElement gGroupTiEvt = parent.addChildElement("gGroupTiEvt");

        if (getrGeVeCan() != null) {
            getrGeVeCan().setupSOAPElements(gGroupTiEvt);
        } else if (getrGeVeInu() != null) {
            getrGeVeInu().setupSOAPElements(gGroupTiEvt);
        } else if (getrGeVeNotRec() != null) {
            getrGeVeNotRec().setupSOAPElements(gGroupTiEvt);
        } else if (getrGeVeConf() != null) {
            getrGeVeConf().setupSOAPElements(gGroupTiEvt);
        } else if (getrGeVeDisconf() != null) {
            getrGeVeDisconf().setupSOAPElements(gGroupTiEvt);
        } else if (getrGeVeDescon() != null) {
            getrGeVeDescon().setupSOAPElements(gGroupTiEvt);
        } else if (getrGeVeTr() != null) {
            getrGeVeTr().setupSOAPElements(gGroupTiEvt);
        }
    }
}
