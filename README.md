# sifen-wrapper

API REST **multi-tenant** en Spring Boot que actúa como wrapper de [`rshk-jsifenlib`](https://github.com/roshkadev/rshk-jsifenlib) para la facturación electrónica de Paraguay (SIFEN - SET).

Múltiples empresas pueden operar contra SIFEN de forma independiente, cada una con sus propios certificados, CSC y ambiente.

El sistema permite **RUC repetido** siempre que cambie el perfil operativo de emisión.
La API permite múltiples empresas con el mismo `ruc`, `dv`, `ambiente` y `nombre`.
El aislamiento multi-tenant se mantiene por `companyId` (JWT/API Key).

## Requisitos

- Java 17+
- Maven 3.8+
- PostgreSQL 14+
- Certificado digital PFX emitido por la DNIT (por empresa)

## Configuración

### Variables de entorno

| Variable | Descripción | Requerida | Default |
|----------|-------------|-----------|---------|
| `DB_HOST` | Host de PostgreSQL | No | `localhost` |
| `DB_PORT` | Puerto de PostgreSQL | No | `5432` |
| `DB_NAME` | Nombre de la base de datos | No | `sifen` |
| `DB_USER` | Usuario de PostgreSQL | No | `postgres` |
| `DB_PASS` | Contraseña de PostgreSQL | **Sí (prod)** | dev default |
| `JWT_SECRET` | Clave secreta para firmar JWT (mín. 32 chars) | **Sí (prod)** | dev default |
| `ENCRYPTION_KEY` | Clave AES-256 en base64 (32 bytes) | **Sí (prod)** | dev default |
| `SIFEN_BATCH_ENABLED` | Habilitar/deshabilitar schedulers de envío y consulta | No | `true` |
| `SIFEN_BATCH_SEND_INTERVAL` | Intervalo de envío de lotes (ms) | No | `60000` |
| `SIFEN_BATCH_POLL_INTERVAL` | Intervalo de consulta de lotes (ms) | No | `600000` |
| `SIFEN_BATCH_MAX_PER_LOTE` | Máximo de DEs por lote | No | `50` |
| `SIFEN_BATCH_MIN_WAIT` | Segundos mínimos antes de consultar un lote | No | `600` |
| `SIFEN_BATCH_MAX_AGE` | Horas máximas para consulta por lote (luego usa CDC individual) | No | `48` |

Generar la clave de cifrado:

```bash
openssl rand -base64 32
```

### application.yml

```yaml
server:
  port: 8000

spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:sifen}
    username: ${DB_USER:postgres}
    password: ${DB_PASS:}
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    locations: classpath:db/migration

security:
  jwt:
    secret: ${JWT_SECRET}
    access-token-expiration: 3600       # 1 hora
    refresh-token-expiration: 604800    # 7 días
  encryption:
    key: ${ENCRYPTION_KEY}

sifen:
  batch:
    enabled: ${SIFEN_BATCH_ENABLED:true}
    send-interval: ${SIFEN_BATCH_SEND_INTERVAL:60000}
    poll-interval: ${SIFEN_BATCH_POLL_INTERVAL:600000}
    max-per-lote: ${SIFEN_BATCH_MAX_PER_LOTE:50}
    min-wait-before-poll: ${SIFEN_BATCH_MIN_WAIT:600}
    max-poll-age-hours: ${SIFEN_BATCH_MAX_AGE:48}
```

> La configuración de SIFEN (certificados, CSC, ambiente) ya **no** se define en YAML. Se gestiona por empresa desde los endpoints de administración.

## Ejecución

```bash
# Crear la base de datos
createdb sifen

# Iniciar la aplicación (Flyway crea las tablas automáticamente)
mvn spring-boot:run
```

Al primer arranque se crea automáticamente:
- **Empresa:** "Administración" (RUC: 00000000)
- **Usuario admin:** `admin@sifen-wrapper.com` / `admin123`

> **Importante:** cambiar la contraseña del admin en producción.

---

## Autenticación

La API soporta dos mecanismos de autenticación:

### JWT (usuarios)

```bash
# Login
curl -X POST http://localhost:8000/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "admin@sifen-wrapper.com", "password": "admin123"}'

# Respuesta
{
  "accessToken": "eyJhbGciOi...",
  "refreshToken": "eyJhbGciOi...",
  "expiresIn": 3600
}

# Usar el token en requests
curl -H "Authorization: Bearer eyJhbGciOi..." http://localhost:8000/invoices/...
```

### API Key (acceso programático)

```bash
# Crear API Key (requiere JWT de ADMIN)
curl -X POST http://localhost:8000/api-keys \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"name": "Mi integración"}'

# Respuesta (el key completo se muestra UNA SOLA VEZ)
{
  "id": 1,
  "keyPrefix": "sw_live_",
  "name": "Mi integración",
  "rawKey": "sw_live_aBcDeFgHiJkLmNoPqRsT..."
}

# Usar el API Key en requests
curl -H "X-API-Key: sw_live_aBcDeFgHiJkLmNoPqRsT..." http://localhost:8000/invoices/...
```

---

## Endpoints

### Autenticación (público)

| Método | Ruta | Descripción |
|--------|------|-------------|
| `POST` | `/auth/login` | Login con email + password → JWT |
| `POST` | `/auth/refresh` | Renovar access token con refresh token |

### Administración de Empresas (ADMIN)

| Método | Ruta | Descripción |
|--------|------|-------------|
| `POST` | `/companies` | Crear empresa |
| `GET` | `/companies` | Listar empresas |
| `GET` | `/companies/{id}` | Detalle de empresa |
| `PUT` | `/companies/{id}` | Actualizar empresa |
| `DELETE` | `/companies/{id}` | Desactivar empresa |
| `POST` | `/companies/{id}/certificate` | Subir certificado PFX (multipart) |
| `DELETE` | `/companies/{id}/certificate` | Eliminar certificado |
| `PUT` | `/companies/{id}/csc` | Actualizar CSC (id + valor) |
| `PUT` | `/companies/{id}/emisor` | Configurar datos del emisor (params) |
| `GET` | `/companies/{id}/emisor` | Obtener configuración del emisor |

### API Keys (ADMIN)

| Método | Ruta | Descripción |
|--------|------|-------------|
| `POST` | `/api-keys` | Crear API Key para la empresa |
| `GET` | `/api-keys` | Listar API Keys (solo prefix + metadata) |
| `DELETE` | `/api-keys/{id}` | Revocar un API Key |

### Facturación Electrónica (autenticado — JWT o API Key)

| Método | Ruta | Descripción |
|--------|------|-------------|
| `POST` | `/invoices/prepare` | **Prepara DE** (genera XML firmado + CDC + QR, sin enviar a SIFEN) |
| `POST` | `/invoices/prepare/batch` | **Prepara múltiples DEs** en una sola llamada |
| `GET` | `/invoices/{cdc}/status` | **Consulta estado local** del DE (con opción `?refresh=true`) |
| `POST` | `/invoices/emit` | Emite un DE (síncrono, **DEPRECADO en PROD**) |
| `POST` | `/invoices/emit/batch` | Envía lote de DEs (asíncrono directo) |
| `POST` | `/invoices/kude` | Genera KUDE (PDF) de un DE |
| `POST` | `/invoices/kude/base64` | Genera KUDE como base64 en JSON |
| `GET` | `/invoices/{cdc}` | Consulta DE por CDC directamente a SIFEN |
| `GET` | `/invoices/batch/{nroLote}` | Consulta estado de lote directamente a SIFEN |
| `GET` | `/invoices/ruc/{ruc}` | Consulta datos de un RUC |
| `POST` | `/invoices/events` | Envía evento (cancelación, inutilización, etc.) |

### Reglas de acceso

| Ruta | Acceso |
|------|--------|
| `/auth/**` | Público |
| `/companies/**` | ADMIN (JWT) |
| `/api-keys/**` | ADMIN (JWT) |
| `/users/**` | ADMIN (JWT) |
| `/invoices/**` | Autenticado (JWT o API Key) |

### Estado del servicio de emisión síncrona

- `POST /invoices/emit` se considera deprecado para ambiente `PROD`.
- En `PROD` este servicio no está habilitado de forma operativa para emisión.
- En producción, SIFEN responde: `RUC del emisor no está habilitado para utilizar este tipo de servicio`.
- **Recomendación:** usar el flujo optimizado `POST /invoices/prepare` → schedulers automáticos → `GET /invoices/{cdc}/status` como flujo estándar de producción.

---

## Flujo Optimizado de Emisión (Prepare + Batch Automático)

El flujo optimizado separa la emisión de FE en dos fases para evitar bloqueos en el punto de venta:

```
┌────────────────────────────────────────────────────────────────────┐
│  POS (Cajero)                  Wrapper                  SIFEN     │
│  ─────────────                 ───────                  ─────     │
│                                                                    │
│  1. Venta + FE                                                     │
│     ──────────► POST /invoices/prepare                             │
│                 • Valida datos                                     │
│                 • Genera XML firmado                               │
│                 • Calcula CDC + QR                                  │
│                 • Persiste (PREPARADO)                              │
│     ◄────────── { cdc, qrUrl, estado }  (< 200ms)                 │
│                                                                    │
│  2. Imprime ticket con CDC + QR  ✓                                 │
│                                                                    │
│  ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─  │
│  (Background — No bloquea la caja)                                 │
│                                                                    │
│                 3. Scheduler envío (cada 60s)                       │
│                    • Agrupa DEs PREPARADO por empresa + tipo        │
│                    • Arma lotes ≤ 50                               │
│                    • Envía lote  ─────────► recibe-lote            │
│                    ◄──────────── nroLote                           │
│                    • Marca ENVIADO                                  │
│                                                                    │
│                 4. Scheduler consulta (cada 10 min)                 │
│                    • Lotes ENVIADO ≥ 10 min                        │
│                    • Consulta lote ──────► consulta-lote           │
│                    ◄──────────── resultados                        │
│                    • Marca APROBADO / RECHAZADO                     │
│                                                                    │
│  5. Consulta manual (cuando necesite)                              │
│     ──────────► GET /invoices/{cdc}/status                         │
│     ◄────────── { cdc, estado, qrUrl, ... }                        │
└────────────────────────────────────────────────────────────────────┘
```

### Fase 1 — Preparación inmediata (síncrona, < 200ms)

El sistema POS envía los datos de la factura. El wrapper genera el XML firmado, calcula el CDC y la URL del QR, persiste el DE en BD local con estado `PREPARADO`, y retorna inmediatamente. **El ticket se imprime con CDC y QR antes de que SIFEN procese el documento.**

#### `POST /invoices/prepare` — Preparar un DE

```bash
curl -X POST http://localhost:8000/invoices/prepare \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "data": {
      "tipoDocumento": 1,
      "establecimiento": "001",
      "punto": "001",
      "numero": "0000025",
      "descripcion": "Factura electrónica",
      "fecha": "2026-03-20T10:30:00",
      "tipoEmision": 1,
      "tipoTransaccion": 1,
      "tipoImpuesto": 1,
      "moneda": "PYG",
      "cliente": {
        "contribuyente": true,
        "ruc": "80069563-1",
        "razonSocial": "TIPS S.A",
        "tipoOperacion": 1,
        "direccion": "Asuncion",
        "numeroCasa": "123",
        "departamento": 1,
        "departamentoDescripcion": "CAPITAL",
        "distrito": 1,
        "distritoDescripcion": "ASUNCION",
        "ciudad": 1,
        "ciudadDescripcion": "ASUNCION",
        "pais": "PRY",
        "tipoContribuyente": 2,
        "codigo": "CLI-01"
      },
      "factura": {"presencia": 1},
      "condicion": {"tipo": 1, "entregas": [{"tipo": 1, "monto": 10000, "moneda": "PYG"}]},
      "items": [{
        "codigo": "SKU-001",
        "descripcion": "Servicio de implementación",
        "cantidad": 1,
        "precioUnitario": 10000,
        "ivaTipo": 1,
        "iva": 10,
        "ivaProporcion": 100,
        "unidadMedida": 77
      }]
    }
  }'
```

> **Nota:** `params` es opcional si la empresa tiene emisor configurado vía `PUT /companies/{id}/emisor`.

#### Caso no contribuyente (B2C) e innominado

Cuando el cliente no tiene RUC válido (o no desea identificarse), emitir como no contribuyente con operación B2C:

- `cliente.contribuyente`: `false` (D201 = 2)
- `cliente.tipoOperacion`: `2` (D202 = B2C)
- `cliente.pais`: `"PRY"` (D203) y descripción Paraguay (D204)
- Para no contribuyente: **no enviar `cliente.ruc`**

Para factura **innominada**:

- `cliente.iTipIDRec`: `5` (D208)
- `cliente.dNumIDRec`: `"0"` (D210)
- `cliente.razonSocial`: `"Sin Nombre"` (D211)

Restricción SIFEN: innominado aplica solo cuando el total de la operación es **menor** a `60.000.000` Gs. Si es igual o mayor, SIFEN exige identificar al receptor (ej. cédula).

Ejemplo de payload de cliente innominado:

```json
{
  "cliente": {
    "contribuyente": false,
    "razonSocial": "Sin Nombre",
    "tipoOperacion": 2,
    "pais": "PRY",
    "paisDescripcion": "Paraguay",
    "tipoContribuyente": 2,
    "iTipIDRec": 5,
    "dNumIDRec": "0"
  }
}
```

#### Respuesta

```json
{
  "success": true,
  "message": "DE preparado correctamente",
  "data": {
    "cdc": "01801676843001001000002522026032010000000251",
    "qrUrl": "https://ekuatia.set.gov.py/consultas/qr?nVersion=150&Id=...",
    "estado": "PREPARADO",
    "numero": "0000025",
    "establecimiento": "001",
    "punto": "001",
    "xmlFirmado": "<?xml version=\"1.0\" encoding=\"UTF-8\"?>...",
    "kude": null
  },
  "error": null
}
```

#### `POST /invoices/prepare/batch` — Preparar múltiples DEs

```bash
curl -X POST http://localhost:8000/invoices/prepare/batch \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '[
    { "data": { "tipoDocumento": 1, "numero": "0000025", ... } },
    { "data": { "tipoDocumento": 1, "numero": "0000026", ... } }
  ]'
```

Retorna un arreglo de `PrepareInvoiceResponse`.

### Fase 2 — Envío y consulta automáticos (background)

Los schedulers se ejecutan automáticamente y no requieren intervención del cliente:

| Scheduler | Intervalo | Función |
|-----------|-----------|---------|
| **BatchSenderService** | Cada 60s (configurable) | Agrupa DEs `PREPARADO` por empresa+tipo → envía lotes ≤50 a SIFEN → marca `ENVIADO` |
| **BatchPollerService** | Cada 10 min (configurable) | Consulta lotes `ENVIADO` con ≥10 min → actualiza estado (`APROBADO`/`RECHAZADO`) |

Los schedulers se habilitan/deshabilitan con la variable `SIFEN_BATCH_ENABLED`.

### Consulta de estado local

#### `GET /invoices/{cdc}/status` — Consultar estado de un DE

Consulta el estado persistido en la BD local. Opcionalmente refresca desde SIFEN si el estado no es final.

```bash
curl http://localhost:8000/invoices/01801676843001001000002522026032010000000251/status \
  -H "Authorization: Bearer $TOKEN"
```

#### Respuesta

```json
{
  "success": true,
  "data": {
    "cdc": "01801676843001001000002522026032010000000251",
    "estado": "APROBADO",
    "codigoEstado": "0260",
    "descripcionEstado": "Autorización del DE satisfactoria",
    "nroLote": "150123456789",
    "qrUrl": "https://ekuatia.set.gov.py/consultas/qr?nVersion=150&Id=...",
    "createdAt": "2026-03-20T10:30:00",
    "sentAt": "2026-03-20T10:31:00",
    "processedAt": "2026-03-20T10:41:00"
  }
}
```

#### Parámetro `?refresh=true`

Fuerza una consulta a SIFEN para DEs en estado no final (`PREPARADO`, `ENVIADO`):

```bash
curl "http://localhost:8000/invoices/{cdc}/status?refresh=true" \
  -H "Authorization: Bearer $TOKEN"
```

### Estados del ciclo de vida de un DE

| Estado | Descripción |
|--------|-------------|
| `PREPARADO` | XML firmado generado y persistido. Pendiente de envío a SIFEN. |
| `ENVIADO` | Incluido en un lote enviado a SIFEN. Pendiente de resultado. |
| `APROBADO` | SIFEN aprobó el DE (código `0260` o `0422`). |
| `APROBADO_CON_OBSERVACION` | SIFEN aprobó con observaciones (código `0261`). |
| `RECHAZADO` | SIFEN rechazó el DE (código `0262` o `0420`). |
| `ERROR` | Error en el envío o lote inexistente. El DE puede reintentar. |

---

## Buenas Prácticas SIFEN (Cumplidas por el Wrapper)

El flujo optimizado cumple con las recomendaciones oficiales de la DNIT para la integración con SIFEN:

### Lotes

1. **Máximo aprovechamiento de lotes:** los DEs se agrupan y envían en lotes de hasta 50 documentos (máximo permitido por SIFEN).
2. **Agrupación por tipo:** cada lote contiene DEs de un solo tipo de documento y un solo RUC emisor (requisito SIFEN, violarlo genera rechazo 0301).
3. **Sin CDC duplicado:** se verifica por constraint UNIQUE que un CDC no se envíe más de una vez. Enviar CDCs repetidos genera bloqueo temporal del RUC emisor (10-60 minutos).
4. **Sin lotes vacíos:** solo se envían lotes con DEs válidos en estado PREPARADO.

### Consulta de resultados

5. **Espera mínima de 10 minutos:** la primera consulta de un lote se realiza después de `minWaitBeforePoll` segundos (default: 600 = 10 min) desde el envío.
6. **Intervalos de consulta ≥ 10 minutos:** el scheduler consulta cada `pollInterval` ms (default: 600000 = 10 min).
7. **Consulta extemporánea:** lotes con más de 48 horas se consultan por CDC individual (SIFEN código 0364).
8. **Sin polling agresivo:** nunca se consulta antes de los 10 minutos, evitando sobrecargar SIFEN.

### Resiliencia

9. **Persistencia local:** todos los DEs se persisten en BD (`electronic_documents`). Si el wrapper se reinicia, los schedulers retoman el procesamiento automáticamente.
10. **Sin pérdida de datos:** si SIFEN no está disponible al enviar un lote, los DEs quedan en estado `PREPARADO` y se reintentan en el siguiente ciclo.
11. **Fallback a consulta individual:** cuando `consulta-lote` retorna código `0364` (extemporáneo), se pasa automáticamente a consulta por CDC individual.

### Rendimiento

12. **Preparación < 200ms:** la fase de preparación solo genera XML firmado localmente, sin comunicación HTTP con SIFEN.
13. **No bloquea el punto de venta:** el cajero recibe CDC + QR inmediatamente y puede imprimir sin esperar a SIFEN.
14. **Procesamiento desacoplado:** el envío y consulta a SIFEN son asíncronos y ejecutados por schedulers en background.

---

## Guía de Integración para Sistemas Clientes

### Migración del flujo antiguo al nuevo

El flujo antiguo (1 DE por lote + polling síncrono) debe reemplazarse por el flujo optimizado:

| Aspecto | Flujo Antiguo | Flujo Nuevo |
|---------|---------------|-------------|
| Endpoint de emisión | `POST /invoices/emit/batch` (1 DE) | `POST /invoices/prepare` |
| Espera por SIFEN | Polling síncrono (3s × 10 intentos) | Sin espera, respuesta inmediata |
| CDC/QR disponible | Solo después de respuesta SIFEN | Inmediatamente al preparar |
| Impresión de ticket | Bloqueada hasta respuesta | Inmediata con CDC + QR |
| Consulta de estado | `GET /invoices/batch/{nroLote}` | `GET /invoices/{cdc}/status` |
| Envío a SIFEN | Síncrono, 1 DE = 1 lote | Automático, hasta 50 DEs por lote |

### Paso a paso para integrar

#### 1. Preparar el DE al momento de la venta

En el momento de crear la venta/factura, llamar a `POST /invoices/prepare`:

```
POST /invoices/prepare
Authorization: Bearer {token} | X-API-Key: {apiKey}
Content-Type: application/json

{
  "data": {
    "tipoDocumento": 1,
    "establecimiento": "001",
    "punto": "001",
    "numero": "0000025",
    ...
  }
}
```

#### 2. Usar la respuesta para imprimir el ticket

La respuesta incluye todo lo necesario para imprimir:

- **`cdc`** — Código de Control del DE (44 caracteres). Guardar en la venta.
- **`qrUrl`** — URL para generar el código QR del ticket.
- **`estado`** — Será `PREPARADO` (el DE aún no fue enviado a SIFEN).
- **`xmlFirmado`** — XML completo firmado (opcional, para respaldo).

#### 3. Eliminar el polling síncrono

**Ya no es necesario** hacer polling desde el sistema cliente. Los schedulers del wrapper se encargan automáticamente de:

- Enviar los DEs preparados a SIFEN en lotes.
- Consultar los resultados de los lotes.
- Actualizar el estado final de cada DE.

#### 4. Consultar estado cuando sea necesario

Para verificar si un DE fue aprobado por SIFEN (por ejemplo, en un panel de administración o reporte):

```
GET /invoices/{cdc}/status
Authorization: Bearer {token} | X-API-Key: {apiKey}
```

Posibles estados en la respuesta:

| Estado | Acción sugerida |
|--------|-----------------|
| `PREPARADO` | Normal en los primeros minutos. El scheduler lo enviará. |
| `ENVIADO` | Enviado a SIFEN, pendiente de resultado. Esperar ~10 min. |
| `APROBADO` | DE aprobado por SIFEN. Todo OK. |
| `APROBADO_CON_OBSERVACION` | Aprobado pero revisar detalles. |
| `RECHAZADO` | Revisar `codigoEstado` y `descripcionEstado` para corregir. |
| `ERROR` | Error en el envío. Se reintentará automáticamente. |

#### 5. (Opcional) Forzar refresco desde SIFEN

Si necesita el estado actualizado al instante:

```
GET /invoices/{cdc}/status?refresh=true
```

Esto consulta directamente a SIFEN y actualiza la BD local.

### Compatibilidad hacia atrás

Los endpoints anteriores siguen funcionando sin cambios:

| Endpoint | Estado |
|----------|--------|
| `POST /invoices/emit` | Funcional (deprecado en PROD) |
| `POST /invoices/emit/batch` | Funcional |
| `GET /invoices/{cdc}` | Funcional (consulta directa a SIFEN) |
| `GET /invoices/batch/{nroLote}` | Funcional (consulta directa a SIFEN) |

Los nuevos endpoints son **complementarios** y se recomiendan como el flujo estándar de producción.

### Ejemplo completo de integración (pseudocódigo)

```python
# === Al momento de la venta ===
response = http.post("/invoices/prepare", {
    "data": {
        "tipoDocumento": 1,
        "establecimiento": "001",
        "punto": "001",
        "numero": next_number,
        "fecha": now(),
        "cliente": { ... },
        "items": [ ... ],
        "condicion": { ... }
    }
})

# Guardar CDC en la venta
venta.cdc = response.data.cdc
venta.qr_url = response.data.qrUrl
venta.save()

# Imprimir ticket inmediatamente con CDC + QR
imprimir_ticket(venta)


# === En un cron/job periódico (opcional) ===
ventas_pendientes = Venta.where(estado_sifen != "APROBADO")
for venta in ventas_pendientes:
    status = http.get(f"/invoices/{venta.cdc}/status")
    venta.estado_sifen = status.data.estado
    venta.save()
```

### Configuración de schedulers

Los schedulers se controlan mediante variables de entorno o propiedades en `application.yml`:

```yaml
sifen:
  batch:
    enabled: true                    # Habilitar/deshabilitar schedulers
    send-interval: 60000             # Intervalo de envío (ms) — default: 60s
    poll-interval: 600000            # Intervalo de consulta (ms) — default: 10 min
    max-per-lote: 50                 # Máximo DEs por lote — default: 50
    min-wait-before-poll: 600        # Espera mínima antes de consultar (seg) — default: 10 min
    max-poll-age-hours: 48           # Máx horas para consulta por lote — default: 48h
```

> Para deshabilitar los schedulers (por ejemplo en entorno de desarrollo), establecer `SIFEN_BATCH_ENABLED=false`.

---

## Gestión de Empresas

Cada empresa opera con su propia configuración SIFEN (certificado, CSC, ambiente). La configuración se gestiona vía API:

### Regla de unicidad (RUC repetido)

- Se pueden crear múltiples empresas con el mismo `ruc` y `dv`.
- No existe una restricción de unicidad por `ruc`, `dv`, `ambiente` o `nombre` a nivel de empresa.
- La separación de configuración y operaciones se realiza por `companyId`.

### Nota Técnica 13 (dBasExe)

- El campo `habilitarNt13` controla si se incluye `dBasExe` dentro de `gCamIVA`.
- En ambientes donde SIFEN responde `Elemento esperado: dBasExe dentro de: gCamIVA`, este flag debe estar en `true`.
- En ambientes donde SIFEN responde `Elemento no esperado: dBasExe`, este flag debe estar en `false`.
- Valor por defecto del proyecto: `true`.

### Crear empresa

```bash
curl -X POST http://localhost:8000/companies \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "nombre": "Mi Empresa S.A.",
    "ruc": "80167684",
    "dv": "3",
    "ambiente": "DEV",
    "habilitarNt13": true
  }'
```

### Actualizar empresa (perfil operativo)

El endpoint `PUT /companies/{id}` permite actualizar `nombre`, `ambiente` y `habilitarNt13`.

Ejemplo:

```bash
curl -X PUT http://localhost:8000/companies/1 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "nombre": "Mi Empresa S.A. - Sucursal Centro",
    "ambiente": "DEV",
    "habilitarNt13": true
  }'
```

### Subir certificado PFX

```bash
curl -X POST http://localhost:8000/companies/1/certificate \
  -H "Authorization: Bearer $TOKEN" \
  -F "certificate=@mi_certificado.pfx" \
  -F "password=mi_password"
```

### Configurar CSC

```bash
curl -X PUT http://localhost:8000/companies/1/csc \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"cscId": "0001", "cscValor": "ABCD0000000000000000000000000000"}'
```

### Configurar datos del emisor

Al configurar los datos del emisor en la empresa, ya **no es necesario** enviar el campo `params` en cada request de facturación. Se toman automáticamente de la empresa.

```bash
curl -X PUT http://localhost:8000/companies/1/emisor \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "version": 150,
    "ruc": "80167684-3",
    "razonSocial": "MIAF E.A.S. UNIPERSONAL",
    "nombreFantasia": "MIAF E.A.S.",
    "actividadesEconomicas": [
      {"codigo": "13990", "descripcion": "FABRICACIÓN DE OTROS PRODUCTOS TEXTILES N.C.P."}
    ],
    "timbradoNumero": "80167684",
    "timbradoFecha": "2026-02-24",
    "tipoContribuyente": 2,
    "tipoRegimen": 8,
    "establecimientos": [{
      "codigo": "001",
      "direccion": "Avda. Mariscal Lopez",
      "numeroCasa": "1234",
      "departamento": 1,
      "departamentoDescripcion": "CAPITAL",
      "distrito": 1,
      "distritoDescripcion": "ASUNCION (DISTRITO)",
      "ciudad": 1,
      "ciudadDescripcion": "ASUNCION (DISTRITO)",
      "telefono": "021123456",
      "email": "facturacion@miempresa.com.py",
      "denominacion": "CASA MATRIZ"
    }]
  }'
```

### Consultar datos del emisor

```bash
curl http://localhost:8000/companies/1/emisor \
  -H "Authorization: Bearer $TOKEN"
```

---

## Tipos de Documento (`tipoDocumento`)

| Código | Descripción |
|--------|-------------|
| 1 | Factura Electrónica |
| 4 | Auto-factura Electrónica |
| 5 | Nota de Crédito Electrónica |
| 6 | Nota de Débito Electrónica |
| 7 | Nota de Remisión Electrónica |

## Ejemplo completo: Emisión de Factura Electrónica (solo DEV)

> **Nota:** Todos los endpoints de `/invoices/**` requieren autenticación (JWT o API Key).
> El certificado, CSC y ambiente se toman de la empresa asociada al usuario/key autenticado.
>
> **`params` es opcional** si la empresa tiene configurados los datos del emisor (vía `PUT /companies/{id}/emisor`). Si se envía `params` en el request, tiene prioridad sobre la configuración almacenada.
>
> **Importante:** en `PROD`, `/invoices/emit` está deprecado y puede devolver `RUC del emisor no está habilitado para utilizar este tipo de servicio`.

### Solicitud

```bash
curl -X POST http://localhost:8000/invoices/emit \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "params": {
      "version": 150,
      "ruc": "80167684-3",
      "razonSocial": "MIAF E.A.S. UNIPERSONAL",
      "nombreFantasia": "MIAF E.A.S.",
      "actividadesEconomicas": [
        {"codigo": "13990", "descripcion": "FABRICACIÓN DE OTROS PRODUCTOS TEXTILES N.C.P ."}
      ],
      "timbradoNumero": "80167684",
      "timbradoFecha": "2026-02-24",
      "tipoContribuyente": 2,
      "tipoRegimen": 8,
      "establecimientos": [{
        "codigo": "001",
        "direccion": "Avda. Mariscal Lopez",
        "numeroCasa": "1234",
        "departamento": 1,
        "departamentoDescripcion": "CAPITAL",
        "distrito": 1,
        "distritoDescripcion": "ASUNCION (DISTRITO)",
        "ciudad": 1,
        "ciudadDescripcion": "ASUNCION (DISTRITO)",
        "telefono": "021123456",
        "email": "facturacion@miempresa.com.py",
        "denominacion": "CASA MATRIZ"
      }]
    },
    "data": {
      "tipoDocumento": 1,
      "establecimiento": "001",
      "punto": "001",
      "numero": "0000015",
      "descripcion": "Factura electrónica de prueba",
      "observacion": "Ambiente test SIFEN",
      "fecha": "2026-03-04T10:09:00",
      "tipoEmision": 1,
      "tipoTransaccion": 1,
      "tipoImpuesto": 1,
      "moneda": "PYG",
      "cliente": {
        "contribuyente": true,
        "ruc": "80069563-1",
        "razonSocial": "TIPS S.A",
        "tipoOperacion": 1,
        "direccion": "Avda. Eusebio Ayala",
        "numeroCasa": "123",
        "departamento": 1,
        "departamentoDescripcion": "CAPITAL",
        "distrito": 1,
        "distritoDescripcion": "ASUNCION (DISTRITO)",
        "ciudad": 1,
        "ciudadDescripcion": "ASUNCION (DISTRITO)",
        "pais": "PRY",
        "paisDescripcion": "Paraguay",
        "tipoContribuyente": 2,
        "telefono": "0981123456",
        "email": "compras@retail.com.py",
        "codigo": "CLI-0001"
      },
      "factura": {"presencia": 1, "fechaEnvio": "2026-03-05"},
      "condicion": {
        "tipo": 1,
        "entregas": [{"tipo": 1, "monto": 10000, "moneda": "PYG"}]
      },
      "items": [{
        "codigo": "SKU-001",
        "descripcion": "Servicio de implementacion",
        "cantidad": 1,
        "precioUnitario": 10000,
        "ivaTipo": 1,
        "ivaProporcion": 100,
        "iva": 10,
        "unidadMedida": 77
      }]
    },
    "qr": {"idCSC": "0001", "csc": "ABCD0000000000000000000000000000"},
    "includeKude": false
  }'
```

### Respuesta exitosa (APROBADO)

```json
{
  "success": true,
  "message": "Documento electrónico enviado correctamente",
  "data": {
    "cdc": "01801676843001001000001522026030410000000154",
    "estado": "APROBADO",
    "codigoEstado": "0260",
    "descripcionEstado": "Autorización del DE satisfactoria",
    "xml": "<?xml version=\"1.0\" encoding=\"UTF-8\"?>...",
    "qrUrl": "https://ekuatia.set.gov.py/consultas-test/qr?nVersion=150&Id=...",
    "kude": null,
    "mensajes": [
      {
        "codigo": "0260",
        "descripcion": "Autorización del DE satisfactoria"
      }
    ],
    "respuestaSifen": {
      "codigoRespuesta": 200,
      "descripcionRespuesta": null,
      "xmlRespuesta": "<?xml version=\"1.0\" encoding=\"UTF-8\"?>..."
    }
  },
  "error": null
}
```

### Respuesta con error (ejemplo: CDC Inválido)

```json
{
  "success": true,
  "message": "Documento electrónico enviado correctamente",
  "data": {
    "cdc": "01801676843001001000000322026022410000030",
    "estado": "RECHAZADO",
    "codigoEstado": "0160",
    "descripcionEstado": "XML malformado [CDC Inválido]",
    "mensajes": [
      {
        "codigo": "0160",
        "descripcion": "XML malformado [CDC Inválido]"
      }
    ]
  },
  "error": null
}
```

### Respuesta con error de servidor

```json
{
  "success": false,
  "message": null,
  "data": null,
  "error": {
    "codigo": "SIFEN_ERROR",
    "descripcion": "No se puede cargar el certificado de cliente: ..."
  }
}
```

## Consulta DE por CDC

El endpoint `GET /invoices/{cdc}` consulta a SIFEN los datos completos de un Documento Electrónico a partir de su CDC (44 caracteres). La respuesta incluye los datos parseados del DE: emisor, receptor, ítems, totales, condición de pago, URL del QR, protocolo de autorización, etc.

### Ejemplo: consultar DE

```bash
curl http://localhost:8000/invoices/01801676843001001000001522026030410000000154 \
  -H "Authorization: Bearer $TOKEN"
```

### Respuesta exitosa

```json
{
  "success": true,
  "data": {
    "cdc": "01801676843001001000001522026030410000000154",
    "estado": "APROBADO",
    "codigoEstado": "0260",
    "descripcionEstado": "Autorización del DE satisfactoria",
    "qrUrl": "https://ekuatia.set.gov.py/consultas/qr?nVersion=150&Id=...",
    "protocoloAutorizacion": "12345678901234567890",
    "fechaProcesamiento": "2026-03-04T10:15:00",
    "documento": {
      "cdc": "01801676843001001000001522026030410000000154",
      "fechaEmision": "2026-03-04T10:09:00",
      "fechaFirma": "2026-03-04T10:09:00",
      "tipoEmision": 1,
      "tipoEmisionDescripcion": "Normal",
      "codigoSeguridad": "000000154",
      "qrUrl": "https://ekuatia.set.gov.py/consultas/qr?nVersion=150&Id=...",
      "timbrado": {
        "tipoDocumento": 1,
        "tipoDocumentoDescripcion": "Factura electrónica",
        "numero": 80167684,
        "establecimiento": "001",
        "puntoExpedicion": "001",
        "numeroDocumento": "0000015",
        "fechaInicioVigencia": "2026-02-24"
      },
      "emisor": {
        "ruc": "80167684",
        "dv": "3",
        "tipoContribuyente": 2,
        "tipoContribuyenteDescripcion": "Persona Jurídica",
        "tipoRegimen": 8,
        "tipoRegimenDescripcion": "Régimen Turismo",
        "razonSocial": "MIAF E.A.S. UNIPERSONAL",
        "nombreFantasia": "MIAF E.A.S.",
        "direccion": "Avda. Mariscal Lopez",
        "numeroCasa": "1234",
        "departamento": 1,
        "departamentoDescripcion": "CAPITAL",
        "distrito": 1,
        "distritoDescripcion": "ASUNCION (DISTRITO)",
        "ciudad": 1,
        "ciudadDescripcion": "ASUNCION (DISTRITO)",
        "telefono": "021123456",
        "email": "facturacion@miempresa.com.py",
        "denominacionSucursal": "CASA MATRIZ",
        "actividadesEconomicas": [
          {"codigo": "13990", "descripcion": "FABRICACIÓN DE OTROS PRODUCTOS TEXTILES N.C.P."}
        ]
      },
      "receptor": {
        "ruc": "80069563",
        "dv": 1,
        "razonSocial": "TIPS S.A",
        "direccion": "Avda. Eusebio Ayala",
        "numeroCasa": 123,
        "departamento": 1,
        "departamentoDescripcion": "CAPITAL",
        "distrito": 1,
        "distritoDescripcion": "ASUNCION (DISTRITO)",
        "ciudad": 1,
        "ciudadDescripcion": "ASUNCION (DISTRITO)",
        "telefono": "0981123456",
        "email": "compras@retail.com.py",
        "codigoCliente": "CLI-0001",
        "tipoOperacion": 1,
        "tipoOperacionDescripcion": "B2B",
        "tipoContribuyente": 2,
        "tipoContribuyenteDescripcion": "Persona Jurídica"
      },
      "tipoTransaccion": 1,
      "tipoTransaccionDescripcion": "Venta de mercadería",
      "tipoImpuesto": 1,
      "tipoImpuestoDescripcion": "IVA",
      "moneda": "PYG",
      "condicion": {
        "tipo": 1,
        "tipoDescripcion": "Contado",
        "entregas": [
          {"tipoPago": 1, "tipoPagoDescripcion": "Efectivo", "monto": 10000, "moneda": "PYG"}
        ]
      },
      "items": [
        {
          "codigo": "SKU-001",
          "descripcion": "Servicio de implementacion",
          "unidadMedida": 77,
          "unidadMedidaDescripcion": "Servicio",
          "cantidad": 1,
          "precioUnitario": 10000,
          "totalBruto": 10000,
          "afectacionIva": 1,
          "afectacionIvaDescripcion": "Gravado IVA",
          "proporcionIva": 100,
          "tasaIva": 10,
          "baseGravadaIva": 9091,
          "liquidacionIva": 909,
          "baseExenta": null
        }
      ],
      "totales": {
        "subtotalExenta": 0,
        "subtotalExonerada": 0,
        "subtotalIva5": 0,
        "subtotalIva10": 10000,
        "totalOperacion": 10000,
        "totalDescuento": 0,
        "totalAnticipo": 0,
        "redondeo": 0,
        "totalGeneral": 10000,
        "liquidacionIva5": 0,
        "liquidacionIva10": 909,
        "totalIva": 909,
        "baseGravada5": 0,
        "baseGravada10": 9091,
        "totalBaseGravada": 9091,
        "totalGuaranies": 10000
      }
    },
    "respuestaSifen": {
      "codigoRespuesta": 200,
      "xmlRespuesta": "<?xml version=\"1.0\" encoding=\"UTF-8\"?>..."
    }
  }
}
```

> **Nota:** Los campos dentro de `documento` se obtienen del XML del DE parseado por SIFEN. Si el DE no fue encontrado o fue rechazado, `documento` será `null` y solo se devolverán `estado`, `codigoEstado` y `descripcionEstado`.

---

## Envío de Lotes (asíncrono)

El endpoint de lotes permite enviar múltiples DE en una sola operación y luego consultar su procesamiento.

Flujo recomendado:

1. Enviar lote con `POST /invoices/emit/batch`.
2. Guardar `nroLote` de la respuesta.
3. Consultar periódicamente con `GET /invoices/batch/{nroLote}` hasta obtener estado final del lote.

Notas importantes:

- El body de `POST /invoices/emit/batch` es un arreglo de objetos `EmitirFacturaRequest`.
- En cada item, `params` puede omitirse si la empresa ya tiene emisor configurado (`PUT /companies/{id}/emisor`).
- La consulta de lote devuelve `nroLote` igual al solicitado en la URL, más el estado consolidado y el detalle por CDC en `resultados`.

### Ejemplo: enviar lote

```bash
curl -X POST http://localhost:8000/invoices/emit/batch \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '[
    {
      "data": {
        "tipoDocumento": 1,
        "establecimiento": "001",
        "punto": "001",
        "numero": "0000020",
        "descripcion": "Factura lote 1",
        "fecha": "2026-03-17T16:30:00",
        "tipoEmision": 1,
        "tipoTransaccion": 1,
        "tipoImpuesto": 1,
        "moneda": "PYG",
        "cliente": {
          "contribuyente": true,
          "ruc": "80069563-1",
          "razonSocial": "TIPS S.A",
          "tipoOperacion": 1,
          "direccion": "Asuncion",
          "numeroCasa": "123",
          "departamento": 1,
          "departamentoDescripcion": "CAPITAL",
          "distrito": 1,
          "distritoDescripcion": "ASUNCION",
          "ciudad": 1,
          "ciudadDescripcion": "ASUNCION",
          "pais": "PRY",
          "tipoContribuyente": 2,
          "codigo": "CLI-01"
        },
        "factura": {"presencia": 1},
        "condicion": {"tipo": 1, "entregas": [{"tipo": 1, "monto": 10000, "moneda": "PYG"}]},
        "items": [{"codigo": "SKU-1", "descripcion": "Servicio", "cantidad": 1, "precioUnitario": 10000, "ivaTipo": 1, "iva": 10, "ivaProporcion": 100, "unidadMedida": 77}]
      }
    },
    {
      "data": {
        "tipoDocumento": 1,
        "establecimiento": "001",
        "punto": "001",
        "numero": "0000021",
        "descripcion": "Factura lote 2",
        "fecha": "2026-03-17T16:31:00",
        "tipoEmision": 1,
        "tipoTransaccion": 1,
        "tipoImpuesto": 1,
        "moneda": "PYG",
        "cliente": {
          "contribuyente": true,
          "ruc": "80069563-1",
          "razonSocial": "TIPS S.A",
          "tipoOperacion": 1,
          "direccion": "Asuncion",
          "numeroCasa": "123",
          "departamento": 1,
          "departamentoDescripcion": "CAPITAL",
          "distrito": 1,
          "distritoDescripcion": "ASUNCION",
          "ciudad": 1,
          "ciudadDescripcion": "ASUNCION",
          "pais": "PRY",
          "tipoContribuyente": 2,
          "codigo": "CLI-01"
        },
        "factura": {"presencia": 1},
        "condicion": {"tipo": 1, "entregas": [{"tipo": 1, "monto": 15000, "moneda": "PYG"}]},
        "items": [{"codigo": "SKU-2", "descripcion": "Servicio premium", "cantidad": 1, "precioUnitario": 15000, "ivaTipo": 1, "iva": 10, "ivaProporcion": 100, "unidadMedida": 77}]
      }
    }
  ]'
```

### Respuesta típica: recepción de lote

```json
{
  "success": true,
  "message": "Lote enviado correctamente",
  "data": {
    "nroLote": "150123456789",
    "estado": "LOTE_RECIBIDO",
    "codigoEstado": "0300",
    "descripcionEstado": "Lote recibido correctamente"
  },
  "error": null
}
```

### Ejemplo: consultar estado de lote

```bash
curl http://localhost:8000/invoices/batch/150123456789 \
  -H "Authorization: Bearer $TOKEN"
```

### Respuesta típica: lote concluido

```json
{
  "success": true,
  "data": {
    "nroLote": "150123456789",
    "estado": "LOTE_CONCLUIDO",
    "codigoEstado": "0362",
    "descripcionEstado": "Lote procesado",
    "mensajes": [
      {
        "codigo": "0362",
        "descripcion": "Lote procesado"
      }
    ],
    "resultados": [
      {
        "cdc": "01801676843001001000002022026031710000000111",
        "estado": "Aprobado",
        "descripcion": "Autorización del DE satisfactoria"
      },
      {
        "cdc": "01801676843001001000002122026031710000000112",
        "estado": "Rechazado",
        "descripcion": "XML Mal Formado."
      }
    ]
  }
}
```

## Campos importantes del request

### `data.numero` (string, 7 dígitos)

Número del documento electrónico. Se usa junto con el establecimiento y punto de expedición
para formar el identificador. Ejemplo: `"0000015"`.

### `data.codigoSeguridad` (string, opcional)

Código de seguridad de 9 dígitos (`dCodSeg` en el Manual Técnico). Si no se envía,
se genera automáticamente a partir de `data.numero` rellenando con ceros a la izquierda.
Este campo forma parte del CDC (posiciones 35-43).

### `data.condicion.entregas[].monto`

El monto total de las entregas de pago **debe coincidir** con el total del documento
(suma de `precioUnitario × cantidad` de todos los items) cuando la condición es Contado (tipo=1).

### Estructura del CDC (44 posiciones)

| Posición | Campo | Dígitos | Descripción |
|----------|-------|---------|-------------|
| 1-2 | iTiDE | 2 | Tipo de documento electrónico |
| 3-10 | dRucEm | 8 | RUC del emisor (sin DV) |
| 11 | dDVEmi | 1 | Dígito verificador del emisor |
| 12-14 | dEst | 3 | Código de establecimiento |
| 15-17 | dPunExp | 3 | Punto de expedición |
| 18-24 | dNumDoc | 7 | Número del documento |
| 25 | iTipCont | 1 | Tipo de contribuyente |
| 26-33 | dFeEmiDE | 8 | Fecha de emisión (YYYYMMDD) |
| 34 | iTipEmi | 1 | Tipo de emisión |
| 35-43 | dCodSeg | 9 | Código de seguridad |
| 44 | dDVId | 1 | Dígito verificador del CDC |

## Códigos de estado SIFEN

| Código | Estado | Descripción |
|--------|--------|-------------|
| `0260` | APROBADO | Autorización del DE satisfactoria |
| `0261` | APROBADO_CON_OBSERVACION | Aprobado con observaciones |
| `0262` | RECHAZADO | Documento rechazado |
| `0600` | EVENTO_APROBADO | Evento registrado correctamente |
| `0160` | XML_MALFORMADO | Error en la estructura XML |
| `0300` | LOTE_RECIBIDO | Lote recibido correctamente |
| `0301` | LOTE_RECHAZADO | Lote rechazado |
| `0362` | LOTE_CONCLUIDO | Lote procesado y con detalle por CDC disponible |

## Códigos de error del wrapper

| Código | HTTP Status | Descripción |
|--------|-------------|-------------|
| `SIFEN_ERROR` | 502 | Error de comunicación o respuesta de SIFEN |
| `INVALID_REQUEST` | 400 | Argumento inválido en la solicitud |
| `INVALID_JSON` | 400 | JSON del body malformado o tipos incorrectos |
| `VALIDATION_ERROR` | 400 | Error de validación de campos |
| `MISSING_FIELD` | 400 | Campo obligatorio nulo |
| `INTERNAL_ERROR` | 500 | Error interno no clasificado |

## Tipos de Evento (`tipoEvento`)

| Código | Descripción | Campos obligatorios |
|--------|-------------|---------------------|
| 1 | Cancelación | `cdc`, `motivo` |
| 2 | Inutilización | `timbrado`, `establecimiento`, `puntoExpedicion`, `numeroDesde`, `numeroHasta`, `tipoDocumento`, `motivo` |
| 3 | Conformidad del receptor | `cdc`, `tipoConformidad` (1=Total, 2=Parcial), `fechaRecepcion` |
| 4 | Disconformidad del receptor | `cdc`, `motivo` |
| 5 | Desconocimiento del receptor | `cdc`, `motivo` + datos receptor |
| 6 | Notificación de recepción | `cdc` + datos receptor + `totalGs` |

### Estructura XML de eventos (v150) validada

La estructura aceptada por SIFEN para `rEnviEventoDe` (WS `siRecepEvento_v150`) es:

- `rEve` contiene `dFecFirma`, `dVerFor` y `gGroupTiEvt` (en ese orden).
- El atributo `Id` de `rEve` es numérico secuencial (`tdIdEve`, hasta 10 dígitos).
- Para cancelación, el CDC (44 dígitos) va en `gGroupTiEvt/rGeVeCan/Id`.
- El bloque `Signature` referencia `#<Id de rEve>`.
- No incluir nodos extra en `rEve/gGroupTiEvt` como `dTiGDE`, `iTiEvt` o `dDesTiEvt`.

Ejemplo referencial (cancelación):

```xml
<rEve Id="1">
  <dFecFirma>2026-03-17T17:01:49</dFecFirma>
  <dVerFor>150</dVerFor>
  <gGroupTiEvt>
    <rGeVeCan>
      <Id>01005324815001002000000322026031710000000035</Id>
      <mOtEve>Cancelación por error en datos del receptor</mOtEve>
    </rGeVeCan>
  </gGroupTiEvt>
</rEve>
```

### Reglas operativas para cancelación

- Plazo legal: una FE puede cancelarse dentro de las 48 horas desde su aprobación.
- Estado del DTE: debe estar aprobado (`0260`) o aprobado con observación (`0261`).
- Restricción por conformidad: no procede cancelación si el receptor ya registró conformidad.
- Secuencia: si hay documentos asociados (por ejemplo, notas), cancelar del último al primero.

### Ejemplo: Cancelación de un DE

```bash
curl -X POST http://localhost:8000/invoices/events \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "tipoEvento": 1,
    "cdc": "01801676843001001000001522026030410000000154",
    "motivo": "Cancelación por error en datos del receptor"
}'
```

### Ejemplo: Inutilización de rango de documentos

```bash
curl -X POST http://localhost:8000/invoices/events \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "tipoEvento": 2,
    "timbrado": 80167684,
    "establecimiento": "001",
    "puntoExpedicion": "001",
    "numeroDesde": "0000001",
    "numeroHasta": "0000010",
    "tipoDocumento": 1,
    "motivo": "Numeración no utilizada"
}'
```

### Ejemplo: Conformidad del receptor

```bash
curl -X POST http://localhost:8000/invoices/events \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "tipoEvento": 3,
    "cdc": "01801676843001001000001522026030410000000154",
    "tipoConformidad": 1,
    "fechaRecepcion": "2026-03-04T10:00:00"
}'
```

## Estructura del Proyecto

```
sifen-wrapper/
├── src/main/java/com/ratones/sifenwrapper/
│   ├── SifenWrapperApplication.java       # @EnableScheduling
│   ├── config/
│   │   ├── SecurityConfig.java           # SecurityFilterChain, BCrypt, filtros
│   │   ├── SecurityProperties.java       # Props: jwt.secret, encryption.key
│   │   ├── AdminSeeder.java              # Crea empresa + usuario admin por defecto
│   │   ├── BatchProperties.java          # Props: sifen.batch.* (schedulers)
│   │   ├── SifenConfiguration.java       # (legacy, delegado a SifenConfigFactory)
│   │   └── SifenProperties.java          # (legacy, sin uso activo)
│   ├── controller/
│   │   ├── AuthController.java           # POST /auth/login, /auth/refresh
│   │   ├── CompanyController.java        # CRUD /companies, certificado, CSC
│   │   ├── ApiKeyController.java         # CRUD /api-keys
│   │   └── InvoiceController.java        # Endpoints SIFEN + prepare + status
│   ├── security/
│   │   ├── TenantContext.java            # ThreadLocal<Long> companyId
│   │   ├── TenantFilter.java             # Setea TenantContext desde auth
│   │   ├── jwt/
│   │   │   ├── JwtService.java           # Generación/validación JWT (HMAC-SHA)
│   │   │   └── JwtAuthenticationFilter.java
│   │   └── apikey/
│   │       └── ApiKeyAuthenticationFilter.java  # Auth por X-API-Key header
│   ├── entity/
│   │   ├── Company.java                  # Empresa con cert, CSC, ambiente
│   │   ├── User.java                     # Usuario con role ADMIN/USER
│   │   ├── ApiKey.java                   # API Key (hash SHA-256)
│   │   ├── AuditLog.java                 # Log de auditoría
│   │   └── ElectronicDocument.java       # DE persistido localmente (prepare+batch)
│   ├── repository/
│   │   ├── CompanyRepository.java
│   │   ├── UserRepository.java
│   │   ├── ApiKeyRepository.java
│   │   ├── AuditLogRepository.java
│   │   └── ElectronicDocumentRepository.java  # Queries para batch/polling
│   ├── service/
│   │   ├── AuthService.java              # Login, refresh token
│   │   ├── CompanyService.java           # CRUD empresas + cifrado
│   │   ├── CertificateService.java       # Validación de PFX
│   │   ├── ApiKeyService.java            # Creación/búsqueda de API Keys
│   │   ├── EncryptionService.java        # AES-256-GCM cifrado/descifrado
│   │   ├── SifenConfigFactory.java       # Multi-tenant SifenConfig + caché
│   │   ├── InvoiceService.java           # Lógica SIFEN + prepararDE + consultas
│   │   ├── KudeService.java              # Generación de KUDE (PDF)
│   │   ├── BatchSenderService.java       # Scheduler: envío automático de lotes
│   │   └── BatchPollerService.java       # Scheduler: consulta automática de lotes
│   ├── mapper/
│   │   └── SifenMapper.java              # DTO → tipos de rshk-jsifenlib
│   ├── dto/
│   │   ├── auth/                         # LoginRequest, LoginResponse, RefreshRequest
│   │   ├── company/                      # CreateCompanyRequest, CompanyResponse, etc.
│   │   ├── apikey/                       # CreateApiKeyRequest, ApiKeyResponse
│   │   ├── request/                      # DTOs de facturación (existentes)
│   │   └── response/                     # PrepareInvoiceResponse, DocumentStatusResponse, etc.
│   ├── exception/
│   │   ├── SifenServiceException.java
│   │   └── GlobalExceptionHandler.java
│   ├── patch/
│       ├── TgCamIVAPatched.java         # Parche: campo dBasExe en gCamIVA (NT13)
│       ├── TgGroupTiEvtPatched.java     # Parche: estructura de eventos SIFEN v150
│       └── TgTotSubPatched.java         # Parche: totales del documento (gTotSub)
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/
│       ├── V1__create_companies_table.sql
│       ├── V2__create_users_table.sql
│       ├── V3__create_api_keys_table.sql
│       ├── V4__create_audit_log_table.sql
│       ├── V5__add_emisor_config_to_companies.sql
│       ├── V6__disable_nt13_default.sql
│       ├── V7__enable_nt13_default.sql
│       ├── V8__create_electronic_documents_table.sql
│       ├── V9__allow_duplicate_ruc_with_operational_profile.sql
│       └── V10__remove_company_operational_unique_index.sql
└── pom.xml
```

## Dependencias clave

| Dependencia | Versión | Propósito |
|-------------|---------|----------|
| `rshk-jsifenlib` | 0.2.4 | Librería SIFEN Paraguay |
| `spring-boot-starter-data-jpa` | (managed) | Persistencia JPA/Hibernate |
| `postgresql` | (managed) | Driver PostgreSQL |
| `flyway-core` | (managed) | Migraciones de BD |
| `spring-boot-starter-security` | (managed) | Spring Security |
| `jjwt-api` / `jjwt-impl` / `jjwt-jackson` | 0.12.6 | JWT |
| `caffeine` | (managed) | Caché en memoria |
| `openpdf` | 2.0.3 | Generación KUDE (PDF) |
| `zxing-core` | 3.5.3 | QR Code |

## KUDE (Constancia de Documento Electrónico)

El KUDE es la representación gráfica en PDF del Documento Electrónico, generada localmente
con **OpenPDF** (sin depender de servicios externos). Incluye:
- Datos del emisor (razón social, RUC, dirección, actividad económica)
- Tipo y número de documento, timbrado, fecha
- Datos del receptor (nombre, RUC, dirección)
- Tabla de ítems con columnas: Código, Descripción, Cant., P. Unit., Exenta, IVA 5%, IVA 10%
- Totales y liquidación de IVA
- Condición de venta
- QR Code generado con ZXing
- CDC formateado y estado SIFEN (APROBADO/RECHAZADO)

### Logo de empresa en KUDE

Podés incluir el logo en el encabezado enviando el campo opcional `params.logoBase64`.

- Acepta base64 puro (`iVBORw0KGgo...`) o Data URI (`data:image/png;base64,...`).
- Si el base64 es inválido, el KUDE se genera igual (solo sin logo).

Ejemplo mínimo:

```json
{
  "params": {
    "ruc": "80167684-3",
    "razonSocial": "MIAF E.A.S. UNIPERSONAL",
    "logoBase64": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAA..."
  },
  "data": {
    "tipoDocumento": 1
  }
}
```

### Opción 1: KUDE incluido en la emisión (solo DEV)

Enviar `"includeKude": true` en el request de emisión:

> En `PROD`, esta opción depende de `/invoices/emit` (síncrono), por lo que aplica la misma restricción de deprecación.

```bash
curl -X POST http://localhost:8000/invoices/emit \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "includeKude": true,
    "params": { ... },
    "data": { ... }
  }'
```

La respuesta incluirá el campo `kude` con el PDF en base64:

```json
{
  "success": true,
  "data": {
    "cdc": "01801676843001001000001...",
    "estado": "APROBADO",
    "kude": "JVBERi0xLjQKMyAwIG9iago8...",
    ...
  }
}
```

### Opción 2: Descargar KUDE como PDF (independiente)

Para generar el KUDE de un documento ya emitido, usar el endpoint dedicado:

```bash
curl -X POST http://localhost:8000/invoices/kude \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -o factura.pdf \
  -d '{
    "cdc": "01801676843001001000001522026030410000000154",
    "qrUrl": "https://ekuatia.set.gov.py/consultas/qr?nVersion=150&Id=01801676843001001000001522026030410000000154&dFeEmiDE=MjAyNS0wMy0wMVQxMDoxMTowMA==&...",
    "estado": "APROBADO",
    "codigoEstado": "0260",
    "descripcionEstado": "Aprobado",
    "params": {
      "version": 150,
      "ruc": "80167684-3",
      "razonSocial": "MIAF E.A.S. UNIPERSONAL",
      "nombreFantasia": "MIAF E.A.S.",
      "actividadesEconomicas": [
        {"codigo": "13990", "descripcion": "FABRICACIÓN DE OTROS PRODUCTOS TEXTILES N.C.P."}
      ],
      "timbradoNumero": "15220260",
      "timbradoFecha": "2024-06-06",
      "tipoContribuyente": 1,
      "tipoRegimen": 8,
      "establecimientos": [{
        "codigo": "001",
        "direccion": "AVDA. GRAL. BERNARDINO CABALLERO C/ 14 DE MAYO",
        "numeroCasa": "0",
        "departamento": 1,
        "departamentoDescripcion": "CAPITAL",
        "distrito": 1,
        "distritoDescripcion": "ASUNCION (DISTRITO)",
        "ciudad": 1,
        "ciudadDescripcion": "ASUNCION (DISTRITO)",
        "telefono": "0971123456",
        "email": "contacto@miaf.com.py"
      }]
    },
    "data": {
      "tipoDocumento": 1,
      "establecimiento": "001",
      "punto": "001",
      "numero": "0000003",
      "fecha": "2025-03-01T10:11:00",
      "tipoEmision": 1,
      "tipoTransaccion": 2,
      "tipoImpuesto": 1,
      "moneda": "PYG",
      "cliente": {
        "contribuyente": true,
        "ruc": "80089752-3",
        "razonSocial": "EMPRESA CLIENTE S.A.",
        "tipoOperacion": 1,
        "direccion": "Calle Principal 123",
        "numeroCasa": "456",
        "departamento": 1,
        "departamentoDescripcion": "CAPITAL",
        "distrito": 1,
        "distritoDescripcion": "ASUNCION",
        "ciudad": 1,
        "ciudadDescripcion": "ASUNCION",
        "pais": "PRY",
        "tipoContribuyente": 1,
        "telefono": "021555555",
        "email": "cliente@empresa.com.py",
        "codigo": "CLI001"
      },
      "condicion": {
        "tipo": 1,
        "entregas": [{
          "tipo": 1,
          "monto": 10000,
          "moneda": "PYG"
        }]
      },
      "items": [{
        "codigo": "SRV001",
        "descripcion": "Servicio de consultoría",
        "cantidad": 1,
        "precioUnitario": 10000,
        "unidadMedida": 77,
        "ivaTipo": 1,
        "iva": 10,
        "ivaProporcion": 100
      }],
      "factura": {
        "presencia": 1
      }
    }
  }'
```

Esto descarga directamente el archivo con el nombre del CDC (ej: `01801676843001001000001522026030410000000154.pdf`).

### Opción 3: KUDE como base64 en JSON

```bash
curl -X POST http://localhost:8000/invoices/kude/base64 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ ... mismo body que opción 2 ... }'
```

Respuesta:

```json
{
  "success": true,
  "message": "KUDE generado correctamente",
  "data": {
    "kude": "JVBERi0xLjQKMyAwIG9iago8...",
    "cdc": "01801676843001001000001522026030410000000154",
    "contentType": "application/pdf"
  }
}
```

## Seguridad

- **JWT:** Access tokens (1h) + Refresh tokens (7d), firmados con HMAC-SHA. Claims: `sub` (userId), `companyId`, `role`.
- **API Keys:** Prefijo `sw_live_` + random base64url. Se almacena solo el hash SHA-256. El key raw se muestra una sola vez al crear.
- **Passwords:** BCrypt con strength 12.
- **Datos sensibles:** Contraseñas de certificados y valores CSC cifrados con AES-256-GCM en BD.
- **Certificados PFX:** Almacenados como `bytea` en PostgreSQL, cargados en memoria solo al firmar.
- **Multi-tenant:** `TenantContext` (ThreadLocal) aislado por request, limpiado en `finally`.
- **Caché:** SifenConfig por empresa con Caffeine (TTL 5 min, max 100 entradas). Se invalida al actualizar empresa/certificado/CSC.

### Variables sensibles

| Variable | Descripción |
|----------|-------------|
| `JWT_SECRET` | Clave HMAC para firmar JWT (mín. 32 chars) |
| `ENCRYPTION_KEY` | Clave AES-256 en base64 para cifrar datos sensibles |
| `DB_PASS` | Contraseña de PostgreSQL |

> **Nunca** commitear estas variables. Usar variables de entorno o un gestor de secretos.

## Notas

- La librería `rshk-jsifenlib` está disponible en Maven Central.
- Cada empresa debe tener su certificado PFX y CSC configurados antes de operar contra SIFEN.
- Para el ambiente DEV, la DNIT provee certificados de prueba.
- Revisar el [Manual Técnico SIFEN v150](https://www.dnit.gov.py/documents/20123/420592/Manual+T%C3%A9cnico+Versi%C3%B3n+150.pdf) para detalles de los campos.
- Esquemas XSD oficiales usados para validar eventos:
  - [WS_SiRecepEvento_v150.xsd](https://github.com/roshkadev/rshk-jsifenlib/blob/master/docs/set/ekuatia.set.gov.py/sifen/xsd/WS_SiRecepEvento_v150.xsd)
  - [siRecepEvento_v150.xsd](https://github.com/roshkadev/rshk-jsifenlib/blob/master/docs/set/ekuatia.set.gov.py/sifen/xsd/siRecepEvento_v150.xsd)
  - [Evento_v150.xsd](https://github.com/roshkadev/rshk-jsifenlib/blob/master/docs/set/ekuatia.set.gov.py/sifen/xsd/Evento_v150.xsd)
- Los subtotales (`gTotSub`) se calculan automáticamente por la librería a partir de los ítems.
- El código de seguridad (`dCodSeg`) se rellena automáticamente a 9 dígitos para generar un CDC válido de 44 posiciones.
- Flyway gestiona el schema de BD automáticamente al iniciar la aplicación.
- El directorio `target/` con el JAR compilado (`sifen-wrapper-1.0.0.jar`) está incluido en el repositorio. Se recomienda agregarlo al `.gitignore` en producción para evitar commits de artefactos binarios.
