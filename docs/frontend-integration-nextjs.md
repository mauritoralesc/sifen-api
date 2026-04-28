# Integracion Frontend con Next.js

Esta guia describe como integrar un frontend en Next.js con `sifen-wrapper`, tomando como base los endpoints y flujos documentados del proyecto.

El objetivo es que el frontend:

- autentique usuarios administrativos con JWT;
- consuma la API de forma segura desde Next.js;
- ejecute el flujo recomendado de facturacion `prepare -> status`;
- administre empresas, API Keys y reenvio de correo sin exponer secretos en el navegador.

## 1. Enfoque recomendado de arquitectura

Para este backend, la integracion mas segura en Next.js es:

- usar `App Router`;
- guardar el `accessToken` y `refreshToken` en cookies `httpOnly`;
- consumir `sifen-wrapper` desde el servidor de Next.js (`Route Handlers`, `Server Actions` o componentes server);
- evitar llamadas directas del navegador a endpoints sensibles como `/auth/refresh`, `/api-keys`, `/companies` o cualquier operacion administrativa;
- reservar el uso de `X-API-Key` para integraciones backend-backend o server-side, no para exponerlo al browser.

### Separacion sugerida

- Browser: formularios, tablas, vistas del dashboard, polling visual.
- Next.js server: login, refresh, proxy autenticado, validacion de sesion, subida de archivos, creacion de API Keys.
- `sifen-wrapper`: logica de negocio SIFEN, multi-tenant, emision, consulta y persistencia.

## 2. Variables de entorno en Next.js

Defini variables privadas en `.env.local` del frontend:

```bash
SIFEN_API_URL=http://localhost:8000
SIFEN_INTERNAL_API_KEY=
```

Notas:

- `SIFEN_API_URL` apunta al backend Spring Boot.
- `SIFEN_INTERNAL_API_KEY` es opcional y solo aplica si el frontend ejecuta procesos server-to-server con API Key.
- No uses prefijo `NEXT_PUBLIC_` para tokens, API Keys o URLs internas si no queres exponerlas al cliente.

## 3. Contrato base de respuestas

La API devuelve un envelope consistente:

```json
{
  "success": true,
  "message": "Login exitoso",
  "data": {},
  "error": null
}
```

Tipos base recomendados en el frontend:

```ts
export type SifenApiResponse<T> = {
  success: boolean;
  message?: string;
  data: T | null;
  error?: {
    codigo?: string;
    descripcion?: string;
  } | null;
};

export type LoginResponse = {
  // Login directo (una empresa) o tras setup-company / switch-company
  accessToken?: string;
  refreshToken?: string;
  expiresIn?: number;
  role?: string;
  companyId?: number;
  // Login multi-empresa o respuesta de registro
  requiresCompanySelection?: boolean;
  selectionToken?: string;
  companies?: CompanyMemberInfo[];
};

export type CompanyMemberInfo = {
  companyId: number;
  nombre: string;
  ruc: string;
  role: string;
};

export type ApiKeyResponse = {
  id: number;
  keyPrefix: string;
  name: string;
  active: boolean;
  expiresAt: string | null;
  createdAt: string;
  rawKey?: string | null;
};

export type PrepareInvoiceResponse = {
  cdc: string;
  qrUrl: string;
  estado: string;
  numero: string;
  establecimiento: string;
  punto: string;
  xmlFirmado: string | null;
  kude: string | null;
};
```

## 4. Cliente HTTP reutilizable

Conviene centralizar el acceso al backend en una capa server-side.

Ejemplo: `src/lib/sifen-client.ts`

```ts
import 'server-only';

import { cookies } from 'next/headers';

const baseUrl = process.env.SIFEN_API_URL;

if (!baseUrl) {
  throw new Error('Missing SIFEN_API_URL');
}

type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  body?: unknown;
  accessToken?: string;
  apiKey?: string;
  contentType?: string;
};

export async function sifenRequest<T>(
  path: string,
  { method = 'GET', body, accessToken, apiKey, contentType = 'application/json' }: RequestOptions = {},
): Promise<T> {
  const headers = new Headers();

  if (contentType) {
    headers.set('Content-Type', contentType);
  }

  if (accessToken) {
    headers.set('Authorization', `Bearer ${accessToken}`);
  }

  if (apiKey) {
    headers.set('X-API-Key', apiKey);
  }

  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers,
    body: body instanceof FormData || body == null ? (body as BodyInit | null | undefined) : JSON.stringify(body),
    cache: 'no-store',
  });

  const payload = await response.json();

  if (!response.ok || !payload.success) {
    throw new Error(payload?.error?.descripcion || payload?.message || 'Error consumiendo SIFEN Wrapper');
  }

  return payload.data as T;
}

export async function getAccessTokenFromCookie() {
  const cookieStore = await cookies();
  return cookieStore.get('sifen_access_token')?.value;
}
```

## 5. Registro y onboarding de nuevos usuarios

El registro se hace en dos pasos:

1. `POST /auth/register` — crea la cuenta y devuelve un `selectionToken` (válido 5 minutos).
2. `POST /auth/setup-company` con `X-Selection-Token` — crea la primera empresa y devuelve el JWT operativo.

Hasta completar el paso 2, el usuario no puede autenticarse con JWT.

### Paso 1: Registrar cuenta

Ejemplo: `app/api/auth/register/route.ts`

```ts
import { NextResponse } from 'next/server';

import type { LoginResponse, SifenApiResponse } from '@/lib/types';

export async function POST(request: Request) {
  const body = await request.json();

  const response = await fetch(`${process.env.SIFEN_API_URL}/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      email: body.email,
      password: body.password,
      fullName: body.fullName,
    }),
    cache: 'no-store',
  });

  const payload: SifenApiResponse<LoginResponse> = await response.json();

  if (!response.ok || !payload.success || !payload.data) {
    return NextResponse.json(
      { message: payload.error?.descripcion || payload.message || 'Error en el registro' },
      { status: response.status },
    );
  }

  // Devolver el selectionToken al cliente para continuar con el setup
  return NextResponse.json({
    selectionToken: payload.data.selectionToken,
    requiresCompanySelection: true,
  });
}
```

curl equivalente:

```bash
curl -X POST http://localhost:8000/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "nuevo@empresa.com", "password": "mi_password", "fullName": "Juan Pérez"}'
```

Respuesta:

```json
{
  "success": true,
  "message": "Cuenta creada correctamente",
  "data": {
    "requiresCompanySelection": true,
    "selectionToken": "eyJhbGciOi...",
    "companies": []
  }
}
```

### Paso 2: Crear la primera empresa

El `selectionToken` recibido se pasa en el header `X-Selection-Token`. La empresa queda creada y el usuario es asignado automáticamente como `ADMIN`. La respuesta incluye el JWT operativo listo para usar.

Ejemplo: `app/api/auth/setup-company/route.ts`

```ts
import { cookies } from 'next/headers';
import { NextResponse } from 'next/server';

import type { LoginResponse, SifenApiResponse } from '@/lib/types';

export async function POST(request: Request) {
  const body = await request.json();
  const { selectionToken, nombre, ruc, dv, ambiente } = body;

  const response = await fetch(`${process.env.SIFEN_API_URL}/auth/setup-company`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Selection-Token': selectionToken,
    },
    body: JSON.stringify({ nombre, ruc, dv, ambiente: ambiente ?? 'DEV' }),
    cache: 'no-store',
  });

  const payload: SifenApiResponse<LoginResponse> = await response.json();

  if (!response.ok || !payload.success || !payload.data?.accessToken) {
    return NextResponse.json(
      { message: payload.error?.descripcion || payload.message || 'Error al crear la empresa' },
      { status: response.status },
    );
  }

  const cookieStore = await cookies();

  cookieStore.set('sifen_access_token', payload.data.accessToken, {
    httpOnly: true,
    sameSite: 'lax',
    secure: process.env.NODE_ENV === 'production',
    path: '/',
    maxAge: payload.data.expiresIn,
  });

  cookieStore.set('sifen_refresh_token', payload.data.refreshToken!, {
    httpOnly: true,
    sameSite: 'lax',
    secure: process.env.NODE_ENV === 'production',
    path: '/',
    maxAge: 60 * 60 * 24 * 7,
  });

  cookieStore.set('sifen_role', payload.data.role!, {
    httpOnly: true,
    sameSite: 'lax',
    secure: process.env.NODE_ENV === 'production',
    path: '/',
    maxAge: payload.data.expiresIn,
  });

  return NextResponse.json({
    ok: true,
    companyId: payload.data.companyId,
    role: payload.data.role,
  });
}
```

curl equivalente:

```bash
curl -X POST http://localhost:8000/auth/setup-company \
  -H "Content-Type: application/json" \
  -H "X-Selection-Token: <selectionToken>" \
  -d '{"nombre": "Mi Empresa S.A.", "ruc": "80167684", "dv": "3", "ambiente": "DEV"}'
```

Respuesta:

```json
{
  "success": true,
  "message": "Empresa configurada correctamente",
  "data": {
    "accessToken": "eyJhbGciOi...",
    "refreshToken": "eyJhbGciOi...",
    "expiresIn": 3600,
    "role": "ADMIN",
    "companyId": 1
  }
}
```

> El `selectionToken` tiene una validez de **5 minutos**. Si expira, el usuario debe volver a registrarse o hacer login.

### Flujo completo de onboarding en UI

```
Formulario registro
  → POST /auth/register
  → guardar selectionToken en memoria (no en cookie, dura 5 min)
  → redirigir a /onboarding/empresa

Formulario primera empresa
  → POST /auth/setup-company (con X-Selection-Token)
  → guardar accessToken + refreshToken en cookies httpOnly
  → redirigir a /dashboard
```

## 6. Login con JWT

Para usuarios del dashboard administrativo, el flujo recomendado es:

1. El usuario envia email y password al frontend.
2. Un `Route Handler` de Next.js llama a `POST /auth/login`.
3. Next.js guarda `accessToken` y `refreshToken` en cookies `httpOnly`.
4. Las pantallas protegidas consumen la API desde el servidor usando ese token.

Ejemplo: `app/api/auth/login/route.ts`

```ts
import { cookies } from 'next/headers';
import { NextResponse } from 'next/server';

import type { LoginResponse, SifenApiResponse } from '@/lib/types';

export async function POST(request: Request) {
  const body = await request.json();

  const response = await fetch(`${process.env.SIFEN_API_URL}/auth/login`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
    cache: 'no-store',
  });

  const payload: SifenApiResponse<LoginResponse> = await response.json();

  if (!response.ok || !payload.success || !payload.data) {
    return NextResponse.json(
      { message: payload.error?.descripcion || payload.message || 'Credenciales invalidas' },
      { status: 401 },
    );
  }

  const cookieStore = await cookies();

  cookieStore.set('sifen_access_token', payload.data.accessToken, {
    httpOnly: true,
    sameSite: 'lax',
    secure: process.env.NODE_ENV === 'production',
    path: '/',
    maxAge: payload.data.expiresIn,
  });

  cookieStore.set('sifen_refresh_token', payload.data.refreshToken, {
    httpOnly: true,
    sameSite: 'lax',
    secure: process.env.NODE_ENV === 'production',
    path: '/',
    maxAge: 60 * 60 * 24 * 7,
  });

  cookieStore.set('sifen_role', payload.data.role, {
    httpOnly: true,
    sameSite: 'lax',
    secure: process.env.NODE_ENV === 'production',
    path: '/',
    maxAge: payload.data.expiresIn,
  });

  return NextResponse.json({ ok: true, role: payload.data.role, companyId: payload.data.companyId });
}
```

## 6. Refresh de token

El backend expone `POST /auth/refresh`. La renovacion debe hacerse del lado servidor cuando:

- una request recibe `401`;
- el frontend detecta sesion expirada;
- o al entrar a una ruta protegida si queres refrescar de forma preventiva.

Ejemplo de helper:

```ts
import { cookies } from 'next/headers';

import type { LoginResponse, SifenApiResponse } from '@/lib/types';

export async function refreshSession() {
  const cookieStore = await cookies();
  const refreshToken = cookieStore.get('sifen_refresh_token')?.value;

  if (!refreshToken) {
    return null;
  }

  const response = await fetch(`${process.env.SIFEN_API_URL}/auth/refresh`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ refreshToken }),
    cache: 'no-store',
  });

  const payload: SifenApiResponse<LoginResponse> = await response.json();

  if (!response.ok || !payload.success || !payload.data) {
    return null;
  }

  cookieStore.set('sifen_access_token', payload.data.accessToken, {
    httpOnly: true,
    sameSite: 'lax',
    secure: process.env.NODE_ENV === 'production',
    path: '/',
    maxAge: payload.data.expiresIn,
  });

  cookieStore.set('sifen_refresh_token', payload.data.refreshToken, {
    httpOnly: true,
    sameSite: 'lax',
    secure: process.env.NODE_ENV === 'production',
    path: '/',
    maxAge: 60 * 60 * 24 * 7,
  });

  return payload.data;
}
```

## 7. Middleware y proteccion de rutas

Usa `middleware.ts` para proteger rutas del dashboard y redirigir al login si no existe cookie de sesion.

Ejemplo minimo:

```ts
import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

export function middleware(request: NextRequest) {
  const token = request.cookies.get('sifen_access_token')?.value;
  const isAuthRoute = request.nextUrl.pathname.startsWith('/login');
  const isProtectedRoute = request.nextUrl.pathname.startsWith('/dashboard');

  if (!token && isProtectedRoute) {
    return NextResponse.redirect(new URL('/login', request.url));
  }

  if (token && isAuthRoute) {
    return NextResponse.redirect(new URL('/dashboard', request.url));
  }

  return NextResponse.next();
}

export const config = {
  matcher: ['/dashboard/:path*', '/login'],
};
```

## 8. Administracion de empresas

Todos los endpoints `/companies/{id}/**` requieren JWT de un usuario `ADMIN` con membresía activa en esa empresa.

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

Respuesta:

```json
{
  "success": true,
  "message": "Empresa creada correctamente",
  "data": {
    "id": 1,
    "nombre": "Mi Empresa S.A.",
    "ruc": "80167684",
    "dv": "3",
    "ambiente": "DEV",
    "cscId": null,
    "hasCertificado": false,
    "hasEmisorConfig": false,
    "habilitarNt13": true,
    "active": true,
    "createdAt": "2026-04-27T10:00:00"
  }
}
```

Campos opcionales en la creacion: `cscId`, `cscValor` (si ya contás con el CSC al momento de crear).

```ts
await sifenRequest('/companies', {
  method: 'POST',
  accessToken,
  body: {
    nombre: 'Mi Empresa S.A.',
    ruc: '80167684',
    dv: '3',
    ambiente: 'DEV',
    habilitarNt13: true,
  },
});
```

### Listar mis empresas

```bash
curl http://localhost:8000/companies \
  -H "Authorization: Bearer $TOKEN"
```

```ts
const companies = await sifenRequest('/companies', { accessToken });
```

### Obtener empresa por ID

```bash
curl http://localhost:8000/companies/1 \
  -H "Authorization: Bearer $TOKEN"
```

### Actualizar empresa

```bash
curl -X PUT http://localhost:8000/companies/1 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "nombre": "Mi Empresa Actualizada S.A.",
    "ruc": "80167684",
    "dv": "3",
    "ambiente": "PROD",
    "habilitarNt13": true
  }'
```

```ts
await sifenRequest('/companies/1', {
  method: 'PUT',
  accessToken,
  body: {
    nombre: 'Mi Empresa Actualizada S.A.',
    ruc: '80167684',
    dv: '3',
    ambiente: 'PROD',
    habilitarNt13: true,
  },
});
```

### Desactivar empresa

```bash
curl -X DELETE http://localhost:8000/companies/1 \
  -H "Authorization: Bearer $TOKEN"
```

### Subir certificado PFX

La subida del certificado debe hacerse desde un `Route Handler` o `Server Action`, porque incluye archivo y password sensible.

```bash
curl -X POST http://localhost:8000/companies/1/certificate \
  -H "Authorization: Bearer $TOKEN" \
  -F "certificate=@/ruta/al/certificado.pfx" \
  -F "password=mi_password_del_pfx"
```

```ts
const formData = new FormData();
formData.append('certificate', file);
formData.append('password', password);

await fetch(`${process.env.SIFEN_API_URL}/companies/${companyId}/certificate`, {
  method: 'POST',
  headers: { Authorization: `Bearer ${accessToken}` },
  body: formData,
  cache: 'no-store',
});
```

Respuesta: el objeto `CompanyResponse` actualizado con `hasCertificado: true`.

### Eliminar certificado

```bash
curl -X DELETE http://localhost:8000/companies/1/certificate \
  -H "Authorization: Bearer $TOKEN"
```

### Actualizar CSC

El CSC (Código de Seguridad del Contribuyente) es requerido para emitir documentos. Se obtiene desde el portal de la SET.

```bash
curl -X PUT http://localhost:8000/companies/1/csc \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "cscId": "0001",
    "cscValor": "AAAA00000000000000000000000000000000000000"
  }'
```

```ts
await sifenRequest('/companies/1/csc', {
  method: 'PUT',
  accessToken,
  body: {
    cscId: '0001',
    cscValor: 'AAAA00000000000000000000000000000000000000',
  },
});
```

### Configurar datos del emisor

El emisor define los datos del contribuyente que aparecerán en los documentos electrónicos. Incluye actividades económicas, establecimientos (puntos de expedición), timbrado y otros datos tributarios.

```bash
curl -X PUT http://localhost:8000/companies/1/emisor \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "version": 150,
    "ruc": "80167684",
    "razonSocial": "Mi Empresa S.A.",
    "nombreFantasia": "Mi Empresa",
    "timbradoNumero": "12345678",
    "timbradoFecha": "2025-01-01",
    "tipoContribuyente": 2,
    "tipoRegimen": 8,
    "actividadesEconomicas": [
      { "codigo": "46900", "descripcion": "Venta al por mayor" }
    ],
    "establecimientos": [
      {
        "codigo": "001",
        "direccion": "Av. Mcal. Lopez",
        "numeroCasa": "1234",
        "departamento": 11,
        "departamentoDescripcion": "CENTRAL",
        "distrito": 1,
        "distritoDescripcion": "LUQUE",
        "ciudad": 3,
        "ciudadDescripcion": "LUQUE",
        "telefono": "0981000000",
        "email": "facturacion@miempresa.com",
        "denominacion": "Casa Central"
      }
    ]
  }'
```

```ts
await sifenRequest('/companies/1/emisor', {
  method: 'PUT',
  accessToken,
  body: { /* mismos campos del curl */ },
});
```

### Obtener configuración del emisor

```bash
curl http://localhost:8000/companies/1/emisor \
  -H "Authorization: Bearer $TOKEN"
```

```ts
const emisorConfig = await sifenRequest('/companies/1/emisor', { accessToken });
```

### Gestión de miembros

Permite agregar usuarios existentes a la empresa o listar/remover los actuales.

```bash
# Agregar miembro existente (por email)
curl -X POST http://localhost:8000/companies/1/members \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"email": "operador@empresa.com", "role": "USER"}'

# Listar miembros
curl http://localhost:8000/companies/1/members \
  -H "Authorization: Bearer $TOKEN"

# Remover miembro
curl -X DELETE http://localhost:8000/companies/1/members/5 \
  -H "Authorization: Bearer $TOKEN"
```

## 9. Administracion de API Keys

Las API Keys se gestionan bajo la ruta de la empresa: `/companies/{id}/api-keys`. Requieren JWT de un usuario `ADMIN` con membresía activa en esa empresa.

Flujo sugerido para el frontend:

1. El administrador ingresa al módulo de integraciones de una empresa.
2. El frontend lista las API Keys existentes con `GET /companies/{id}/api-keys`.
3. Al crear una nueva, mostrar `rawKey` una sola vez y forzar al usuario a copiarla.
4. Nunca volver a exponer `rawKey` desde el frontend una vez cerrada esa vista.

### Crear API Key

```bash
curl -X POST http://localhost:8000/companies/1/api-keys \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "POS Caja 1"}'
```

Respuesta:

```json
{
  "success": true,
  "message": "API Key creada. Guarde el valor, no se mostrará de nuevo.",
  "data": {
    "id": 1,
    "keyPrefix": "sw_live_aBcDeF",
    "name": "POS Caja 1",
    "active": true,
    "expiresAt": null,
    "createdAt": "2026-04-27T10:00:00",
    "rawKey": "sw_live_aBcDeFgHiJkLmNoPqRsTuVwXyZ"
  }
}
```

> `rawKey` solo aparece en esta respuesta. Una vez cerrada la vista, no puede recuperarse.

```ts
const createdKey = await sifenRequest<ApiKeyResponse>('/companies/1/api-keys', {
  method: 'POST',
  accessToken,
  body: { name: 'POS Caja 1' },
});

// createdKey.rawKey solo estara presente en este momento
```

### Listar API Keys

```bash
curl http://localhost:8000/companies/1/api-keys \
  -H "Authorization: Bearer $TOKEN"
```

```ts
const keys = await sifenRequest<ApiKeyResponse[]>('/companies/1/api-keys', { accessToken });
```

### Revocar API Key

```bash
curl -X DELETE http://localhost:8000/companies/1/api-keys/3 \
  -H "Authorization: Bearer $TOKEN"
```

```ts
await sifenRequest('/companies/1/api-keys/3', { method: 'DELETE', accessToken });
```

## 9b. Gestión de usuarios por empresa

Los usuarios se crean y administran bajo la ruta de la empresa: `/companies/{id}/users`.

> **Diferencia con miembros:** `POST /companies/{id}/users` crea un usuario nuevo y lo agrega a la empresa en un solo paso. `POST /companies/{id}/members` agrega un usuario que ya existe en el sistema.

### Crear usuario en la empresa

```bash
curl -X POST http://localhost:8000/companies/1/users \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "operador@empresa.com",
    "password": "password123",
    "fullName": "Operador Caja",
    "role": "USER"
  }'
```

El campo `role` acepta `ADMIN` o `USER`.

### Listar usuarios de la empresa

```bash
curl http://localhost:8000/companies/1/users \
  -H "Authorization: Bearer $TOKEN"
```

### Actualizar usuario

```bash
curl -X PATCH http://localhost:8000/companies/1/users/5 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"fullName": "Nuevo Nombre", "password": "nuevaPassword"}'
```

Solo se actualizan los campos enviados (`email`, `fullName`, `password`).

### Desactivar usuario de la empresa

```bash
curl -X DELETE http://localhost:8000/companies/1/users/5 \
  -H "Authorization: Bearer $TOKEN"
```

Desactiva la membresía del usuario en esa empresa (no elimina la cuenta).

## 10. Flujo recomendado de facturacion en frontend

Para ambiente productivo, el flujo recomendado por el proyecto es:

1. `POST /invoices/prepare`
2. imprimir ticket o mostrar comprobante con `cdc` y `qrUrl`
3. consultar `GET /invoices/{cdc}/status` cuando se necesite
4. usar `?refresh=true` solo si se necesita una consulta inmediata

No se recomienda basar el frontend principal en `POST /invoices/emit` para produccion.

### Preparar factura

Ejemplo server-side:

```ts
const prepared = await sifenRequest<PrepareInvoiceResponse>('/invoices/prepare', {
  method: 'POST',
  accessToken,
  body: {
    data: {
      tipoDocumento: 1,
      establecimiento: '001',
      punto: '001',
      numero: '0000025',
      descripcion: 'Factura electronica',
      fecha: '2026-03-20T10:30:00',
      tipoEmision: 1,
      tipoTransaccion: 1,
      tipoImpuesto: 1,
      moneda: 'PYG',
      cliente: {
        contribuyente: true,
        ruc: '80069563-1',
        razonSocial: 'TIPS S.A',
        tipoOperacion: 1,
        direccion: 'Asuncion',
        numeroCasa: '123',
        departamento: 1,
        departamentoDescripcion: 'CAPITAL',
        distrito: 1,
        distritoDescripcion: 'ASUNCION',
        ciudad: 1,
        ciudadDescripcion: 'ASUNCION',
        pais: 'PRY',
        tipoContribuyente: 2,
        codigo: 'CLI-01',
      },
      factura: {
        presencia: 1,
      },
      condicion: {
        tipo: 1,
        entregas: [{ tipo: 1, monto: 10000, moneda: 'PYG' }],
      },
      items: [
        {
          codigo: 'SKU-001',
          descripcion: 'Servicio de implementacion',
          cantidad: 1,
          precioUnitario: 10000,
          ivaTipo: 1,
          iva: 10,
          ivaProporcion: 100,
          unidadMedida: 77,
        },
      ],
    },
  },
});
```

### UX recomendada luego de `prepare`

Cuando la API responde:

- mostrar `cdc` en pantalla;
- renderizar el QR usando `qrUrl`;
- guardar el `cdc` en la venta local del frontend o del sistema cliente;
- marcar visualmente el documento como `PREPARADO`;
- ofrecer una accion de consulta de estado.

## 11. Consulta de estado y polling controlado

El endpoint principal para seguimiento es `GET /invoices/{cdc}/status`.

Estados posibles:

- `PREPARADO`
- `ENVIADO`
- `APROBADO`
- `APROBADO_CON_OBSERVACION`
- `RECHAZADO`
- `ERROR`

Recomendacion de frontend:

- polling corto solo mientras el usuario este mirando el detalle del documento;
- si el estado es final (`APROBADO`, `APROBADO_CON_OBSERVACION`, `RECHAZADO`), detener polling;
- evitar polling agresivo cada pocos segundos sobre muchas facturas.

Ejemplo con React Query o polling manual:

```ts
async function getInvoiceStatus(cdc: string, refresh = false) {
  const suffix = refresh ? '?refresh=true' : '';

  return sifenRequest(`/invoices/${cdc}/status${suffix}`, {
    accessToken,
  });
}
```

## 12. Reenvio de email de factura aprobada

El frontend puede ofrecer un boton de reenvio desde el detalle del comprobante:

```ts
await sifenRequest(`/invoices/${cdc}/resend-email`, {
  method: 'POST',
  accessToken,
});
```

Casos a contemplar en UI:

- si la factura no esta aprobada, informar que el correo puede no enviarse;
- si el backend no tiene `RESEND_API_KEY`, el backend omitira el envio y registrara logs;
- mostrar `email`, `reason` y `resendId` cuando existan en la respuesta.

## 13. Generacion de KUDE

Hay dos formas de integrarlo en frontend:

- `POST /invoices/kude`: devuelve PDF binario;
- `POST /invoices/kude/base64`: devuelve el PDF como string base64 dentro de JSON.

Recomendacion:

- usar `/kude` si queres descargar o abrir el PDF en nueva pestaña;
- usar `/kude/base64` si necesitas embebido dentro de una respuesta JSON o visor custom.

Ejemplo para descarga:

```ts
const response = await fetch('/api/invoices/kude', {
  method: 'POST',
  body: JSON.stringify(payload),
});

const blob = await response.blob();
const url = URL.createObjectURL(blob);
window.open(url, '_blank');
```

## 14. Soporte multi-tenant en UI

El backend aisla operaciones por `companyId` en JWT o API Key. En el frontend eso implica:

- mostrar la empresa activa en el layout;
- restringir modulos segun `role`;
- si un usuario pertenece a una empresa, consumir datos siempre bajo ese contexto autenticado;
- si existe un panel superadmin, validar bien que el backend y el frontend no mezclen sesiones o company switching sin relogin o un flujo explicito.

## 15. Estructura sugerida del proyecto Next.js

```text
src/
  app/
    api/
      auth/
        login/route.ts
        refresh/route.ts
      companies/
        route.ts
      invoices/
        prepare/route.ts
        [cdc]/status/route.ts
    dashboard/
      companies/page.tsx
      invoices/page.tsx
      integrations/page.tsx
    login/page.tsx
  components/
    forms/
    invoices/
    layout/
  lib/
    auth.ts
    sifen-client.ts
    types.ts
```

## 16. Recomendaciones operativas

- No expongas `accessToken`, `refreshToken` ni `rawKey` a `localStorage` salvo que tengas una razon fuerte y controles el riesgo.
- Ejecuta las llamadas administrativas y sensibles desde el servidor de Next.js.
- Usa el flujo `prepare` como camino principal para emision productiva.
- Trata `rawKey` como secreto de una sola visualizacion.
- Si integras upload de certificado, procesa `multipart/form-data` del lado servidor.
- Maneja los estados finales y no finales de factura de forma distinta en la UI.

## 17. Roadmap sugerido para el frontend

Orden recomendado de implementacion:

1. Registro + onboarding (crear empresa).
2. Login + logout + refresh de sesion.
3. Layout privado y middleware.
4. CRUD de empresas.
5. Configuracion de certificado, CSC y emisor.
6. Modulo de API Keys.
7. Flujo `prepare` con vista de detalle por `cdc`.
8. Consulta de estado y reenvio de email.
9. Generacion y visualizacion de KUDE.

## 18. Seguimiento de Logs y Eventos (Trazabilidad)

Para cumplir con el requerimiento de visibilidad de eventos sin necesidad de ingresar al servidor, el backend provee un módulo de logs a través de la API.

Desde el frontend se recomienda implementar una pantalla "Logs de Eventos" con dos vistas o pestañas:

### 18.0. Prerrequisito de seguridad para exponer `/logs/**`

Con la configuracion actual de seguridad, las rutas `/logs/**` no estan habilitadas explicitamente y quedan alcanzadas por `anyRequest().denyAll()`.

Antes de integrar frontend, habilita estos endpoints en `SecurityConfig` segun tu politica:

- opcion A: `authenticated()` para cualquier usuario autenticado;
- opcion B: `hasRole('ADMIN')` si solo queres acceso administrativo.

Ejemplo de regla sugerida:

```java
.requestMatchers("/logs/**").hasRole("ADMIN")
```

Sin este ajuste, el frontend recibira `403 Forbidden` al consultar logs.

### 18.0.1. Contrato de paginacion real (`PageResponse<T>`)

Ambos endpoints de logs devuelven el mismo envelope:

```json
{
  "success": true,
  "data": {
    "content": [],
    "pageNumber": 0,
    "pageSize": 20,
    "totalElements": 150,
    "totalPages": 8,
    "last": false
  }
}
```

Tipos recomendados para frontend:

```ts
export type PageResponse<T> = {
  content: T[];
  pageNumber: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
};

export type ElectronicDocumentLogDTO = {
  id: number;
  companyId: number;
  cdc: string;
  tipoDocumento: number;
  numero: string;
  establecimiento: string;
  punto: string;
  estado: string;
  nroLote: string | null;
  sifenCodigo: string | null;
  sifenMensaje: string | null;
  requestData: Record<string, unknown> | string | null;
  responseData: Record<string, unknown> | string | null;
  createdAt: string;
  sentAt: string | null;
  processedAt: string | null;
};

export type AuditLogDTO = {
  id: number;
  companyId: number;
  userId: number | null;
  action: string;
  resource: string;
  detail: string | null;
  ip: string | null;
  createdAt: string;
};
```

Nota importante sobre payloads JSON:

- `requestData` y `responseData` se parsean en backend;
- si el parseo JSON falla, backend retorna el contenido como `string`;
- por eso en frontend conviene tipar ambos campos como `object | string | null`.

### 18.1. Logs de Transacciones (Facturación y Lotes)

Consulta del historial de Documentos Electrónicos (facturas emitidas), lotes enviados, a qué empresa corresponden y los resultados de SIFEN (`requestData` y `responseData`).

Endpoint:

- `GET /logs/transactions`

Query params soportados por controlador:

- `companyId` (opcional)
- `estado` (opcional)
- `page` (opcional, default `0`)
- `size` (opcional, default `20`)

Comportamiento real del servicio:

- ordena por `createdAt DESC`;
- aplica filtros combinables por `companyId` y `estado`;
- en contexto tenant (usuario de empresa), ignora `companyId` enviado por query y fuerza el `companyId` del tenant autenticado.

Ejemplo de integración para obtener resultados paginados:

```ts
const transactionsResponse = await sifenRequest<PageResponse<ElectronicDocumentLogDTO>>('/logs/transactions?page=0&size=20', {
  accessToken,
});

const data = transactionsResponse;
```

Ejemplo con filtro de estado:

```ts
const transactionsApproved = await sifenRequest<PageResponse<ElectronicDocumentLogDTO>>(
  '/logs/transactions?estado=APROBADO&page=0&size=20',
  { accessToken },
);
```

**Recomendación de UI:**
- Usar una tabla paginada.
- Mostrar columnas clave: `Empresa ID`, `CDC`, `Nro. Lote`, `Estado SIFEN`, `Código SIFEN`, `Fecha Envío`.
- Agregar un botón "Ver Payload" para abrir un Modal que muestre en formato JSON el request y response exactos enviados a la SET.
- Si `requestData` o `responseData` llega como string, intentar `JSON.parse` con fallback a texto plano.

### 18.2. Logs de Auditoría (Sistema)

Consulta de los eventos administrativos del sistema (login, creación de empresa, carga de certificados, etc.).

Endpoint:

- `GET /logs/audit`

Query params soportados por controlador:

- `companyId` (opcional)
- `page` (opcional, default `0`)
- `size` (opcional, default `20`)

Comportamiento real del servicio:

- ordena por `createdAt DESC`;
- en contexto tenant (usuario de empresa), ignora `companyId` enviado por query y fuerza el `companyId` del tenant autenticado.

```ts
const auditResponse = await sifenRequest<PageResponse<AuditLogDTO>>('/logs/audit?page=0&size=20', {
  accessToken,
});

const data = auditResponse;
```

**Recomendación de UI:**
- Mostrar columnas: `Fecha`, `Acción`, `Usuario ID`, `Recurso`, `Detalle`, `IP`.
- Permitir filtro por empresa solo para perfiles globales (si habilitas ese caso).

### 18.3. Helper recomendado para consumo de logs

```ts
type GetLogsParams = {
  page?: number;
  size?: number;
  companyId?: number;
  estado?: string;
};

function toQuery(params: Record<string, string | number | undefined>) {
  const query = new URLSearchParams();

  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && `${value}`.length > 0) {
      query.set(key, String(value));
    }
  });

  return query.toString();
}

export async function getTransactionLogs(accessToken: string, params: GetLogsParams = {}) {
  const query = toQuery({
    page: params.page ?? 0,
    size: params.size ?? 20,
    companyId: params.companyId,
    estado: params.estado,
  });

  return sifenRequest<PageResponse<ElectronicDocumentLogDTO>>(`/logs/transactions?${query}`, {
    accessToken,
  });
}

export async function getAuditLogs(
  accessToken: string,
  params: Pick<GetLogsParams, 'page' | 'size' | 'companyId'> = {},
) {
  const query = toQuery({
    page: params.page ?? 0,
    size: params.size ?? 20,
    companyId: params.companyId,
  });

  return sifenRequest<PageResponse<AuditLogDTO>>(`/logs/audit?${query}`, {
    accessToken,
  });
}
```

## 19. Resumen de endpoints a integrar primero

### Públicos

- `POST /auth/register`
- `POST /auth/setup-company` *(requiere `X-Selection-Token`)*
- `POST /auth/login`
- `POST /auth/refresh`
- `POST /auth/switch-company` *(requiere `X-Selection-Token`, para login multi-empresa)*

### Admin JWT

- `GET /companies`
- `POST /companies`
- `GET /companies/{id}`
- `PUT /companies/{id}`
- `DELETE /companies/{id}`
- `POST /companies/{id}/certificate`
- `DELETE /companies/{id}/certificate`
- `PUT /companies/{id}/csc`
- `PUT /companies/{id}/emisor`
- `GET /companies/{id}/emisor`
- `POST /companies/{id}/members`
- `GET /companies/{id}/members`
- `DELETE /companies/{id}/members/{userId}`
- `POST /companies/{id}/api-keys`
- `GET /companies/{id}/api-keys`
- `DELETE /companies/{id}/api-keys/{keyId}`
- `POST /companies/{id}/users`
- `GET /companies/{id}/users`
- `GET /companies/{id}/users/{userId}`
- `PATCH /companies/{id}/users/{userId}`
- `DELETE /companies/{id}/users/{userId}`
- `GET /logs/transactions`
- `GET /logs/audit`

### Facturacion autenticada

- `POST /invoices/prepare`
- `POST /invoices/prepare/batch`
- `GET /invoices/{cdc}/status`
- `POST /invoices/{cdc}/resend-email`
- `POST /invoices/kude`
- `POST /invoices/kude/base64`

Con esta base, el frontend en Next.js queda alineado con los flujos reales del backend y con la recomendacion operativa del proyecto para produccion.