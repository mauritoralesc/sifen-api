# Error 400 — CDC duplicado: "Ya existe un documento con CDC X para esta empresa"

## Qué es este error

Al llamar a `POST /invoices/prepare`, la API retorna HTTP `400` con el siguiente cuerpo:

```json
{
  "success": false,
  "message": "Ya existe un documento con CDC 01800532481001001000000120012026050400942895111 para esta empresa"
}
```

Esto significa que ya existe en la base de datos un documento electrónico con ese CDC exacto, asociado a la misma empresa (API Key).

## Por qué ocurre

El CDC (Código de Control del Documento) de 44 caracteres es calculado por SIFEN a partir de los siguientes campos del documento:

```
[iTiDE][dNumTim][dEst][dPunExp][dNumDoc][dFeEmiDE][dRucEm][dDVEmi][iTipAmb][dCodSeg]
```

En particular:
- **`dNumDoc`**: número de factura (ej. `0000012`)
- **`dEst`**: establecimiento (ej. `001`)
- **`dPunExp`**: punto de expedición (ej. `001`)
- **`dNumTim`**: número de timbrado
- **`dFeEmiDE`**: fecha/hora de emisión
- **`iTipAmb`**: ambiente (`1`=PROD, `2`=DEV)

Si la combinación `establecimiento + punto + número + timbrado + fecha` ya fue usada para un `POST /invoices/prepare` previo, el CDC generado será idéntico y la operación es rechazada.

### Casos frecuentes

| Caso | Causa |
|---|---|
| Reintento automático del ERP | La primera llamada llegó al servidor y guardó el documento, pero el ERP recibió timeout o error de red y volvió a intentar con los mismos datos |
| Número de factura no incrementado | El ERP envió el mismo `numero` más de una vez (bug en la generación del correlativo) |
| Reenvío manual del operador | El operador reprocesó una factura que ya había sido preparada correctamente |

## Cómo debe manejarlo el ERP

### 1. Diferenciar el 400 de CDC duplicado

La respuesta tiene el campo `message` que contiene el CDC afectado. El ERP debe detectar esta condición específica y **no tratarla como un error fatal**:

```js
if (response.status === 400 && response.data.message?.includes("Ya existe un documento con CDC")) {
  const cdc = extraerCdcDelMensaje(response.data.message);
  // Continuar con el CDC existente en lugar de fallar
}
```

Para extraer el CDC del mensaje:

```js
function extraerCdcDelMensaje(mensaje) {
  // El CDC tiene siempre 44 caracteres alfanuméricos
  const match = mensaje.match(/CDC ([A-Z0-9]{44})/);
  return match ? match[1] : null;
}
```

### 2. Consultar el estado del documento existente

Con el CDC extraído, consultar el estado local:

```
GET /invoices/{cdc}/status
```

Respuesta esperada:

```json
{
  "cdc": "01800532481001001000000120012026050400942895111",
  "estado": "PREPARADO",
  "qrUrl": "https://ekuatia.set.gov.py/consultas/qr?nVersion=150&...",
  "createdAt": "2026-05-05T10:32:00"
}
```

### 3. Flujo recomendado en el ERP

```
POST /invoices/prepare
        │
        ├─ 201 / 200 OK ──────────────────► Guardar cdc + qrUrl, imprimir ticket
        │
        └─ 400 "Ya existe CDC X" ─────────► GET /invoices/{X}/status
                                                    │
                                                    ├─ estado: PREPARADO / ENVIADO / APROBADO
                                                    │         Usar cdc + qrUrl existentes ✓
                                                    │
                                                    └─ estado: ERROR / RECHAZADO
                                                              Corregir el documento y
                                                              usar un número diferente
```

### 4. Implementación de referencia (pseudocódigo)

```js
async function prepararFactura(payload) {
  try {
    const response = await api.post("/invoices/prepare", payload);
    return { cdc: response.data.cdc, qrUrl: response.data.qrUrl };

  } catch (error) {
    if (error.response?.status === 400) {
      const msg = error.response.data?.message ?? "";
      const cdcMatch = msg.match(/CDC ([A-Z0-9]{44})/);

      if (cdcMatch) {
        const cdcExistente = cdcMatch[1];
        // El documento ya existe — consultar su estado actual
        const status = await api.get(`/invoices/${cdcExistente}/status`);
        const estado = status.data.estado;

        if (["PREPARADO", "ENVIADO", "APROBADO", "APROBADO_CON_OBSERVACION"].includes(estado)) {
          // Documento válido — reutilizar
          return { cdc: status.data.cdc, qrUrl: status.data.qrUrl, estadoExistente: estado };
        } else {
          // ERROR o RECHAZADO — necesita intervención
          throw new Error(`Documento con CDC ${cdcExistente} en estado ${estado}. Revisar.`);
        }
      }
    }
    throw error; // Otro tipo de error — propagar
  }
}
```

## Importante: ¿Cuándo está bien reemitir con el mismo número?

**Nunca.** El número de factura es el correlativo del timbrado y debe ser único por `establecimiento + punto`. Si SIFEN ya aceptó un documento con ese número (estado `APROBADO`), emitir otro con el mismo número constituye una irregularidad fiscal.

La única acción válida cuando el documento aprobado tiene un error de datos es:
1. Emitir una **Nota de Crédito** (`tipoDocumento: 5`) referenciando el CDC aprobado.
2. Emitir un nuevo documento con el **siguiente número correlativo**.
