# Motivo de rechazo y reenviabilidad — Guía para el ERP

Antes, `GET /invoices/{cdc}/status` informaba `estado=RECHAZADO` sin decir por qué, y
sin indicar si el CDC podía reenviarse. Esta guía documenta los campos nuevos que
resuelven eso y cómo usarlos.

## Qué trae ahora `/status`

```json
{
  "success": true,
  "data": {
    "cdc": "01005324815001001000035422026072710000003541",
    "estado": "RECHAZADO",
    "codigoEstado": "1300",
    "descripcionEstado": "El tipo de operación no compatible con la naturaleza del receptor",
    "mensajes": [
      { "codigo": "1300", "descripcion": "El tipo de operación no compatible con la naturaleza del receptor" }
    ],
    "reenviable": true,
    "clasificacionReenvio": "REQUIERE_CORRECCION",
    "accionSugerida": "Corrija los datos indicados por el código 1300 y reenvíe con POST /invoices/{cdc}/resend incluyendo el payload corregido en el body."
  }
}
```

| Campo | Descripción |
|---|---|
| `mensajes` | Todos los mensajes SIFEN del resultado (antes solo se exponía el primero). Se reconstruye desde el detalle completo guardado en la consulta de lote cuando está disponible. |
| `reenviable` | `true` si el documento puede reenviarse con `POST /invoices/{cdc}/resend` reutilizando el mismo CDC. |
| `clasificacionReenvio` | `AUTOMATICO` \| `REQUIERE_CORRECCION` \| `NO_REENVIABLE` (ver abajo). |
| `accionSugerida` | Texto listo para mostrar al operador o loguear. |

`GET /invoices/batch/{nroLote}` también expone `codigo`, `reenviable` y
`clasificacionReenvio` por cada CDC en `resultados[]`, útil para conciliar un lote
completo sin consultar documento por documento.

## Las tres clasificaciones

Basadas en el Manual Técnico SIFEN v150, §6.5 (pp.27-28): un DE rechazado puede
reenviarse con el mismo CDC **siempre que el ajuste no altere los campos que lo
componen**. Si los altera, hay que inutilizar el número y emitir uno nuevo.

| Clasificación | Significado | Qué hacer |
|---|---|---|
| `AUTOMATICO` | El problema no está en los datos del documento (reloj de firma, transitorio de infraestructura, error interno del wrapper). | `POST /invoices/{cdc}/resend` sin body. |
| `REQUIERE_CORRECCION` | El CDC no se ve afectado, pero hay que corregir un dato (RUC receptor, actividad económica, IVA, timbrado, ítems...). Es el caso más común. | `POST /invoices/{cdc}/resend` con el payload corregido en el body (ver abajo). |
| `NO_REENVIABLE` | La corrección necesaria toca un campo que compone el CDC (establecimiento, punto, número, fecha de emisión, RUC emisor...). | Inutilizar el número (`POST /invoices/events`, evento 2) y emitir un documento nuevo con el siguiente correlativo. |

### Códigos que afectan el CDC (`NO_REENVIABLE`)

El CDC se compone de: tipo de documento, RUC emisor + DV, establecimiento, punto de
expedición, número de documento, tipo de contribuyente, fecha de emisión, tipo de
emisión y código de seguridad. Los códigos SIFEN ligados a esos campos:

| Código | Motivo |
|---|---|
| 1000, 1001, 1002 | CDC no corresponde con el XML / CDC duplicado / documento duplicado |
| 1003 | DV del CDC inválido |
| 1050 | Tipo de emisión inválido en esta etapa |
| 1105, 1106 | Establecimiento / punto de expedición incorrecto |
| 1109 | Número de documento ya inutilizado |
| 1150, 1151, 1156 | Fecha/hora de emisión inválida (retraso / adelanto / anterior al sistema) |
| 1250, 1251, 1252, 1253 | RUC del emisor inexistente / inhabilitado / inactivo / DV incorrecto |

Cualquier otro código de rechazo cae en `REQUIERE_CORRECCION` por defecto — es la
categoría más amplia y cubre timbrado, receptor, ítems, IVA y totales.

### Casos particulares en `AUTOMATICO`

- **1004** — firma digital adelantada. Ver [reenvio-rechazados-1004.md](reenvio-rechazados-1004.md).
- **1264** — RUC no habilitado para el servicio síncrono (`/invoices/emit`): reenviar por
  lote (`/invoices/prepare` → `/resend`) sí funciona y no altera el CDC.
- Errores internos del wrapper sin código SIFEN (`estado=ERROR` sin `sifenCodigo`, p. ej.
  fallo de red o certificado): un reintento simple suele resolverlo.

## Reenvío con datos corregidos

Cuando la clasificación es `REQUIERE_CORRECCION`, `POST /invoices/{cdc}/resend` acepta
el mismo body que `/invoices/prepare` (`EmitirFacturaRequest`):

```bash
curl -X POST https://sifenapi.ratones.dev/invoices/{cdc}/resend \
  -H "X-API-Key: sw_live_..." \
  -H "Content-Type: application/json" \
  -d '{ "params": { ... }, "data": { ...campos corregidos... } }'
```

El wrapper valida el payload igual que en `prepare` (reglas del receptor, ítems,
establecimiento) y luego **recalcula el CDC con los datos corregidos**: si no coincide
exactamente con el CDC original, la operación se rechaza con `400` — es la misma
salvaguarda que impide reenviar sin body cuando los datos cambiaron. Por eso una
corrección que toque un campo del CDC nunca puede colarse por este camino, aunque el
ERP se equivoque en la clasificación.

Si de todas formas se necesita forzar el intento sobre un documento `NO_REENVIABLE`
(por ejemplo, para confirmar el diagnóstico), agregar `?force=true` — esto solo omite
el bloqueo orientativo por clasificación; el guard de igualdad de CDC sigue aplicando
siempre, sin excepción.

## Auditoría del rechazo

Cada vez que un documento se reenvía, el motivo del rechazo anterior (estado, código,
mensaje, lote) se archiva en la columna interna `response_data` antes de limpiarse, así
que el historial completo de intentos queda disponible para soporte vía
`GET /logs/transactions` (JWT ADMIN) — no se pierde al reenviar.
