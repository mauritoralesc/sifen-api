package com.ratones.sifenwrapper.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.ratones.sifenwrapper.dto.request.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Servicio para generar el KUDE (Constancia de Documento Electrónico) en formato PDF.
 * Sigue el formato estándar definido por la SET (Subsecretaría de Estado de Tributación).
 */
@Slf4j
@Service
public class KudeService {

    // ─── Fuentes ──────────────────────────────────────────────────────────────
    private static final Font TITLE_FONT = new Font(Font.HELVETICA, 14, Font.BOLD, Color.BLACK);
    private static final Font HEADER_FONT = new Font(Font.HELVETICA, 10, Font.BOLD, Color.BLACK);
    private static final Font NORMAL_FONT = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.BLACK);
    private static final Font SMALL_FONT = new Font(Font.HELVETICA, 7, Font.NORMAL, Color.GRAY);
    private static final Font BOLD_FONT = new Font(Font.HELVETICA, 9, Font.BOLD, Color.BLACK);
    private static final Font TABLE_HEADER_FONT = new Font(Font.HELVETICA, 8, Font.BOLD, Color.WHITE);
    private static final Font TABLE_CELL_FONT = new Font(Font.HELVETICA, 8, Font.NORMAL, Color.BLACK);
    private static final Font TOTAL_FONT = new Font(Font.HELVETICA, 10, Font.BOLD, Color.BLACK);
    private static final Font FOOTER_FONT = new Font(Font.HELVETICA, 7, Font.ITALIC, Color.GRAY);

    private static final Color HEADER_BG = new Color(44, 62, 80);
    private static final Color ROW_ALT_BG = new Color(245, 245, 245);
    private static final Color BORDER_COLOR = new Color(200, 200, 200);

    private static final float QR_SIZE_PT = 28 * 72f / 25.4f; // 28 mm en puntos PDF
    private static final float LOGO_MAX_WIDTH_PT = 90f;
    private static final float LOGO_MAX_HEIGHT_PT = 60f;

    // ─── Generación del KUDE ──────────────────────────────────────────────────

    /**
     * Genera el KUDE como byte array PDF a partir de los datos del request.
     */
    public byte[] generarKude(KudeRequest request) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 28, 28, 24, 24);
            PdfWriter writer = PdfWriter.getInstance(document, baos);
            document.open();

            // Encabezado del documento
            addEncabezado(document, request);

            document.add(new Paragraph(" ", new Font(Font.HELVETICA, 3)));

            // Información de emisión (cliente + condición + checkboxes)
            addInfoEmision(document, request);

            document.add(new Paragraph(" ", new Font(Font.HELVETICA, 3)));

            // Tabla de items con totales integrados
            addTablaItems(document, request);

            document.add(new Paragraph(" ", new Font(Font.HELVETICA, 3)));

            // Forma de pago
            addCondicion(document, request);

            document.add(new Paragraph(" ", new Font(Font.HELVETICA, 3)));

            // QR y CDC
            addQrYCdc(document, writer, request);

            document.add(new Paragraph(" ", new Font(Font.HELVETICA, 3)));

            // Pie de página
            addPie(document);

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

    private void addEncabezado(Document doc, KudeRequest req) throws DocumentException {
        ParamsDTO params = req.getParams();
        DataDTO data = req.getData();

        String tipoDoc = resolverTipoDocumento(data.getTipoDocumento());
        String numero = String.format("%s-%s-%s", data.getEstablecimiento(), data.getPunto(), data.getNumero());

        // Tabla principal: 2 columnas — info empresa (izq) | caja doc (der)
        PdfPTable mainTable = new PdfPTable(2);
        mainTable.setWidthPercentage(100);
        mainTable.setWidths(new float[]{3, 2});

        // ── Columna izquierda: logo + info empresa ──
        PdfPCell leftCell = new PdfPCell();
        leftCell.setBorder(Rectangle.NO_BORDER);
        leftCell.setPadding(4);
        leftCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

        // Logo + razón social en sub-tabla
        PdfPTable logoTable = new PdfPTable(2);
        logoTable.setWidthPercentage(100);
        logoTable.setWidths(new float[]{1, 4});
        Image logo = buildLogoImage(params != null ? params.getLogoBase64() : null);
        PdfPCell lcell = new PdfPCell();
        lcell.setBorder(Rectangle.NO_BORDER);
        lcell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        if (logo != null) lcell.addElement(logo);
        logoTable.addCell(lcell);

        PdfPCell rcell = new PdfPCell();
        rcell.setBorder(Rectangle.NO_BORDER);
        rcell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        Paragraph razon = new Paragraph(params.getRazonSocial(), TITLE_FONT);
        rcell.addElement(razon);
        if (params.getNombreFantasia() != null && !params.getNombreFantasia().isBlank()) {
            rcell.addElement(new Paragraph(params.getNombreFantasia(), NORMAL_FONT));
        }
        logoTable.addCell(rcell);
        leftCell.addElement(logoTable);

        leftCell.addElement(new Paragraph("RUC: " + params.getRuc(), BOLD_FONT));

        if (params.getEstablecimientos() != null && !params.getEstablecimientos().isEmpty()) {
            EstablecimientoDTO est = params.getEstablecimientos().get(0);
            StringBuilder dir = new StringBuilder();
            if (est.getDireccion() != null) dir.append(est.getDireccion());
            if (est.getCiudadDescripcion() != null) dir.append(" - ").append(est.getCiudadDescripcion());
            if (est.getDepartamentoDescripcion() != null) dir.append(", ").append(est.getDepartamentoDescripcion());
            leftCell.addElement(new Paragraph(dir.toString(), SMALL_FONT));
            if (est.getTelefono() != null) leftCell.addElement(new Paragraph("Tel: " + est.getTelefono(), SMALL_FONT));
            if (est.getEmail() != null) leftCell.addElement(new Paragraph("Email: " + est.getEmail(), SMALL_FONT));
        }
        if (params.getActividadesEconomicas() != null && !params.getActividadesEconomicas().isEmpty()) {
            String act = params.getActividadesEconomicas().stream()
                    .map(ActividadEconomicaDTO::getDescripcion)
                    .collect(java.util.stream.Collectors.joining(" / "));
            leftCell.addElement(new Paragraph("Act. Económica: " + act, SMALL_FONT));
        }

        mainTable.addCell(leftCell);

        // ── Columna derecha: caja de documento con borde ──
        PdfPCell rightCell = new PdfPCell();
        rightCell.setBorder(Rectangle.BOX);
        rightCell.setBorderColor(BORDER_COLOR);
        rightCell.setBorderWidth(1.5f);
        rightCell.setPadding(6);
        rightCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

        Paragraph tipoDocPara = new Paragraph(tipoDoc, new Font(Font.HELVETICA, 11, Font.BOLD, Color.BLACK));
        tipoDocPara.setAlignment(Element.ALIGN_CENTER);
        rightCell.addElement(tipoDocPara);

        addDocBoxLine(rightCell, "RUC: ", params.getRuc());
        addDocBoxLine(rightCell, "Timbrado N°: ", params.getTimbradoNumero());
        addDocBoxLine(rightCell, "Fecha Inicio Vigencia: ", nulo(params.getTimbradoFecha()));
        addDocBoxLine(rightCell, "N° Documento: ", numero);

        mainTable.addCell(rightCell);
        doc.add(mainTable);
    }

    private void addDocBoxLine(PdfPCell cell, String label, String value) {
        Paragraph p = new Paragraph();
        p.setSpacingBefore(2);
        p.add(new Chunk(label, SMALL_FONT));
        p.add(new Chunk(value, new Font(Font.HELVETICA, 8, Font.BOLD, Color.BLACK)));
        cell.addElement(p);
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

    private void addInfoEmision(Document doc, KudeRequest req) throws DocumentException {
        DataDTO data = req.getData();
        ParamsDTO params = req.getParams();
        ClienteDTO cliente = data.getCliente();
        CondicionDTO condicion = data.getCondicion();

        boolean innominado = cliente == null || isInnominado(cliente);
        String nombreReceptor = innominado ? "Sin Nombre" : nulo(cliente != null ? cliente.getRazonSocial() : null);
        String docReceptor = innominado ? "Innominado" : nulo(resolveDocumentoCliente(cliente));

        // Condición de venta: checkboxes
        boolean esContado = condicion == null || condicion.getTipo() == 1;
        String chkContado = esContado ? "[X] Contado" : "[ ] Contado";
        String chkCredito = !esContado ? "[X] Crédito" : "[ ] Crédito";

        // Tabla principal de info emisión: 2 columnas
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{1, 1});
        table.setSpacingBefore(4);

        PdfPCell leftCell = new PdfPCell();
        leftCell.setBorder(Rectangle.BOX);
        leftCell.setBorderColor(BORDER_COLOR);
        leftCell.setPadding(5);

        // Condición checkboxes
        Paragraph condPara = new Paragraph();
        condPara.add(new Chunk(chkContado + "   " + chkCredito, BOLD_FONT));
        leftCell.addElement(condPara);

        leftCell.addElement(new Paragraph("Fecha Emisión: " + formatearFecha(data.getFecha()), NORMAL_FONT));
        leftCell.addElement(new Paragraph("Tipo Transacción: " + resolverTipoTransaccion(data.getTipoTransaccion()), SMALL_FONT));
        leftCell.addElement(new Paragraph("Moneda: " + nulo(data.getMoneda(), "PYG"), SMALL_FONT));

        if (data.getCajero() != null && !data.getCajero().isBlank()) {
            leftCell.addElement(new Paragraph("Cajero: " + data.getCajero(), SMALL_FONT));
        }

        if (condicion != null && condicion.getCredito() != null) {
            CreditoDTO cred = condicion.getCredito();
            leftCell.addElement(new Paragraph("Plazo: " + cred.getPlazo() + " días  Cuotas: " + cred.getCuotas(), SMALL_FONT));
        }

        table.addCell(leftCell);

        PdfPCell rightCell = new PdfPCell();
        rightCell.setBorder(Rectangle.BOX);
        rightCell.setBorderColor(BORDER_COLOR);
        rightCell.setPadding(5);

        rightCell.addElement(new Paragraph("Nombre/Razón Social: " + nombreReceptor, BOLD_FONT));
        rightCell.addElement(new Paragraph("RUC/CI: " + docReceptor, NORMAL_FONT));
        if (cliente != null) {
            if (cliente.getTelefono() != null) rightCell.addElement(new Paragraph("Tel: " + cliente.getTelefono(), SMALL_FONT));
            StringBuilder dir = new StringBuilder();
            if (cliente.getDireccion() != null) dir.append(cliente.getDireccion());
            if (cliente.getCiudadDescripcion() != null) dir.append(" - ").append(cliente.getCiudadDescripcion());
            if (dir.length() > 0) rightCell.addElement(new Paragraph("Dirección: " + dir, SMALL_FONT));
            if (cliente.getEmail() != null) rightCell.addElement(new Paragraph("Email: " + cliente.getEmail(), SMALL_FONT));
        }
        if (data.getSocio() != null && !data.getSocio().isBlank()) {
            rightCell.addElement(new Paragraph("Socio: " + data.getSocio(), SMALL_FONT));
        }

        table.addCell(rightCell);
        doc.add(table);
    }

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

    private void addTablaItems(Document doc, KudeRequest req) throws DocumentException {
        List<ItemDTO> items = req.getData().getItems();
        if (items == null || items.isEmpty()) return;

        // Tabla: Cod | Descripción | P. Unit. | Descuento | Exentas | 5% | 10%
        PdfPTable table = new PdfPTable(7);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{10, 32, 14, 12, 12, 10, 10});
        table.setSpacingBefore(6);

        String[] headers = {"Cód.", "Descripción", "P. Unitario", "Descuento", "Exentas", "5%", "10%"};
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, TABLE_HEADER_FONT));
            cell.setBackgroundColor(HEADER_BG);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setPadding(4);
            cell.setBorderColor(HEADER_BG);
            table.addCell(cell);
        }

        BigDecimal totalExenta = BigDecimal.ZERO;
        BigDecimal totalIva5 = BigDecimal.ZERO;
        BigDecimal totalIva10 = BigDecimal.ZERO;
        BigDecimal totalDescuento = BigDecimal.ZERO;

        boolean alternateRow = false;
        for (ItemDTO item : items) {
            Color bgColor = alternateRow ? ROW_ALT_BG : Color.WHITE;

            BigDecimal cantidad = item.getCantidad() != null ? item.getCantidad() : BigDecimal.ONE;
            BigDecimal precioUnit = item.getPrecioUnitario() != null ? item.getPrecioUnitario() : BigDecimal.ZERO;
            BigDecimal subtotal = cantidad.multiply(precioUnit);
            BigDecimal descuento = item.getDescuento() != null ? item.getDescuento() : BigDecimal.ZERO;
            BigDecimal subtotalNeto = subtotal.subtract(descuento);

            totalDescuento = totalDescuento.add(descuento);

            BigDecimal exenta = BigDecimal.ZERO;
            BigDecimal iva5 = BigDecimal.ZERO;
            BigDecimal iva10 = BigDecimal.ZERO;
            int ivaTipo = item.getIvaTipo();
            BigDecimal tasaIva = item.getIva() != null ? item.getIva() : BigDecimal.ZERO;

            if (ivaTipo == 3 || tasaIva.compareTo(BigDecimal.ZERO) == 0) {
                exenta = subtotalNeto;
                totalExenta = totalExenta.add(subtotalNeto);
            } else if (tasaIva.compareTo(BigDecimal.valueOf(5)) == 0) {
                iva5 = subtotalNeto;
                totalIva5 = totalIva5.add(subtotalNeto);
            } else {
                iva10 = subtotalNeto;
                totalIva10 = totalIva10.add(subtotalNeto);
            }

            // Descripción incluye cantidad si > 1
            String desc = nulo(item.getDescripcion());
            if (cantidad.compareTo(BigDecimal.ONE) > 0) {
                desc = formatNumber(cantidad) + " x " + desc;
            }

            addItemCell(table, nulo(item.getCodigo()), bgColor, Element.ALIGN_CENTER);
            addItemCell(table, desc, bgColor, Element.ALIGN_LEFT);
            addItemCell(table, formatCurrency(precioUnit), bgColor, Element.ALIGN_RIGHT);
            addItemCell(table, descuento.compareTo(BigDecimal.ZERO) > 0 ? formatCurrency(descuento) : "", bgColor, Element.ALIGN_RIGHT);
            addItemCell(table, exenta.compareTo(BigDecimal.ZERO) > 0 ? formatCurrency(exenta) : "", bgColor, Element.ALIGN_RIGHT);
            addItemCell(table, iva5.compareTo(BigDecimal.ZERO) > 0 ? formatCurrency(iva5) : "", bgColor, Element.ALIGN_RIGHT);
            addItemCell(table, iva10.compareTo(BigDecimal.ZERO) > 0 ? formatCurrency(iva10) : "", bgColor, Element.ALIGN_RIGHT);

            alternateRow = !alternateRow;
        }

        // ── Filas de totales integradas ──
        BigDecimal totalGeneral = totalExenta.add(totalIva5).add(totalIva10);

        BigDecimal liqIva5 = totalIva5.compareTo(BigDecimal.ZERO) > 0
                ? totalIva5.multiply(BigDecimal.valueOf(5)).divide(BigDecimal.valueOf(105), 0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal liqIva10 = totalIva10.compareTo(BigDecimal.ZERO) > 0
                ? totalIva10.multiply(BigDecimal.TEN).divide(BigDecimal.valueOf(110), 0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal totalIvaLiq = liqIva5.add(liqIva10);

        // Fila SUBTOTALES (colspan=4 cubre Cód+Desc+P.Unit+Desc, luego Exentas+5%+10% = 7 cols)
        addTotalsRow(table, "SUBTOTAL", formatCurrency(totalExenta), formatCurrency(totalIva5), formatCurrency(totalIva10));

        // Fila TOTAL (monto en letras ocupa columnas 2-5, monto ocupa col 6-7)
        PdfPCell totalLabelCell = new PdfPCell(new Phrase("TOTAL DE LA OPERACIÓN", new Font(Font.HELVETICA, 8, Font.BOLD, Color.WHITE)));
        totalLabelCell.setColspan(2);
        totalLabelCell.setBackgroundColor(HEADER_BG);
        totalLabelCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        totalLabelCell.setPadding(4);
        table.addCell(totalLabelCell);

        PdfPCell letrasCell = new PdfPCell(new Phrase(montoEnLetras(totalGeneral), new Font(Font.HELVETICA, 7, Font.ITALIC, Color.BLACK)));
        letrasCell.setColspan(4);
        letrasCell.setHorizontalAlignment(Element.ALIGN_LEFT);
        letrasCell.setPadding(4);
        letrasCell.setBorderColor(BORDER_COLOR);
        table.addCell(letrasCell);

        PdfPCell totalValCell = new PdfPCell(new Phrase(formatCurrency(totalGeneral), new Font(Font.HELVETICA, 9, Font.BOLD, Color.BLACK)));
        totalValCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        totalValCell.setPadding(4);
        totalValCell.setBorderColor(BORDER_COLOR);
        table.addCell(totalValCell);

        // Fila LIQUIDACIÓN IVA
        PdfPCell ivaLabelCell = new PdfPCell(new Phrase("LIQUIDACIÓN IVA", new Font(Font.HELVETICA, 7, Font.BOLD, Color.WHITE)));
        ivaLabelCell.setColspan(2);
        ivaLabelCell.setBackgroundColor(new Color(100, 120, 140));
        ivaLabelCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        ivaLabelCell.setPadding(3);
        table.addCell(ivaLabelCell);

        PdfPCell ivaDetailCell = new PdfPCell(new Phrase(
                "(5%) " + formatCurrency(liqIva5) + "   (10%) " + formatCurrency(liqIva10) + "   TOTAL IVA: " + formatCurrency(totalIvaLiq),
                SMALL_FONT));
        ivaDetailCell.setColspan(5);
        ivaDetailCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        ivaDetailCell.setPadding(3);
        ivaDetailCell.setBorderColor(BORDER_COLOR);
        table.addCell(ivaDetailCell);

        doc.add(table);
    }

    private void addTotalsRow(PdfPTable table, String label, String exenta, String iva5, String iva10) {
        Font f = new Font(Font.HELVETICA, 7, Font.BOLD, Color.BLACK);
        // colspan=4: Cód + Desc + P.Unit + Descuento — luego Exentas + 5% + 10% = 7 cols total
        PdfPCell lbl = new PdfPCell(new Phrase(label, f));
        lbl.setColspan(4);
        lbl.setHorizontalAlignment(Element.ALIGN_RIGHT);
        lbl.setPadding(3);
        lbl.setBorderColor(BORDER_COLOR);
        table.addCell(lbl);

        PdfPCell eCell = new PdfPCell(new Phrase(exenta, f));
        eCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        eCell.setPadding(3);
        eCell.setBorderColor(BORDER_COLOR);
        table.addCell(eCell);

        PdfPCell i5Cell = new PdfPCell(new Phrase(iva5, f));
        i5Cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        i5Cell.setPadding(3);
        i5Cell.setBorderColor(BORDER_COLOR);
        table.addCell(i5Cell);

        PdfPCell i10Cell = new PdfPCell(new Phrase(iva10, f));
        i10Cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        i10Cell.setPadding(3);
        i10Cell.setBorderColor(BORDER_COLOR);
        table.addCell(i10Cell);
    }

    private void addCondicion(Document doc, KudeRequest req) throws DocumentException {
        CondicionDTO condicion = req.getData().getCondicion();
        if (condicion == null || condicion.getEntregas() == null || condicion.getEntregas().isEmpty()) return;

        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(BORDER_COLOR);
        cell.setPadding(4);
        cell.addElement(new Paragraph("Forma de Pago", BOLD_FONT));

        for (EntregaDTO entrega : condicion.getEntregas()) {
            String tipoPago = resolverTipoPago(entrega.getTipo());
            cell.addElement(new Paragraph(
                    tipoPago + ": " + formatCurrency(entrega.getMonto()) + " " + nulo(entrega.getMoneda(), "PYG"),
                    SMALL_FONT));
        }

        table.addCell(cell);
        doc.add(table);
    }

    private void addQrYCdc(Document doc, PdfWriter writer, KudeRequest req) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{1, 2});
        table.setSpacingBefore(6);

        // Columna izquierda: QR
        PdfPCell qrCell = new PdfPCell();
        qrCell.setBorder(Rectangle.NO_BORDER);
        qrCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        qrCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        qrCell.setPadding(4);

        if (req.getQrUrl() != null && !req.getQrUrl().isBlank()) {
            try {
                Image qrImage = generarQrImage(req.getQrUrl(), 300, 300);
                qrImage.scaleAbsolute(QR_SIZE_PT, QR_SIZE_PT);
                qrImage.setAlignment(Image.ALIGN_CENTER);
                qrCell.addElement(qrImage);
            } catch (Exception e) {
                log.warn("No se pudo generar QR: {}", e.getMessage());
                qrCell.addElement(new Paragraph("QR no disponible", SMALL_FONT));
            }
        } else {
            qrCell.addElement(new Paragraph("QR no disponible", SMALL_FONT));
        }

        table.addCell(qrCell);

        // Columna derecha: texto validación + CDC
        PdfPCell infoCell = new PdfPCell();
        infoCell.setBorder(Rectangle.NO_BORDER);
        infoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        infoCell.setPadding(4);

        Paragraph valText = new Paragraph(
                "Consulte la validez de este documento en:\nhttps://ekuatia.set.gov.py/consultas",
                SMALL_FONT);
        infoCell.addElement(valText);

        if (req.getCdc() != null) {
            infoCell.addElement(new Paragraph("CDC:", BOLD_FONT));
            String cdcFormateado = formatearCdc(req.getCdc());
            infoCell.addElement(new Paragraph(cdcFormateado, new Font(Font.COURIER, 8, Font.BOLD, HEADER_BG)));
        }

        if (req.getEstado() != null) {
            Paragraph estadoPara = new Paragraph();
            estadoPara.setSpacingBefore(4);
            estadoPara.add(new Chunk("Estado: ", BOLD_FONT));
            Color estadoColor = "APROBADO".equalsIgnoreCase(req.getEstado())
                    ? new Color(39, 174, 96)
                    : new Color(231, 76, 60);
            estadoPara.add(new Chunk(req.getEstado(), new Font(Font.HELVETICA, 9, Font.BOLD, estadoColor)));
            if (req.getCodigoEstado() != null) {
                estadoPara.add(new Chunk(" (" + req.getCodigoEstado() + ")", NORMAL_FONT));
            }
            infoCell.addElement(estadoPara);
        }

        if (req.getQrUrl() != null) {
            infoCell.addElement(new Paragraph(req.getQrUrl(), SMALL_FONT));
        }

        table.addCell(infoCell);
        doc.add(table);
    }

    private void addPie(Document doc) throws DocumentException {
        addLineSeparator(doc, BORDER_COLOR, 0.5f);

        Paragraph titulo = new Paragraph(
                "Información de interés del facturador electrónico emisor",
                new Font(Font.HELVETICA, 7, Font.BOLD, Color.BLACK));
        titulo.setSpacingBefore(4);
        doc.add(titulo);

        Paragraph info = new Paragraph(
                "Este DTE fue generado por medios informáticos autorizados. Para verificar su autenticidad " +
                "tiene un plazo de 72 horas hábiles a partir de su emisión. " +
                "Consulte en https://ekuatia.set.gov.py/consultas",
                FOOTER_FONT);
        doc.add(info);

        Paragraph pagina = new Paragraph("Página 1 de 1", FOOTER_FONT);
        pagina.setAlignment(Element.ALIGN_RIGHT);
        doc.add(pagina);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void addLineSeparator(Document doc, Color color, float width) throws DocumentException {
        PdfPTable lineTable = new PdfPTable(1);
        lineTable.setWidthPercentage(100);
        PdfPCell lineCell = new PdfPCell();
        lineCell.setBorder(Rectangle.BOTTOM);
        lineCell.setBorderColor(color);
        lineCell.setBorderWidth(width);
        lineCell.setFixedHeight(1);
        lineTable.addCell(lineCell);
        doc.add(lineTable);
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

    private PdfPCell createInfoCell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingBottom(2);
        return cell;
    }

    private void addItemCell(PdfPTable table, String text, Color bgColor, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, TABLE_CELL_FONT));
        cell.setBackgroundColor(bgColor);
        cell.setHorizontalAlignment(alignment);
        cell.setPadding(4);
        cell.setBorderColor(BORDER_COLOR);
        table.addCell(cell);
    }

    private String formatCurrency(BigDecimal value) {
        if (value == null) return "0";
        return String.format("%,.0f", value).replace(',', '.');
    }

    private String formatNumber(BigDecimal value) {
        if (value == null) return "0";
        return value.stripTrailingZeros().toPlainString();
    }

    private String formatearFecha(String fechaIso) {
        if (fechaIso == null) return "-";
        try {
            // De "2025-03-01T10:11:00" a "01/03/2025 10:11"
            String[] parts = fechaIso.split("T");
            String[] dateParts = parts[0].split("-");
            String time = parts.length > 1 ? parts[1].substring(0, 5) : "";
            return dateParts[2] + "/" + dateParts[1] + "/" + dateParts[0] + " " + time;
        } catch (Exception e) {
            return fechaIso;
        }
    }

    private String formatearCdc(String cdc) {
        if (cdc == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cdc.length(); i++) {
            if (i > 0 && i % 8 == 0) sb.append(" ");
            sb.append(cdc.charAt(i));
        }
        return sb.toString();
    }

    private String nulo(String value) {
        return value != null ? value : "-";
    }

    private String nulo(String value, String defaultValue) {
        return value != null && !value.isBlank() ? value : defaultValue;
    }

    private String resolverTipoDocumento(int tipo) {
        return switch (tipo) {
            case 1 -> "FACTURA ELECTRÓNICA";
            case 4 -> "AUTO-FACTURA ELECTRÓNICA";
            case 5 -> "NOTA DE CRÉDITO ELECTRÓNICA";
            case 6 -> "NOTA DE DÉBITO ELECTRÓNICA";
            case 7 -> "NOTA DE REMISIÓN ELECTRÓNICA";
            default -> "DOCUMENTO ELECTRÓNICO";
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

    private String resolverTipoPago(int tipo) {
        return switch (tipo) {
            case 1 -> "Efectivo";
            case 2 -> "Cheque";
            case 3 -> "Tarjeta de crédito";
            case 4 -> "Tarjeta de débito";
            case 5 -> "Transferencia";
            case 6 -> "Giro";
            case 7 -> "Billetera electrónica";
            case 8 -> "Tarjeta empresarial";
            case 9 -> "Vale";
            case 10 -> "Retención";
            case 11 -> "Pago por anticipo";
            case 12 -> "Valor fiscal";
            case 13 -> "Valor comercial";
            case 14 -> "Compensación";
            case 15 -> "Permuta";
            case 16 -> "Pago bancario";
            case 17 -> "Pago Móvil";
            case 18 -> "Donación";
            case 19 -> "Promoción";
            case 20 -> "Muestra médica";
            case 21 -> "Cortesía";
            default -> "Tipo " + tipo;
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
    private static final String[] CENTENAS = {
        "", "CIEN", "DOSCIENTOS", "TRESCIENTOS", "CUATROCIENTOS", "QUINIENTOS",
        "SEISCIENTOS", "SETECIENTOS", "OCHOCIENTOS", "NOVECIENTOS"
    };

    private String montoEnLetras(BigDecimal amount) {
        if (amount == null) return "";
        long monto = amount.setScale(0, RoundingMode.HALF_UP).longValue();
        return "Son: " + numeroEnLetras(monto) + " GUARANÍES";
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
        if (n >= 20) {
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
