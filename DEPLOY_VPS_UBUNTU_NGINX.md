# Despliegue en VPS Ubuntu + Nginx (Producción)

Guia practica para desplegar `sifen-wrapper` en un VPS Ubuntu con Nginx como reverse proxy.

Esta version esta ajustada a una configuracion Nginx donde ya existen sitios activos en `sites-enabled`:

- `default`
- `ocre-pos`

El despliegue agrega un tercer sitio (`sifen-wrapper`) sin romper los existentes.

## 1. Supuestos

- VPS con Ubuntu 22.04/24.04.
- Dominio apuntando al VPS (ejemplo: `sifenapi.ratones.dev`).
- Acceso SSH con usuario sudo.
- Puerto interno de la app: `18000`.

Nota: en este servidor el puerto `8000` ya estaba ocupado por Coolify.

## 2. Preparar servidor

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y git curl unzip ufw nginx postgresql postgresql-contrib openjdk-17-jre-headless
```

Configurar firewall:

```bash
sudo ufw allow OpenSSH
sudo ufw allow 'Nginx Full'
sudo ufw --force enable
sudo ufw status
```

## 3. Crear usuario de servicio y rutas

```bash
sudo useradd --system --create-home --shell /usr/sbin/nologin sifen
sudo mkdir -p /opt/sifen-wrapper
sudo chown -R sifen:sifen /opt/sifen-wrapper
```

## 4. Configurar PostgreSQL

Entrar a PostgreSQL:

```bash
sudo -u postgres psql
```

Crear usuario y base:

```sql
CREATE USER sifen_app WITH PASSWORD 'CAMBIAR_PASSWORD_FUERTE';
CREATE DATABASE sifen_wrapper OWNER sifen_app;
\q
```

## 4.1. Habilitar conexión remota a PostgreSQL con `sifen_app` (opcional)

Si la app o una herramienta de administración se conectará desde otra máquina, además de crear el usuario `sifen_app` debes permitir conexiones remotas en PostgreSQL.

1. Editar `postgresql.conf` para escuchar fuera de localhost:

```bash
sudo nano /etc/postgresql/16/main/postgresql.conf
```

Buscar y ajustar:

```conf
listen_addresses = '*'
```

Nota: si tu versión instalada no es `16`, reemplaza la ruta por la versión real (`15`, `14`, etc.). Puedes verla con:

```bash
ls /etc/postgresql/
```

2. Autorizar la IP origen en `pg_hba.conf` usando el usuario `sifen_app`:

```bash
sudo nano /etc/postgresql/16/main/pg_hba.conf
```

Agregar una línea como esta al final:

```conf
host    sifen_wrapper    sifen_app    186.158.200.88
scram-sha-256
```

Ejemplos:

```conf
host    sifen_wrapper    sifen_app    203.0.113.10/32    scram-sha-256
host    sifen_wrapper    sifen_app    192.168.1.50/32    scram-sha-256
```

Usa `/32` para una sola IP. Evita abrir `0.0.0.0/0` en producción salvo que tengas una razón fuerte y controles adicionales.

3. Abrir el puerto 5432 solo para la IP autorizada:

```bash
sudo ufw allow from 186.158.200.88 to any port 5432 proto tcp
sudo ufw status
```

4. Reiniciar PostgreSQL y validar que quedó escuchando en red:

```bash
sudo systemctl restart postgresql
sudo systemctl status postgresql --no-pager
sudo ss -lntp | grep 5432
```

5. Probar la conexión remota con el usuario `sifen_app` desde la máquina cliente:

```bash
psql "host=IP_DEL_VPS port=5432 dbname=sifen_wrapper user=sifen_app sslmode=prefer"
```

Si quieres forzar TLS desde el cliente:

```bash
psql "host=IP_DEL_VPS port=5432 dbname=sifen_wrapper user=sifen_app sslmode=require"
```

6. Verificación rápida de permisos ya dentro de PostgreSQL:

```sql
\conninfo
\dt
SELECT current_user, current_database();
```

Recomendación: si solo necesitas acceso administrativo ocasional, es más seguro usar un túnel SSH en vez de exponer PostgreSQL públicamente.

Ejemplo con túnel SSH:

```bash
ssh -L 5432:127.0.0.1:5432 usuario@IP_DEL_VPS
psql "host=127.0.0.1 port=5432 dbname=sifen_wrapper user=sifen_app"
```

## 5. Generar artefacto (JAR)

### Opción A (recomendada): construir en tu máquina y subir

En tu máquina local, desde la raíz del proyecto:

```bash
mvn clean package -DskipTests
```

Subir JAR al VPS:

```bash
scp /Users/mauricio/sifen-api/target/sifen-wrapper-1.0.0.jar root@5.78.122.42:/tmp/sifen-wrapper.jar
ssh root@5.78.122.42
sudo mv /tmp/sifen-wrapper.jar /opt/sifen-wrapper/app.jar
sudo chown sifen:sifen /opt/sifen-wrapper/app.jar
sudo systemctl restart sifen-wrapper
sudo systemctl status sifen-wrapper --no-pager
```

Importante: si el servicio ya estaba corriendo, `start` no carga el nuevo JAR; necesitas `restart` para que Java levante el artefacto actualizado.

### Opción B: construir en el VPS

```bash
sudo apt install -y maven
cd /opt/sifen-wrapper
git clone https://TU_REPO.git src
cd src
mvn clean package -DskipTests
sudo cp target/sifen-wrapper-1.0.0.jar /opt/sifen-wrapper/app.jar
sudo chown sifen:sifen /opt/sifen-wrapper/app.jar
sudo systemctl restart sifen-wrapper
sudo systemctl status sifen-wrapper --no-pager
```

## 6. Variables de entorno de producción

Crear archivo de entorno:

```bash
sudo tee /etc/sifen-wrapper.env > /dev/null <<'EOF'
DB_HOST=127.0.0.1
DB_PORT=5432
DB_NAME=sifen_wrapper
DB_USER=sifen_app
DB_PASS=CAMBIAR_PASSWORD_FUERTE

JWT_SECRET=CAMBIAR_SECRETO_LARGO_MIN_32_CARACTERES
ENCRYPTION_KEY=CAMBIAR_BASE64_32_BYTES
SERVER_PORT=18000

# Opcional: ajustar logs en producción
LOGGING_LEVEL_COM_RATONES=INFO
LOGGING_LEVEL_COM_ROSHKA_SIFEN=INFO
EOF
```

Si PostgreSQL corre en otro servidor o habilitaste acceso remoto, cambia `DB_HOST` por la IP privada, IP pública o DNS del servidor de base de datos.

Si ya llegaste a usar credenciales reales en archivos o historial shell, rotalas antes de pasar a produccion.

Generar `ENCRYPTION_KEY` válida (32 bytes en base64):

```bash
openssl rand -base64 32
```

Proteger archivo:

```bash
sudo chown root:root /etc/sifen-wrapper.env
sudo chmod 600 /etc/sifen-wrapper.env
```

Si vienes de una instalacion previa en `8000`, aplicar ajuste de puerto:

```bash
echo 'SERVER_PORT=18000' | sudo tee -a /etc/sifen-wrapper.env
```

## 7. Crear servicio systemd

```bash
sudo tee /etc/systemd/system/sifen-wrapper.service > /dev/null <<'EOF'
[Unit]
Description=Sifen Wrapper API
After=network.target postgresql.service

[Service]
Type=simple
User=sifen
Group=sifen
WorkingDirectory=/opt/sifen-wrapper
EnvironmentFile=/etc/sifen-wrapper.env
Environment=TZ=America/Asuncion
ExecStart=/usr/bin/java -Duser.timezone=America/Asuncion -Xms256m -Xmx1024m -jar /opt/sifen-wrapper/app.jar
SuccessExitStatus=143
Restart=always
RestartSec=5

# Hardening
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
ProtectHome=true
ReadWritePaths=/opt/sifen-wrapper

[Install]
WantedBy=multi-user.target
EOF
```

Activar servicio:

```bash
sudo systemctl daemon-reload
sudo systemctl enable sifen-wrapper
sudo systemctl start sifen-wrapper
sudo systemctl status sifen-wrapper --no-pager
```

Si estas desplegando una nueva version y el servicio ya existe, usa `sudo systemctl restart sifen-wrapper` en lugar de `start`.

Verificar reloj y zona horaria del host (importante para firma SIFEN):

```bash
timedatectl
date
date -u
```

Ver logs:

```bash
sudo journalctl -u sifen-wrapper -f
```

## 8. Configurar Nginx como reverse proxy

Verificar estado actual (deberias ver `default` y `ocre-pos`):

```bash
ls -la /etc/nginx/sites-available/
ls -la /etc/nginx/sites-enabled/
```

No elimines `ocre-pos`. Puedes mantener `default` mientras no interfiera con tu subdominio de API.

Crear sitio:

```bash
sudo tee /etc/nginx/sites-available/sifen-wrapper > /dev/null <<'EOF'
server {
    listen 80;
    server_name sifenapi.ratones.dev;

    client_max_body_size 12m;

    location / {
      proxy_pass http://127.0.0.1:18000;
        proxy_http_version 1.1;

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";

        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }
}
EOF
```

Habilitar sitio y validar:

```bash
sudo ln -s /etc/nginx/sites-available/sifen-wrapper /etc/nginx/sites-enabled/sifen-wrapper 2>/dev/null || true
sudo nginx -t
sudo systemctl reload nginx
```

## 9. HTTPS con Let's Encrypt

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d sifenapi.ratones.dev
```

Nota: Certbot agregara automaticamente un bloque `listen 443 ssl` similar al que ya tienes en `ocre-pos`, usando `options-ssl-nginx.conf` y `ssl-dhparams.pem`. No necesitas modificar `nginx.conf` global.

Probar renovación automática:

```bash
sudo certbot renew --dry-run
```

## 10. Verificación final

Health básico desde VPS:

```bash
curl -i -X POST http://127.0.0.1:18000/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@synctema.com","password":"admin123"}'
```

Verificación por dominio:

```bash
curl -i -X POST https://sifenapi.ratones.dev/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@synctema.com","password":"admin123"}'
```

Si el login devuelve JSON del backend (token o error de credenciales), el proxy está funcionando.

Estado validado en este servidor:

- `http://127.0.0.1:18000/auth/login` responde `HTTP/1.1 200`.
- `https://sifenapi.ratones.dev/auth/login` responde `HTTP/2 200`.
- Ambos devuelven JSON `{"success":true,"message":"Login exitoso",...}`.

## 10.1 Diagnóstico rápido si aparece HTML/419 de Coolify

Si `https://sifenapi.ratones.dev/auth/login` devuelve HTML de Coolify (ej. error 419), el dominio no está llegando a este Nginx + app, sino al panel/ingress de Coolify.

1) Verificar DNS del dominio:

```bash
dig +short sifenapi.ratones.dev A
dig +short sifenapi.ratones.dev AAAA
```

La IP debe coincidir con tu VPS (ejemplo esperado: `5.78.122.42`).

2) Verificar que Nginx local enruta por `server_name` correcto:

```bash
curl -i -H "Host: sifenapi.ratones.dev" http://127.0.0.1/
```

3) Verificar que el sitio esté cargado:

```bash
ls -la /etc/nginx/sites-enabled/
sudo nginx -T | grep -n "server_name sifenapi.ratones.dev"
```

4) Verificar que el backend responde en localhost:

```bash
curl -i http://127.0.0.1:18000/auth/login
sudo journalctl -u sifen-wrapper -n 100 --no-pager
```

5) Si usas Cloudflare/CDN proxy, desactiva temporalmente el proxy (modo DNS only) para validar origen.

6) Si el dominio está administrado por Coolify, crea en Coolify una regla/servicio que enrute ese host a `http://127.0.0.1:8000`, o mueve el dominio para que apunte directamente al VPS con este Nginx.

7) Detectar conflicto de puertos con Coolify:

```bash
sudo ss -lntp | grep :443
sudo docker ps --format '{{.ID}} {{.Names}} {{.Ports}}' | grep -Ei 'coolify|traefik|caddy|:443|:8000'
```

Si Coolify ocupa `:8000`, mantener la API en `:18000` y apuntar Nginx a ese puerto.

## 11. Operación diaria

Reiniciar servicio:

```bash
sudo systemctl restart sifen-wrapper
```

Estado:

```bash
sudo systemctl status sifen-wrapper --no-pager
```

Logs en vivo:

```bash
sudo journalctl -u sifen-wrapper -f
```

## 12. Actualización de versión

1. Subir nuevo JAR a `/opt/sifen-wrapper/app.jar`.
2. Mantener `/etc/sifen-wrapper.env`.
3. Reiniciar servicio.
4. Verificar que el proceso haya arrancado nuevamente y que el `Active:` muestre la hora actual del despliegue.

```bash
sudo systemctl restart sifen-wrapper
sudo systemctl status sifen-wrapper --no-pager
sudo journalctl -u sifen-wrapper -n 50 --no-pager
```

Notas:

- `sudo systemctl daemon-reload` solo hace falta si cambiaste el archivo `.service`.
- `sudo systemctl reload nginx` solo hace falta si cambiaste la configuracion de Nginx; no es necesario para publicar cambios del JAR.

## 13. Nota funcional importante (PROD)

- El endpoint síncrono `POST /invoices/emit` está deprecado/no operativo en producción por restricción de SIFEN.
- Usar en producción el flujo asíncrono:
  - `POST /invoices/emit/batch`
  - `GET /invoices/batch/{nroLote}`

## 14. Checklist rápido

- `sifen-wrapper.service` en estado `active (running)`.
- Nginx `active (running)` y `nginx -t` OK.
- Certificado TLS válido en dominio.
- Variables `JWT_SECRET` y `ENCRYPTION_KEY` configuradas.
- Base PostgreSQL creada y accesible.
- Endpoint batch probado en producción.
