# Correo electrónico y uso de API Key — Guía ERP

Referencia completa para el reenvío de correo electrónico al cliente y el uso correcto de la API Key desde el ERP.

---

## 1. Autenticación con API Key

Todas las llamadas a la API deben incluir el header `X-API-Key`:

```http
X-API-Key: sw_live_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

### Cómo funciona internamente

1. El filtro de seguridad lee el header `X-API-Key` en cada request.
2. Valida que la clave exista en la base de datos, esté activa y no haya expirado.
3. Establece automáticamente el **tenant** (empresa emisora) asociado a esa clave — no es necesario enviar `companyId` en los requests.
4. La API Key otorga acceso a todos los endpoints bajo `/invoices/**`.

### Buenas prácticas

| Recomendación | Detalle |
|---|---|
| Una key por ambiente | Usar una API Key distinta para DEV y PROD. Nunca mezclarlas. |
| No exponer en frontend | La API Key debe estar solo en el backend del ERP, nunca en código del cliente. |
| Rotar periódicamente | Generar una nueva key y deshabilitar la anterior en caso de compromiso. |
| Sin bearer ni Basic | El header es `X-API-Key: <valor>`, no `Authorization: Bearer <valor>`. |

### Ejemplo de configuración en el ERP (JavaScript)

```js
const sifenApi = axios.create({
  baseURL: "https://api.tudominio.com",
  headers: {
    "X-API-Key": process.env.SIFEN_API_KEY,
    "Content-Type": "application/json",
  },
});
```

---

## 2. Reenvío de correo electrónico al cliente

Cuando una factura es aprobada por SIFEN, el sistema envía automáticamente un correo al cliente con el KUDE adjunto. Si el envío inicial falla, o el cliente solicita reenvío, se usa este endpoint.

### Endpoint

```
POST /invoices/{cdc}/resend-email
```

| Parámetro | Tipo | Descripción |
|---|---|---|
| `cdc` | path | Código de Control del Documento (44 caracteres) |

No requiere body.

### Ejemplo de request

```http
POST /invoices/01005324815001002000003722026050510000000373/resend-email
X-API-Key: sw_live_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

### Respuesta exitosa (`200 OK`)

```json
{
  "success": true,
  "message": "Correo reenviado correctamente",
  "data": {
    "cdc": "01005324815001002000003722026050510000000373",
    "sent": "true",
    "email": "cliente@empresa.com",
    "reason": "",
    "resendId": "6f2b1c3e-aaaa-bbbb-cccc-1234567890ab"
  }
}
```

### Respuesta cuando no se puede enviar (`200 OK`, `sent: false`)

El endpoint **siempre responde `200`**. Cuando el envío no fue posible, `sent` es `"false"` y `reason` describe el motivo:

```json
{
  "success": true,
  "message": "No se pudo reenviar el correo",
  "data": {
    "cdc": "01005324815001002000003722026050510000000373",
    "sent": "false",
    "email": "",
    "reason": "El documento no está aprobado",
    "resendId": ""
  }
}
```

### Causas posibles de `sent: false`

| `reason` | Causa | Acción |
|---|---|---|
| `"El documento no está aprobado"` | Estado es `PREPARADO`, `ENVIADO`, `RECHAZADO`, etc. | Esperar aprobación antes de reenviar |
| `"El cliente no tiene email en el request"` | El campo `data.cliente.email` estaba vacío al emitir | No hay reenvío posible; avisar al usuario |
| `"No se pudo leer requestData..."` | Error interno al leer los datos del documento | Contactar soporte |
| `"RESEND_API_KEY no configurada"` | Falta configuración del servidor de correo | Configurar en el servidor |
| `"Resend rechazó la solicitud: 422"` | Email inválido o dominio no verificado en Resend | Revisar configuración de Resend |

### Cuándo usar el reenvío

```
ERP                          SIFEN Wrapper
 │                                │
 │── GET /{cdc}/status ──────────►│
 │◄── { estado: "APROBADO" } ────│
 │                                │
 │  [cliente solicita reenvío]   │
 │                                │
 │── POST /{cdc}/resend-email ───►│ valida estado APROBADO
 │                                │ obtiene email del cliente
 │                                │ genera KUDE PDF
 │                                │── Resend API ──────────────► cliente@empresa.com
 │◄── { sent: "true" } ──────────│
```

### Implementación de referencia (JavaScript)

```js
async function reenviarEmailFactura(cdc) {
  const { data } = await sifenApi.post(`/invoices/${cdc}/resend-email`);

  const { sent, reason, email, resendId } = data.data;

  if (sent === "true") {
    console.log(`Email reenviado a ${email} (id: ${resendId})`);
    return { ok: true, email };
  } else {
    console.warn(`No se pudo reenviar: ${reason}`);
    return { ok: false, reason };
  }
}
```

> **Nota:** `sent` es un `string` (`"true"` / `"false"`), no un booleano. Verificar con `=== "true"`.

---

## 3. Contenido del correo enviado

El correo que recibe el cliente incluye:

- **Asunto:** `Factura aprobada - CDC <cdc>`
- **Remitente:** Configurado en el servidor (`fromName <fromEmail>`, ej: `SYNCTEMA <no-reply@synctema.com>`)
- **Destinatario:** `data.cliente.email` del request original de emisión
- **Adjunto:** KUDE en formato PDF (`kude-<cdc>.pdf`)
- **Cuerpo HTML y texto plano** con CDC, estado, código SIFEN y URL del QR

El correo es idéntico al que se envía automáticamente tras la aprobación.

---

## 4. Prerequisito: el campo `email` del cliente

Para que el envío funcione, el request de emisión original debe incluir el email del cliente:

```json
{
  "data": {
    "cliente": {
      "ruc": "80012345-1",
      "razonSocial": "Empresa Cliente S.A.",
      "email": "facturacion@empresa.com"
    }
  }
}
```

Si `email` se omite o está vacío, **ni el envío automático ni el reenvío funcionarán**. El campo no es obligatorio para SIFEN, pero sí para la funcionalidad de correo.

---

## 5. Verificación rápida (curl)

```bash
curl -s -X POST \
  "https://api.tudominio.com/invoices/01005324815001002000003722026050510000000373/resend-email" \
  -H "X-API-Key: sw_live_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" | jq .
```

---

## 6. Relación con otros documentos

| Documento | Contenido |
|---|---|
| [`erp-integration-guide.md`](erp-integration-guide.md) | Flujo completo de emisión, estados, polling, CDC duplicado |
| [`kude.md`](kude.md) | Generación del KUDE PDF por separado |
| [`metodos-de-pago.md`](metodos-de-pago.md) | Referencia de métodos de pago en el request |
