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

    private static final float QR_SIZE_PT = 40 * 72f / 25.4f; // 40 mm en puntos PDF

    // ─── Generación del KUDE ──────────────────────────────────────────────────

    /**
     * Genera el KUDE como byte array PDF a partir de los datos del request.
     */
    public byte[] generarKude(KudeRequest request) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter writer = PdfWriter.getInstance(document, baos);
            document.open();

            // Encabezado del documento
            addEncabezado(document, request);

            document.add(new Paragraph(" ", SMALL_FONT));

            // Información del documento
            addInfoDocumento(document, request);

            document.add(new Paragraph(" ", SMALL_FONT));

            // Información del cliente
            addInfoCliente(document, request);

            document.add(new Paragraph(" ", SMALL_FONT));

            // Tabla de items
            addTablaItems(document, request);

            document.add(new Paragraph(" ", SMALL_FONT));

            // Totales
            addTotales(document, request);

            document.add(new Paragraph(" ", SMALL_FONT));

            // Condición de venta
            addCondicion(document, request);

            document.add(new Paragraph(" ", SMALL_FONT));

            // QR y CDC
            addQrYCdc(document, writer, request);

            document.add(new Paragraph(" ", SMALL_FONT));

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

        // Tipo de documento
        String tipoDoc = resolverTipoDocumento(req.getData().getTipoDocumento());

        PdfPTable headerTable = new PdfPTable(1);
        headerTable.setWidthPercentage(100);

        // Nombre de la empresa
        PdfPCell empresaCell = new PdfPCell();
        empresaCell.setBorder(Rectangle.NO_BORDER);
        empresaCell.setPaddingBottom(4);

        Paragraph empresa = new Paragraph();
        empresa.add(new Chunk(params.getRazonSocial(), TITLE_FONT));
        if (params.getNombreFantasia() != null && !params.getNombreFantasia().isBlank()) {
            empresa.add(new Chunk("\n" + params.getNombreFantasia(), NORMAL_FONT));
        }
        empresaCell.addElement(empresa);
        headerTable.addCell(empresaCell);

        // RUC
        PdfPCell rucCell = createInfoCell("RUC: " + params.getRuc(), BOLD_FONT);
        headerTable.addCell(rucCell);

        // Dirección del establecimiento
        if (params.getEstablecimientos() != null && !params.getEstablecimientos().isEmpty()) {
            EstablecimientoDTO est = params.getEstablecimientos().get(0);
            StringBuilder dir = new StringBuilder();
            if (est.getDireccion() != null) dir.append(est.getDireccion());
            if (est.getCiudadDescripcion() != null) dir.append(" - ").append(est.getCiudadDescripcion());
            if (est.getDepartamentoDescripcion() != null) dir.append(", ").append(est.getDepartamentoDescripcion());
            headerTable.addCell(createInfoCell(dir.toString(), NORMAL_FONT));

            if (est.getTelefono() != null) {
                headerTable.addCell(createInfoCell("Tel: " + est.getTelefono(), NORMAL_FONT));
            }
            if (est.getEmail() != null) {
                headerTable.addCell(createInfoCell("Email: " + est.getEmail(), NORMAL_FONT));
            }
        }

        // Actividad económica
        if (params.getActividadesEconomicas() != null && !params.getActividadesEconomicas().isEmpty()) {
            String act = params.getActividadesEconomicas().get(0).getDescripcion();
            headerTable.addCell(createInfoCell("Act. Económica: " + act, SMALL_FONT));
        }

        doc.add(headerTable);

        // Línea separadora
        doc.add(new Paragraph(" ", new Font(Font.HELVETICA, 2)));
        addLineSeparator(doc, HEADER_BG, 1.5f);

        // Tipo de documento grande
        Paragraph tipoPara = new Paragraph(tipoDoc, TITLE_FONT);
        tipoPara.setAlignment(Element.ALIGN_CENTER);
        tipoPara.setSpacingBefore(8);
        tipoPara.setSpacingAfter(4);
        doc.add(tipoPara);
    }

    private void addInfoDocumento(Document doc, KudeRequest req) throws DocumentException {
        DataDTO data = req.getData();
        ParamsDTO params = req.getParams();

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{1, 1});

        // Columna izquierda
        PdfPCell leftCell = new PdfPCell();
        leftCell.setBorder(Rectangle.BOX);
        leftCell.setBorderColor(BORDER_COLOR);
        leftCell.setPadding(8);

        String numero = String.format("%s-%s-%s",
                data.getEstablecimiento(), data.getPunto(), data.getNumero());
        leftCell.addElement(new Paragraph("Nro. Documento: " + numero, BOLD_FONT));
        leftCell.addElement(new Paragraph("Timbrado: " + params.getTimbradoNumero(), NORMAL_FONT));
        leftCell.addElement(new Paragraph("Inicio Vigencia: " + nulo(params.getTimbradoFecha()), NORMAL_FONT));

        table.addCell(leftCell);

        // Columna derecha
        PdfPCell rightCell = new PdfPCell();
        rightCell.setBorder(Rectangle.BOX);
        rightCell.setBorderColor(BORDER_COLOR);
        rightCell.setPadding(8);

        rightCell.addElement(new Paragraph("Fecha Emisión: " + formatearFecha(data.getFecha()), BOLD_FONT));
        rightCell.addElement(new Paragraph("Tipo Transacción: " + resolverTipoTransaccion(data.getTipoTransaccion()), NORMAL_FONT));
        rightCell.addElement(new Paragraph("Moneda: " + nulo(data.getMoneda(), "PYG"), NORMAL_FONT));

        table.addCell(rightCell);

        doc.add(table);
    }

    private void addInfoCliente(Document doc, KudeRequest req) throws DocumentException {
        ClienteDTO cliente = req.getData().getCliente();
        if (cliente == null) return;

        boolean innominado = isInnominado(cliente);
        String nombreReceptor = innominado ? "Sin Nombre" : nulo(cliente.getRazonSocial());
        String docReceptor = innominado ? "Innominado" : nulo(resolveDocumentoCliente(cliente));

        Paragraph titulo = new Paragraph("DATOS DEL RECEPTOR", HEADER_FONT);
        titulo.setSpacingAfter(4);
        doc.add(titulo);

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{1, 1});

        PdfPCell leftCell = new PdfPCell();
        leftCell.setBorder(Rectangle.BOX);
        leftCell.setBorderColor(BORDER_COLOR);
        leftCell.setPadding(8);

        leftCell.addElement(new Paragraph("Nombre/Razón Social: " + nombreReceptor, BOLD_FONT));
        leftCell.addElement(new Paragraph("RUC/CI: " + docReceptor, NORMAL_FONT));
        if (cliente.getTelefono() != null) {
            leftCell.addElement(new Paragraph("Teléfono: " + cliente.getTelefono(), NORMAL_FONT));
        }

        table.addCell(leftCell);

        PdfPCell rightCell = new PdfPCell();
        rightCell.setBorder(Rectangle.BOX);
        rightCell.setBorderColor(BORDER_COLOR);
        rightCell.setPadding(8);

        StringBuilder dir = new StringBuilder();
        if (cliente.getDireccion() != null) dir.append(cliente.getDireccion());
        if (cliente.getCiudadDescripcion() != null) dir.append(" - ").append(cliente.getCiudadDescripcion());
        rightCell.addElement(new Paragraph("Dirección: " + dir, NORMAL_FONT));
        if (cliente.getEmail() != null) {
            rightCell.addElement(new Paragraph("Email: " + cliente.getEmail(), NORMAL_FONT));
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

        Paragraph titulo = new Paragraph("DETALLE DE LA OPERACIÓN", HEADER_FONT);
        titulo.setSpacingAfter(4);
        doc.add(titulo);

        // Tabla: Código | Descripción | Cant. | P. Unit. | Exenta | IVA 5% | IVA 10%
        PdfPTable table = new PdfPTable(7);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{10, 30, 8, 14, 13, 13, 13});

        // Headers
        String[] headers = {"Código", "Descripción", "Cant.", "P. Unit.", "Exenta", "IVA 5%", "IVA 10%"};
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, TABLE_HEADER_FONT));
            cell.setBackgroundColor(HEADER_BG);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setPadding(5);
            cell.setBorderColor(HEADER_BG);
            table.addCell(cell);
        }

        // Filas de items
        boolean alternateRow = false;
        for (ItemDTO item : items) {
            Color bgColor = alternateRow ? ROW_ALT_BG : Color.WHITE;

            BigDecimal cantidad = item.getCantidad() != null ? item.getCantidad() : BigDecimal.ONE;
            BigDecimal precioUnit = item.getPrecioUnitario() != null ? item.getPrecioUnitario() : BigDecimal.ZERO;
            BigDecimal subtotal = cantidad.multiply(precioUnit);

            // Clasificar subtotal según tasa IVA
            BigDecimal exenta = BigDecimal.ZERO;
            BigDecimal iva5 = BigDecimal.ZERO;
            BigDecimal iva10 = BigDecimal.ZERO;

            int ivaTipo = item.getIvaTipo();
            BigDecimal tasaIva = item.getIva() != null ? item.getIva() : BigDecimal.ZERO;

            if (ivaTipo == 3 || tasaIva.compareTo(BigDecimal.ZERO) == 0) {
                // Exenta
                exenta = subtotal;
            } else if (tasaIva.compareTo(BigDecimal.valueOf(5)) == 0) {
                iva5 = subtotal;
            } else if (tasaIva.compareTo(BigDecimal.TEN) == 0) {
                iva10 = subtotal;
            } else {
                // Gravada pero tasa no estándar, poner en IVA 10% por defecto
                iva10 = subtotal;
            }

            addItemCell(table, nulo(item.getCodigo()), bgColor, Element.ALIGN_CENTER);
            addItemCell(table, nulo(item.getDescripcion()), bgColor, Element.ALIGN_LEFT);
            addItemCell(table, formatNumber(cantidad), bgColor, Element.ALIGN_CENTER);
            addItemCell(table, formatCurrency(precioUnit), bgColor, Element.ALIGN_RIGHT);
            addItemCell(table, exenta.compareTo(BigDecimal.ZERO) > 0 ? formatCurrency(exenta) : "", bgColor, Element.ALIGN_RIGHT);
            addItemCell(table, iva5.compareTo(BigDecimal.ZERO) > 0 ? formatCurrency(iva5) : "", bgColor, Element.ALIGN_RIGHT);
            addItemCell(table, iva10.compareTo(BigDecimal.ZERO) > 0 ? formatCurrency(iva10) : "", bgColor, Element.ALIGN_RIGHT);

            alternateRow = !alternateRow;
        }

        doc.add(table);
    }

    private void addTotales(Document doc, KudeRequest req) throws DocumentException {
        List<ItemDTO> items = req.getData().getItems();
        if (items == null) return;

        // Calcular totales
        BigDecimal totalExenta = BigDecimal.ZERO;
        BigDecimal totalIva5 = BigDecimal.ZERO;
        BigDecimal totalIva10 = BigDecimal.ZERO;
        BigDecimal totalGeneral = BigDecimal.ZERO;

        for (ItemDTO item : items) {
            BigDecimal cantidad = item.getCantidad() != null ? item.getCantidad() : BigDecimal.ONE;
            BigDecimal precioUnit = item.getPrecioUnitario() != null ? item.getPrecioUnitario() : BigDecimal.ZERO;
            BigDecimal subtotal = cantidad.multiply(precioUnit);

            BigDecimal descuento = item.getDescuento() != null ? item.getDescuento() : BigDecimal.ZERO;
            subtotal = subtotal.subtract(descuento);

            totalGeneral = totalGeneral.add(subtotal);

            int ivaTipo = item.getIvaTipo();
            BigDecimal tasaIva = item.getIva() != null ? item.getIva() : BigDecimal.ZERO;

            if (ivaTipo == 3 || tasaIva.compareTo(BigDecimal.ZERO) == 0) {
                totalExenta = totalExenta.add(subtotal);
            } else if (tasaIva.compareTo(BigDecimal.valueOf(5)) == 0) {
                totalIva5 = totalIva5.add(subtotal);
            } else {
                totalIva10 = totalIva10.add(subtotal);
            }
        }

        // Calcular liquidación IVA
        BigDecimal liqIva5 = totalIva5.compareTo(BigDecimal.ZERO) > 0
                ? totalIva5.multiply(BigDecimal.valueOf(5)).divide(BigDecimal.valueOf(105), 0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal liqIva10 = totalIva10.compareTo(BigDecimal.ZERO) > 0
                ? totalIva10.multiply(BigDecimal.TEN).divide(BigDecimal.valueOf(110), 0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal totalIva = liqIva5.add(liqIva10);

        // Tabla de totales (alineada a la derecha)
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(50);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.setWidths(new float[]{1, 1});

        addTotalRow(table, "Subtotal Exenta:", formatCurrency(totalExenta));
        addTotalRow(table, "Subtotal IVA 5%:", formatCurrency(totalIva5));
        addTotalRow(table, "Subtotal IVA 10%:", formatCurrency(totalIva10));

        // Separador
        PdfPCell sepCell = new PdfPCell(new Phrase("", NORMAL_FONT));
        sepCell.setColspan(2);
        sepCell.setBorder(Rectangle.BOTTOM);
        sepCell.setBorderColor(BORDER_COLOR);
        sepCell.setPadding(2);
        table.addCell(sepCell);

        addTotalRowBold(table, "TOTAL:", formatCurrency(totalGeneral));

        // Liquidación IVA
        PdfPCell ivaHeaderCell = new PdfPCell(new Phrase("", NORMAL_FONT));
        ivaHeaderCell.setColspan(2);
        ivaHeaderCell.setBorder(Rectangle.NO_BORDER);
        ivaHeaderCell.setPadding(4);
        table.addCell(ivaHeaderCell);

        addTotalRow(table, "Liq. IVA 5%:", formatCurrency(liqIva5));
        addTotalRow(table, "Liq. IVA 10%:", formatCurrency(liqIva10));
        addTotalRowBold(table, "Total IVA:", formatCurrency(totalIva));

        doc.add(table);
    }

    private void addCondicion(Document doc, KudeRequest req) throws DocumentException {
        CondicionDTO condicion = req.getData().getCondicion();
        if (condicion == null) return;

        String tipoCondicion = condicion.getTipo() == 1 ? "Contado" : "Crédito";

        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(BORDER_COLOR);
        cell.setPadding(6);
        cell.addElement(new Paragraph("Condición de Venta: " + tipoCondicion, BOLD_FONT));

        if (condicion.getEntregas() != null && !condicion.getEntregas().isEmpty()) {
            for (EntregaDTO entrega : condicion.getEntregas()) {
                String tipoPago = resolverTipoPago(entrega.getTipo());
                cell.addElement(new Paragraph(
                        tipoPago + ": " + formatCurrency(entrega.getMonto())
                                + " " + nulo(entrega.getMoneda(), "PYG"),
                        NORMAL_FONT));
            }
        }

        if (condicion.getCredito() != null) {
            CreditoDTO credito = condicion.getCredito();
            cell.addElement(new Paragraph("Plazo: " + credito.getPlazo() + " días | Cuotas: " + credito.getCuotas(), NORMAL_FONT));
        }

        table.addCell(cell);
        doc.add(table);
    }

    private void addQrYCdc(Document doc, PdfWriter writer, KudeRequest req) throws DocumentException {
        // --- QR centrado a 40 mm ---
        PdfPTable qrTable = new PdfPTable(1);
        qrTable.setWidthPercentage(100);
        qrTable.setSpacingBefore(4);

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

        qrTable.addCell(qrCell);
        doc.add(qrTable);

        // --- CDC e info debajo del QR ---
        PdfPTable infoTable = new PdfPTable(1);
        infoTable.setWidthPercentage(100);

        PdfPCell cdcCell = new PdfPCell();
        cdcCell.setBorder(Rectangle.NO_BORDER);
        cdcCell.setPadding(4);

        if (req.getCdc() != null) {
            Paragraph cdcLabel = new Paragraph("CDC (Código de Control):", BOLD_FONT);
            cdcLabel.setAlignment(Element.ALIGN_CENTER);
            cdcCell.addElement(cdcLabel);
            String cdcFormateado = formatearCdc(req.getCdc());
            Paragraph cdcPara = new Paragraph(cdcFormateado, new Font(Font.COURIER, 10, Font.BOLD, HEADER_BG));
            cdcPara.setAlignment(Element.ALIGN_CENTER);
            cdcCell.addElement(cdcPara);
        }

        if (req.getEstado() != null) {
            Paragraph estadoPara = new Paragraph();
            estadoPara.setSpacingBefore(6);
            estadoPara.setAlignment(Element.ALIGN_CENTER);
            estadoPara.add(new Chunk("Estado: ", BOLD_FONT));

            Color estadoColor = "APROBADO".equalsIgnoreCase(req.getEstado())
                    ? new Color(39, 174, 96)
                    : new Color(231, 76, 60);
            estadoPara.add(new Chunk(req.getEstado(), new Font(Font.HELVETICA, 10, Font.BOLD, estadoColor)));

            if (req.getCodigoEstado() != null) {
                estadoPara.add(new Chunk(" (" + req.getCodigoEstado() + ")", NORMAL_FONT));
            }
            cdcCell.addElement(estadoPara);
        }

        if (req.getQrUrl() != null) {
            Paragraph qrUrlPara = new Paragraph(req.getQrUrl(), SMALL_FONT);
            qrUrlPara.setSpacingBefore(4);
            qrUrlPara.setAlignment(Element.ALIGN_CENTER);
            cdcCell.addElement(qrUrlPara);
        }

        infoTable.addCell(cdcCell);
        doc.add(infoTable);
    }

    private void addPie(Document doc) throws DocumentException {
        addLineSeparator(doc, BORDER_COLOR, 0.5f);

        Paragraph pie = new Paragraph(
                "Este documento es una representación gráfica de un Documento Tributario Electrónico (DTE). " +
                        "Verifique su validez en https://ekuatia.set.gov.py/consultas",
                FOOTER_FONT);
        pie.setAlignment(Element.ALIGN_CENTER);
        pie.setSpacingBefore(6);
        doc.add(pie);

        Paragraph generado = new Paragraph(
                "Generado por sifen-wrapper v1.0.0",
                FOOTER_FONT);
        generado.setAlignment(Element.ALIGN_CENTER);
        doc.add(generado);
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

    private void addTotalRow(PdfPTable table, String label, String value) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, NORMAL_FONT));
        labelCell.setBorder(Rectangle.NO_BORDER);
        labelCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        labelCell.setPadding(3);
        table.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, NORMAL_FONT));
        valueCell.setBorder(Rectangle.NO_BORDER);
        valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        valueCell.setPadding(3);
        table.addCell(valueCell);
    }

    private void addTotalRowBold(PdfPTable table, String label, String value) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, TOTAL_FONT));
        labelCell.setBorder(Rectangle.NO_BORDER);
        labelCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        labelCell.setPadding(3);
        table.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, TOTAL_FONT));
        valueCell.setBorder(Rectangle.NO_BORDER);
        valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        valueCell.setPadding(3);
        table.addCell(valueCell);
    }

    private String formatCurrency(BigDecimal value) {
        if (value == null) return "0";
        return String.format("%,.0f", value);
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
}
