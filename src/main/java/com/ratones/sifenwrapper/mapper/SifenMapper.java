package com.ratones.sifenwrapper.mapper;

import com.roshka.sifen.core.beans.DocumentoElectronico;
import com.roshka.sifen.core.fields.request.de.*;
import com.ratones.sifenwrapper.patch.TgTimbPatched;
import com.roshka.sifen.core.types.*;
import com.roshka.sifen.internal.util.SifenUtil;
import com.ratones.sifenwrapper.dto.request.*;
import com.ratones.sifenwrapper.patch.TgCamIVAPatched;
import com.ratones.sifenwrapper.patch.TgTotSubPatched;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Convierte los DTOs del REST API a los tipos de dominio de rshk-jsifenlib.
 * Basado en el Manual Técnico SIFEN v150.
 */
@Slf4j
@UtilityClass
public class SifenMapper {

    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final ZoneId PY_ZONE = ZoneId.of("America/Asuncion");
    private static final int CODIGO_SEGURIDAD_LENGTH = 9;

    // ─── DocumentoElectronico ─────────────────────────────────────────────────

    public DocumentoElectronico toDocumentoElectronico(EmitirFacturaRequest req) {
        DataDTO data = req.getData();
        ParamsDTO params = req.getParams();

        // Auto-pad numero a 7 dígitos (requerido por SIFEN)
        if (data.getNumero() != null) {
            data.setNumero(String.format("%07d", Long.parseLong(data.getNumero())));
        }

        DocumentoElectronico de = new DocumentoElectronico();

        // Fecha de firma digital y sistema de facturación
        de.setdFecFirma(LocalDateTime.now(PY_ZONE));
        de.setdSisFact((short) 1); // 1 = Sistema del contribuyente

        // Operación del DE (tipo emisión, código seguridad)
        TgOpeDE opeDE = new TgOpeDE();
        opeDE.setiTipEmi(TTipEmi.getByVal((short) data.getTipoEmision()));
        // dCodSeg debe ser 9 dígitos para que el CDC tenga 44 caracteres
        opeDE.setdCodSeg(padCodigoSeguridad(data.getCodigoSeguridad() != null
                ? data.getCodigoSeguridad() : data.getNumero()));
        de.setgOpeDE(opeDE);

        // Timbrado
        TgTimb timb = buildTimbrado(data, params);
        de.setgTimb(timb);

        // Datos generales de la operación (fecha, emisor, receptor, operación comercial)
        TdDatGralOpe datGralOpe = buildDatosGenerales(data, params);
        de.setgDatGralOpe(datGralOpe);

        // Datos específicos por tipo de DE (factura, NC/ND, remisión, items)
        TgDtipDE dtipDE = buildDtipDE(data);
        de.setgDtipDE(dtipDE);

        // Subtotales (obligatorio – la librería calcula automáticamente en setupSOAPElements)
        TgTotSub totSub = new TgTotSubPatched();
        de.setgTotSub(totSub);

        return de;
    }

    // ─── Timbrado ─────────────────────────────────────────────────────────────

    private TgTimb buildTimbrado(DataDTO data, ParamsDTO params) {
        TgTimb timb = new TgTimbPatched();
        timb.setiTiDE(TTiDE.getByVal((short) data.getTipoDocumento()));
        timb.setdNumTim(Integer.parseInt(params.getTimbradoNumero()));
        timb.setdEst(data.getEstablecimiento());
        timb.setdPunExp(data.getPunto());
        timb.setdNumDoc(data.getNumero());
        if (params.getTimbradoFecha() != null) {
            timb.setdFeIniT(LocalDate.parse(params.getTimbradoFecha(), DATE_FMT));
        }
        return timb;
    }

    // ─── Datos Generales ─────────────────────────────────────────────────────

    private TdDatGralOpe buildDatosGenerales(DataDTO data, ParamsDTO params) {
        TdDatGralOpe dg = new TdDatGralOpe();

        dg.setdFeEmiDE(LocalDateTime.parse(data.getFecha(), DATE_TIME_FMT));

        // Operación comercial (tipo transacción, impuesto, moneda)
        TgOpeCom opeCom = new TgOpeCom();
        opeCom.setiTipTra(TTipTra.getByVal((short) data.getTipoTransaccion()));
        opeCom.setiTImp(TTImp.getByVal((short) data.getTipoImpuesto()));
        if (data.getMoneda() != null) {
            opeCom.setcMoneOpe(CMondT.valueOf(data.getMoneda()));
        }
        dg.setgOpeCom(opeCom);

        // Emisor
        TgEmis emisor = buildEmisor(params, data);
        dg.setgEmis(emisor);

        // Receptor (cliente)
        TgDatRec receptor = buildReceptor(data.getCliente());
        dg.setgDatRec(receptor);

        return dg;
    }

    private TgEmis buildEmisor(ParamsDTO params, DataDTO data) {
        TgEmis emisor = new TgEmis();

        String[] rucEmisor = normalizeRucAndDv(params.getRuc());
        emisor.setdRucEm(rucEmisor[0]);
        emisor.setdDVEmi(rucEmisor[1]);
        emisor.setdNomEmi(params.getRazonSocial());
        emisor.setdNomFanEmi(params.getNombreFantasia());
        emisor.setiTipCont(TiTipCont.getByVal((short) params.getTipoContribuyente()));
        if (params.getTipoRegimen() > 0) {
            emisor.setcTipReg(TTipReg.getByVal((short) params.getTipoRegimen()));
        }

        // Actividades económicas
        if (params.getActividadesEconomicas() != null) {
            List<TgActEco> actividades = new ArrayList<>();
            for (ActividadEconomicaDTO ae : params.getActividadesEconomicas()) {
                TgActEco act = new TgActEco();
                act.setcActEco(ae.getCodigo());
                act.setdDesActEco(ae.getDescripcion());
                actividades.add(act);
            }
            emisor.setgActEcoList(actividades);
        }

        // Datos de dirección del emisor (del primer establecimiento)
        if (params.getEstablecimientos() != null && !params.getEstablecimientos().isEmpty()) {
            EstablecimientoDTO est = params.getEstablecimientos().get(0);
            emisor.setdDirEmi(est.getDireccion());
            emisor.setdNumCas(est.getNumeroCasa() != null ? est.getNumeroCasa() : "0");
            emisor.setcDepEmi(TDepartamento.getByVal((short) est.getDepartamento()));
            emisor.setcDisEmi((short) est.getDistrito());
            emisor.setdDesDisEmi(est.getDistritoDescripcion());
            emisor.setcCiuEmi(est.getCiudad());
            emisor.setdDesCiuEmi(est.getCiudadDescripcion());
            emisor.setdTelEmi(est.getTelefono());
            emisor.setdEmailE(est.getEmail());
            if (est.getDenominacion() != null) {
                emisor.setdDenSuc(est.getDenominacion());
            }
        }

        return emisor;
    }

    private TgDatRec buildReceptor(ClienteDTO cliente) {
        TgDatRec receptor = new TgDatRec();

        if (cliente == null) return receptor;

        receptor.setiNatRec(cliente.isContribuyente() ? TiNatRec.CONTRIBUYENTE : TiNatRec.NO_CONTRIBUYENTE);
        receptor.setiTiOpe(TiTiOpe.getByVal((short) cliente.getTipoOperacion()));

        if (cliente.getPais() != null) {
            try {
                receptor.setcPaisRec(PaisType.valueOf(cliente.getPais()));
            } catch (IllegalArgumentException e) {
                log.warn("Código de país no reconocido: {}, usando PRY", cliente.getPais());
                receptor.setcPaisRec(PaisType.PRY);
            }
        } else {
            receptor.setcPaisRec(PaisType.PRY);
        }

        if (cliente.isContribuyente() && cliente.getRuc() != null) {
            String[] rucReceptor = normalizeRucAndDv(cliente.getRuc());
            receptor.setdRucRec(rucReceptor[0]);
            receptor.setdDVRec(Short.parseShort(rucReceptor[1]));
        }

        Integer tipoDocumentoReceptor = resolveTipoDocumentoReceptor(cliente);
        if (tipoDocumentoReceptor != null) {
            receptor.setiTipIDRec(TiTipDocRec.getByVal(tipoDocumentoReceptor.shortValue()));
            receptor.setdNumIDRec(resolveNumeroDocumentoReceptor(cliente, tipoDocumentoReceptor));
        }

        receptor.setdNomRec(resolveNombreReceptor(cliente, tipoDocumentoReceptor));
        receptor.setdNomFanRec(cliente.getNombreFantasia());
        if (cliente.getTipoContribuyente() > 0) {
            receptor.setiTiContRec(TiTipCont.getByVal((short) cliente.getTipoContribuyente()));
        }
        receptor.setdDirRec(cliente.getDireccion());
        if (cliente.getNumeroCasa() != null) {
            try {
                receptor.setdNumCasRec(Integer.parseInt(cliente.getNumeroCasa()));
            } catch (NumberFormatException e) {
                receptor.setdNumCasRec(0);
            }
        }
        if (cliente.getDepartamento() > 0) {
            receptor.setcDepRec(TDepartamento.getByVal((short) cliente.getDepartamento()));
        }
        receptor.setcDisRec((short) cliente.getDistrito());
        receptor.setdDesDisRec(cliente.getDistritoDescripcion());
        receptor.setcCiuRec(cliente.getCiudad());
        receptor.setdDesCiuRec(cliente.getCiudadDescripcion());
        receptor.setdTelRec(cliente.getTelefono());
        receptor.setdEmailRec(cliente.getEmail());
        receptor.setdCodCliente(cliente.getCodigo());

        return receptor;
    }

    private Integer resolveTipoDocumentoReceptor(ClienteDTO cliente) {
        if (cliente.getITipIDRec() != null) {
            return cliente.getITipIDRec();
        }
        if (cliente.getTipoDocumentoIdentidad() != null) {
            return cliente.getTipoDocumentoIdentidad();
        }
        if (cliente.getTipoDocumento() != null) {
            return cliente.getTipoDocumento();
        }
        return cliente.getDocumentoTipo();
    }

    private String resolveNumeroDocumentoReceptor(ClienteDTO cliente, Integer tipoDocumentoReceptor) {
        if (cliente.getDNumIDRec() != null && !cliente.getDNumIDRec().isBlank()) {
            return cliente.getDNumIDRec();
        }
        if (cliente.getNumeroDocumentoIdentidad() != null && !cliente.getNumeroDocumentoIdentidad().isBlank()) {
            return cliente.getNumeroDocumentoIdentidad();
        }
        if (cliente.getNumeroDocumento() != null && !cliente.getNumeroDocumento().isBlank()) {
            return cliente.getNumeroDocumento();
        }
        if (cliente.getDocumentoNumero() != null && !cliente.getDocumentoNumero().isBlank()) {
            return cliente.getDocumentoNumero();
        }

        // Innominado requiere dNumIDRec = "0".
        if (tipoDocumentoReceptor != null && tipoDocumentoReceptor == 5) {
            return "0";
        }
        return "0";
    }

    private String resolveNombreReceptor(ClienteDTO cliente, Integer tipoDocumentoReceptor) {
        if (tipoDocumentoReceptor != null && tipoDocumentoReceptor == 5) {
            return "Sin Nombre";
        }
        return cliente.getRazonSocial();
    }

    // ─── Datos específicos por tipo de DE ─────────────────────────────────────

    private TgDtipDE buildDtipDE(DataDTO data) {
        TgDtipDE dtipDE = new TgDtipDE();

        switch (data.getTipoDocumento()) {
            case 1:
                dtipDE.setgCamFE(buildCamFE(data));
                if (data.getCondicion() != null) {
                    dtipDE.setgCamCond(buildCondicion(data.getCondicion()));
                }
                break;
            case 4:
                dtipDE.setgCamAE(buildCamAE(data));
                dtipDE.setgCamFE(buildCamFE(data));
                if (data.getCondicion() != null) {
                    dtipDE.setgCamCond(buildCondicion(data.getCondicion()));
                }
                break;
            case 5:
            case 6:
                dtipDE.setgCamNCDE(buildCamNCDE(data));
                break;
            case 7:
                dtipDE.setgCamNRE(buildCamNRE(data));
                break;
            default:
                log.warn("Tipo de documento no mapeado específicamente: {}", data.getTipoDocumento());
                break;
        }

        // Items (obligatorio para todos los tipos)
        dtipDE.setgCamItemList(buildItems(data.getItems()));

        return dtipDE;
    }

    // ─── Factura ──────────────────────────────────────────────────────────────

    private TgCamFE buildCamFE(DataDTO data) {
        TgCamFE camFE = new TgCamFE();

        if (data.getFactura() != null) {
            camFE.setiIndPres(TiIndPres.getByVal((short) data.getFactura().getPresencia()));
            // E503 dFecEmNR: solo informar cuando iIndPres != 1 (no presencial)
            if (data.getFactura().getFechaEnvio() != null && data.getFactura().getPresencia() != 1) {
                camFE.setdFecEmNR(LocalDate.parse(data.getFactura().getFechaEnvio(), DATE_FMT));
            }
        }

        return camFE;
    }

    private TgCamAE buildCamAE(DataDTO data) {
        TgCamAE camAE = new TgCamAE();
        if (data.getAutoFactura() != null) {
            camAE.setiNatVen(TiNatVen.getByVal((short) data.getAutoFactura().getTipoVendedor()));
            camAE.setdNomVen(data.getAutoFactura().getNombre());
            camAE.setdDirVen(data.getAutoFactura().getDireccion());
            camAE.setdNumCasVen(data.getAutoFactura().getNumeroCasa());
            camAE.setcDepVen(TDepartamento.getByVal((short) data.getAutoFactura().getDepartamento()));
            camAE.setcDisVen((short) data.getAutoFactura().getDistrito());
            camAE.setdDesDisVen(data.getAutoFactura().getDistritoDescripcion());
            camAE.setcCiuVen(data.getAutoFactura().getCiudad());
            camAE.setdDesCiuVen(data.getAutoFactura().getCiudadDescripcion());
        }
        return camAE;
    }

    private TgCamNCDE buildCamNCDE(DataDTO data) {
        TgCamNCDE camNCDE = new TgCamNCDE();
        if (data.getNotaCreditoDebito() != null) {
            camNCDE.setiMotEmi(TiMotEmi.getByVal((short) data.getNotaCreditoDebito().getMotivo()));
        }
        return camNCDE;
    }

    private TgCamNRE buildCamNRE(DataDTO data) {
        TgCamNRE camNRE = new TgCamNRE();
        if (data.getRemision() != null) {
            camNRE.setdKmR(data.getRemision().getKms());
            camNRE.setdFecEm(LocalDate.ofEpochDay(data.getRemision().getFechaFactura()));
        }
        return camNRE;
    }

    // ─── Condición ───────────────────────────────────────────────────────────

    private TgCamCond buildCondicion(CondicionDTO condicion) {
        TgCamCond camCond = new TgCamCond();
        camCond.setiCondOpe(TiCondOpe.getByVal((short) condicion.getTipo()));

        if (condicion.getEntregas() != null) {
            List<TgPaConEIni> entregas = new ArrayList<>();
            for (EntregaDTO e : condicion.getEntregas()) {
                TgPaConEIni entrega = new TgPaConEIni();
                entrega.setiTiPago(TiTiPago.getByVal((short) e.getTipo()));
                entrega.setdMonTiPag(e.getMonto());
                if (e.getMoneda() != null) {
                    entrega.setcMoneTiPag(CMondT.valueOf(e.getMoneda()));
                }
                entregas.add(entrega);
            }
            camCond.setgPaConEIniList(entregas);
        }

        if (condicion.getCredito() != null) {
            TgPagCred credito = new TgPagCred();
            credito.setiCondCred(TiCondCred.getByVal((short) condicion.getCredito().getTipo()));
            credito.setdPlazoCre(String.valueOf(condicion.getCredito().getPlazo()));
            // dCuotas sólo aplica cuando iCondCred = 2 (CUOTA); para tipo 1 (PLAZO) no se debe enviar
            if (condicion.getCredito().getTipo() == 2) {
                credito.setdCuotas((short) condicion.getCredito().getCuotas());
            }
            camCond.setgPagCred(credito);
        }

        return camCond;
    }

    // ─── Items ────────────────────────────────────────────────────────────────

    /**
     * Rellena el código de seguridad a 9 dígitos (requerido para el CDC de 44 posiciones).
     * Si el valor recibido contiene solo dígitos, se rellena con ceros a la izquierda.
     * Si es alfanumérico se trunca/rellena a 9 caracteres.
     */
    private String padCodigoSeguridad(String valor) {
        if (valor == null || valor.isBlank()) {
            valor = "000000001";
        }
        String soloDigitos = valor.replaceAll("[^0-9]", "");
        if (!soloDigitos.isEmpty()) {
            // Pad numérico a 9 dígitos
            long num = Long.parseLong(soloDigitos);
            return String.format("%0" + CODIGO_SEGURIDAD_LENGTH + "d", num);
        }
        // Fallback: pad con ceros a la izquierda
        return String.format("%" + CODIGO_SEGURIDAD_LENGTH + "s", valor)
                .replace(' ', '0');
    }

    /**
     * Normaliza RUC y DV. Si el DV no viene en el valor recibido, lo calcula con
     * la utilidad oficial de la librería rshk-jsifenlib.
     */
    private String[] normalizeRucAndDv(String rawRuc) {
        if (rawRuc == null || rawRuc.isBlank()) {
            return new String[]{"", "0"};
        }

        String[] partes = rawRuc.trim().split("-", 2);
        String ruc = partes[0].replaceAll("[^0-9]", "");

        if (ruc.isBlank()) {
            return new String[]{"", "0"};
        }

        String dv = partes.length > 1 ? partes[1].replaceAll("[^0-9]", "") : "";
        if (dv.isBlank()) {
            dv = SifenUtil.generateDv(ruc);
            log.debug("DV de RUC calculado automáticamente: {}-{}", ruc, dv);
        }

        return new String[]{ruc, dv};
    }

    private List<TgCamItem> buildItems(List<ItemDTO> items) {
        List<TgCamItem> resultado = new ArrayList<>();
        if (items == null) return resultado;

        for (ItemDTO item : items) {
            TgCamItem camItem = new TgCamItem();
            camItem.setdCodInt(item.getCodigo());
            camItem.setdDesProSer(item.getDescripcion());
            camItem.setcUniMed(TcUniMed.getByVal((short) item.getUnidadMedida()));
            camItem.setdCantProSer(item.getCantidad() != null ? item.getCantidad() : BigDecimal.ONE);

            // Valor del item
            TgValorItem valorItem = new TgValorItem();
            valorItem.setdPUniProSer(item.getPrecioUnitario());

            // Descuento y anticipo
            TgValorRestaItem valorRestaItem = new TgValorRestaItem();
            if (item.getDescuento() != null) valorRestaItem.setdDescItem(item.getDescuento());
            if (item.getAnticipo() != null) valorRestaItem.setdAntPreUniIt(item.getAnticipo());
            valorItem.setgValorRestaItem(valorRestaItem);

            camItem.setgValorItem(valorItem);

            // IVA
            TgCamIVA camIVA = new TgCamIVAPatched();
            camIVA.setiAfecIVA(TiAfecIVA.getByVal((short) item.getIvaTipo()));
            camIVA.setdTasaIVA(item.getIva() != null ? item.getIva() : BigDecimal.ZERO);
            camIVA.setdPropIVA(item.getIvaProporcion() != null ? item.getIvaProporcion() : BigDecimal.valueOf(100));
            camItem.setgCamIVA(camIVA);

            resultado.add(camItem);
        }

        return resultado;
    }
}
