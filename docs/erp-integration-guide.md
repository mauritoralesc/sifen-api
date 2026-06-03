# Guía de integración ERP — SIFEN Wrapper

Ajustes necesarios en el ERP para una integración correcta con la API.

---

## 1. Usar `/invoices/prepare` en lugar de `/invoices/emit`

`POST /invoices/emit` está **deprecado en producción**. SIFEN no habilita el servicio de recepción síncrona para la mayoría de emisores.

| Endpoint | Comportamiento | Usar en |
|---|---|---|
| `POST /invoices/emit` | Envía directamente a SIFEN y espera respuesta inmediata | No usar en PROD |
| `POST /invoices/prepare` | Genera XML firmado, persiste localmente, el batch lo envía automáticamente | **Flujo estándar** |

---

## 2. Flujo completo recomendado

```
ERP                         SIFEN Wrapper               SIFEN
 │                               │                         │
 │── POST /invoices/prepare ────►│                         │
 │◄── { cdc, qrUrl, estado:     │                         │
 │      "PREPARADO" } (<200ms)  │                         │
 │                               │                         │
 │  [imprimir ticket con         │                         │
 │   CDC + QR inmediatamente]   │                         │
 │                               │                         │
 │                          [~60s] BatchSenderService      │
 │                               │── recepcionLote ───────►│
 │                               │◄── nroLote ─────────────│
 │                               │  estado → ENVIADO       │
 │                               │                         │
 │                          [~10min] BatchPollerService     │
 │                               │── consultaLote ────────►│
 │                               │◄── resultados ──────────│
 │                               │  estado → APROBADO      │
 │                               │                         │
 │── GET /{cdc}/status ─────────►│                         │
 │◄── { estado: "APROBADO" } ───│                         │
```

---

## 3. Máquina de estados del documento

| Estado | Significado | Acción recomendada en ERP |
|---|---|---|
| `PREPARADO` | XML generado, pendiente de envío a SIFEN | Mostrar como "en proceso" |
| `ENVIADO` | Lote recibido por SIFEN, esperando resultado | Mostrar como "en proceso" |
| `APROBADO` | SIFEN aprobó el documento | Mostrar como "aprobado", habilitar descarga KUDE |
| `APROBADO_CON_OBSERVACION` | Aprobado con observaciones menores | Tratar igual que APROBADO |
| `RECHAZADO` | SIFEN rechazó el documento — datos inválidos | Notificar al operador, no reintentar con mismo número |
| `ERROR` | Fallo interno (cert, datos corruptos, etc.) | Notificar al operador, revisar logs |
| `CANCELADO` | Documento cancelado por evento | Mostrar como cancelado |
| `INUTILIZADO` | Número inutilizado por evento | Mostrar como inutilizado |

---

## 4. Polling del estado: cuándo y cómo consultar

### Endpoint

```
GET /invoices/{cdc}/status
GET /invoices/{cdc}/status?refresh=true
```

### Comportamiento según `refresh`

| Parámetro | Comportamiento |
|---|---|
| Sin `refresh` (default) | Devuelve el estado de la BD local. Rápido, sin llamar a SIFEN. |
| `?refresh=true` | Si el estado es `ENVIADO`, consulta SIFEN para obtener el resultado final. |

### Respuesta cuando el lote está procesando

Si el lote todavía no fue procesado por SIFEN, `?refresh=true` devuelve:

```json
{
  "estado": "ENVIADO",
  "codigoEstado": "0361",
  "descripcionEstado": "Lote en procesamiento — resultado pendiente"
}
```

Esto **no es un error** — significa que hay que esperar y reintentar. No marcar como rechazado.

### Estrategia de polling recomendada

```js
async function esperarAprobacion(cdc, maxIntentos = 20, intervaloMs = 30000) {
  for (let i = 0; i < maxIntentos; i++) {
    const { data } = await api.get(`/invoices/${cdc}/status?refresh=true`);
    const estado = data.data.estado;
    const codigo = data.data.codigoEstado;

    if (["APROBADO", "APROBADO_CON_OBSERVACION"].includes(estado)) {
      return { aprobado: true, estado };
    }

    if (["RECHAZADO", "ERROR"].includes(estado)) {
      return { aprobado: false, estado, descripcion: data.data.descripcionEstado };
    }

    // PREPARADO, ENVIADO, o código 0361 (lote procesando) → esperar
    if (i < maxIntentos - 1) {
      await delay(intervaloMs);
    }
  }
  return { aprobado: false, estado: "TIMEOUT" };
}
```

**Tiempos de referencia:**
- `PREPARADO → ENVIADO`: ~60 segundos (BatchSenderService)
- `ENVIADO → APROBADO`: ~10-15 minutos (SIFEN procesa el lote + BatchPollerService)
- Total esperado: 10-20 minutos desde la preparación

---

## 5. Manejo del error 400 — CDC duplicado

Si el ERP envía el mismo documento dos veces (reintento por timeout, bug en correlativo, etc.), la API devuelve:

```json
{
  "success": false,
  "message": "Ya existe un documento con CDC 01005324815001002000003722026050510000000373 para esta empresa"
}
```

**Esto no es un error fatal.** El documento ya existe y puede estar en cualquier estado válido.

### Manejo correcto

```js
async function prepararFactura(payload) {
  try {
    const res = await api.post("/invoices/prepare", payload);
    return res.data.data; // { cdc, qrUrl, estado }

  } catch (error) {
    if (error.response?.status === 400) {
      const msg = error.response.data?.message ?? "";
      const match = msg.match(/CDC ([A-Z0-9]{44})/);

      if (match) {
        const cdc = match[1];
        const status = await api.get(`/invoices/${cdc}/status`);
        const estado = status.data.data.estado;

        if (["PREPARADO", "ENVIADO", "APROBADO", "APROBADO_CON_OBSERVACION"].includes(estado)) {
          // Reutilizar el documento existente
          return status.data.data;
        }

        // ERROR o RECHAZADO — necesita intervención manual
        throw new Error(`Documento existente en estado ${estado}. Revisar CDC: ${cdc}`);
      }
    }
    throw error;
  }
}
```

### Cuándo usar el mismo número vs. uno nuevo

| Situación | Acción |
|---|---|
| Reintento por timeout/error de red | Reutilizar el CDC existente (ver flujo arriba) |
| Documento en `RECHAZADO` por datos inválidos | Corregir datos y usar el **siguiente número correlativo** |
| Documento en `APROBADO` con datos incorrectos | Emitir **Nota de Crédito** (`tipoDocumento: 5`) + nuevo documento con número siguiente |

> **Nunca reusar un número de factura aprobado.** El correlativo es único por timbrado + establecimiento + punto y su reutilización constituye una irregularidad fiscal ante la DNIT.

---

## 6. Códigos de respuesta SIFEN relevantes

### Consulta de lote (`GET /invoices/batch/{nroLote}`)

| Código | Descripción | Acción |
|---|---|---|
| `0361` | Lote en procesamiento | Esperar, reintentar más tarde |
| `0362` | Lote concluido | Consultar estado de cada CDC |
| `0360` | Lote inexistente | Error grave, contactar soporte |
| `0364` | Consulta extemporánea (>48h) | El wrapper consulta automáticamente por CDC individual |

### Consulta de documento (`GET /invoices/{cdc}/status?refresh=true`)

| Código | Descripción | Estado local |
|---|---|---|
| `0420` | Documento aprobado | `APROBADO` |
| `0422` | No existe o rechazado | `RECHAZADO` |
| `0361` | Lote aún procesando | `ENVIADO` (no modificado) |

> **Importante:** el código `0422` puede significar "todavía no fue procesado" si el lote está en `0361`. El wrapper ya maneja esto automáticamente — nunca marcará el documento como `RECHAZADO` si el lote está en procesamiento.

---

## 7. Resumen de endpoints utilizados por el ERP

| Método | Endpoint | Cuándo usar |
|---|---|---|
| `POST` | `/invoices/prepare` | Emitir una factura |
| `POST` | `/invoices/prepare/batch` | Emitir múltiples facturas en una llamada |
| `GET` | `/invoices/{cdc}/status` | Consultar estado sin llamar a SIFEN |
| `GET` | `/invoices/{cdc}/status?refresh=true` | Consultar estado forzando verificación en SIFEN |
| `POST` | `/invoices/{cdc}/resend-email` | Reenviar email de aprobación al cliente |
| `POST` | `/invoices/kude` | Generar PDF KUDE del documento |
| `POST` | `/invoices/kude/base64` | Generar PDF KUDE como base64 |
| `POST` | `/invoices/events` | Cancelar, inutilizar, conformar, etc. |

---

## 8. Autenticación

Todas las llamadas del ERP deben usar **API Key** en el header:

```
X-API-Key: sw_live_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

La API Key determina automáticamente la empresa (tenant) y su ambiente (PROD/DEV). No mezclar API Keys de DEV y PROD en el mismo flujo de trabajo.
