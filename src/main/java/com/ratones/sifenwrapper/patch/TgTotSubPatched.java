package com.ratones.sifenwrapper.patch;

import com.roshka.sifen.core.fields.request.de.TgDtipDE;
import com.roshka.sifen.core.fields.request.de.TgOpeCom;
import com.roshka.sifen.core.fields.request.de.TgTotSub;
import com.roshka.sifen.core.types.TTiDE;
import org.w3c.dom.Node;

import javax.xml.soap.SOAPElement;
import javax.xml.soap.SOAPException;

/**
 * Parche de compatibilidad para algunos validadores SIFEN que rechazan
 * campos opcionales de gTotSub aunque sean válidos en XSD recientes.
 */
public class TgTotSubPatched extends TgTotSub {

    @Override
    public void setupSOAPElements(SOAPElement DE, TTiDE iTiDE, TgDtipDE gDtipDE, TgOpeCom gOpeCom) throws SOAPException {
        super.setupSOAPElements(DE, iTiDE, gDtipDE, gOpeCom);

        SOAPElement gTotSub = findChildElement(DE, "gTotSub");
        if (gTotSub == null) {
            return;
        }

        // Se omiten por compatibilidad con estructura esperada por DNIT en este ambiente.
        removeChildrenByLocalName(gTotSub, "dComi");
        removeChildrenByLocalName(gTotSub, "dLiqTotIVA5");
        removeChildrenByLocalName(gTotSub, "dLiqTotIVA10");
        removeChildrenByLocalName(gTotSub, "dIVAComi");
    }

    private SOAPElement findChildElement(SOAPElement parent, String localName) {
        Node child = parent.getFirstChild();
        while (child != null) {
            if (child instanceof SOAPElement) {
                SOAPElement childElement = (SOAPElement) child;
                if (localName.equals(childElement.getLocalName()) || localName.equals(childElement.getNodeName())) {
                    return childElement;
                }
            }
            child = child.getNextSibling();
        }
        return null;
    }

    private void removeChildrenByLocalName(SOAPElement parent, String localName) {
        Node child = parent.getFirstChild();
        while (child != null) {
            Node next = child.getNextSibling();
            if (child instanceof SOAPElement) {
                SOAPElement childElement = (SOAPElement) child;
                if (localName.equals(childElement.getLocalName()) || localName.equals(childElement.getNodeName())) {
                    parent.removeChild(child);
                }
            }
            child = next;
        }
    }
}
