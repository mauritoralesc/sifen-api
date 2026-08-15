# Rechazo SIFEN 1004 ("firma adelantada") y reenvío con el mismo CDC

> El 1004 es un caso particular de la mecánica general de rechazo y reenvío. Para el
> catálogo completo de códigos, la clasificación de reenviabilidad (`GET /invoices/{cdc}/status`
> → `reenviable`/`clasificacionReenvio`) y el reenvío con datos corregidos, ver
> [motivo-rechazo-y-reenvio.md](motivo-rechazo-y-reenvio.md).

## El error

```json
{
  "cdc": "01005324815001001000035422026072710000003541",
  "estado": "Rechazado",
  "descripcion": "La fecha y hora de la firma digital es adelantada"
}
```

SIFEN rechaza el documento (código `1004`) cuando `dFecFirma` (fecha/hora de la firma
digital del XML) es **posterior** a la hora oficial del servidor SIFEN en el momento de
la validación.

## Causa raíz

El wrapper firma cada documento con la hora del sistema (`America/Asuncion`) en el
momento del envío — la lógica de zona horaria siempre fue correcta. Si SIFEN considera
esa hora "adelantada", la causa es que **el reloj del servidor donde corre el wrapper
está desincronizado** (NTP deshabilitado o con drift) respecto a la hora oficial.

### Diagnóstico en el VPS

```bash
timedatectl
```

Buscar `System clock synchronized: yes` y `NTP service: active`. Si dice `no`/`inactive`,
ese es el problema. Comparar además contra un servidor externo:

```bash
date -u
curl -sI https://ekuatia.set.gov.py | grep -i '^date'
```

Un desfase de más de unos pocos segundos hacia adelante ya puede disparar el 1004.

### Corrección del reloj

```bash
sudo timedatectl set-ntp true
timedatectl   # re-verificar "synchronized: yes"
```

Si el problema persiste, instalar y habilitar `chrony` en lugar de `systemd-timesyncd`.

## Mitigación en el wrapper (margen de seguridad)

Además de corregir el reloj del servidor, el wrapper ahora firma con un **margen de
seguridad hacia atrás** de `SifenMapper.FIRMA_BACKDATE_SECONDS` (120 segundos): en vez
de firmar con la hora exacta, firma con "ahora − 2 minutos". Esto absorbe desfases
moderados de reloj sin depender exclusivamente de NTP. La firma nunca queda antes de la
fecha de emisión del documento (`dFeEmiDE`), porque SIFEN exige `dFecFirma >= dFeEmiDE`.

Esta mitigación reduce el riesgo pero **no reemplaza** la corrección del reloj del
servidor — es un colchón, no la solución.

## Por qué se puede reenviar con el mismo CDC

Según el Manual Técnico SIFEN, un DE rechazado puede corregirse y reenviarse
reutilizando el **mismo CDC**, siempre que el ajuste no modifique los campos que
componen los 44 dígitos del CDC:

```
[iTiDE][dRucEm][dDVEmi][dEst][dPunExp][dNumDoc][iTipCont][dFeEmiDE (solo fecha)][iTipEmi][dCodSeg] + DV
```

`dFecFirma` **no forma parte del CDC** — corregir la hora de firma y volver a firmar no
altera el CDC del documento. El wrapper ya reconstruye y re-firma el DE desde los datos
originales en cada intento de envío (`BatchSenderService`), así que reenviar un
documento rechazado es, en esencia, devolverlo a la cola de envío.

Antes de reencolar, el wrapper **verifica que el CDC recalculado coincida exactamente**
con el original (`InvoiceService.reenviarDocumento`, y como salvaguarda adicional en
cada ciclo de `BatchSenderService`); si no coincide, el documento se marca `ERROR` en
lugar de enviarse con un CDC distinto, evitando una irregularidad fiscal.

## Cómo reenviar

### Un documento puntual

```bash
curl -X POST https://sifenapi.ratones.dev/invoices/{cdc}/resend \
  -H "X-API-Key: sw_live_..."
```

Respuesta:

```json
{
  "success": true,
  "message": "Documento reencolado para reenvío con el mismo CDC",
  "data": { "cdc": "...", "estado": "PREPARADO" }
}
```

Solo funciona sobre documentos en estado `RECHAZADO` o `ERROR`. Sobre `PREPARADO` o
`ENVIADO` devuelve `400` (ya está en curso — evita duplicados). El scheduler
(`BatchSenderService`, cada ~60s) toma el documento, lo re-firma con la hora corregida
y lo reenvía. Se puede repetir el reenvío las veces que sea necesario hasta que SIFEN
lo apruebe.

Para el 1004 no hace falta enviar body: el problema es la hora de firma, no los datos
del documento. Si el rechazo requiere corregir datos (RUC, actividad económica, IVA,
etc.), ver [motivo-rechazo-y-reenvio.md](motivo-rechazo-y-reenvio.md#reenvío-con-datos-corregidos).

### Todos los rechazados por el mismo código

```bash
curl -X POST 'https://sifenapi.ratones.dev/invoices/resend-rejected?codigo=1004' \
  -H "X-API-Key: sw_live_..."
```

`codigo` es opcional (default `1004`). Reencola todos los documentos `RECHAZADO` de la
empresa con ese código SIFEN. Devuelve un resultado por documento:

```json
{
  "success": true,
  "data": [
    { "cdc": "...3541", "reenviado": true, "estadoAnterior": "RECHAZADO",
      "detalle": "Reencolado como PREPARADO para reenvío" }
  ]
}
```

Un error en un documento no interrumpe el procesamiento del resto.

## Plazos

Reenviar dentro de los plazos de transmisión de SIFEN (72 horas desde la fecha de
emisión, según el régimen aplicable) para evitar multas por extemporaneidad. Si el
documento no puede aprobarse a tiempo, la alternativa es anularlo/inutilizarlo y emitir
uno nuevo con el siguiente número correlativo — nunca reutilizar el número de un
documento cuyo plazo venció.
