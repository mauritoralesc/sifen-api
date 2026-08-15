# Eventos SIFEN — guía técnica

Esta guía documenta el subdominio de eventos (`siRecepEvento`) del wrapper: qué tipos
soporta, cómo se persisten, qué se valida antes de llamar a SIFEN, y qué hacer ante
timeouts y duplicados. Complementa la tabla de tipos en el [README](../README.md#tipos-de-evento-tipoevento).

## 1. Los 6 tipos soportados

`rshk-jsifenlib` 0.2.4 permite emitir 6 tipos de evento (más "Transporte", no expuesto
por este wrapper porque no hay caso de uso hoy). **No existe soporte de librería para
el evento de Ajuste** ni para los eventos automáticos de la DNIT (retención, CCFF,
anticipo, remisión) — la librería expone getters para leerlos en una respuesta, pero no
setters para emitirlos. No son alcanzables sin cambiar de versión de librería.

| Tipo | Nombre | Actor | Requiere CDC local |
|------|--------|-------|---------------------|
| 1 | Cancelación | Emisor | Sí — debe pertenecer a esta empresa |
| 2 | Inutilización | Emisor | No (opera sobre un rango de numeración) |
| 3 | Conformidad del receptor | Receptor | No — el DE lo emitió otra empresa |
| 4 | Disconformidad del receptor | Receptor | No |
| 5 | Desconocimiento del receptor | Receptor | No |
| 6 | Notificación de recepción | Receptor | No |

## 2. Máquina de estados del evento

```
ENVIADO ──► APROBADO
        ├─► RECHAZADO
        ├─► ERROR              (fallo interno del wrapper o excepción no clasificada)
        ├─► ERROR_CONEXION     (SIFEN devolvió HTML — sesión SSL/infra)
        └─► INDETERMINADO      (timeout o respuesta sin señal interpretable — ver §6)
```

`ENVIADO` se persiste **antes** de llamar a SIFEN (ver §3). Todo evento termina en uno
de los otros cinco estados o, si el proceso murió a mitad de camino, se queda visible en
`ENVIADO` — un estado así de viejo es en sí mismo la señal de que algo se cortó.

## 3. Persistencia y el `rEve/@Id`

Cada evento se guarda en `sifen_events`, con `company_id`, el tipo, el CDC (o el rango
para inutilización), el `evento_id` enviado a SIFEN, el código/mensaje de resultado, el
protocolo de autorización, y los XML enviado/recibido para forense.

El atributo `Id` de `rEve` (`tdIdEve` — numérico secuencial, hasta 10 dígitos) se obtiene
de una secuencia de Postgres (`sifen_event_id_seq`) **antes** de llamar a SIFEN, y la fila
en `sifen_events` se inserta y **commitea en su propia transacción** antes de que la
llamada de red empiece. Así, si el proceso muere o el socket expira a mitad del envío
síncrono, el registro de que ese `evento_id` pudo haberse enviado ya existe en la base —
no se pierde información sobre si el evento puede estar vivo en SIFEN.

## 4. Efecto sobre `electronic_documents`

Solo los eventos de emisor (tipos 1 y 2) tocan documentos, y solo cuando SIFEN aprueba:

- **Cancelación aprobada** → el documento pasa a `CANCELADO`. No se toca `processedAt`
  (es el ancla histórica de la ventana de 48h; sobrescribirlo rompería la trazabilidad).
- **Inutilización aprobada** → los documentos existentes en el rango que no estén ya
  `APROBADO`/`APROBADO_CON_OBSERVACION` pasan a `INUTILIZADO`. No se crean filas
  sintéticas para números nunca usados — el rango completo queda registrado en la propia
  fila de `sifen_events` (`timbrado`, `establecimiento`, `punto_expedicion`,
  `numero_desde`, `numero_hasta`), que es la fuente de verdad para ese caso.
- **Eventos de receptor (3-6)** no tocan ningún documento: el DE pertenece a otra
  empresa. El wrapper solo enlaza `electronic_document_id` de forma oportunista si el
  CDC casualmente existe también localmente (auto-pruebas, facturación intra-grupo).

## 5. Validaciones antes de llamar a SIFEN

### Formato (todo tipo)
CDC de 44 dígitos con dígito verificador válido; motivo de 15 a 500 caracteres cuando
aplica; establecimiento/punto de 3 dígitos; rangos numéricos de hasta 7 dígitos.

### Cancelación (tipo 1)
- El documento debe existir para esta empresa, estar `APROBADO` o
  `APROBADO_CON_OBSERVACION`, y no estar ya `CANCELADO`.
- **Plazo: 48 horas** desde la aprobación (constante fija, no configurable). Se calcula
  desde `processedAt` (o `sentAt`/`createdAt` si falta) con el mismo reloj de sistema que
  escribe esas columnas — nunca con la zona horaria paraguaya, que introduciría un
  desfase de 3-4h en un servidor en UTC.
- No debe existir una conformidad (tipo 3) ya `APROBADO` para el mismo CDC **registrada
  a través de este wrapper**. Si el receptor la presentó por otra vía, esta comprobación
  no la ve; SIFEN rechazará con el código correspondiente.

### Inutilización (tipo 2)
- Rango: `numeroHasta >= numeroDesde` y como máximo **1000 números** por evento.
- Si la empresa tiene `emisorConfig` cargado, se valida que el establecimiento y el
  timbrado coincidan con los configurados.
- Se rechaza si algún documento existente en el rango ya está `APROBADO` o
  `APROBADO_CON_OBSERVACION` — inutilizar un número aprobado es ilegal y SIFEN lo
  rechazaría de todas formas.

### Eventos del receptor (tipos 3-6)
No se exige que el CDC exista localmente: el DE lo emitió otra empresa. El control de
tenant real es `rucReceptor` — si el payload lo omite se autocompleta con el RUC de la
empresa autenticada; si lo envía, debe coincidir con ese RUC. Esto evita que una empresa
registre un evento de receptor a nombre de otra.

## 6. Timeouts: por qué nunca se reintenta automáticamente

El servicio SIFEN es síncrono. Si el socket expira o falla la conexión, la librería
entrega esa falla como una excepción genérica que no distingue "seguro no llegó" de
"puede haber llegado". El wrapper asume siempre el caso pesimista: marca el evento
`INDETERMINADO` y devuelve un error explicando que **no debe reintentarse** — reenviar
un evento idéntico puede bloquear el RUC emisor entre 10 y 60 minutos.

### Reconciliación bajo demanda

`POST /invoices/events/{id}/reconcile` es la única forma de intentar resolver un evento
`INDETERMINADO`, y solo funciona parcialmente:

- **Tipo 1 (cancelación):** se consulta el DE por CDC. Solo el código `0263` (Cancelado)
  cuenta como confirmación positiva. Cualquier otra respuesta —incluido un `0422`— **no**
  confirma que el evento no se procesó: `consultaDE` puede devolver falsos negativos
  mientras SIFEN converge (ver `docs/erp-polling-rechazado-falso.md`). En ese caso el
  evento queda `INDETERMINADO` y la respuesta es `NO_CONCLUYENTE`.
- **Tipos 2-6:** SIFEN no expone ningún servicio de consulta de eventos, así que la
  reconciliación siempre devuelve `NO_CONCLUYENTE` con la sugerencia de verificar en el
  portal e-Kuatia.

Deliberadamente **no existe un poller automático** para `INDETERMINADO`. No hay WS de
consulta de eventos, no hay lote contra el cual desambiguar (a diferencia de
`BatchPollerService`), y una conclusión automática equivocada sobre un evento fiscal
(por ejemplo, "la cancelación no llegó" cuando sí llegó) tiene consecuencias fiscales
reales. La reconciliación queda en manos del ERP o de un operador humano.

## 7. Duplicados

SIFEN bloquea el RUC emisor entre 10 y 60 minutos ante el mismo evento enviado más de
una vez. El wrapper se defiende en dos capas:

- **Base de datos (a prueba de carreras):** índices únicos parciales que impiden más de
  un evento `ENVIADO`/`INDETERMINADO`/`APROBADO` por empresa+tipo+CDC (o +rango, para
  inutilización). Es la única capa que sobrevive a un doble-click concurrente.
- **Aplicación (mensajes claros):** antes de tocar la secuencia de `evento_id`, se
  rechaza con `409 EVENT_DUPLICATE` si ya existe un evento aprobado, o uno en curso
  enviado hace menos de 60 segundos.

Repetir un evento ya `APROBADO` requiere intervención manual en la base de datos
(`UPDATE sifen_events SET estado='RECHAZADO' WHERE id=...`) — a propósito no existe un
`?force=true` en la API.

## 8. Guía rápida para el ERP

```bash
# Cancelación
curl -X POST http://localhost:8000/invoices/events \
  -H "X-API-Key: $API_KEY" -H "Content-Type: application/json" \
  -d '{
    "tipoEvento": 1,
    "cdc": "01801676843001001000001522026030410000000154",
    "motivo": "Cancelación por error en datos del receptor"
}'

# Historial de eventos de un CDC
curl -s "http://localhost:8000/invoices/01801676843001001000001522026030410000000154/events" \
  -H "X-API-Key: $API_KEY"

# Listado paginado con filtros
curl -s "http://localhost:8000/invoices/events?tipoEvento=1&estado=APROBADO&page=0&size=20" \
  -H "X-API-Key: $API_KEY"

# Reconciliar un evento INDETERMINADO
curl -X POST "http://localhost:8000/invoices/events/42/reconcile" \
  -H "X-API-Key: $API_KEY"
```

`GET /invoices/{cdc}/status` también incluye `cancelado` (true si hay una cancelación
aprobada) y `ultimoEvento` (resumen del evento más reciente para ese CDC), sin necesidad
de una llamada aparte.
