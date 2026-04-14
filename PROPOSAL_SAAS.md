# Propuesta de Desarrollo — Adaptación a Modelo SaaS e Integración Bancard

Fecha: 31 de marzo de 2026

## **Resumen ejecutivo**

Propuesta para transformar Ocre POS en una oferta SaaS multiempresa/multisucursal, habilitar suscripción recurrente y procesar pagos con la pasarela Bancard. El objetivo es permitir onboarding rápido de comercios, facturación electrónica por tenant (SIFEN), y cobro automatizado de suscripciones, manteniendo la funcionalidad POS existente.

Beneficios clave:
- Monetización recurrente (MRR).
- Escalabilidad y despliegue centralizado.
- Conservación de la integración SIFEN por empresa.
- Flujos de pago y reconciliación automatizados con Bancard.

## **Antecedentes / Estado actual**

Ocre POS es una plataforma Laravel 10 + Livewire 3 con arquitectura modular (nwidart). Ya soporta multiempresa y multisucursal en modelo lógico; Tiene integración con SIFEN por empresa, generación de CDC, KUDE, y módulos completos para ventas, compras, stock, reportes y más. El repositorio contiene un módulo `companies` y `branches` con configuraciones SIFEN por tenant.

## **Objetivo del proyecto**

1. Reforzar y adaptar la aplicación para operar como SaaS: onboarding, tenancy, billing y autoservicio.
2. Integrar Bancard como pasarela principal para pagos de suscripción y pagos puntuales (POS online/checkout para e-commerce y pagos desde panel admin).
3. Mantener y exponer la configuración SIFEN por tenant, asegurando emisión electrónica por empresa.

## **Alcance**

- Onboarding: registro de empresa (signup), periodo de prueba (14 días), configuración inicial (RUC, sucursal, timbrado SIFEN opcional).
- Autenticación y tenant routing: subdominios por tenant (ej: tienda.midominio.com) y/o path-based opcional.
- Gestión de planes y suscripciones: planes Basic / Pro / Enterprise, facturación mensual/anual.
- Integración Bancard: crear método de pago, tokenización, cobro inicial y recurrente, webhooks para eventos (pago aprobado/declinado/refund).
- Self-service: panel de administración por empresa (config SIFEN, sucursales, usuarios, facturación de su suscripción).
- Jobs y cola: facturación recurrente y reintentos de pago; emisión SIFEN asincrónica (ya se hace fuera de la transacción DB).
- Reporting básico de ingresos y métricas MRR.

## **Propuesta técnica (alto nivel)**

1. Modelo de multitenancy recomendado:
   - Fase 1: Shared DB + tenant_id (company_id) scoping por modelo crítico. Aprovechar los campos y scopes ya presentes (`company_id`, `branch_id`, `active_company()`), implementando middleware `SetTenant` que seleccione el tenant por subdominio o por usuario.
   - Fase 2: opcional migración a DB por tenant para clientes Enterprise.

2. Routing/identidad:
   - Usar subdominios (recomendado) y registro DNS automatizado. Fallback por path para pruebas locales.

3. Datos sensibles y seguridad:
   - TLS obligatorio, cifrado en reposo para backups, keys en vault (Azure KeyVault / AWS Secrets Manager).
   - No almacenar PAN. Usar tokenización provista por Bancard. Persistir solo `payment_method_token` (en tabla `payment_methods` ligada a `company_id`).

4. Colas y Workers:
   - Jobs para facturación recurrente, reintentos Bancard, y emisión SIFEN. Usar Redis como broker y supervisor de workers.

5. Infraestructura mínima recomendada:
   - Contenedores Docker + Kubernetes (o App Service + RDS), MySQL gestionado, Redis, S3-compatible para media, CI/CD y autoscaling horizontal para webworkers.

## **Integración Bancard — especificación y pasos**

Nota: detalles concretos (endpoints y parámetros) deben confirmar contra la documentación oficial de Bancard y el comercio (Merchant ID, API Key, modo sandbox).

1. Flujos a implementar:
   - Pago único: checkout para pagar facturas/suscripciones desde panel.
   - Suscripción recurrente: tokenización + cargo recurrente mensual/anual.
   - Webhooks: `payment.succeeded`, `payment.failed`, `refund.succeeded`.

2. Pasos técnicos:
   - Crear servicio `BancardService` con métodos: `createPaymentIntent()`, `confirmPayment()`, `tokenizeCard()`, `chargeToken()`, `refund()`.
   - Añadir modelos: `PaymentMethod (company_id, token, brand, last4, expiry)`, `BillingSubscription (company_id, plan_id, status, next_billing_at)`, `BillingInvoice (company_id, amount, currency, status, external_id)`.
   - Endpoints públicos: `POST /api/payments/webhook/bancard` (verificar firma HMAC), `POST /api/subscriptions/create`, `POST /api/subscriptions/cancel`.
   - Webhooks actualizan `BillingSubscription` y generan `BillingInvoice` y `BillingPayment` según resultado.

3. Seguridad y cumplimiento:
   - Usar HTTPS, verificar HMAC en webhooks, no almacenar datos de tarjeta.
   - Evaluar alcance PCI: si se usa tokenización completa de Bancard y no se tocan PANs, se reduce alcance pero se deben seguir recomendaciones del gateway.

4. Reconciliación:
   - Implementar job diario que compare `BillingInvoice` vs `BillingPayment` y genere reporte de conciliación.

5. Pruebas:
   - Entorno sandbox Bancard, pruebas E2E simulando pagos aprobados / declinados / 3DS.

## **Integración API con e‑commerce**

### Objetivo

Permitir que tiendas online y marketplaces integren su catálogo, stock y órdenes con Ocre POS vía API REST segura y webhooks, automatizando cobros (Bancard), creación de ventas y emisión SIFEN cuando corresponda.

### Autenticación y permisos

- API Keys por `company_id`: token largo (bearer) que se puede generar/revocar desde el panel admin de la empresa.
- Alternativa: OAuth2 (client_credentials) para integraciones de partner.
- Scopes recomendados: `read:products`, `write:orders`, `read:stock`, `webhooks:manage`.
- Almacenar solo hash de la clave en BD; mostrar token completo una sola vez al generar.

### Endpoints principales (REST v1)

- `GET /api/v1/products` — listado paginado; filtros: `sku`, `category`, `branch_id`, `q`.
- `GET /api/v1/products/{sku}` — detalle producto (incluye `price_gs`, `tax_percentage`, `stock_by_branch`).
- `POST /api/v1/customers` — crear/actualizar cliente (permitir `ruc` para emisión SIFEN).
- `POST /api/v1/orders` — crear orden (payload abajo).
- `GET /api/v1/orders/{external_id}` — estado de la orden (pending/paid/fulfilled/cancelled).
- `GET /api/v1/branches/{branch_id}/stock` — estado stock por producto.
- `POST /api/v1/webhooks/test` — endpoint para validar configuración (admin).

Ejemplo: `POST /api/v1/orders` (cabeceras: `Authorization: Bearer <API_KEY>`, `Idempotency-Key: <uuid>`) — body JSON:

{
   "external_id": "STORE-12345",
   "channel": "shopify",
   "customer": {
      "ruc": "80069563-1",
      "name": "TIPS S.A",
      "email": "compras@retail.com.py"
   },
   "items": [
      {"sku": "SKU-001", "quantity": 2, "unit_price_gs": 15000, "tax_percentage": 10}
   ],
   "payments": [
      {"gateway": "bancard", "token": "tok_abc123", "amount_gs": 30000}
   ],
   "shipping": {"method": "delivery", "cost_gs": 5000},
   "metadata": {"sales_channel_order_id": "98765"}
}

Notas:
- Todas las cantidades y precios deben enviarse en guaraníes enteros (`unit_price_gs`, `amount_gs`).
- Requerir cabecera `Idempotency-Key` para evitar duplicados.

### Flujo de órdenes y pagos

1. E‑commerce envía `POST /api/v1/orders` con `payments` que incluyen `gateway: bancard` y `token` (tokenización cliente-side en comercio o en frontend Bancard).
2. Ocre POS valida stock (para `branch_id` si aplica) y crea `Sale` internamente (estado `pending`).
3. Backend llama a `BancardService->chargeToken()` si viene token de Bancard; registra `SalePayment` y `BillingPayment`.
4. Si pago aprobado: marcar `Sale` como `paid` y disparar emisión SIFEN (si cliente tiene `ruc`) de forma asíncrona (job). Responder al e‑commerce con `sale_id`, `status`, `cdc` (si existe).
5. Si pago falló: retornar error estructurado y dejar `Sale` en `payment_failed` para reintentos manuales.

### Webhooks (salida) — eventos emitidos por Ocre POS

Eventos recomendados:
- `order.created` — tras creación de venta (pending).
- `order.paid` — pago aprobado (incluye `sale_id`, `cdc` si aplica).
- `order.failed` — pago rechazado.
- `stock.updated` — baja de stock por venta.
- `invoice.approved` / `invoice.rejected` — resultado SIFEN.

Verificación: incluir `X-OCRE-SIGNATURE: hmac_sha256` calculado con secret del webhook (configurable por tenant). Reintentos con backoff hasta 5 veces.

### Seguridad y buenas prácticas

- TLS obligatorio.
- Limitar solicitudes por `API Key`: ejemplo default `60 requests/min` por key; niveles superiores según plan.
- Validar `Idempotency-Key` para operaciones de creación.
- Verificar firma HMAC en webhooks entrantes (Bancard) y salientes.
- Registrar todas las llamadas a `storage/logs/api-YYYY-MM-DD.log` con trazabilidad `company_id` y `external_id`.

### Rate limits y cuotas por plan

- Plan Basic: 60 req/min, 10k req/mes.
- Plan Pro: 300 req/min, 100k req/mes.
- Enterprise: SLA a medida.

### Versionado y compatibilidad

- Exponer `/api/v1/...`. Mantener compatibilidad hacia atrás; comunicados de deprecación con 3 meses de antelación.

### Mapping a modelos internos

- `product.sku` → `products.product_code`.
- `unit_price_gs` → `products.product_price` (guardar en Gs).
- `orders` → crear `Sale`, `SaleDetails`, `SalePayments` y `CashRegister` entries según configuración.
- Si `customer.ruc` presente → al confirmar pago, disparar `ElectronicInvoiceController::emitInvoice()` asíncrono.

### Admin / UI

- Interfaz para generar/revocar `API Keys`, ver logs de integraciones, registrar endpoints de webhook y ver entregas/errores.

### Pruebas y sandbox

- Entorno staging con claves Bancard sandbox. Proveer ejemplos curl y colecciones Postman para:
   - Listar productos
   - Crear cliente
   - Crear orden + simular pago aprobado/declinado

Ejemplo curl (listar productos):

curl -H "Authorization: Bearer <API_KEY>" \
   https://api.tu-dominio.com/api/v1/products

### Errores, reintentos y conciliación

- Webhooks: retries exponenciales; marcar entregas fallidas en UI.
- Job diario para reconciliar `BillingInvoice` vs `BillingPayment` y enviar reporte al admin.

### Próximos pasos (implementación técnica)

1. Crear migraciones: `api_keys`, `webhooks`, `api_logs`.
2. Scaffolding de controladores API: `Api\ProductController`, `Api\OrderController`, `Api\CustomerController`.
3. Middleware `CheckApiKey`, `RateLimiter`, `SetTenant`.
4. Implementar `BancardService` y flujos de token/charge.
5. Tests E2E en sandbox (simular Bancard + SIFEN responses).

---

## **Casos de uso comerciales (primeros target)**

- Tiendas minoristas pequeñas (1–3 sucursales) que necesitan POS + facturación electrónica.
- Franquicias con múltiples sucursales buscando control centralizado y reporting consolidado.
- Comercios que requieren emisión inmediata de factura electrónica (SIFEN) y KUDE.
- Restaurantes con POS en varias estaciones y cierre de caja por turno.
- Comercios online que requieren checkout con integración a Bancard y emisión de factura electrónica.

## **Proyección de ganancias (estimada)**

Suposiciones:
- Precio plan Basic: Gs. 99.000/mes, Pro: Gs. 249.000/mes, Enterprise: Gs. 599.000/mes.
- Fees de procesamiento (Bancard): aprox. 3.5% + fijo (estimado). Retención mensual por churn 5% (mensual).

Escenarios (MRR al mes 12):
- Conservador: 50 clientes activos, ARPU promedio Gs. 149.000 → MRR = Gs. 7.450.000 → Ingresos/año ≈ Gs. 89.400.000.
- Moderado: 200 clientes activos, ARPU Gs. 199.000 → MRR = Gs. 39.800.000 → Ingresos/año ≈ Gs. 477.600.000.
- Agresivo: 500 clientes activos, ARPU Gs. 249.000 → MRR = Gs. 124.500.000 → Ingresos/año ≈ Gs. 1.494.000.000.

Ejemplo neto aproximado (Moderado):
- MRR: Gs. 39.800.000
- Fees (3.5%): ~Gs. 1.393.000
- Hosting y Operaciones (estimado): Gs. 2.000.000
- Margen bruto aproximado: Gs. 36.407.000 / mes

Nota: cifras de costos operativos y fees deben afinarse con cotizaciones reales (Bancard, Infraestructura, Soporte).

## **Módulos existentes y sus características (resumen)**

- **Sale**: Ventas, POS, pagos, caja registradora. Emisión SIFEN ligada a `companies`/`branches`.
- **Product**: Gestión productos, categorías, códigos de barra, stock por sucursal.
- **People**: Clientes y proveedores, campo `ruc` para clientes.
- **Purchase**: Compras y pagos a proveedores.
- **Quotation**: Cotizaciones y conversión a ventas, envío por email.
- **SalesReturn / PurchasesReturn**: Devoluciones con pagos y seguimiento.
- **Expense**: Gastos y categorías.
- **Adjustment**: Ajustes de inventario por sucursal.
- **Currency**: Monedas y tasas.
- **Setting**: Configuración global y por empresa (incluye variables SIFEN fallback en `.env`).
- **User / Roles**: Gestión usuarios con `company_id` y pivot `branch_user`.
- **Upload**: Subida de archivos (Spatie / FilePond).
- **Reports**: Reportes financieros (ganancias, pagos, ventas, compras).

Observación: la plataforma ya contiene scopes y fields para multiempresa; eso reduce el esfuerzo inicial de tenancy (permitiendo comenzar con shared-db y scoping).

## **Roadmap y estimación

: diseño de tenant routing, ajuste de middleware, definición de modelos billing.
2. Integración Bancard: `BancardService`, tokenización, webhooks, modelos de suscripción. Pruebas sandbox incluidas.
3. Onboarding y self-service : signup, trial, panel de billing.
: scheduler, reintentos, conciliación.
.
6. Despliegue y monitoreo.

## **Riesgos y mitigaciones**

- Riesgo: Alcance PCI por manejo de tarjetas. Mitigación: tokenización y responsabilidades minimizadas; solicitar guía Bancard.
- Riesgo: Errores SIFEN en emisión concurrente. Mitigación: emisión asincrónica y reintentos controlados en cola.
- Riesgo: fuga de datos entre tenants. Mitigación: auditorías, scopes globales y tests de seguridad, cifrado.

## **KPIs sugeridos**

- MRR, Churn mensual, CAC, ARPU, Tiempo medio de onboarding, % facturas SIFEN aprobadas, % pagos Bancard aceptados.

## **Próximos pasos recomendados**

1. Validar documentación técnica y credenciales Bancard (sandbox + keys).
2. Priorizar modelo de tenancy (subdominio vs path) y diseñar middleware `SetTenant`.
3. Implementar modelos `BillingSubscription`, `PaymentMethod`, `BillingInvoice` y pruebas unitarias.
4. Implementar webhook endpoint y proceso de conciliación.
5. Lanzar piloto con 1–3 comercios para validar flujo de suscripción y facturación SIFEN.
