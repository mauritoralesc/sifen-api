# Propuesta de Desarrollo — sifen-wrapper: Modelo Comercial, Integración Bancard y Distribución SaaS / Self-hosting

Fecha: 31 de marzo de 2026

---

## **Resumen ejecutivo**

Propuesta para transformar **sifen-wrapper** —API REST multi-tenant en Spring Boot que actúa como intermediario entre sistemas de facturación y SIFEN (SET de Paraguay)— en un producto comercial distribuible bajo dos modelos complementarios:

1. **SaaS gestionado**: servicio en la nube con suscripción mensual/anual, multi-tenant, sin instalación por parte del cliente.
2. **Self-hosting (licencia perpetua)**: entregable instalable (JAR + Docker Compose) con pago único por instancia, ideal para empresas con requisitos de privacidad o infraestructura propia.

Adicionalmente, se propone integrar **Bancard** como pasarela de cobro para la facturación del propio servicio SaaS y como funcionalidad opcional que los clientes puedan habilitar en sus integraciones.

Beneficios clave:
- Monetización dual (MRR + licencias perpetuas).
- Base de código única con configuración por perfil de despliegue.
- Valor diferencial: solución lista para producción con amplio manejo de errores SIFEN (0160, 1004, 1309, NT13), documentación y soporte premium.
- Reducción de time-to-market para integradores: en lugar de implementar librerías desde cero, consumen una API REST autenticada.

---

## **Estado actual del producto**

sifen-wrapper ya es un producto funcional con las siguientes capacidades:

| Capacidad | Estado actual |
|---|---|
| Autenticación JWT + API Key por tenant | ✅ Producción |
| Gestión de empresas (RUC, certificado PFX, CSC, ambiente) | ✅ Producción |
| Emisión síncrona y asíncrona de DEs a SIFEN | ✅ Producción |
| Preparación de DEs (CDC + XML firmado + QR) sin envío inmediato | ✅ Producción |
| Envío por lotes (batch scheduler) y consulta de estado | ✅ Producción |
| Generación de KUDE (PDF) y base64 | ✅ Producción |
| Consulta de RUC, estado de lote, CDC | ✅ Producción |
| Envío de eventos (cancelación, inutilización) | ✅ Producción |
| Normalización y auto-cálculo de DV de RUC (error 1309) | ✅ Producción |
| Manejo de zona horaria PY en `dFecFirma` (error 1004) | ✅ Producción |
| Encriptación AES-256 de certificados y contraseñas en BD | ✅ Producción |
| Multi-tenant por `companyId` (JWT/API Key) | ✅ Producción |
| Migraciones Flyway + PostgreSQL | ✅ Producción |

Lo que **no** existe aún y es objeto de esta propuesta:
- Portal de autoservicio (dashboard web para clientes).
- Módulo de licenciamiento y activación (útil para self-hosting).
- Integración Bancard para cobro de suscripciones o pagos de clientes.
- Rate limiting por plan, billing automático, webhooks de salida.
- Empaquetado oficial para self-hosting (Docker Compose, instalador).
- Documentación pública, portal de developer.

---

## **Objetivo del proyecto**

1. Añadir capa de **billing y licenciamiento** sin romper la arquitectura multi-tenant existente.
2. Integrar **Bancard** para cobros internos (suscripciones SaaS) y como capacidad opcional para clientes.
3. Generar un **empaquetado self-hosting** (licencia perpetua o de tiempo limitado) listo para distribución.
4. Crear un **portal de administración web** básico para gestión de tenants, API Keys, facturas de suscripción y onboarding.
5. Publicar **documentación pública** con referencia de API, guías de inicio rápido y ejemplos.

---

## **Alcance**

### Módulo 1 — Licenciamiento y activación

- Generación de **License Key** cifrada con datos: `tenantId`, `plan`, `maxCompanies`, `maxApiKeys`, `expiresAt`.
- Endpoint de activación local: el wrapper valida la license key al arrancar (modo self-hosting) o consulta el servidor central (modo SaaS).
- Revocación remota de licencias (para impago o fraude).
- Panel de generación de licenses para el equipo comercial (backoffice interno).

### Módulo 2 — Planes y suscripciones

- Planes definidos: **Starter / Business / Enterprise** (SaaS) y **Solo / Team / Unlimited** (self-hosting perpetuo).
- Tabla `subscription_plans`, `subscriptions`, `billing_invoices`, `billing_payments`.
- Restricciones por plan: número máximo de empresas (tenants), API Keys, lotes por mes, retención de historial de DEs.
- Facturación mensual/anual con posibilidad de SIFEN habilitada para emitir la propia factura electrónica al cliente.

### Módulo 3 — Integración Bancard

- **Cobro de suscripciones SaaS**: tokenización de tarjeta, cargo recurrente mensual/anual, gestión de reintentos.
- **Pagos puntuales (self-hosting)**: checkout para pago de licencia perpetua o renovación de soporte.
- Webhooks Bancard: `payment.succeeded`, `payment.failed`, `refund.succeeded`.
- No se almacenan PANs; solo `payment_method_token` via tokenización Bancard.

### Módulo 4 — Portal web de administración (SaaS)

- Onboarding: registro, email de verificación, periodo de prueba 14 días.
- Panel por tenant: gestión de empresas, certificados PFX, API Keys, consumo mensual de DEs.
- Facturación: historial de facturas, métodos de pago, cambio de plan.
- Administración global (superadmin): lista de tenants, revenue, logs de SIFEN por tenant.

### Módulo 5 — Portal developer / documentación

- Documentación OpenAPI/Swagger pública generada desde el código (ya parcialmente disponible).
- Guías: "Primeros pasos", "Preparar y emitir una factura", "Manejo de errores SIFEN", "Webhooks".
- Colección Postman exportable.
- Sandbox público (ambiente DEV SIFEN) con credenciales de prueba.

### Módulo 6 — Empaquetado self-hosting

- `docker-compose.yml` oficial con la app + PostgreSQL + Nginx (TLS automático con Let's Encrypt).
- Script de instalación para Ubuntu 22.04+ (curl | bash, sin dependencias manuales).
- Wizard de primer arranque (configura BD, genera `ENCRYPTION_KEY`, `JWT_SECRET`, carga license key).
- Soporte para actualización en caliente: `docker compose pull && docker compose up -d`.
- Documentación de backup / restore de BD y certificados PFX.

### Módulo 7 — Webhooks salientes y rate limiting

- Configuración de endpoints webhook por tenant: eventos `invoice.approved`, `invoice.rejected`, `invoice.cancelled`, `batch.completed`.
- Reintentos con backoff exponencial (máx 5 intentos), log de entregas en panel.
- Middleware de rate limiting por API Key según plan (`X-RateLimit-*` headers).
- Throttle configurable: Starter: 30 req/min, Business: 120 req/min, Enterprise: sin límite.

---

## **Propuesta técnica (alto nivel)**

### Arquitectura actual vs. objetivo

```
Actual:
  Cliente HTTP → [Spring Boot API] → [PostgreSQL] → [SIFEN SOAP WS]
  Autenticación: JWT + API Key (multi-tenant por companyId)

Objetivo (SaaS):
  Navegador  → [React/Vue SPA] ──┐
  Cliente HTTP → [Spring Boot API] ← [License Validator] ← [License Server]
                    │
                    ├── [PostgreSQL + Flyway]
                    ├── [Bancard API]
                    ├── [SIFEN SOAP WS]
                    └── [Webhook Dispatcher (async)]

Objetivo (Self-hosting):
  [JAR / Docker Compose] local en VPS del cliente
  License Key cifrada validada en arranque (requiere ping a servidor de activación cada 30 días)
```

### Decisiones de diseño

1. **Base de código única**: la misma aplicación Spring Boot arranca en modo `SAAS` o `SELFHOSTED` según variable de entorno `APP_MODE`. El modo self-hosting omite los módulos de billing en la nube y valida localmente la license key.
2. **License Key**: firmada con RSA-2048 privada del servidor central; la clave pública embebida en el JAR valida sin conexión durante 30 días. Renovación silenciosa si hay red disponible.
3. **Portal web**: React + Vite (o Next.js), desplegado como SPA separada o servido desde Spring Boot estático. Comunica via la misma API REST.
4. **Datos sensibles**: certificados PFX ya cifrados con AES-256; `payment_method_token` de Bancard cifrado igual. Backups con `pg_dump` + age/GPG.
5. **Colas**: Spring `@Scheduled` + tabla de estado en BD (ya implementado). Para mayor escala: migrar a Redis + Spring Batch sin romper el contrato.

### Stack tecnológico

| Capa | Tecnología |
|---|---|
| Backend | Java 17 + Spring Boot 3.2 (existente) |
| ORM / Migraciones | Spring Data JPA + Flyway (existente) |
| Base de datos | PostgreSQL 14+ (existente) |
| Autenticación | JWT + API Key (existente) |
| Facturación SIFEN | rshk-jsifenlib 0.2.4 (existente) |
| Generación PDF | OpenPDF 2.0.3 (existente) |
| Integración pagos | Bancard VPOS / API REST |
| Frontend portal | React 18 + Vite + shadcn/ui |
| Documentación API | Springdoc OpenAPI 2.x |
| Contenedores | Docker 24+ + Docker Compose v2 |
| Reverse proxy | Nginx + Certbot (self-hosting) |
| CI/CD | GitHub Actions (build + test + release) |

---

## **Integración Bancard — especificación y pasos**

### Flujos a implementar

1. **Pago de suscripción SaaS**: tenant ingresa tarjeta → tokenización client-side (JS SDK Bancard) → backend llama a `chargeToken()` → crea `BillingInvoice` + `BillingPayment`.
2. **Compra de licencia self-hosting**: checkout one-time → genera `LicenseKey` y envía por email.
3. **Renovación de soporte anual**: cargo recurrente anual sobre token guardado.
4. **Webhooks entrantes de Bancard**: verificación HMAC-SHA256 del payload, actualización de estado de `BillingPayment`.

### Modelos nuevos (migraciones Flyway)

```sql
-- V11__create_billing_tables.sql
CREATE TABLE subscription_plans (...);      -- plan, precio, límites
CREATE TABLE subscriptions (...);           -- tenant → plan, estado, fechas
CREATE TABLE billing_invoices (...);        -- monto, moneda, estado, external_id
CREATE TABLE billing_payments (...);        -- intento de cobro, resultado
CREATE TABLE payment_methods (...);         -- company_id, token, brand, last4, expiry

-- V12__create_license_keys_table.sql
CREATE TABLE license_keys (...);            -- key cifrada, plan, maxCompanies, expiresAt

-- V13__create_webhooks_tables.sql
CREATE TABLE webhook_endpoints (...);       -- url, secret, eventos suscritos
CREATE TABLE webhook_deliveries (...);      -- log de entregas y reintentos
```

### Servicios nuevos

```java
BancardService          // createPaymentIntent, tokenizeCard, chargeToken, refund
BillingService          // createSubscription, cancelSubscription, generateInvoice
LicenseService          // generateKey, validateKey, revokeKey
WebhookDispatcher       // dispatchEvent, retry con backoff, log to webhook_deliveries
RateLimitService        // verificar límite por API Key según plan activo
```

### Endpoints nuevos

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/billing/subscribe` | Crear suscripción con token de tarjeta |
| `POST` | `/billing/cancel` | Cancelar suscripción |
| `GET` | `/billing/invoices` | Historial de facturas del tenant |
| `POST` | `/billing/payment-methods` | Agregar método de pago (token Bancard) |
| `DELETE` | `/billing/payment-methods/{id}` | Eliminar método de pago |
| `POST` | `/payments/webhook/bancard` | Endpoint público para webhooks Bancard (verificación HMAC) |
| `POST` | `/licenses/activate` | Activar license key (self-hosting) |
| `GET` | `/licenses/status` | Estado de licencia activa |
| `POST` | `/webhooks` | Registrar endpoint de webhook saliente |
| `GET` | `/webhooks/{id}/deliveries` | Log de entregas de un webhook |

### Seguridad

- HTTPS obligatorio en todos los endpoints de billing.
- Verificación de firma HMAC-SHA256 en webhooks entrantes de Bancard antes de procesar el payload.
- `payment_method_token` cifrado con AES-256 antes de persistir.
- Scope de API Keys restringido: las API Keys de cliente no tienen acceso a endpoints de `/billing/**` (solo JWT de admin de tenant).
- Validación de que el monto facturado coincide con el plan activo antes de ejecutar cargo.

---

## **Modelos de distribución**

### Modelo A — SaaS gestionado (suscripción)

El servicio se opera centralizadamente. El cliente no instala nada; consume la API REST del servidor. Cada tenant tiene su propia empresa, certificados y API Keys aislados.

**Onboarding**: registro web → email de verificación → periodo de prueba 14 días → ingresa tarjeta → activa plan.

**Planes SaaS**:

| Plan | Precio/mes | Empresas | API Keys | DEs/mes | Soporte |
|---|---|---|---|---|---|
| Starter | Gs. 149.000 | 1 | 3 | 1.000 | Email (48h) |
| Business | Gs. 349.000 | 5 | 10 | 10.000 | Email (24h) + WhatsApp |
| Enterprise | Gs. 899.000 | Ilimitado | Ilimitado | Ilimitado | Dedicado + SLA |

Precio anual con descuento del 15 %:

| Plan | Precio/año |
|---|---|
| Starter | Gs. 1.520.000 |
| Business | Gs. 3.560.000 |
| Enterprise | Gs. 9.170.000 |

### Modelo B — Self-hosting (licencia perpetua)

El cliente descarga el paquete (JAR + Docker Compose), lo instala en su propio VPS y paga una sola vez. Incluye actualizaciones menores (patch/minor) por 12 meses; las actualizaciones de versión mayor requieren renovación de soporte.

**Planes Self-hosting**:

| Plan | Precio único | Empresas | API Keys | Soporte incluido | Actualizaciones |
|---|---|---|---|---|---|
| Solo | Gs. 950.000 | 1 | 5 | 3 meses email | 12 meses |
| Team | Gs. 2.200.000 | 10 | 25 | 6 meses WhatsApp | 12 meses |
| Unlimited | Gs. 4.500.000 | Ilimitado | Ilimitado | 12 meses dedicado | 18 meses |

**Renovación de soporte anual** (opcional, después del período incluido):

| Plan | Renovación/año |
|---|---|
| Solo | Gs. 350.000 |
| Team | Gs. 750.000 |
| Unlimited | Gs. 1.500.000 |

### Modelo C — OEM / White label

Para revendedores que quieren integrar sifen-wrapper como componente de su propio producto (ERP, POS, e-commerce). Precio negociado por volumen, con marca personalizable, sin restricción de tenants.

Precio base orientativo: Gs. 6.000.000 + Gs. 1.500.000/año soporte.

---

## **Proyecciones de ingresos**

### Hipótesis base

- Mercado objetivo: PYMEs paraguayas con obligación de emisión electrónica. Miles de contribuyentes activos en SET con necesidad de integración.
- Tasa de conversión trial → pago: 25 %.
- Churn mensual SaaS: 4 %.
- Ventas self-hosting: estacionales (picos en enero-febrero, antes del cierre fiscal).
- Precio dólar de referencia para costos: Gs. 7.500 / USD.

### Escenario SaaS — MRR al mes 12

| Escenario | Clientes activos | ARPU promedio | MRR | Ingresos anuales |
|---|---|---|---|---|
| Conservador | 30 | Gs. 199.000 | Gs. 5.970.000 | Gs. 71.640.000 |
| Moderado | 120 | Gs. 299.000 | Gs. 35.880.000 | Gs. 430.560.000 |
| Agresivo | 350 | Gs. 399.000 | Gs. 139.650.000 | Gs. 1.675.800.000 |

**Estimación de costos operativos mensuales (SaaS, escenario moderado)**:

| Ítem | Costo estimado/mes |
|---|---|
| Hosting VPS / cloud (2 nodos + BD gestionada) | Gs. 1.500.000 |
| Dominio + TLS + CDN | Gs. 150.000 |
| Fees Bancard (procesamiento ~3,5 %) | Gs. 1.256.000 |
| Soporte y operaciones (tiempo parcial) | Gs. 2.000.000 |
| **Total costos** | **Gs. 4.906.000** |
| **Margen bruto (moderado)** | **Gs. 30.974.000 / mes** |

### Escenario Self-hosting — ventas anuales (año 1)

| Escenario | Licencias Solo | Licencias Team | Licencias Unlimited | Renovaciones soporte | Total año |
|---|---|---|---|---|---|
| Conservador | 15 | 8 | 2 | 10 | Gs. 41.050.000 |
| Moderado | 50 | 25 | 8 | 40 | Gs. 144.750.000 |
| Agresivo | 120 | 60 | 20 | 100 | Gs. 348.500.000 |

> Cálculo moderado detallado: (50×950.000) + (25×2.200.000) + (8×4.500.000) + (40×350.000) = Gs. 47.500.000 + 55.000.000 + 36.000.000 + 14.000.000 = **Gs. 152.500.000**.

### Modelo mixto — proyección año 1 total (moderado)

| Fuente | Ingresos estimados |
|---|---|
| SaaS (MRR acumulado durante ramp-up) | Gs. 215.280.000 |
| Self-hosting licencias | Gs. 152.500.000 |
| OEM / White label (1 cliente) | Gs. 7.500.000 |
| **Total año 1** | **Gs. 375.280.000** |
| Costos operativos año (~Gs. 59M) | Gs. 58.872.000 |
| **Ganancia bruta estimada año 1** | **~Gs. 316.000.000** |

> Nota: las cifras de costos deben verificarse con cotizaciones reales de Bancard, proveedor cloud y dedicación de equipo.

---

## **Roadmap de implementación**

### Fase 1 — Fundamentos comerciales

- Migraciones V11, V12, V13: tablas de billing, licencias, webhooks.
- `LicenseService`: generación, validación (firma RSA), revocación.
- `RateLimitService` por plan.
- Empaquetado Docker Compose inicial con variables de entorno documentadas.

### Fase 2 — Integración Bancard

- `BancardService`: `tokenizeCard`, `chargeToken`, `refund`.
- Endpoint `POST /payments/webhook/bancard` con verificación HMAC.
- `BillingService`: crear suscripción, generar `BillingInvoice`, actualizar estado.
- Pruebas E2E en sandbox Bancard (pago aprobado, declinado, 3DS).

### Fase 3 — Portal web

- Diseño UI (Figma): onboarding, dashboard tenant, billing, API Keys, webhooks.
- Implementación React SPA: autenticación, gestión empresas, API Keys, KUDE viewer.
- Sección billing: historial facturas, agregar tarjeta, cambiar plan.
- Panel superadmin: lista tenants, revenue, logs SIFEN por tenant.

### Fase 4 — Self-hosting y empaquetado

- `docker-compose.yml` oficial.
- Script de instalación automatizado (Ubuntu 22.04+).
- Wizard de primer arranque (CLI interactivo en Java o Bash).
- Generación de builds firmados: `mvn release:prepare release:perform` + GitHub Actions.
- Documentación de instalación, backup y actualización.

### Fase 5 — Documentación y portal developer

- Springdoc OpenAPI habilitado con descripción de endpoints, schemas y ejemplos.
- Sitio de documentación: Docusaurus o VitePress con guías y referencia de API.
- Colección Postman con todos los endpoints y ejemplos de error SIFEN.
- Sandbox público (SIFEN DEV) con credenciales compartidas para pruebas.

### Fase 6 — QA, hardening y release

- Tests de integración con Bancard sandbox y SIFEN DEV.
- Penetration test básico (OWASP Top 10): injection, broken auth, datos expuestos, IDOR.
- Performance test con locust/k6 (objetivo: 100 req/s sostenido, P95 < 500 ms).
- Release `1.1.0` (SaaS) y `1.1.0-selfhosted` (self-hosting).

---

## **Seguridad y cumplimiento**

- HTTPS obligatorio; HSTS habilitado en Nginx.
- Certificados PFX y contraseñas cifrados AES-256 en reposo (ya implementado).
- `payment_method_token` Bancard cifrado igualmente; no se almacenan PANs.
- Verificación HMAC-SHA256 en webhooks entrantes de Bancard antes de cualquier acción.
- Rate limiting y bloqueo de IP tras 10 intentos de login fallidos consecutivos.
- Audit log de operaciones críticas (crear/eliminar empresa, subir certificado, cambiar plan).
- Evaluación PCI-DSS: con tokenización completa, el alcance se reduce; se recomienda confirmar con Bancard el nivel de cumplimiento requerido.
- Backups cifrados automáticos de PostgreSQL (`pg_dump` + age/GPG) con retención de 30 días.
- Separación de datos multi-tenant por `company_id` en cada query (ya implementado mediante `TenantContext`).

---

## **Riesgos y mitigaciones**

| Riesgo | Impacto | Mitigación |
|---|---|---|
| Cambios en API SIFEN/SET (nuevos XSD) | Alto | Monitoreo de comunicados SET; actualizar `rshk-jsifenlib` rápidamente |
| Error 0160 persistente en producción | Alto | Documentación detallada del problema (ver SIFEN issues); soporte proactivo al cliente |
| Fuga de datos entre tenants | Crítico | Global scope `company_id`, tests de IDOR, auditoría de queries JPA |
| Pérdida de certificado PFX del cliente | Alto | Backup cifrado; instrucciones explícitas en onboarding |
| Impago → licencia self-hosting activa | Medio | Validación periódica (cada 30 días) contra servidor de activación |
| Cambios de comisiones Bancard | Medio | Ajuste de precios con aviso 30 días de anticipación |
| Competencia con soluciones SET propias | Bajo | Diferenciación por soporte local, manejo de errores probado, integración multi-tenant |

---

## **KPIs**

- **SaaS**: MRR, Churn mensual, ARR, ARPU, Trial-to-Paid ratio, Tiempo medio de onboarding, % DEs aprobados en SIFEN, uptime (objetivo 99,9 %).
- **Self-hosting**: Licencias vendidas por mes, Tasa de renovación de soporte, NPS de clientes.
- **Técnicos**: P95 de latencia por endpoint, tasa de error 5xx, cola de DEs pendientes (objetivo < 5 min de retraso).

---

## **Próximos pasos recomendados**

1. **Validar credenciales Bancard sandbox** y firmar acuerdo de procesador de pagos (requiere RUC de la empresa operadora del servicio).
2. **Definir estructura legal**: si se venden licencias self-hosting como software, evaluar régimen tributario (IVA, IRACIS) con asesor local.
3. **Priorizar Fase 1 y Fase 2**: son el habilitador de toda la facturación. Fase 3 (portal web) puede iniciarse en paralelo con diseño solo.
4. **Establecer dominio y marca**: nombre del servicio, dominio principal, subdomino `api.`, `docs.`, `app.`.
5. **Piloto con 2–3 clientes early adopters**: probar flujo completo SaaS (trial → pago Bancard → API Key → emitir DEs) antes del release público.
6. **Preparar comunicado SIFEN**: notificar a clientes actuales (si los hay) sobre mejoras y nuevo modelo de licenciamiento.
