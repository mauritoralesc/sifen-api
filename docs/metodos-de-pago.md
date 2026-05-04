# Métodos de Pago — Guía de Integración para el ERP

Esta guía describe cómo enviar correctamente el campo `condicion.entregas` en los endpoints de facturación (`/invoices/prepare`, `/invoices/emit`, etc.) para cada método de pago soportado por SIFEN.

---

## Estructura base de `condicion`

```json
"condicion": {
  "tipo": 1,
  "entregas": [
    { ...entrega... }
  ]
}
```

| Campo | Descripción | Valores |
|-------|-------------|---------|
| `tipo` | Condición de la operación | `1` = Contado, `2` = Crédito |
| `entregas` | Lista de métodos de pago (puede ser más de uno) | Ver secciones abajo |

---

## Todos los tipos de pago (`tipo` en `entregas`)

| Código | Descripción | Campos adicionales requeridos |
|--------|-------------|-------------------------------|
| `1` | Efectivo | Ninguno |
| `2` | Cheque | `numeroCheque`, `bancoEmisor` |
| `3` | Tarjeta de crédito | `denominacionTarjeta`, `formaProcesamiento` |
| `4` | Tarjeta de débito | `denominacionTarjeta`, `formaProcesamiento` |
| `5` | Transferencia | Ninguno |
| `6` | Giro | Ninguno |
| `7` | Billetera electrónica | Ninguno |
| `8` | Tarjeta empresarial | `denominacionTarjeta`, `formaProcesamiento` |
| `9` | Vale | Ninguno |
| `10` | Retención | Ninguno |
| `11` | Pago por anticipo | Ninguno |
| `12` | Valor fiscal | Ninguno |
| `13` | Valor comercial | Ninguno |
| `14` | Compensación | Ninguno |
| `15` | Permuta | Ninguno |
| `16` | Pago bancario | Ninguno |
| `17` | Pago Móvil | Ninguno |
| `18` | Donación | Ninguno |
| `19` | Promoción | Ninguno |
| `20` | Consumo Interno | Ninguno |
| `21` | Pago Electrónico | Ninguno |
| `99` | Otro | Ninguno |

---

## Tipo 1 — Efectivo

```json
"condicion": {
  "tipo": 1,
  "entregas": [
    {
      "tipo": 1,
      "monto": 150000,
      "moneda": "PYG"
    }
  ]
}
```

---

## Tipo 2 — Cheque

Campos adicionales **requeridos**:

| Campo | Tipo | Descripción |
|-------|------|-------------|
| `numeroCheque` | `string` | Número del cheque (se rellena con ceros a 8 dígitos) |
| `bancoEmisor` | `string` | Nombre del banco emisor |

```json
"condicion": {
  "tipo": 1,
  "entregas": [
    {
      "tipo": 2,
      "monto": 500000,
      "moneda": "PYG",
      "numeroCheque": "00123456",
      "bancoEmisor": "BANCO CONTINENTAL"
    }
  ]
}
```

---

## Tipos 3 y 4 — Tarjeta de crédito / débito

Campos adicionales **requeridos**:

| Campo | Tipo | Descripción |
|-------|------|-------------|
| `denominacionTarjeta` | `int` | Ver tabla de denominaciones abajo |
| `formaProcesamiento` | `int` | Ver tabla de formas de procesamiento abajo |

Campos **opcionales**:

| Campo | Tipo | Descripción |
|-------|------|-------------|
| `razonSocialProcesadora` | `string` | Razón social de la procesadora (ej. "BANCARD S.A.") |
| `rucProcesadora` | `string` | RUC de la procesadora |
| `dvProcesadora` | `number` | Dígito verificador del RUC de la procesadora |
| `codigoAutorizacion` | `number` | Código de autorización de la transacción |
| `nombreTitular` | `string` | Nombre del titular de la tarjeta |
| `numerosUltimosTarjeta` | `number` | Últimos 4 dígitos de la tarjeta |

### Denominaciones de tarjeta (`denominacionTarjeta`)

| Código | Descripción |
|--------|-------------|
| `1` | Visa |
| `2` | Mastercard |
| `3` | American Express |
| `4` | Maestro |
| `5` | Panal |
| `6` | Cabal |
| `99` | Otro |

### Formas de procesamiento (`formaProcesamiento`)

| Código | Descripción |
|--------|-------------|
| `1` | POS |
| `2` | Pago Electrónico |
| `9` | Otro |

### Ejemplo — Tarjeta de débito (tipo 4)

```json
"condicion": {
  "tipo": 1,
  "entregas": [
    {
      "tipo": 4,
      "monto": 587000,
      "moneda": "PYG",
      "denominacionTarjeta": 1,
      "formaProcesamiento": 1,
      "razonSocialProcesadora": "BANCARD S.A.",
      "codigoAutorizacion": 123456,
      "numerosUltimosTarjeta": 1234
    }
  ]
}
```

### Ejemplo — Tarjeta de crédito (tipo 3)

```json
"condicion": {
  "tipo": 1,
  "entregas": [
    {
      "tipo": 3,
      "monto": 940000,
      "moneda": "PYG",
      "denominacionTarjeta": 2,
      "formaProcesamiento": 1,
      "razonSocialProcesadora": "INFONET S.A.",
      "numerosUltimosTarjeta": 5678
    }
  ]
}
```

---

## Tipos 5, 6, 7, 16, 17, 21 — Transferencia, Giro, Billetera, Pago bancario, Pago Móvil, Pago Electrónico

Sin campos adicionales. Solo `tipo`, `monto` y `moneda`.

```json
"condicion": {
  "tipo": 1,
  "entregas": [
    {
      "tipo": 7,
      "monto": 250000,
      "moneda": "PYG"
    }
  ]
}
```

---

## Pagos en moneda extranjera

Cuando `moneda` es distinta de `PYG`, se debe enviar el campo `tipoCambio`:

| Campo | Tipo | Descripción |
|-------|------|-------------|
| `tipoCambio` | `BigDecimal` | Tipo de cambio respecto al guaraní |

```json
"condicion": {
  "tipo": 1,
  "entregas": [
    {
      "tipo": 1,
      "monto": 100,
      "moneda": "USD",
      "tipoCambio": 7500.00
    }
  ]
}
```

---

## Pago mixto (múltiples métodos)

Se pueden enviar varias entregas en el mismo documento:

```json
"condicion": {
  "tipo": 1,
  "entregas": [
    {
      "tipo": 1,
      "monto": 100000,
      "moneda": "PYG"
    },
    {
      "tipo": 4,
      "monto": 487000,
      "moneda": "PYG",
      "denominacionTarjeta": 1,
      "formaProcesamiento": 1
    }
  ]
}
```

---

## Condición a crédito

Cuando `condicion.tipo = 2`, se debe incluir el campo `credito`:

```json
"condicion": {
  "tipo": 2,
  "entregas": [
    {
      "tipo": 1,
      "monto": 0,
      "moneda": "PYG"
    }
  ],
  "credito": {
    "tipo": 1,
    "plazo": "30 días"
  }
}
```

Para crédito en cuotas (`tipo = 2`), agregar `cuotas`:

```json
"credito": {
  "tipo": 2,
  "plazo": "6 meses",
  "cuotas": 6
}
```

---

## Errores comunes

| Error | Causa | Solución |
|-------|-------|----------|
| `gPagTarCD is null` | Tipo 3 o 4 sin `denominacionTarjeta` o `formaProcesamiento` | Enviar ambos campos obligatorios |
| `gPagCheq is null` | Tipo 2 sin `numeroCheque` o `bancoEmisor` | Enviar ambos campos obligatorios |
| Campo nulo en `dTiCamTiPag` | Moneda distinta de PYG sin `tipoCambio` | Enviar `tipoCambio` cuando `moneda != "PYG"` |
