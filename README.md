# Back Ventas — Innovatech Chile

API REST para la gestión de órdenes de venta de Innovatech Chile. Expone un CRUD completo sobre la entidad `Venta` y actúa como servicio de origen para el flujo de despacho: cuando el frontend registra una venta y genera una orden de despacho, este servicio es actualizado para marcar el campo `despachoGenerado` en `true`.

---

## Arquitectura

```
GitHub (push a main)
       |
       v
GitHub Actions (CI/CD)
  |-- Build & Test (Maven + H2 en memoria)
  |-- Docker build & push --> Amazon ECR
  `-- Deploy --> AWS ECS Fargate
                     |
               +-----+-----+
               | ECS Task  |
               |  :8080    |
               +-----+-----+
                     |
                RDS MySQL
```

**Stack:**
- Java 17 + Spring Boot 3.4.4
- Spring Data JPA + Hibernate
- Bean Validation (Jakarta Validation)
- Lombok
- SpringDoc OpenAPI 2.7.0 (Swagger UI)
- H2 en memoria para tests (sin dependencia de MySQL)
- MySQL (AWS RDS) en producción
- Docker multietapa (Maven → JRE Alpine)
- AWS ECS Fargate + Amazon ECR
- CI/CD con GitHub Actions

---

## Endpoints

Base URL: `http://<ALB-DNS>/api/v1/ventas`
Swagger UI: `http://<ALB-DNS>/swagger-ui.html`

| Metodo | Endpoint | Descripcion | Respuesta exitosa |
|---|---|---|---|
| `GET` | `/api/v1/ventas` | Listar todas las ventas | `200 OK` |
| `GET` | `/api/v1/ventas/{idVenta}` | Obtener venta por ID | `200 OK` |
| `POST` | `/api/v1/ventas` | Crear nueva venta | `201 Created` |
| `PUT` | `/api/v1/ventas/{idVenta}` | Actualizar venta existente | `200 OK` |
| `DELETE` | `/api/v1/ventas/{idVenta}` | Eliminar venta | `204 No Content` |

### Modelo de datos (Venta)

| Campo | Tipo | Validacion | Descripcion |
|---|---|---|---|
| `idVenta` | `Long` | Generado automaticamente | Identificador unico |
| `direccionCompra` | `String` | `@NotBlank` | Direccion de entrega |
| `valorCompra` | `int` | — | Valor de la orden en pesos |
| `fechaCompra` | `LocalDate` | `@NotNull`, formato ISO | Fecha de la compra |
| `despachoGenerado` | `Boolean` | `@NotNull`, defecto `false` | Indica si ya se genero despacho |

### Ejemplo body (POST)

```json
{
  "direccionCompra": "Av. Providencia 1234, Santiago",
  "valorCompra": 35000,
  "fechaCompra": "2025-07-15",
  "despachoGenerado": false
}
```

### Ejemplo body (PUT — marcar despacho generado)

El servicio de Despachos llama a este endpoint luego de crear una orden de despacho:

```json
{
  "despachoGenerado": true
}
```

El `updateVenta` aplica actualizacion parcial: solo sobreescribe los campos que vienen con valor no nulo en el body.

### Respuestas de error

| Codigo | Situacion |
|---|---|
| `404 Not Found` | Venta no encontrada por el ID indicado |
| `400 Bad Request` | Body invalido (validaciones de campo fallidas) |

---

## Logica de negocio relevante

`VentaServiceImpl` implementa actualizacion parcial: antes de persistir, verifica campo por campo si el valor recibido es no nulo y no vacio. Esto permite que el servicio de Despachos actualice solo `despachoGenerado` sin tener que enviar todos los campos de la venta.

```
PUT /api/v1/ventas/{id}  <-- llamado desde back-despachos al crear una orden
Body: { "despachoGenerado": true }
      |
      v
VentaServiceImpl.updateVenta()
      |-- busca la venta en BD
      |-- solo actualiza los campos no nulos del body
      `-- persiste y retorna la venta actualizada
```

---

## Tests

Los tests unitarios se ejecutan con **Mockito** y **H2 en memoria**, sin necesidad de una base de datos real. El perfil de test se activa automaticamente via `application-test.properties`.

```
src/test/java/persistence/service/VentaServiceTest.java

  - whenSavingValidVenta_thenItIsPersistedCorrectly
      Verifica que saveVenta() llama a repository.save() exactamente una vez
      y que la venta retornada tiene los mismos datos ingresados.

  - whenVentaIsSaved_thenIdIsAssigned
      Verifica que al guardar, el objeto retornado tiene un ID asignado
      distinto de null.
```

Correr los tests:

```bash
./mvnw clean verify
```

La fase `verify` incluye compilacion, tests unitarios y empaquetado. El pipeline de CI los ejecuta en este mismo orden antes de construir la imagen Docker.

---

## Variables de entorno

No hay credenciales ni endpoints hardcodeados en el codigo. La conexion a la base de datos se resuelve en tiempo de ejecucion mediante variables de entorno referenciadas en `application.properties`.

| Variable | Descripcion | Ejemplo |
|---|---|---|
| `DB_ENDPOINT` | Host del RDS MySQL | `innovatech-db.xxxx.us-east-1.rds.amazonaws.com` |
| `DB_PORT` | Puerto de la base de datos | `3306` |
| `DB_NAME` | Nombre de la base de datos | `ventas` |
| `DB_USERNAME` | Usuario MySQL | `admin` |
| `DB_PASSWORD` | Contrasena MySQL | (guardada en GitHub Secrets) |

En los tests estas variables no son necesarias porque `application-test.properties` configura H2 en memoria directamente.

---

## Correr localmente con Docker

```bash
# 1. Clonar el repositorio
git clone https://github.com/<tu-usuario>/innovatech-back-ventas.git
cd innovatech-back-ventas/Springboot-API-REST

# 2. Build de la imagen (multietapa: compila con Maven, corre con JRE Alpine)
docker build -t back-ventas:local .

# 3. Correr el contenedor
docker run -d -p 8080:8080 \
  -e DB_ENDPOINT=localhost \
  -e DB_PORT=3306 \
  -e DB_NAME=ventas \
  -e DB_USERNAME=root \
  -e DB_PASSWORD=root \
  back-ventas:local

# 4. Verificar
curl http://localhost:8080/api/v1/ventas
# Swagger UI: http://localhost:8080/swagger-ui.html
```

### Sin Docker (Maven directo)

```bash
cd Springboot-API-REST

# Solo tests
./mvnw clean verify

# Levantar la aplicacion
export DB_ENDPOINT=localhost
export DB_PORT=3306
export DB_NAME=ventas
export DB_USERNAME=root
export DB_PASSWORD=root
./mvnw spring-boot:run
```

---

## Pipeline CI/CD (GitHub Actions)

El pipeline se encuentra en `.github/workflows/ci-cd.yml` y se activa con cada `push` a `main`.

### Flujo

```
push a main
    |
    v
[Job 1] build-and-test
    |-- Checkout codigo
    |-- Setup Java 17 (con cache Maven)
    `-- mvn clean verify  (tests con H2, sin RDS)
    |
    v
[Job 2] build-and-push-ecr  (solo si Job 1 pasa)
    |-- Configurar credenciales AWS
    |-- Login en Amazon ECR
    `-- docker build + tag SHA + tag latest + push
    |
    v
[Job 3] deploy-ecs  (solo si Job 2 pasa)
    |-- Obtener Task Definition actual de ECS
    |-- Reemplazar imagen por la del SHA del commit
    `-- Registrar nueva revision y forzar redeploy del servicio
```

El tag de imagen usa el SHA del commit (`github.sha`) para trazabilidad: cada despliegue es identificable en ECR por el commit exacto que lo origino.

### Secrets requeridos en GitHub

Ir a Settings → Secrets and variables → Actions:

| Secret | Descripcion |
|---|---|
| `AWS_ACCESS_KEY_ID` | Access Key de AWS Academy |
| `AWS_SECRET_ACCESS_KEY` | Secret Key de AWS Academy |
| `AWS_SESSION_TOKEN` | Session Token (renovar cada ~4h en AWS Academy) |
| `AWS_REGION` | `us-east-1` |
| `AWS_ACCOUNT_ID` | ID de la cuenta AWS (12 digitos) |

---

## Infraestructura AWS

| Recurso | Valor |
|---|---|
| Cluster | `innovatech-cluster` (ECS Fargate) |
| Servicio ECS | `back-ventas` |
| Task Definition | `innovatech-back-ventas` |
| Imagen ECR | `<account>.dkr.ecr.us-east-1.amazonaws.com/innovatech/back-ventas` |
| Puerto | `8080` |
| CPU / Memoria | 512 vCPU / 1024 MB |
| Logs | CloudWatch `/ecs/innovatech/back-ventas` |
| Autoscaling | Target Tracking 50% CPU — min. 1, max. 4 tareas |
| Acceso | Via Application Load Balancer (ALB), path `/api/v1/ventas/*` |

### Ver logs en produccion

```bash
# Seguir logs en tiempo real
aws logs tail /ecs/innovatech/back-ventas --follow --region us-east-1

# Filtrar errores
aws logs filter-log-events \
  --log-group-name /ecs/innovatech/back-ventas \
  --filter-pattern "ERROR" \
  --region us-east-1
```

### Verificar estado del servicio

```bash
aws ecs describe-services \
  --cluster innovatech-cluster \
  --services back-ventas \
  --query 'services[0].[runningCount,desiredCount,status]' \
  --output table
```

---

## Estructura del proyecto

```
Springboot-API-REST/
|-- src/
|   |-- main/
|   |   |-- java/com/citt/
|   |   |   |-- config/
|   |   |   |   `-- OpenApiConfing.java          # Configuracion Swagger/OpenAPI
|   |   |   |-- controller/
|   |   |   |   `-- VentaController.java         # Endpoints REST
|   |   |   |-- exceptions/
|   |   |   |   |-- VentaNotFoundException.java
|   |   |   |   |-- RestResponseEntityExceptionHandler.java
|   |   |   |   `-- dto/ErrorMessage.java
|   |   |   |-- persistence/
|   |   |   |   |-- entity/Venta.java            # Entidad JPA
|   |   |   |   |-- repository/VentaRepository.java
|   |   |   |   `-- services/
|   |   |   |       |-- VentaService.java        # Interfaz
|   |   |   |       `-- VentaServiceImpl.java    # Logica de negocio
|   |   |   `-- SpringbootApiRestApplication.java
|   |   `-- resources/
|   |       |-- application.properties           # Config produccion (variables de entorno)
|   |       `-- application-test.properties      # Config tests (H2 en memoria)
|   `-- test/
|       `-- java/
|           |-- com/citt/SpringbootApiRestApplicationTests.java
|           `-- persistence/service/VentaServiceTest.java
|-- Dockerfile
|-- pom.xml
`-- .github/
    `-- workflows/
        `-- ci-cd.yaml
```

2 de julio 2026
