package com.ratones.sifenwrapper.patch;

import com.roshka.sifen.core.fields.request.de.TgCamIVA;
import com.roshka.sifen.core.types.CMondT;
import com.roshka.sifen.internal.ctx.GenerationCtx;

import javax.xml.soap.SOAPElement;
import javax.xml.soap.SOAPException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Parche para rshk-jsifenlib 0.2.4.
 * En ambientes donde NT13 está activo, SIFEN exige dBasExe dentro de gCamIVA.
 * Por compatibilidad, se emite siempre con NT13: calculado para iAfecIVA=4 y
 * con valor 0 para los demás casos.
 */
public class TgCamIVAPatched extends TgCamIVA {

    @Override
    public void setupSOAPElements(GenerationCtx generationCtx, SOAPElement gCamItem,
                                  CMondT cMoneOpe, BigDecimal dTotOpeItem) throws SOAPException {
        SOAPElement gCamIVA = gCamItem.addChildElement("gCamIVA");
        gCamIVA.addChildElement("iAfecIVA").setTextContent(String.valueOf(getiAfecIVA().getVal()));
        gCamIVA.addChildElement("dDesAfecIVA").setTextContent(getiAfecIVA().getDescripcion());
        gCamIVA.addChildElement("dPropIVA").setTextContent(String.valueOf(getdPropIVA()));
        gCamIVA.addChildElement("dTasaIVA").setTextContent(String.valueOf(getdTasaIVA()));

        int scale = cMoneOpe.name().equals("PYG") ? 0 : 2;
        dTotOpeItem = dTotOpeItem.setScale(scale, RoundingMode.HALF_UP);

        BigDecimal hundred = BigDecimal.valueOf(100);
        BigDecimal propIVA = getdPropIVA().divide(hundred, scale, RoundingMode.HALF_UP);

        if (getiAfecIVA().getVal() == 1 || getiAfecIVA().getVal() == 4) {
            if (getdTasaIVA().equals(BigDecimal.valueOf(10))) {
                setdBasGravIVA(dTotOpeItem.multiply(propIVA).divide(BigDecimal.valueOf(1.1), scale, RoundingMode.HALF_UP));
                setdLiqIVAItem(dTotOpeItem.multiply(propIVA).divide(BigDecimal.valueOf(11), scale, RoundingMode.HALF_UP));
            } else if (getdTasaIVA().equals(BigDecimal.valueOf(5))) {
                setdBasGravIVA(dTotOpeItem.multiply(propIVA).divide(BigDecimal.valueOf(1.05), scale, RoundingMode.HALF_UP));
                setdLiqIVAItem(dTotOpeItem.multiply(propIVA).divide(BigDecimal.valueOf(21), scale, RoundingMode.HALF_UP));
            }
        } else {
            setdBasGravIVA(BigDecimal.ZERO);
            setdLiqIVAItem(BigDecimal.ZERO);
        }

        gCamIVA.addChildElement("dBasGravIVA").setTextContent(String.valueOf(getdBasGravIVA()));
        gCamIVA.addChildElement("dLiqIVAItem").setTextContent(String.valueOf(getdLiqIVAItem()));

        if (generationCtx.isHabilitarNotaTecnica13()) {
            BigDecimal dBasExe = BigDecimal.ZERO;
            if (getiAfecIVA().getVal() == 4) {
                dBasExe = (dTotOpeItem.multiply(hundred.subtract(propIVA)).multiply(hundred))
                        .divide((getdTasaIVA().multiply(propIVA)).add(BigDecimal.valueOf(10000)), scale, RoundingMode.HALF_UP);
            }
            setdBasExe(dBasExe);
            gCamIVA.addChildElement("dBasExe").setTextContent(String.valueOf(dBasExe));
        }
    }
}