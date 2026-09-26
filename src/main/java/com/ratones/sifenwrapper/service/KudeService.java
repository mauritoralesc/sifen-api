package com.ratones.sifenwrapper.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.ratones.sifenwrapper.dto.request.*;
import com.roshka.sifen.core.types.TcUniMed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Servicio para generar el KUDE (Constancia de Documento Electrónico) en formato PDF.
 * Sigue el formato gráfico de la SET: banda de título, caja de emisor/timbrado, caja de
 * receptor/condición, grilla de ítems estirada hasta el pie, bandas de totales y caja de QR + CDC.
 */
@Slf4j
@Service
public class KudeService {

    // ─── Fuentes ──────────────────────────────────────────────────────────────
    private static final Font BAND_FONT = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.BLACK);
    private static final Font LABEL_FONT = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, Color.BLACK);
    private static final Font LABEL_BOLD_FONT = new Font(Font.HELVETICA, 8.5f, Font.BOLD, Color.BLACK);
    private static final Font EMISOR_FONT = new Font(Font.HELVETICA, 10, Font.BOLD, Color.BLACK);
    private static final Font TIPO_DOC_FONT = new Font(Font.HELVETICA, 11, Font.BOLD, Color.BLACK);
    private static final Font CHECK_FONT = new Font(Font.HELVETICA, 7.5f, Font.BOLD, Color.BLACK);
    private static final Font TABLE_HEADER_FONT = new Font(Font.HELVETICA, 7.5f, Font.BOLD, Color.BLACK);
    private static final Font TABLE_CELL_FONT = new Font(Font.HELVETICA, 7, Font.NORMAL, Color.BLACK);
    private static final Font TOTAL_LABEL_FONT = new Font(Font.HELVETICA, 9, Font.BOLD, Color.BLACK);
    private static final Font TOTAL_VALUE_FONT = new Font(Font.HELVETICA, 7, Font.BOLD, Color.BLACK);
    private static final Font LETRAS_FONT = new Font(Font.HELVETICA, 8, Font.NORMAL, Color.BLACK);
    private static final Font CONSULTA_FONT = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, Color.BLACK);
    private static final Font URL_FONT = new Font(Font.HELVETICA, 12, Font.BOLD, Color.BLACK);
    private static final Font CDC_FONT = new Font(Font.HELVETICA, 11, Font.BOLD, Color.BLACK);
    private static final Font NOTA_FONT = new Font(Font.HELVETICA, 6.5f, Font.NORMAL, Color.BLACK);
    private static final Font SMALL_FONT = new Font(Font.HELVETICA, 7, Font.NORMAL, Color.GRAY);
    private static final Font ESTADO_FONT = new Font(Font.HELVETICA, 8, Font.BOLD, new Color(192, 57, 43));

    private static final Color BAND_BG = new Color(217, 217, 217);
    private static final float BORDER_WIDTH = 0.75f;
    private static final float LEADING = 11f;
    private static final float SECTION_GAP = 3f;
    /** Holgura para que redondeos del cálculo de alturas no empujen el QR a una segunda página. */
    private static final float MARGEN_RELLENO_PT = 4f;

    private static final float QR_SIZE_PT = 34 * 72f / 25.4f; // 34 mm en puntos PDF
    private static final float LOGO_MAX_WIDTH_PT = 170f;
    private static final float LOGO_MAX_HEIGHT_PT = 50f;

    /** Cod. | Descripción | U.M. | Cant. | Precio Unitario | Descuento | Cotización | Exentas | 5% | 10% */
    private static final float[] ITEM_WIDTHS = {7, 21, 5.5f, 5.5f, 11, 8.5f, 8.5f, 11, 11, 11};

    private static final DecimalFormatSymbols SIMBOLOS_PY = simbolosPy();

    // ─── Generación del KUDE ──────────────────────────────────────────────────

    /**
     * Genera el KUDE como byte array PDF a partir de los datos del request.
     */
    public byte[] generarKude(KudeRequest request) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 28, 28, 28, 28);
            PdfWriter writer = PdfWriter.getInstance(document, baos);
            document.open();
            float anchoUtil = document.right() - document.left();

            addEncabezado(document, request);
            addInfoEmision(document, request);
            addDocumentoAsociado(document, request);

            Totales totales = calcularTotales(request.getData());
            PdfPTable tablaItems = buildTablaItems(request, anchoUtil);
            PdfPTable tablaTotales = buildTablaTotales(request, totales, anchoUtil);
            PdfPTable tablaQr = buildQrYCdc(request, anchoUtil);
            PdfPTable nota = buildNotaPie(request, anchoUtil);

            // La grilla de ítems se estira para dejar totales y QR al pie de la página.
            // Si los ítems no entran en una sola página no se rellena y el contenido fluye.
            float alturaResto = SECTION_GAP + tablaItems.getTotalHeight() + tablaTotales.getTotalHeight()
                    + tablaQr.getTotalHeight() + (nota != null ? nota.getTotalHeight() : 0);
            float relleno = writer.getVerticalPosition(true) - document.bottom() - alturaResto - MARGEN_RELLENO_PT;
            if (relleno > 0) {
                addFilaRelleno(tablaItems, relleno);
            }

            document.add(tablaItems);
            document.add(tablaTotales);
            document.add(tablaQr);
            if (nota != null) {
                document.add(nota);
            }

            document.close();
            return baos.toByteArray();

        } catch (Exception e) {
            log.error("Error al generar KUDE PDF: {}", e.getMessage(), e);
            throw new RuntimeException("Error al generar KUDE: " + e.getMessage(), e);
        }
    }

    /**
     * Genera el KUDE como String base64.
     */
    public String generarKudeBase64(KudeRequest request) {
        byte[] pdfBytes = generarKude(request);
        return Base64.getEncoder().encodeToString(pdfBytes);
    }

    // ─── Secciones del KUDE ───────────────────────────────────────────────────

    /** Banda "KuDE De ..." + caja con datos del emisor (izq.) y del timbrado/número (der.). */
    private void addEncabezado(Document doc, KudeRequest req) throws DocumentException {
        ParamsDTO params = req.getParams();
        DataDTO data = req.getData();

        String nombreDoc = resolverNombreDocumento(data.getTipoDocumento());
        String numero = String.format("%s-%s-%s", data.getEstablecimiento(), data.getPunto(), data.getNumero());

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{62, 38});

        PdfPCell banda = new PdfPCell(new Phrase("KuDE De " + nombreDoc, BAND_FONT));
        banda.setColspan(2);
        banda.setBackgroundColor(BAND_BG);
        banda.setHorizontalAlignment(Element.ALIGN_CENTER);
        banda.setVerticalAlignment(Element.ALIGN_MIDDLE);
        banda.setPadding(4);
        estiloBorde(banda, Rectangle.BOX);
        table.addCell(banda);

        // ── Columna izquierda: logo + datos del emisor ──
        PdfPCell emisorCell = celdaContenido(Rectangle.LEFT | Rectangle.BOTTOM);
        Image logo = buildLogoImage(params.getLogoBase64());
        if (logo != null) {
            emisorCell.addElement(logo);
        }
        if (!isBlank(params.getRazonSocial())) {
            Paragraph razon = new Paragraph(13, params.getRazonSocial(), EMISOR_FONT);
            razon.setSpacingBefore(logo != null ? 4 : 0);
            emisorCell.addElement(razon);
        }
        if (!isBlank(params.getNombreFantasia()) && !params.getNombreFantasia().equalsIgnoreCase(params.getRazonSocial())) {
            emisorCell.addElement(linea(params.getNombreFantasia()));
        }
        if (params.getEstablecimientos() != null && !params.getEstablecimientos().isEmpty()) {
            EstablecimientoDTO est = params.getEstablecimientos().get(0);
            if (!isBlank(est.getDireccion())) emisorCell.addElement(linea(est.getDireccion()));
            String ciudad = !isBlank(est.getCiudadDescripcion()) ? est.getCiudadDescripcion() : est.getDepartamentoDescripcion();
            if (!isBlank(ciudad)) emisorCell.addElement(linea(ciudad));
            if (!isBlank(est.getTelefono())) emisorCell.addElement(linea(est.getTelefono()));
            if (!isBlank(est.getEmail())) emisorCell.addElement(linea(est.getEmail()));
        }
        if (params.getActividadesEconomicas() != null && !params.getActividadesEconomicas().isEmpty()) {
            String act = params.getActividadesEconomicas().stream()
                    .map(ActividadEconomicaDTO::getDescripcion)
                    .collect(java.util.stream.Collectors.joining(" / "));
            emisorCell.addElement(campo("Actividad Económica:", act));
        }
        table.addCell(emisorCell);

        // ── Columna derecha: RUC, timbrado, tipo y número de documento ──
        PdfPCell docCell = celdaContenido(Rectangle.RIGHT | Rectangle.BOTTOM);
        docCell.addElement(campo("RUC:", params.getRuc()));
        docCell.addElement(campo("Timbrado Nº:", params.getTimbradoNumero()));
        docCell.addElement(campo("Fecha Inicio de Vigencia:", formatearFecha(params.getTimbradoFecha())));

        Paragraph tipoDoc = new Paragraph(15, nombreDoc.toUpperCase(Locale.ROOT), TIPO_DOC_FONT);
        tipoDoc.setSpacingBefore(14);
        docCell.addElement(tipoDoc);
        docCell.addElement(new Paragraph(15, numero, TIPO_DOC_FONT));
        table.addCell(docCell);

        doc.add(table);
    }

    /** Caja con fecha/condición/moneda (izq.) y datos del receptor (der.). */
    private void addInfoEmision(Document doc, KudeRequest req) throws DocumentException {
        DataDTO data = req.getData();
        ClienteDTO cliente = data.getCliente();
        CondicionDTO condicion = data.getCondicion();

        boolean innominado = cliente == null || isInnominado(cliente);
        String nombreReceptor = innominado ? "Sin Nombre" : vacio(cliente.getRazonSocial());
        String docReceptor = innominado ? "Innominado" : vacio(resolveDocumentoCliente(cliente));

        // NC/ND (5/6) no tienen condición de venta: mostrar el motivo de emisión (E401)
        // en su lugar. "[X] Contado" sería espurio, ya que condicion siempre es null ahí.
        boolean esNotaCreditoDebito = data.getTipoDocumento() == 5 || data.getTipoDocumento() == 6;

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{50, 50});
        table.setSpacingBefore(SECTION_GAP);

        // ── Columna izquierda ──
        PdfPCell leftCell = celdaContenido(Rectangle.LEFT | Rectangle.TOP | Rectangle.BOTTOM);
        leftCell.addElement(campo("Fecha y hora de emisión:", formatearFecha(data.getFecha())));

        if (esNotaCreditoDebito) {
            leftCell.addElement(campo("Motivo de emisión:", resolverMotivoEmision(data), LABEL_BOLD_FONT));
        } else {
            boolean esContado = condicion == null || condicion.getTipo() == 1;
            leftCell.addElement(buildCondicionVenta(esContado));

            String cuotas = "";
            if (!esContado && condicion.getCredito() != null) {
                CreditoDTO cred = condicion.getCredito();
                if (cred.getCuotas() > 0) cuotas = String.valueOf(cred.getCuotas());
                if (cred.getPlazo() > 0) cuotas += (cuotas.isEmpty() ? "" : "      ") + "Plazo: " + cred.getPlazo() + " días";
            }
            leftCell.addElement(campo("Cuotas:", cuotas));
        }

        String moneda = monedaDocumento(data);
        BigDecimal tipoCambio = esGuaranies(data) ? BigDecimal.ZERO : tipoCambioDeEntregas(condicion);
        Paragraph monedaPara = campo("Moneda:", moneda);
        monedaPara.add(new Chunk("        Tipo de Cambio:  ", LABEL_FONT));
        monedaPara.add(new Chunk(tipoCambio != null ? formatMonto(tipoCambio) : "", LABEL_FONT));
        leftCell.addElement(monedaPara);

        String globalOItem = esGuaranies(data) ? "Ninguno" : (tipoCambio != null ? "Global" : "");
        leftCell.addElement(campo("Tipo de cambio global o por ítem:", globalOItem));

        if (!isBlank(data.getCajero())) {
            leftCell.addElement(campo("Cajero:", data.getCajero()));
        }
        table.addCell(leftCell);

        // ── Columna derecha: receptor ──
        PdfPCell rightCell = celdaContenido(Rectangle.RIGHT | Rectangle.TOP | Rectangle.BOTTOM);
        rightCell.addElement(campo("RUC/Documento de Identidad Nro.:", docReceptor));
        rightCell.addElement(campo("Nombre o Razón Social:", nombreReceptor));

        String direccion = "";
        String telefono = "";
        String email = "";
        if (cliente != null) {
            StringBuilder dir = new StringBuilder(vacio(cliente.getDireccion()));
            if (!isBlank(cliente.getCiudadDescripcion())) {
                dir.append(dir.length() > 0 ? " - " : "").append(cliente.getCiudadDescripcion());
            }
            direccion = dir.toString();
            telefono = vacio(cliente.getTelefono());
            email = vacio(cliente.getEmail());
        }
        rightCell.addElement(campo("Dirección:", direccion));
        rightCell.addElement(campo("Teléfono:", telefono));
        rightCell.addElement(campo("Correo Electrónico:", email));

        if (data.getTipoTransaccion() > 0) {
            rightCell.addElement(campo("Tipo de Transacción:", resolverTipoTransaccion(data.getTipoTransaccion()), LABEL_BOLD_FONT));
        }
        if (!isBlank(data.getSocio())) {
            rightCell.addElement(campo("Socio:", data.getSocio()));
        }
        table.addCell(rightCell);

        doc.add(table);
    }

    /** Línea "Condición de venta:  Contado [ ]  Crédito [X]" con casillas dibujadas como celdas. */
    private PdfPTable buildCondicionVenta(boolean esContado) throws DocumentException {
        PdfPTable t = new PdfPTable(5);
        t.setTotalWidth(new float[]{95, 40, 15, 50, 15});
        t.setLockedWidth(true);
        t.setHorizontalAlignment(Element.ALIGN_LEFT);
        t.setSpacingBefore(3);
        t.setSpacingAfter(1);

        t.addCell(celdaCondicion("Condición de venta:", LABEL_FONT, Element.ALIGN_LEFT));
        t.addCell(celdaCondicion("Contado", LABEL_BOLD_FONT, Element.ALIGN_LEFT));
        t.addCell(casilla(esContado));
        t.addCell(celdaCondicion("Crédito", LABEL_BOLD_FONT, Element.ALIGN_RIGHT));
        t.addCell(casilla(!esContado));
        return t;
    }

    private PdfPCell celdaCondicion(String texto, Font font, int alignment) {
        PdfPCell c = new PdfPCell(new Phrase(texto, font));
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(0);
        c.setPaddingRight(alignment == Element.ALIGN_RIGHT ? 6 : 0);
        c.setFixedHeight(LEADING);
        c.setHorizontalAlignment(alignment);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setUseAscender(true);
        return c;
    }

    private PdfPCell casilla(boolean marcada) {
        PdfPCell c = new PdfPCell(new Phrase(marcada ? "X" : "", CHECK_FONT));
        estiloBorde(c, Rectangle.BOX);
        c.setPadding(0);
        c.setFixedHeight(LEADING);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setUseAscender(true);
        return c;
    }

    /** Grupo H: documento que la NC/ND ajusta. No-op si el request no trae documentoAsociado. */
    private void addDocumentoAsociado(Document doc, KudeRequest req) throws DocumentException {
        DocumentoAsociadoDTO asociado = req.getData().getDocumentoAsociado();
        if (asociado == null || asociado.getTipoDocumentoAsociado() == null) return;

        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setSpacingBefore(SECTION_GAP);

        PdfPCell cell = celdaContenido(Rectangle.BOX);
        cell.addElement(new Paragraph(LEADING, "Documento Asociado", LABEL_BOLD_FONT));

        if (asociado.getTipoDocumentoAsociado() == 1) {
            cell.addElement(campo("CDC:", formatearCdc(asociado.getCdcAsociado())));
        } else if (asociado.getTipoDocumentoAsociado() == 2) {
            String numero = String.format("%s-%s-%s",
                    nulo(asociado.getEstablecimientoAsociado()),
                    nulo(asociado.getPuntoAsociado()),
                    nulo(asociado.getNumeroAsociado()));
            Paragraph p = campo("Comprobante impreso — Timbrado:", nulo(asociado.getTimbradoAsociado()));
            p.add(new Chunk("      Nº:  " + numero + "      Fecha:  " + formatearFecha(asociado.getFechaEmisionAsociado()), LABEL_FONT));
            cell.addElement(p);
        }

        table.addCell(cell);
        doc.add(table);
    }

    private PdfPTable buildTablaItems(KudeRequest req, float ancho) throws DocumentException {
        PdfPTable table = new PdfPTable(ITEM_WIDTHS.length);
        table.setWidths(ITEM_WIDTHS);
        table.setSpacingBefore(SECTION_GAP);
        table.setHeaderRows(2);

        String[] fijos = {"Cod.", "Descripción", "U.M.", "Cant.", "Precio\nUnitario", "Descuento", "Cotización"};
        for (String h : fijos) {
            PdfPCell cell = celdaEncabezado(h, Rectangle.BOX);
            cell.setRowspan(2);
            table.addCell(cell);
        }
        PdfPCell valorVenta = celdaEncabezado("Valor de Venta", Rectangle.TOP | Rectangle.LEFT | Rectangle.RIGHT);
        valorVenta.setColspan(3);
        valorVenta.setPaddingBottom(0);
        table.addCell(valorVenta);
        table.addCell(celdaEncabezado("Exentas", Rectangle.LEFT | Rectangle.BOTTOM));
        table.addCell(celdaEncabezado("5%", Rectangle.BOTTOM));
        table.addCell(celdaEncabezado("10%", Rectangle.RIGHT | Rectangle.BOTTOM));

        List<ItemDTO> items = req.getData().getItems();
        if (items != null) {
            for (ItemDTO item : items) {
                BigDecimal cantidad = item.getCantidad() != null ? item.getCantidad() : BigDecimal.ONE;
                BigDecimal precioUnit = item.getPrecioUnitario() != null ? item.getPrecioUnitario() : BigDecimal.ZERO;
                BigDecimal descuento = item.getDescuento() != null ? item.getDescuento() : BigDecimal.ZERO;
                BigDecimal neto = montoNeto(item);
                int columna = columnaIva(item);

                addItemCell(table, vacio(item.getCodigo()), Element.ALIGN_CENTER);
                addItemCell(table, vacio(item.getDescripcion()), Element.ALIGN_LEFT);
                addItemCell(table, resolverUnidadMedida(item.getUnidadMedida()), Element.ALIGN_CENTER);
                addItemCell(table, formatCantidad(cantidad), Element.ALIGN_CENTER);
                addItemCell(table, formatMonto(precioUnit), Element.ALIGN_RIGHT);
                addItemCell(table, formatMonto(descuento), Element.ALIGN_RIGHT);
                addItemCell(table, formatMonto(BigDecimal.ZERO), Element.ALIGN_RIGHT);
                addItemCell(table, formatMonto(columna == 0 ? neto : BigDecimal.ZERO), Element.ALIGN_RIGHT);
                addItemCell(table, formatMonto(columna == 5 ? neto : BigDecimal.ZERO), Element.ALIGN_RIGHT);
                addItemCell(table, formatMonto(columna == 10 ? neto : BigDecimal.ZERO), Element.ALIGN_RIGHT);
            }
        }

        table.setTotalWidth(ancho);
        table.setLockedWidth(true);
        return table;
    }

    /** Fila vacía que estira las columnas de la grilla de ítems hasta el bloque de totales. */
    private void addFilaRelleno(PdfPTable table, float altura) {
        for (int i = 0; i < ITEM_WIDTHS.length; i++) {
            PdfPCell cell = new PdfPCell(new Phrase(""));
            estiloBorde(cell, Rectangle.LEFT | Rectangle.RIGHT | Rectangle.BOTTOM);
            cell.setFixedHeight(altura);
            table.addCell(cell);
        }
    }

    /** SUBTOTAL, TOTAL DE LA OPERACIÓN, TOTAL EN GUARANÍES, LIQUIDACIÓN IVA y monto en letras. */
    private PdfPTable buildTablaTotales(KudeRequest req, Totales t, float ancho) throws DocumentException {
        boolean guaranies = esGuaranies(req.getData());

        PdfPTable table = new PdfPTable(ITEM_WIDTHS.length);
        table.setWidths(ITEM_WIDTHS);

        table.addCell(celdaTotal(new Phrase("SUBTOTAL", TOTAL_LABEL_FONT), 7, Element.ALIGN_LEFT, Rectangle.BOX));
        table.addCell(celdaTotal(new Phrase(formatMonto(t.exenta()), TOTAL_VALUE_FONT), 1, Element.ALIGN_RIGHT, Rectangle.BOX));
        table.addCell(celdaTotal(new Phrase(formatMonto(t.gravada5()), TOTAL_VALUE_FONT), 1, Element.ALIGN_RIGHT, Rectangle.BOX));
        table.addCell(celdaTotal(new Phrase(formatMonto(t.gravada10()), TOTAL_VALUE_FONT), 1, Element.ALIGN_RIGHT, Rectangle.BOX));

        table.addCell(celdaTotal(new Phrase("TOTAL DE LA OPERACIÓN:", TOTAL_LABEL_FONT), 9, Element.ALIGN_LEFT, Rectangle.BOX));
        table.addCell(celdaTotal(new Phrase(formatMonto(t.total()), TOTAL_VALUE_FONT), 1, Element.ALIGN_RIGHT, Rectangle.BOX));

        if (guaranies) {
            table.addCell(celdaTotal(new Phrase("TOTAL EN GUARANÍES:", TOTAL_LABEL_FONT), 9, Element.ALIGN_LEFT, Rectangle.BOX));
            table.addCell(celdaTotal(new Phrase(formatEntero(t.total()), TOTAL_VALUE_FONT), 1, Element.ALIGN_RIGHT, Rectangle.BOX));
        }

        int escala = guaranies ? 0 : 2;
        BigDecimal liq5 = t.liquidacionIva5(escala);
        BigDecimal liq10 = t.liquidacionIva10(escala);
        table.addCell(celdaTotal(new Phrase("LIQUIDACIÓN IVA:", TOTAL_LABEL_FONT), 2, Element.ALIGN_LEFT,
                Rectangle.LEFT | Rectangle.TOP | Rectangle.BOTTOM));
        table.addCell(celdaTotal(fraseLiquidacion("(5%)", liq5), 3, Element.ALIGN_LEFT,
                Rectangle.TOP | Rectangle.BOTTOM));
        table.addCell(celdaTotal(fraseLiquidacion("(10%)", liq10), 2, Element.ALIGN_LEFT,
                Rectangle.TOP | Rectangle.BOTTOM));
        table.addCell(celdaTotal(fraseLiquidacion("TOTAL IVA:", liq5.add(liq10)), 3, Element.ALIGN_LEFT,
                Rectangle.RIGHT | Rectangle.TOP | Rectangle.BOTTOM));

        table.addCell(celdaTotal(new Phrase(montoEnLetras(t.total(), req.getData()), LETRAS_FONT), 10,
                Element.ALIGN_LEFT, Rectangle.BOX));

        table.setKeepTogether(true);
        table.setTotalWidth(ancho);
        table.setLockedWidth(true);
        return table;
    }

    private Phrase fraseLiquidacion(String etiqueta, BigDecimal monto) {
        Phrase p = new Phrase();
        p.add(new Chunk(etiqueta + "  ", TOTAL_LABEL_FONT));
        p.add(new Chunk(formatMonto(monto), TOTAL_VALUE_FONT));
        return p;
    }

    /** Caja inferior: QR (izq.) + leyenda de consulta, CDC y aviso de representación gráfica (der.). */
    private PdfPTable buildQrYCdc(KudeRequest req, float ancho) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidths(new float[]{27, 73});

        PdfPCell qrCell;
        Image qrImage = null;
        if (!isBlank(req.getQrUrl())) {
            try {
                qrImage = generarQrImage(req.getQrUrl(), 300, 300);
                qrImage.scaleAbsolute(QR_SIZE_PT, QR_SIZE_PT);
            } catch (Exception e) {
                log.warn("No se pudo generar QR: {}", e.getMessage());
            }
        }
        qrCell = qrImage != null ? new PdfPCell(qrImage, false) : new PdfPCell(new Phrase("QR no disponible", SMALL_FONT));
        estiloBorde(qrCell, Rectangle.LEFT | Rectangle.BOTTOM);
        qrCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        qrCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        qrCell.setPadding(10);
        table.addCell(qrCell);

        PdfPCell infoCell = celdaContenido(Rectangle.RIGHT | Rectangle.BOTTOM);
        infoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        infoCell.setPaddingTop(12);
        infoCell.setPaddingBottom(12);
        infoCell.setPaddingLeft(4);
        infoCell.setPaddingRight(8);

        String tipoDoc = resolverNombreDocumento(req.getData().getTipoDocumento()).toUpperCase(Locale.ROOT);
        String articulo = tipoDoc.startsWith("DOCUMENTO") ? "ESTE " : "ESTA ";
        infoCell.addElement(centrado(
                "CONSULTE LA VALIDEZ DE " + articulo + tipoDoc + " CON EL NÚMERO CDC IMPRESO ABAJO EN:",
                CONSULTA_FONT, 10, 0));
        infoCell.addElement(centrado("www.ekuatia.set.gov.py/consultas", URL_FONT, 14, 8));

        if (req.getCdc() != null) {
            infoCell.addElement(centrado(formatearCdc(req.getCdc()), CDC_FONT, 14, 12));
        }

        infoCell.addElement(centrado(
                "ESTE DOCUMENTO ES UNA REPRESENTACIÓN GRÁFICA DE UN DOCUMENTO ELECTRÓNICO (XML) "
                        + "SI SU DOCUMENTO ELECTRÓNICO PRESENTA ALGÚN ERROR, PUEDE SOLICITAR LA MODIFICACIÓN "
                        + "DENTRO DE LAS 72 HORAS DE EMISIÓN DE ESTE COMPROBANTE",
                CONSULTA_FONT, 10, 14));

        // Un KUDE de un DE no aprobado no debe pasar por válido: se marca el estado SIFEN.
        if (req.getEstado() != null && !req.getEstado().toUpperCase(Locale.ROOT).startsWith("APROBADO")) {
            String estado = "Estado SIFEN: " + req.getEstado()
                    + (req.getCodigoEstado() != null ? " (" + req.getCodigoEstado() + ")" : "");
            infoCell.addElement(centrado(estado, ESTADO_FONT, 11, 8));
        }

        table.addCell(infoCell);

        table.setKeepTogether(true);
        table.setTotalWidth(ancho);
        table.setLockedWidth(true);
        return table;
    }

    /** Nota libre debajo de la caja del QR (data.observacion); null si no se envió. */
    private PdfPTable buildNotaPie(KudeRequest req, float ancho) {
        String observacion = req.getData().getObservacion();
        if (isBlank(observacion)) return null;

        PdfPTable table = new PdfPTable(1);
        PdfPCell cell = new PdfPCell(new Phrase(observacion, NOTA_FONT));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        cell.setPaddingTop(2);
        table.addCell(cell);

        table.setTotalWidth(ancho);
        table.setLockedWidth(true);
        return table;
    }

    // ─── Totales ──────────────────────────────────────────────────────────────

    private record Totales(BigDecimal exenta, BigDecimal gravada5, BigDecimal gravada10) {
        BigDecimal total() {
            return exenta.add(gravada5).add(gravada10);
        }

        BigDecimal liquidacionIva5(int escala) {
            return gravada5.multiply(BigDecimal.valueOf(5)).divide(BigDecimal.valueOf(105), escala, RoundingMode.HALF_UP);
        }

        BigDecimal liquidacionIva10(int escala) {
            return gravada10.multiply(BigDecimal.TEN).divide(BigDecimal.valueOf(110), escala, RoundingMode.HALF_UP);
        }
    }

    private Totales calcularTotales(DataDTO data) {
        BigDecimal exenta = BigDecimal.ZERO;
        BigDecimal gravada5 = BigDecimal.ZERO;
        BigDecimal gravada10 = BigDecimal.ZERO;
        if (data.getItems() != null) {
            for (ItemDTO item : data.getItems()) {
                BigDecimal neto = montoNeto(item);
                switch (columnaIva(item)) {
                    case 0 -> exenta = exenta.add(neto);
                    case 5 -> gravada5 = gravada5.add(neto);
                    default -> gravada10 = gravada10.add(neto);
                }
            }
        }
        return new Totales(exenta, gravada5, gravada10);
    }

    private BigDecimal montoNeto(ItemDTO item) {
        BigDecimal cantidad = item.getCantidad() != null ? item.getCantidad() : BigDecimal.ONE;
        BigDecimal precioUnit = item.getPrecioUnitario() != null ? item.getPrecioUnitario() : BigDecimal.ZERO;
        BigDecimal descuento = item.getDescuento() != null ? item.getDescuento() : BigDecimal.ZERO;
        return cantidad.multiply(precioUnit).subtract(descuento);
    }

    /** Columna "Valor de Venta" del ítem: 0 = Exentas, 5 = 5%, 10 = 10%. */
    private int columnaIva(ItemDTO item) {
        BigDecimal tasaIva = item.getIva() != null ? item.getIva() : BigDecimal.ZERO;
        if (item.getIvaTipo() == 3 || tasaIva.compareTo(BigDecimal.ZERO) == 0) return 0;
        if (tasaIva.compareTo(BigDecimal.valueOf(5)) == 0) return 5;
        return 10;
    }

    // ─── Helpers de celdas ────────────────────────────────────────────────────

    private void estiloBorde(PdfPCell cell, int bordes) {
        cell.setBorder(bordes);
        cell.setBorderColor(Color.BLACK);
        cell.setBorderWidth(BORDER_WIDTH);
    }

    /** Celda en modo compuesto (se llena con addElement) para las cajas de encabezado. */
    private PdfPCell celdaContenido(int bordes) {
        PdfPCell cell = new PdfPCell();
        estiloBorde(cell, bordes);
        cell.setPaddingTop(6);
        cell.setPaddingBottom(8);
        cell.setPaddingLeft(12);
        cell.setPaddingRight(8);
        return cell;
    }

    private PdfPCell celdaEncabezado(String texto, int bordes) {
        PdfPCell cell = new PdfPCell(new Phrase(9.5f, texto, TABLE_HEADER_FONT));
        estiloBorde(cell, bordes);
        cell.setBackgroundColor(BAND_BG);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(2);
        cell.setMinimumHeight(13);
        return cell;
    }

    private void addItemCell(PdfPTable table, String text, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, TABLE_CELL_FONT));
        estiloBorde(cell, Rectangle.LEFT | Rectangle.RIGHT);
        cell.setHorizontalAlignment(alignment);
        cell.setPaddingTop(3);
        cell.setPaddingBottom(3);
        cell.setPaddingLeft(2);
        cell.setPaddingRight(2);
        table.addCell(cell);
    }

    private PdfPCell celdaTotal(Phrase contenido, int colspan, int alignment, int bordes) {
        PdfPCell cell = new PdfPCell(contenido);
        estiloBorde(cell, bordes);
        cell.setColspan(colspan);
        cell.setBackgroundColor(BAND_BG);
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPaddingTop(3);
        cell.setPaddingBottom(4);
        cell.setPaddingLeft(alignment == Element.ALIGN_LEFT ? 8 : 3);
        cell.setPaddingRight(3);
        return cell;
    }

    /** "Etiqueta:  valor" en una línea, ambos en fuente normal salvo que se indique otra para el valor. */
    private Paragraph campo(String etiqueta, String valor) {
        return campo(etiqueta, valor, LABEL_FONT);
    }

    private Paragraph campo(String etiqueta, String valor, Font fuenteValor) {
        Paragraph p = new Paragraph(LEADING);
        p.add(new Chunk(etiqueta + "  ", LABEL_FONT));
        p.add(new Chunk(valor != null ? valor : "", fuenteValor));
        return p;
    }

    private Paragraph linea(String texto) {
        return new Paragraph(LEADING, texto, LABEL_FONT);
    }

    private Paragraph centrado(String texto, Font font, float leading, float spacingBefore) {
        Paragraph p = new Paragraph(leading, texto, font);
        p.setAlignment(Element.ALIGN_CENTER);
        p.setSpacingBefore(spacingBefore);
        return p;
    }

    private Image buildLogoImage(String logoBase64) {
        if (logoBase64 == null || logoBase64.isBlank()) {
            return null;
        }

        try {
            String raw = logoBase64.trim();
            int commaIdx = raw.indexOf(',');
            if (raw.startsWith("data:") && commaIdx > 0) {
                raw = raw.substring(commaIdx + 1);
            }

            byte[] logoBytes = Base64.getDecoder().decode(raw);
            Image logo = Image.getInstance(logoBytes);
            logo.scaleToFit(LOGO_MAX_WIDTH_PT, LOGO_MAX_HEIGHT_PT);
            logo.setAlignment(Image.ALIGN_LEFT);
            return logo;
        } catch (Exception e) {
            log.warn("No se pudo decodificar el logo de empresa para KUDE: {}", e.getMessage());
            return null;
        }
    }

    private Image generarQrImage(String content, int width, int height) throws Exception {
        QRCodeWriter qrWriter = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.MARGIN, 1);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

        BitMatrix bitMatrix = qrWriter.encode(content, BarcodeFormat.QR_CODE, width, height, hints);

        ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(bitMatrix, "PNG", pngOut);

        Image qrImage = Image.getInstance(pngOut.toByteArray());
        qrImage.scaleToFit(width, height);
        return qrImage;
    }

    // ─── Helpers de datos ─────────────────────────────────────────────────────

    private boolean isInnominado(ClienteDTO cliente) {
        Integer tipoDoc = resolveTipoDocumentoReceptor(cliente);
        return tipoDoc != null && tipoDoc == 5;
    }

    private Integer resolveTipoDocumentoReceptor(ClienteDTO cliente) {
        if (cliente.getITipIDRec() != null) return cliente.getITipIDRec();
        if (cliente.getTipoDocumentoIdentidad() != null) return cliente.getTipoDocumentoIdentidad();
        if (cliente.getTipoDocumento() != null) return cliente.getTipoDocumento();
        return cliente.getDocumentoTipo();
    }

    private String resolveDocumentoCliente(ClienteDTO cliente) {
        if (cliente.getRuc() != null && !cliente.getRuc().isBlank()) return cliente.getRuc();
        if (cliente.getDNumIDRec() != null && !cliente.getDNumIDRec().isBlank()) return cliente.getDNumIDRec();
        if (cliente.getNumeroDocumentoIdentidad() != null && !cliente.getNumeroDocumentoIdentidad().isBlank()) return cliente.getNumeroDocumentoIdentidad();
        if (cliente.getNumeroDocumento() != null && !cliente.getNumeroDocumento().isBlank()) return cliente.getNumeroDocumento();
        if (cliente.getDocumentoNumero() != null && !cliente.getDocumentoNumero().isBlank()) return cliente.getDocumentoNumero();
        return null;
    }

    private String monedaDocumento(DataDTO data) {
        return nulo(data.getMoneda(), "PYG");
    }

    private boolean esGuaranies(DataDTO data) {
        return "PYG".equalsIgnoreCase(monedaDocumento(data));
    }

    /** DataDTO no trae tipo de cambio de la operación; se toma el primero informado en las entregas. */
    private BigDecimal tipoCambioDeEntregas(CondicionDTO condicion) {
        if (condicion == null || condicion.getEntregas() == null) return null;
        return condicion.getEntregas().stream()
                .map(EntregaDTO::getTipoCambio)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private String resolverUnidadMedida(int unidadMedida) {
        try {
            TcUniMed um = TcUniMed.getByVal((short) unidadMedida);
            if (um != null && um.getAbreviatura() != null) return um.getAbreviatura();
        } catch (Exception ignored) {
            // unidad no reconocida por la librería: se muestra vacía
        }
        return "";
    }

    private static DecimalFormatSymbols simbolosPy() {
        DecimalFormatSymbols s = new DecimalFormatSymbols(Locale.ROOT);
        s.setGroupingSeparator('.');
        s.setDecimalSeparator(',');
        return s;
    }

    /** 185301 → "185.301,00" */
    private String formatMonto(BigDecimal value) {
        DecimalFormat f = new DecimalFormat("#,##0.00", SIMBOLOS_PY);
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f.format(value != null ? value : BigDecimal.ZERO);
    }

    /** 185301 → "185.301" */
    private String formatEntero(BigDecimal value) {
        DecimalFormat f = new DecimalFormat("#,##0", SIMBOLOS_PY);
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f.format(value != null ? value : BigDecimal.ZERO);
    }

    /** 1 → "1", 2.5 → "2,5" */
    private String formatCantidad(BigDecimal value) {
        DecimalFormat f = new DecimalFormat("#,##0.####", SIMBOLOS_PY);
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f.format(value != null ? value : BigDecimal.ZERO);
    }

    /** "2025-03-01T10:11:00" → "01/03/2025 10:11:00"; "2025-03-01" → "01/03/2025". */
    private String formatearFecha(String fechaIso) {
        if (fechaIso == null || fechaIso.isBlank()) return "";
        try {
            String[] parts = fechaIso.split("T");
            String[] dateParts = parts[0].split("-");
            String fecha = dateParts[2] + "/" + dateParts[1] + "/" + dateParts[0];
            if (parts.length > 1) {
                fecha += " " + parts[1].substring(0, Math.min(8, parts[1].length()));
            }
            return fecha;
        } catch (Exception e) {
            return fechaIso;
        }
    }

    /** CDC en grupos de 4 dígitos, como lo imprime el formato de la SET. */
    private String formatearCdc(String cdc) {
        if (cdc == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cdc.length(); i++) {
            if (i > 0 && i % 4 == 0) sb.append(" ");
            sb.append(cdc.charAt(i));
        }
        return sb.toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String vacio(String value) {
        return value != null ? value : "";
    }

    private String nulo(String value) {
        return value != null ? value : "-";
    }

    private String nulo(String value, String defaultValue) {
        return value != null && !value.isBlank() ? value : defaultValue;
    }

    private String resolverMotivoEmision(DataDTO data) {
        if (data.getNotaCreditoDebito() == null) return "-";
        return switch (data.getNotaCreditoDebito().getMotivo()) {
            case 1 -> "Devolución y ajuste de precios";
            case 2 -> "Devolución";
            case 3 -> "Descuento";
            case 4 -> "Bonificación";
            case 5 -> "Crédito incobrable";
            case 6 -> "Recupero de costo";
            case 7 -> "Recupero de gasto";
            case 8 -> "Ajuste de precio";
            default -> String.valueOf(data.getNotaCreditoDebito().getMotivo());
        };
    }

    /** Nombre del tipo de DE en formato título ("Factura Electrónica"); se usa en mayúsculas donde corresponde. */
    private String resolverNombreDocumento(int tipo) {
        return switch (tipo) {
            case 1 -> "Factura Electrónica";
            case 4 -> "Autofactura Electrónica";
            case 5 -> "Nota de Crédito Electrónica";
            case 6 -> "Nota de Débito Electrónica";
            case 7 -> "Nota de Remisión Electrónica";
            default -> "Documento Electrónico";
        };
    }

    private String resolverTipoTransaccion(int tipo) {
        return switch (tipo) {
            case 1 -> "Venta de mercadería";
            case 2 -> "Prestación de servicios";
            case 3 -> "Mixto";
            case 4 -> "Venta de activo fijo";
            case 5 -> "Venta de divisas";
            case 6 -> "Compra de divisas";
            case 7 -> "Promoción o entrega de muestras";
            case 8 -> "Donación";
            case 9 -> "Anticipo";
            case 10 -> "Compra de productos";
            case 11 -> "Compra de servicios";
            case 12 -> "Venta de crédito fiscal";
            case 13 -> "Muestras médicas";
            default -> String.valueOf(tipo);
        };
    }

    // ─── Monto en letras ──────────────────────────────────────────────────────

    private static final String[] UNIDADES = {
        "", "UN", "DOS", "TRES", "CUATRO", "CINCO", "SEIS", "SIETE", "OCHO", "NUEVE",
        "DIEZ", "ONCE", "DOCE", "TRECE", "CATORCE", "QUINCE", "DIECISÉIS",
        "DIECISIETE", "DIECIOCHO", "DIECINUEVE"
    };
    private static final String[] DECENAS = {
        "", "DIEZ", "VEINTE", "TREINTA", "CUARENTA",
        "CINCUENTA", "SESENTA", "SETENTA", "OCHENTA", "NOVENTA"
    };
    private static final String[] VEINTI = {
        "VEINTE", "VEINTIÚN", "VEINTIDÓS", "VEINTITRÉS", "VEINTICUATRO",
        "VEINTICINCO", "VEINTISÉIS", "VEINTISIETE", "VEINTIOCHO", "VEINTINUEVE"
    };
    private static final String[] CENTENAS = {
        "", "CIEN", "DOSCIENTOS", "TRESCIENTOS", "CUATROCIENTOS", "QUINIENTOS",
        "SEISCIENTOS", "SETECIENTOS", "OCHOCIENTOS", "NOVECIENTOS"
    };

    /** "SON GS. (GUARANIES): CIENTO OCHENTA Y CINCO MIL ..." (o "SON USD: ..." en otra moneda). */
    private String montoEnLetras(BigDecimal amount, DataDTO data) {
        if (amount == null) return "";
        long monto = amount.setScale(0, RoundingMode.HALF_UP).longValue();
        String prefijo = esGuaranies(data) ? "SON GS. (GUARANIES): " : "SON " + monedaDocumento(data).toUpperCase(Locale.ROOT) + ": ";
        return prefijo + numeroEnLetrasFinal(monto);
    }

    /** "UN"/"VEINTIÚN" apocopados solo van delante de MIL/MILLÓN; al final del número son "UNO"/"VEINTIUNO". */
    private String numeroEnLetrasFinal(long n) {
        String letras = numeroEnLetras(n);
        if (letras.endsWith("VEINTIÚN")) return letras.substring(0, letras.length() - "VEINTIÚN".length()) + "VEINTIUNO";
        if (letras.equals("UN") || letras.endsWith(" UN")) return letras + "O";
        return letras;
    }

    private String numeroEnLetras(long n) {
        if (n == 0) return "CERO";
        StringBuilder sb = new StringBuilder();
        if (n >= 1_000_000_000L) {
            long b = n / 1_000_000_000L;
            sb.append(numeroEnLetras(b)).append(b == 1 ? " MIL MILLÓN" : " MIL MILLONES");
            n %= 1_000_000_000L;
            if (n > 0) sb.append(" ");
        }
        if (n >= 1_000_000L) {
            long m = n / 1_000_000L;
            sb.append(numeroEnLetras(m)).append(m == 1 ? " MILLÓN" : " MILLONES");
            n %= 1_000_000L;
            if (n > 0) sb.append(" ");
        }
        if (n >= 1_000L) {
            long t = n / 1_000L;
            sb.append(t == 1 ? "MIL" : numeroEnLetras(t) + " MIL");
            n %= 1_000L;
            if (n > 0) sb.append(" ");
        }
        if (n >= 100L) {
            int c = (int) (n / 100);
            sb.append(c == 1 && n % 100 > 0 ? "CIENTO" : CENTENAS[c]);
            n %= 100;
            if (n > 0) sb.append(" ");
        }
        if (n >= 20 && n < 30) {
            sb.append(VEINTI[(int) (n - 20)]);
            n = 0;
        } else if (n >= 30) {
            sb.append(DECENAS[(int) (n / 10)]);
            n %= 10;
            if (n > 0) sb.append(" Y ");
        }
        if (n > 0) {
            sb.append(UNIDADES[(int) n]);
        }
        return sb.toString().trim();
    }
}
