# ERP: Evitar RECHAZADO falso por polling prematuro

## Problema observado

Al emitir una factura y consultar el estado inmediatamente con `?refresh=true`,
el documento puede quedar con estado `RECHAZADO` en la base de datos aunque SIFEN lo aprobó.

### Cronología del incidente (2026-05-05)

| Hora       | Evento                                                      |
|------------|-------------------------------------------------------------|
| 14:37:21   | Lote enviado a SIFEN (`nroLote=15660711722233539454`)       |
| 14:37:24   | SIFEN confirma recepción del lote                           |
| 14:37:40   | ERP consulta `?refresh=false` → retorna `ENVIADO` (OK)     |
| 14:37:54   | ERP consulta `?refresh=true` → **bug** → graba `RECHAZADO` |
| Posterior  | SIFEN confirma que el CDC estaba `Aprobado` (código 0260)  |

### Causa raíz

Cuando el lote ya fue concluido (`0362`) pero el ERP llama `?refresh=true`
antes de que el scheduler interno haya procesado los resultados, el wrapper
consultaba el CDC individualmente a SIFEN (`consultaDE`). Ese endpoint puede:

- Devolver `0422` ("No existe") por latencia de consistencia interna de SIFEN.
- Devolver un código que versiones antiguas del wrapper mapeaban incorrectamente.

**Este bug fue corregido en el wrapper (v1.0.1)**: ahora cuando el lote está `0362`,
el resultado se extrae directamente de la respuesta del lote sin llamar `consultaDE`.

---

## Recomendación para el ERP

### Regla principal

> **No llamar `?refresh=true` en los primeros 5 minutos después de emitir una factura.**

El scheduler interno del wrapper consulta los lotes cada 10 minutos. Si el ERP
no necesita el estado inmediato, simplemente espera la próxima consulta periódica.

### Estrategia de polling recomendada

```
1. POST /invoices/prepare   →  estado: PREPARADO
2. (scheduler automático envía el lote cada 60s)
3. GET /invoices/{cdc}/status?refresh=false   (polling sin forzar SIFEN)
   → Repetir cada 30-60s hasta que estado ≠ ENVIADO  (max ~15 min)
4. Solo si han pasado >10 min y el estado sigue ENVIADO:
   GET /invoices/{cdc}/status?refresh=true
```

### Cuándo usar `?refresh=true`

| Situación                                           | ¿Usar refresh=true? |
|-----------------------------------------------------|---------------------|
| Inmediatamente después de emitir (<5 min)           | ❌ No               |
| Verificación periódica cada 30-60s                  | ❌ No               |
| Han pasado >10 min y el estado sigue siendo ENVIADO | ✅ Sí               |
| El scheduler ya actualizó a APROBADO/RECHAZADO      | No es necesario     |

### Respuestas especiales a manejar

Cuando el lote aún está procesando, el wrapper retorna:

```json
{
  "estado": "ENVIADO",
  "codigoEstado": "0361",
  "descripcionEstado": "Lote en procesamiento — resultado pendiente"
}
```

Ante este response: **no cambiar el estado en el ERP, volver a consultar en 30s**.

### Flujo con `?refresh=true` (post-fix)

A partir de la versión corregida del wrapper, la lógica es segura:

- **Lote `0361`** (en procesamiento): retorna `ENVIADO` sin modificar DB.
- **Lote `0362`** (concluido): extrae resultado del CDC directamente del lote → graba `APROBADO` o `RECHAZADO` correctamente.
- **Lote `0364`** (extemporáneo, >48h) o sin lote: consulta `consultaDE` individualmente.

---

## Corrección de datos afectados

El CDC `01005324815001001000003222026050510000000320` quedó en estado `RECHAZADO`
incorrecto. Se corrigió mediante la migración `V15__fix_rechazado_falso_20260505.sql`
que actualiza el estado a `APROBADO` con código `0260`.

Si el ERP tiene cache local del estado de este documento, debe refrescharlo
consultando `/invoices/01005324815001001000003222026050510000000320/status`.
