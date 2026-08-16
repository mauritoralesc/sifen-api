# Notas de Crédito Electrónicas (NCE) — guía técnica

Esta guía documenta la emisión de Notas de Crédito (`tipoDocumento: 5`) y Débito
(`tipoDocumento: 6`) a través del wrapper, basada en el Manual Técnico SIFEN v150.
No hay endpoints de emisión dedicados: una NC/ND se emite con los mismos
`POST /invoices/prepare` / `POST /invoices/emit` que una factura, agregando el grupo
E5 (`notaCreditoDebito`) y el grupo H (`documentoAsociado`). Complementa la tabla de
tipos del [README](../README.md#tipos-de-documento-tipodocumento).

## 1. Flujo recomendado

Igual que una factura: `POST /invoices/prepare` (genera XML firmado + CDC + QR sin
tocar SIFEN, queda `PREPARADO`) → `BatchSenderService` la agrupa por
company+tipoDocumento y la envía en el próximo ciclo (`sifen.batch.send-interval`,
60s por defecto) → `BatchPollerService` consulta el lote (`sifen.batch.poll-interval`,
600s por defecto) y actualiza el estado. `GET /invoices/{cdc}/status?refresh=true`
para consultar bajo demanda. Ver [erp-integration-guide.md](erp-integration-guide.md)
para el contrato completo del pipeline.

`POST /invoices/emit` (síncrono) también acepta NC/ND, pero está deprecado en PROD
igual que para facturas — ver el README.

Al aprobarse, SIFEN genera automáticamente el evento de "Devolución y Ajuste de
precios" vinculado a la factura afectada; el wrapper no lo modela como un evento propio
ni cambia el estado local de la factura — para ver qué NCs afectan a una factura, use
`GET /invoices/{cdc}/credit-notes` (§5).

## 2. Payload

### 2.1 Grupo E5 — motivo de emisión (`notaCreditoDebito`)

```json
"notaCreditoDebito": { "motivo": 2 }
```

| `motivo` (iMotEmi) | Descripción | Uso típico |
|---|---|---|
| 1 | Devolución y ajuste de precios | Ajuste mixto (valor + cantidad) |
| 2 | Devolución | Retorno físico de mercadería |
| 3 | Descuento | Ajuste puramente financiero |
| 4 | Bonificación | Reconocimiento comercial |
| 5 | Crédito incobrable | — |
| 6 | Recupero de costo | — |
| 7 | Recupero de gasto | — |
| 8 | Ajuste de precio | — |

Los ítems de la NC representan lo que se devuelve/ajusta; el wrapper calcula los
totales de la NC a partir de `data.items` como en cualquier otro documento.

### 2.2 Grupo H — documento asociado (`documentoAsociado`)

Obligatorio y único (regla SIFEN 2415: una NC ajusta exactamente un documento). Dos
formas, mutuamente excluyentes:

**Por CDC de un documento electrónico** (`tipoDocumentoAsociado: 1`):

```json
"documentoAsociado": {
  "tipoDocumentoAsociado": 1,
  "cdcAsociado": "01800000000000000000000000000000000000000000"
}
```

Solo se puede asociar a una Factura Electrónica (CDC con prefijo `01`) o Autofactura
(`04`) emitida por el mismo RUC autenticado.

**Por comprobante impreso** (`tipoDocumentoAsociado: 2`):

```json
"documentoAsociado": {
  "tipoDocumentoAsociado": 2,
  "timbradoAsociado": "12345678",
  "establecimientoAsociado": "001",
  "puntoAsociado": "001",
  "numeroAsociado": "0000001",
  "tipoComprobanteAsociado": 1,
  "fechaEmisionAsociado": "2023-10-27"
}
```

`tipoComprobanteAsociado` (H009): `1`=Factura, `2`=Nota de crédito, `3`=Nota de
débito, `4`=Nota de remisión, `5`=Comprobante de retención.

### 2.3 Ejemplo mínimo completo

```json
{
  "data": {
    "tipoDocumento": 5,
    "establecimiento": "001",
    "punto": "001",
    "numero": "0000042",
    "fecha": "2026-08-15T10:00:00",
    "tipoEmision": 1,
    "tipoTransaccion": 1,
    "tipoImpuesto": 1,
    "moneda": "PYG",
    "cliente": { "...": "igual que en una factura" },
    "items": [{ "...": "lo que se devuelve/ajusta" }],
    "notaCreditoDebito": { "motivo": 2 },
    "documentoAsociado": {
      "tipoDocumentoAsociado": 1,
      "cdcAsociado": "01800000000000000000000000000000000000000000"
    }
  }
}
```

## 3. Validaciones locales

`NotaCreditoValidator` (aplica a `tipoDocumento` 5 y 6) pre-valida antes de generar el
XML, devolviendo `400 INVALID_REQUEST` con mensaje en español. Corta lo que puede
cortar localmente para evitar un viaje redondo a SIFEN; el resto queda para que SIFEN
decida.

| Validación local | Regla SIFEN equivalente | Cuándo se aplica |
|---|---|---|
| `documentoAsociado` obligatorio y único | 2415 | Siempre |
| `notaCreditoDebito.motivo` en 1..8 | E401 | Siempre |
| CDC asociado con DV válido, tipo 01/04, mismo RUC del tenant | — (estructural) | Asociación por CDC |
| Documento asociado en estado `APROBADO`/`APROBADO_CON_OBSERVACION` y sin cancelación registrada | 2404 | Solo si el CDC existe en la base local |
| Moneda de la NC == moneda del documento asociado | 2438 | Solo si el CDC existe local y su moneda es conocida |
| Suma de NC (`PREPARADO`/`ENVIADO`/aprobadas) + esta NC ≤ monto total de la factura | 2417 | Solo si el CDC existe local y tiene `montoTotal` registrado |

### CDC asociado que no existe en la base local

Si `cdcAsociado` es estructuralmente válido y pertenece al RUC del tenant pero no hay
ningún `ElectronicDocument` con ese CDC (factura histórica, emitida antes de este
wrapper, o desde otro sistema), el wrapper **no bloquea la NC**: registra un
`warning` en el log y deja que SIFEN aplique 2404/2438/2417 al procesar el lote. Si
SIFEN rechaza, el documento queda `RECHAZADO` con el código y mensaje de SIFEN
disponibles en `GET /invoices/{cdc}/status`.

### Nota sobre 2417 y redondeo

El monto local de la NC nueva se estima con la misma fórmula que usa el wrapper para
validar el receptor innominado (cantidad × precio − descuento por ítem, sin IVA). El
monto que SIFEN valida (`dTotGralOpe`, F014) lo calcula la librería con reglas de IVA
más finas y puede diferir en centavos por redondeo. La validación local es un filtro
conservador, no un sustituto de la validación de SIFEN.

## 4. Filas anteriores a esta migración

Los `ElectronicDocument` creados antes de la migración `V17` no tienen `moneda` ni
`monto_total` poblados (salvo el backfill best-effort de moneda que hace la propia
migración). Si una NC se asocia a uno de esos documentos, las validaciones 2438/2417
se omiten con un warning — SIFEN decide.

## 5. Consultar las NC de una factura

```bash
curl http://localhost:8000/invoices/{cdcFactura}/credit-notes \
  -H "X-API-Key: $API_KEY"
```

Devuelve las NC (`tipoDocumento: 5`) emitidas por la empresa autenticada cuyo
`documentoAsociado.cdcAsociado` sea `cdcFactura`, con estado, motivo, moneda, monto y
resultado SIFEN de cada una. No incluye NC que referencien la factura por comprobante
impreso (no hay CDC que buscar) ni NC emitidas por otras empresas.

## 6. KUDE y email

El KUDE de una NC/ND titula "NOTA DE CRÉDITO ELECTRÓNICA" / "NOTA DE DÉBITO
ELECTRÓNICA", muestra el motivo de emisión en lugar de los checkboxes
Contado/Crédito (que no aplican, la NC no tiene grupo de condición), y agrega una
sección "Documento Asociado" con el CDC o los datos del comprobante impreso. Ver
[kude.md](kude.md) para el resto del layout, que es idéntico al de una factura.

El correo de aprobación automática ajusta el asunto y el cuerpo según el tipo de
documento ("Nota de Crédito aprobada - CDC ...", etc.) — ver
[correo-electronico-y-api-key.md](correo-electronico-y-api-key.md).
