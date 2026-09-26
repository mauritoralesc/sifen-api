# KUDE — Guía de Integración para ERP

El KUDE (Constancia de Documento Electrónico) es la representación gráfica en PDF de un Documento Tributario Electrónico (DTE) emitido por SIFEN. Este documento describe los campos que el ERP puede enviar para personalizar la generación del KUDE.

---

## Estructura del request

```json
{
  "params": { ... },
  "data": { ... },
  "cdc": "...",
  "qrUrl": "...",
  "estado": "APROBADO",
  "codigoEstado": "0422"
}
```

---

## Campos del encabezado (`params`)

| Campo                    | Tipo     | Descripción                                                   |
|--------------------------|----------|---------------------------------------------------------------|
| `ruc`                    | string   | RUC del emisor (ej. `80012345-1`)                             |
| `razonSocial`            | string   | Razón social del emisor                                       |
| `nombreFantasia`         | string   | Nombre de fantasía (opcional)                                 |
| `logoBase64`             | string   | Logo en Base64 (PNG o JPG, puede incluir prefijo `data:...`)  |
| `timbradoNumero`         | string   | Número de timbrado                                            |
| `timbradoFecha`          | string   | Fecha de inicio de vigencia del timbrado (ej. `2024-01-01`)   |
| `actividadesEconomicas`  | array    | Lista de actividades (`[{"descripcion": "..."}]`)             |
| `establecimientos`       | array    | Ver sección siguiente                                         |

### Establecimiento (`establecimientos[0]`)

| Campo                    | Tipo     | Descripción                  |
|--------------------------|----------|------------------------------|
| `direccion`              | string   | Dirección física             |
| `ciudadDescripcion`      | string   | Ciudad                       |
| `departamentoDescripcion`| string   | Departamento                 |
| `telefono`               | string   | Teléfono de contacto         |
| `email`                  | string   | Email de contacto            |

---

## Campos del documento (`data`)

| Campo               | Tipo     | Requerido | Descripción                                          |
|---------------------|----------|-----------|------------------------------------------------------|
| `tipoDocumento`     | int      | Sí        | 1=Factura, 4=Auto-factura, 5=NC, 6=ND, 7=Remisión   |
| `establecimiento`   | string   | Sí        | Número de establecimiento (ej. `001`)                |
| `punto`             | string   | Sí        | Punto de expedición (ej. `001`)                      |
| `numero`            | string   | Sí        | Número de documento (ej. `0000001`)                  |
| `fecha`             | string   | Sí        | Fecha/hora de emisión en ISO 8601 (`2025-03-01T10:00:00`) |
| `tipoTransaccion`   | int      | Sí        | Tipo de transacción (ver tabla)                      |
| `moneda`            | string   | No        | Moneda. Default `PYG`                                |
| `cajero`            | string   | No        | Nombre del cajero (aparece en el KUDE si se envía)   |
| `socio`             | string   | No        | Número o nombre del socio (aparece en el KUDE)       |
| `observacion`       | string   | No        | Nota libre impresa al pie, debajo de la caja del QR (ej. leyendas legales del emisor). No se envía a SIFEN |
| `cliente`           | object   | Sí        | Datos del receptor (ver sección siguiente)           |
| `condicion`         | object   | Sí        | Condición de venta y pagos                           |
| `items`             | array    | Sí        | Ítems del documento                                  |

### Tipos de transacción

| Valor | Descripción              |
|-------|--------------------------|
| 1     | Venta de mercadería      |
| 2     | Prestación de servicios  |
| 3     | Mixto                    |
| 4     | Venta de activo fijo     |
| 9     | Anticipo                 |

---

## Campos del receptor (`data.cliente`)

| Campo              | Tipo   | Descripción                                                |
|--------------------|--------|------------------------------------------------------------|
| `razonSocial`      | string | Nombre o razón social                                      |
| `ruc`              | string | RUC del receptor                                           |
| `iTipIDRec`        | int    | Tipo de documento: 1=RUC, 2=CI, 3=Pasaporte, 5=Innominado |
| `dNumIDRec`        | string | Número de documento de identidad                           |
| `telefono`         | string | Teléfono (opcional)                                        |
| `email`            | string | Email (opcional)                                           |
| `direccion`        | string | Dirección (opcional)                                       |
| `ciudadDescripcion`| string | Ciudad (opcional)                                          |

> Si `iTipIDRec` es `5`, el receptor se trata como **innominado** y el KUDE mostrará "Sin Nombre / Innominado".

---

## Condición de venta (`data.condicion`)

| Campo      | Tipo  | Descripción                             |
|------------|-------|-----------------------------------------|
| `tipo`     | int   | 1=Contado, 2=Crédito                    |
| `entregas` | array | Lista de medios de pago (ver sección)   |
| `credito`  | object| Datos del crédito (si tipo=2)           |

El KUDE marca con una `X` la casilla "Contado" o "Crédito" según el valor de `tipo`. Para crédito, la línea "Cuotas" muestra `cuotas` y `plazo`.

### Entregas (formas de pago)

Ver [docs/metodos-de-pago.md](./metodos-de-pago.md) para la referencia completa.

### Crédito (`data.condicion.credito`)

| Campo    | Tipo | Descripción            |
|----------|------|------------------------|
| `plazo`  | int  | Plazo en días          |
| `cuotas` | int  | Cantidad de cuotas     |

---

## Ítems (`data.items`)

| Campo            | Tipo       | Descripción                              |
|------------------|------------|------------------------------------------|
| `codigo`         | string     | Código del producto/servicio             |
| `descripcion`    | string     | Descripción (requerido)                  |
| `cantidad`       | BigDecimal | Cantidad. Default 1                      |
| `precioUnitario` | BigDecimal | Precio unitario (requerido)              |
| `descuento`      | BigDecimal | Descuento por ítem (opcional, default 0) |
| `ivaTipo`        | int        | 1=Gravado, 3=Exento                      |
| `iva`            | BigDecimal | Tasa de IVA: 5 ó 10                      |
| `unidadMedida`   | int        | Código SET de unidad de medida (ej. `77` = UNI); se imprime su abreviatura en la columna U.M. |

---

## Campos raíz del request KUDE

| Campo          | Tipo   | Descripción                                          |
|----------------|--------|------------------------------------------------------|
| `cdc`          | string | Código de Control del DTE (44 dígitos)               |
| `qrUrl`        | string | URL del QR para validación en eKuatia               |
| `estado`       | string | Estado del DTE: `APROBADO` o `RECHAZADO`             |
| `codigoEstado` | string | Código de respuesta SIFEN (ej. `0422`)               |

---

## Ejemplo de request completo

```json
{
  "params": {
    "ruc": "80012345-1",
    "razonSocial": "Empresa Ejemplo S.A.",
    "nombreFantasia": "Ejemplo",
    "timbradoNumero": "12345678",
    "timbradoFecha": "2024-01-01",
    "actividadesEconomicas": [{ "descripcion": "Venta al por menor" }],
    "establecimientos": [{
      "direccion": "Avda. España 123",
      "ciudadDescripcion": "Asunción",
      "departamentoDescripcion": "Central",
      "telefono": "021-123456",
      "email": "facturacion@empresa.com"
    }]
  },
  "data": {
    "tipoDocumento": 1,
    "establecimiento": "001",
    "punto": "001",
    "numero": "0000001",
    "fecha": "2025-03-01T10:00:00",
    "tipoTransaccion": 1,
    "tipoImpuesto": 1,
    "moneda": "PYG",
    "cajero": "María López",
    "socio": "12345",
    "cliente": {
      "razonSocial": "Juan Pérez",
      "iTipIDRec": 2,
      "dNumIDRec": "1234567",
      "telefono": "0981-123456"
    },
    "condicion": {
      "tipo": 1,
      "entregas": [
        { "tipo": 1, "monto": 150000, "moneda": "PYG" }
      ]
    },
    "items": [
      {
        "codigo": "P001",
        "descripcion": "Producto de prueba",
        "cantidad": 2,
        "precioUnitario": 75000,
        "ivaTipo": 1,
        "iva": 10
      }
    ]
  },
  "cdc": "01800123451001001000000120250301112233400194723456",
  "qrUrl": "https://ekuatia.set.gov.py/consultas/qr?...",
  "estado": "APROBADO",
  "codigoEstado": "0422"
}
```

---

## Layout del KUDE generado

El KUDE sigue el formato gráfico de la SET, en este orden:

1. **Banda de título** — "KuDE De Factura Electrónica" (o el tipo de documento que corresponda) sobre fondo gris.
2. **Emisor** — izquierda: logo, razón social, dirección, ciudad, teléfono, email y actividad económica; derecha: RUC, Timbrado Nº, Fecha Inicio de Vigencia, tipo de documento y número `001-001-0000001`.
3. **Emisión / receptor** — izquierda: fecha y hora de emisión, condición de venta con casillas Contado/Crédito (o motivo de emisión en NC/ND), cuotas, moneda, tipo de cambio y cajero; derecha: RUC/documento, nombre o razón social, dirección, teléfono, correo, tipo de transacción y socio.
4. **Documento asociado** — solo NC/ND.
5. **Grilla de ítems** — `Cod. | Descripción | U.M. | Cant. | Precio Unitario | Descuento | Cotización | Valor de Venta (Exentas | 5% | 10%)`. La grilla se estira hasta el pie de la página. Si los ítems no entran en una página, la grilla continúa en la siguiente con el encabezado de columnas repetido.
6. **Totales** — SUBTOTAL, TOTAL DE LA OPERACIÓN, TOTAL EN GUARANÍES (solo PYG), LIQUIDACIÓN IVA (5% / 10% / total) y monto en letras (`SON GS. (GUARANIES): ...`).
7. **QR y CDC** — QR a la izquierda; a la derecha, la leyenda de consulta en `www.ekuatia.set.gov.py/consultas`, el CDC en grupos de 4 dígitos y el aviso de representación gráfica / 72 horas. Si `estado` no es `APROBADO*`, se agrega en rojo "Estado SIFEN: <estado> (<código>)".
8. **Nota al pie** — `data.observacion`, si se envía.

Montos con separador de miles `.` y decimales `,` (`185.301,00`).
