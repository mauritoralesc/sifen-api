package com.ratones.sifenwrapper.patch;

import com.roshka.sifen.core.fields.request.de.TgTimb;

import javax.xml.soap.SOAPElement;
import javax.xml.soap.SOAPException;

/**
 * Parche para rshk-jsifenlib 0.2.4.
 * El campo dNumTim (C004) tiene longitud exacta de 8 dígitos según el Manual Técnico v150.
 * La librería serializa el valor como String.valueOf(int) sin relleno de ceros,
 * lo que provoca rechazo en SIFEN. Se sobreescribe setupSOAPElements para aplicar
 * el formato "%08d" y garantizar los 8 dígitos requeridos.
 */
public class TgTimbPatched extends TgTimb {

    @Override
    public void setupSOAPElements(SOAPElement DE) throws SOAPException {
        SOAPElement gTimb = DE.addChildElement("gTimb");
        gTimb.addChildElement("iTiDE")
                .setTextContent(String.valueOf(getiTiDE().getVal()));
        gTimb.addChildElement("dDesTiDE")
                .setTextContent(getiTiDE().getDescripcion());
        gTimb.addChildElement("dNumTim")
                .setTextContent(String.format("%08d", getdNumTim()));
        gTimb.addChildElement("dEst")
                .setTextContent(getdEst());
        gTimb.addChildElement("dPunExp")
                .setTextContent(getdPunExp());
        gTimb.addChildElement("dNumDoc")
                .setTextContent(getdNumDoc());
        if (getdSerieNum() != null) {
            gTimb.addChildElement("dSerieNum")
                    .setTextContent(getdSerieNum());
        }
        gTimb.addChildElement("dFeIniT")
                .setTextContent(getdFeIniT().toString());
    }
}
