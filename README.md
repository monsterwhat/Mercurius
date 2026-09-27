# Mercurius

![Screenshot](Mercurius.png)

## Descripción

Mercurius es un programa de inventarios diseñado para ayudar a las empresas a gestionar sus productos de manera eficiente. Ofrece una interfaz intuitiva y una variedad de funcionalidades para facilitar el seguimiento y control de inventarios.

## Requisitos Previos

- **Java 25+** - JDK versión 25 o superior
- **Maven 3.9+** - Herramienta de construcción
- **PostgreSQL 14+** - Base de datos relacional (verificado con PostgreSQL 18)

## Instalación

### 1. Configurar Base de Datos
```sql
-- Crear base de datos
CREATE DATABASE mercurius;

-- Crear usuario (opcional, ajustar según configuración)
CREATE USER mercurius WITH PASSWORD 'Mercurius@1!';

-- Otorgar permisos sobre la base de datos
GRANT ALL PRIVILEGES ON DATABASE mercurius TO mercurius;

-- PostgreSQL 15+ restringe el esquema public al propietario;
-- conceder acceso para que Hibernate pueda crear las tablas
\c mercurius
GRANT ALL ON SCHEMA public TO mercurius;
```

### 2. Configurar Aplicación
Solo para desarrollo local (`%dev`), editar `app/src/main/resources/application.properties` si es necesario:
```properties
quarkus.datasource.username=tu_usuario
quarkus.datasource.password=tu_contraseña
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5433/mercurius
```
> **Producción (`%prod`):** no edites `application.properties`. Configura por variables de entorno
> (el arranque en `%prod` falla si faltan):
> `DB_USERNAME` / `DB_PASSWORD` / `DB_URL` (JDBC URL),
> `MERCATUS_JWT_SECRET` (≥32 bytes) y `MERCATUS_CORS_ORIGINS`.
> Claves `authSessionKey` y `haciendaEncryptionKey` son **autogeneradas en la BD** (`appsettings`) en el primer arranque — no requieren variables de entorno.

### 3. Compilar y Ejecutar
```bash
# Ir al directorio del proyecto Quarkus
cd app

# Modo desarrollo
.\run-dev.bat

# O con Maven directamente
mvn quarkus:dev

# Compilar para producción
mvn clean package
java -jar target\mercurius-quarkus-runner.jar

# O con el script de Windows (hace lo mismo: mvn clean + package sin pruebas)
build-uberjar.bat
```

### 4. Acceder a la Aplicación
La aplicación estará disponible en: `http://localhost:8081/Mercurius`

## Pruebas (Testing)

Ejecutar `mvn test` requiere una instancia local de PostgreSQL (Dev Services está deshabilitado, no se usa Docker):

- **Instancia**: PostgreSQL local en el puerto `5433`
- **Bases de datos**: `mercurius` (aplicación) y `mercurius_test` (pruebas)
- **Usuario**: `mercurius` / contraseña `Mercurius@1!`

Las pruebas de autenticación usan form-cookie auth (login real vía `/Mercurius/j_security_check`) contra la base `mercurius_test`, con las credenciales sembradas por `app/src/test/resources/import-test.sql`: usuario `admin` / contraseña `admin123`.

El recorrido de aceptación de extremo a extremo (login → crear artículo → ajuste de inventario → factura electrónica v4.4 → venta POS → recibos → exportación → logout) está definido en `scripts\e2e-journey.ps1`; se ejecuta contra el servidor de desarrollo en `http://localhost:8081` con las credenciales sembradas (`admin` / `admin123`). Código de salida `0` = todos los pasos pasaron.

### Facturas reales de prueba (fixtures generadas)

`app/src/test/resources/fixtures/reales/` (20 facturas v4.3 + 20 v4.4 derivadas) son datos **generados, no editados a mano**. La cadena es:

1. `scripts\anonymize-facturas-fixtures.ps1` — anonimiza las facturas originales (RUC, correos, teléfonos, direcciones, `Clave`) conservando la forma del documento.
2. `scripts\derive-facturas-v44.ps1` — deriva el conjunto v4.4 del v4.3 aplicando los cambios del esquema v4.4 (`ProveedorSistemas`, `CodigoActividad` → `CodigoActividadEmisor`, `Codigo` → `CodigoCABYS`, `MedioPago` a `ResumenFactura`, ...).
3. `scripts\verify-real-fixtures.ps1` — **la puerta**: valida que ningún valor prohibido (RUC, correo, teléfono, dirección, referencia) de las facturas originales sobrevivió en las fixtures. Código de salida distinto de `0` = no se tocan las fixtures.

Regla: **no editar a mano nada bajo `fixtures/reales/`**. Un cambio manual se pierde en la siguiente regeneración y, peor, evade el chequeo de anonimización. Si hay que cambiar algo, se cambia el script que genera y se vuelve a correr `verify-real-fixtures.ps1`.

## Seguridad

Variables de entorno sensibles (ver `app/src/main/resources/application.properties`):

- **`DB_PASSWORD`** / **`DB_URL`** - Sobrescrituras por entorno de la contraseña y URL JDBC de la base de datos, sin editar `application.properties`.
- **Claves en BD (autogeneradas):** `authSessionKey` y `haciendaEncryptionKey` se generan al primer arranque y se guardan en `appsettings` — no requieren variables de entorno. Rotación vía `AppSettingsService.rotate*` (requiere re-login para `authSessionKey`).

## Estándares de Código

Convenciones obligatorias para todo cambio en `app/src` (backend Quarkus + plantillas Qute):

- **Dominio en español** - Clases, entidades y términos de negocio en español (`Articulos`, `FacturasRecibidas`, `ComprobanteService`); solo el vocabulario técnico (frameworks, patrones) va en inglés.
- **Dinero con `BigDecimal`** - Ningún importe monetario usa `float` ni `double`.
- **Anotaciones `jakarta.*`** - Prohibido `javax.*` de Java EE (`javax.inject`, `javax.persistence`, `javax.ws.rs`, ...). Los únicos `javax.*` aceptados son los del propio JDK (`javax.xml`, `javax.crypto`, `javax.imageio`, `javax.swing`).
- **Lombok donde ya se usa** - No reintroducir boilerplate (getters, setters, constructores) en clases que ya usan anotaciones Lombok.
- **Sin supresión silenciosa de errores** - Prohibido `@SuppressWarnings` sin justificación escrita en el propio código y prohibidos los bloques `catch` vacíos.
- **Caracterizar antes de cambiar** - Al tocar comportamiento existente, registrar primero el comportamiento observable actual (con una prueba que lo demuestre) y solo después modificar.
- **Pruebas contra PostgreSQL local** - Dev Services está deshabilitado (sin Docker): las pruebas arrancan contra `mercurius_test` en `localhost:5433` con login form-cookie real (`admin` / `admin123` sembrado por `import-test.sql`). Ver [Pruebas (Testing)](#pruebas-testing).
- **Fixtures generadas no se editan a mano** - Nada bajo `app/src/test/resources/fixtures/reales/` se toca a mano: se regeneran con `scripts\anonymize-facturas-fixtures.ps1` + `scripts\derive-facturas-v44.ps1` y se validan con `scripts\verify-real-fixtures.ps1`, que es la puerta (código de salida `0`). Ver [Facturas reales de prueba](#facturas-reales-de-prueba-fixtures-generadas).

### Estado de conformidad (2026-09-26)

| Estándar | Estado | Detalle |
|----------|--------|---------|
| `@SuppressWarnings` justificados | Conforme | 15 casos en `main`, todos `"unchecked"` en casts inevitables (mapas sin tipo, `Query` crudo, Jackson `Map.class`); 0 en pruebas |
| Sin `catch` vacíos | Conforme | 4 en `main` revisados: 1 corregido con justificación escrita (`OrdenCompraResource.java:891`), 3 sondas de mejor esfuerzo documentadas (`BackupService`, `GService`, `DbSessionKeyConfigSource`); en pruebas solo limpiezas documentadas (`TributacionResourceTest`) |
| Dinero con `BigDecimal` | Conforme | Importes en `BigDecimal`; los `double` hallados son días, porcentajes, scores y stock — no dinero |
| Anotaciones `jakarta.*` | Conforme | 0 imports `javax.*` de Java EE en `main` y pruebas (los restantes son del JDK: `javax.xml`, `javax.crypto`, `javax.imageio`, `javax.swing`) |
| Dominio en español | Conforme (2026-09-26, verificado) | 10 entidades renombradas: `Users→Usuarios`, `Clients→Clientes`, `ApiClients→ClientesApi`, `AppSettings→ConfiguracionAplicacion`, `StockAlert→AlertaStock`, `ReorderSuggestion→SugerenciaReposicion`, `ProfitMarginHistory→HistorialMargen`, `ProfitMarginSnapshot→CorteMargen`, `UserShortcut→AtajoUsuario`, `PagoEntry→EntradaPago` (148 archivos, JPQL incluido; `@Table`, rutas REST, JSON y plantillas intactos). DTOs/servicios/recursos conservan nombre por compatibilidad de API. Verificado: compila (586+75 archivos, javac 25) y suite en verde (ver [Pruebas](#pruebas)) |
| Sin `System.out` en `main` | Conforme (2026-09-26) | 6 restos migrados a `LOG` (`MensajeReceptorService`, `FacturasRecibidasResource.java:863`): `warn` para fallos, `debug` para trazas |
| Aserciones en pruebas | Conforme (2026-09-26) | `ArticuloResourceTest.java:418`: justificación escrita agregada (relectura de mejor esfuerzo, resultado ya afirmado arriba) |

### Pruebas

**Cómo obtener la línea base** (el número cambia cada vez que se agregan pruebas, no lo des por fijo):

```bash
cd app
mvn -B -ntp test
```

Precondiciones (Dev Services está deshabilitado, `%test.quarkus.datasource.devservices.enabled=false`):

- **PostgreSQL local en el puerto `5433`** con las bases `mercurius` (aplicación) y `mercurius_test` (pruebas), usuario `mercurius` / contraseña `Mercurius@1!` (ver [Instalación](#1-configurar-base-de-datos)).
- El perfil `%test` usa `drop-and-create` y ejecuta `app/src/test/resources/import-test.sql`, que siembra el usuario **`admin` / `admin123`** (hash BCrypt real) usado por las pruebas de autenticación. No hay que sembrar nada a mano.

Estado actual de este cambio: **821 pruebas en verde, 0 fallos, 0 errores, 2 omitidas**. Antes de las fixtures de facturas reales eran 688; el salto a 821 incluye `fixtures/reales` y tres clases de prueba nuevas. Las 2 omitidas son las de respaldo real, que se saltan solas cuando el binario cliente `pg_dump` no está en el entorno (los binarios servidor y cliente de PostgreSQL se distribuyen por separado); en un runner con `postgresql-client` instalado **sí se ejecutan**.

El CI (`.github/workflows/ci.yml`, job `test`) replica exactamente este contrato con un contenedor de servicio `postgres` en `localhost:5433`, así que un rojo local casi siempre es estado de `mercurius_test`, no código.

**Corregido en esta sesión**
- Plantilla de ajustes: `{backupRutaEfectiva}` sin supplying (error real de render) y tarjeta `#backup-status-card` ausente.
- `HX-Redirect` de CABYS apuntando a la página en vez de a `/api/app/cabys/table`.
- Secciones "Ranking de Rendimiento de Proveedores" y de subida XML ausentes en Inventario (los endpoints ya existían).
- Log de archivo deshabilitado en `%test`: el LogManager abortaba la JVM al rotar el log bloqueado.
- Raíz `/` responde 303 y coincide con `RootRedirectResource`.
- Envoltório 401 de la API: carve-out en la permission policy para los tres endpoints **auto-verificados** (`/api/app/auth/me`, `/api/app/pos/cart`, `/api/app/pos/cart-panel`). Los endpoints `@RolesAllowed` (p. ej. `/api/app/cabys`) conservan el challenge 302 del navegador: ambos contratos aplican a endpoints distintos, y `RoleMatrixTest` sigue verde.
- Página desconocida autenticada: `FallbackResource` y `NotFoundExceptionMapper` rebotan 303 a `/Mercurius/app` en vez de renderizar un 404.
- **Policy de la API enumerada (default-deny):** el blanket `/api/app/*` se sustituyó por las 24 sub-APIs reales, así un `/api/app/desconocido` responde 404 JSON en vez del redirect de login. Como `ExportResource` dependía exclusivamente de ese blanket, lleva ahora su propio `@RolesAllowed({"admin","registro"})`.

**Nota de entorno:** la suite es sensible al estado de la base de pruebas y no debe correrse en paralelo con otro build sobre el mismo `target/` (el puerto de pruebas es el 8081 fijo y `target/` se pisa). Ante fallos de compilación o de arranque, reinicializar `mercurius_test` y reintentar.

## Tecnologías Utilizadas

### Backend
- **Java 25** - Última versión de Java con soporte para virtual threads y mejoras de rendimiento
- **Quarkus 3.36.2** - Framework Java nativo en la nube para alto rendimiento y bajo consumo de memoria
- **PostgreSQL 18** - Base de datos relacional (único motor soportado)
- **Hibernate ORM + Panache** - Mapeo objeto-relacional integrado con Quarkus
- **Quarkus REST (RESTEasy Reactive) + Qute** - APIs REST y plantillas tipadas nativas
- **Quarkus Security** - Autenticación form-cookie y autorización por roles
- **Extensiones Quarkus** - REST Client, Mailer, Cache (Caffeine), SmallRye OpenAPI, Fault Tolerance y CSRF (Double Submit Cookie)
- **Maven 3.9+** - Herramienta de gestión de dependencias y construcción

### Frontend
- **Qute** - Motor de plantillas nativo de Quarkus para renderizado del lado del servidor
- **HTMX 2.0.10** - Interactividad AJAX declarativa directamente en HTML
- **Alpine.js 3.16.1** - Reactividad ligera del lado del cliente
- **Bulma.io 1.0.4** - Framework CSS moderno basado en Flexbox
- **Chart.js 4.4.8** - Gráficos del panel de analítica
- **Quarkus Web Bundler 2.3.3** - Pipeline de assets en tiempo de construcción (`/static/bundle/*`); las librerías frontend se resuelven desde mvnpm durante la construcción

### Librerías Adicionales
- **Lombok 1.18.46** - Reducción de código boilerplate mediante anotaciones
- **BCrypt 0.10.2** - Hashing seguro de contraseñas
- **Apache POI 5.2.5** - Generación de archivos Excel (XLS/XLSX)
- **OpenPDF 2.0.2** - Generación de documentos PDF
- **Jackson (versión gestionada por quarkus-bom)** - Procesamiento JSON y XML
- **Apache PDFBox 3.0.6** - Manipulación de documentos PDF
- **Jakarta Mail (API 2.1.5)** - Envío de correos electrónicos
- **JJWT 0.12.6** - Tokens JWT para la autenticación del marketplace Mercatus
- **XAdES4j 2.4.1** - Firmas digitales XAdES-EPES exigidas por Hacienda
- **Quarkus ZXing 1.1.0** - Generación de códigos QR para el PDF de Hacienda v4.4
- **OkapiBarcode 0.5.0** - Generación de códigos de barras

### Plataforma
- **Quarkus Scheduler** - Tareas programadas automatizadas
- **Vert.x + Undertow** - Servidor reactivo y contenedor de servlets para compatibilidad

### Pruebas
- **JUnit 5 + REST-Assured** - Pruebas de integración con login form-cookie real
- **AssertJ 3.27.7 + Mockito 5.14.2** - Aserciones y simulación de beans CDI
- **BouncyCastle 1.80** - Generación de certificados P12 de prueba

## Características

### Artículos
| Característica                | Estado       | Comentario                                    |
|-------------------------------|--------------|-----------------------------------------------|
| Gestión de productos          | Implementado |                                               |
| Categorías                    | Implementado |                                               |
| Inventarios                   | Implementado |                                               |
| Búsqueda avanzada             | Implementado | Permite búsquedas por un criterio en todas las características del artículo (nombre, precio, descripción, código CABYS, etc.) |
| Importación/Exportación       | Rechazado    | Solo exportación de datos implementada         |

#### Subcategorías de Artículos
| Subcategoría                  | Estado       | Comentario                                    | Descripción                                                                 |
|-------------------------------|--------------|-----------------------------------------------|-----------------------------------------------------------------------------|
| Activos                       | Implementado |                                               | Artículos que fueron ingresados al sistema                                  |
| Inactivos                     | Implementado |                                               | Artículos que se retiraron del sistema                                      |
| Procesados                    | Implementado |                                               | Artículos que ya fueron procesados por el sistema y se pueden utilizar      |
| Pendientes                    | Implementado |                                               | Artículos sin procesar por el sistema (ej. no tienen utilidad por lo que aún no están en el sistema) |
| Promociones                   | Implementado |                                               | Artículos en promoción, pueden ser singulares o múltiples                   |

#### Subcategorías de Categorías
| Subcategoría                  | Estado       | Comentario                                    | Descripción                                                                 |
|-------------------------------|--------------|-----------------------------------------------|-----------------------------------------------------------------------------|
| Departamentos                 | Implementado |                                               | Distribuidores de los artículos (creados automáticamente al subir facturas) |
| Familias                      | Implementado |                                               | Grupos de artículos independientes de los departamentos (ej. Refrescos o Licores) |

#### Subcategorías de Inventarios
| Subcategoría                  | Estado       | Comentario                                    | Descripción                                                                 |
|-------------------------------|--------------|-----------------------------------------------|-----------------------------------------------------------------------------|
| Activos                       | Implementado |                                               | Inventarios que fueron agregados al sistema                                 |
| Inactivos                     | Implementado |                                               | Inventarios que fueron removidos del sistema                                |
| Procesados                    | Implementado |                                               | Inventarios que fueron procesados por el sistema                            |
| Pendientes                    | Implementado |                                               | Inventarios que aún no fueron procesados por el sistema                     |

### Usuarios
| Tipo de Usuario               | Estado       | Comentario                                    | Descripción                                                                 |
|-------------------------------|--------------|-----------------------------------------------|-----------------------------------------------------------------------------|
| Usuarios del Sistema          | Implementado |                                               | Usuarios que tienen acceso al sistema para gestionar inventarios y otras funcionalidades |
| Clientes                      | Implementado |                                               | Usuarios que interactúan con el sistema para realizar compras y ver el estado de sus pedidos |
| Administradores               | Implementado |                                               | Usuarios con permisos avanzados para gestionar configuraciones del sistema  |

### Tributación
| Característica                | Estado       | Comentario                                    |
|-------------------------------|--------------|-----------------------------------------------|
| Consultas                     | Pendiente    | Consultas de facturas                         |
| Cabys                         | Implementado | Trae el catálogo completo de tributación y lo ingresa al sistema |
| Tipo de Cambio                | Implementado | Consulta directamente a tributación el tipo de cambio de manera diaria |
| Declaraciones                 | Rechazado    | No es una prioridad hasta tener facturación electrónica funcional |

### Reportes
| Característica                | Estado       | Comentario                                    |
|-------------------------------|--------------|-----------------------------------------------|
| Reportes de Facturación       | Implementado | Incluye movimientos, facturación de artículos, ventas por familia, ventas por departamento y todas las facturas |
| Reportes de Recibos           | Implementado | Incluye todos los recibos, recibos vigentes y recibos vencidos |
| Reportes de Inventarios       | Implementado | Incluye artículos, departamentos, familias, inventarios y generar etiquetas (sin implementar) |
| Reportes de Usuarios          | Implementado | Incluye usuarios detallados                   |
| Reportes Automáticos por Correo | Implementado | Enviar reportes automáticos a correos específicos |

### Otras Características
| Característica                | Estado       | Comentario                                    |
|-------------------------------|--------------|-----------------------------------------------|
| Integración con facturación electrónica | Implementada   | Procesa cualquier factura electrónica v4.4 válida de manera correcta |
| Interfaz multilingüe          | Rechazado    | No es una prioridad en este momento           |
| Backup Automático             | Pendiente    | Realizar copias de seguridad automáticas de la base de datos |


