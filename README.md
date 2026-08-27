# Mercurius

![Screenshot](Mercurius.png)

## Descripción

Mercurius es un programa de inventarios diseñado para ayudar a las empresas a gestionar sus productos de manera eficiente. Ofrece una interfaz intuitiva y una variedad de funcionalidades para facilitar el seguimiento y control de inventarios.

## Requisitos Previos

- **Java 25+** - JDK versión 25 o superior
- **Maven 3.8+** - Herramienta de construcción
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
Editar `src/main/resources/application.properties` y ajustar la configuración de la base de datos si es necesario:
```properties
quarkus.datasource.username=tu_usuario
quarkus.datasource.password=tu_contraseña
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5433/mercurius
```

### 3. Compilar y Ejecutar
```bash
# Ir al directorio del proyecto Quarkus
cd mercurius-quarkus

# Modo desarrollo
.\run-dev.bat

# O con Maven directamente
mvn quarkus:dev

# Compilar para producción
mvn clean package
java -jar target\mercurius-quarkus-runner.jar
```

### 4. Acceder a la Aplicación
La aplicación estará disponible en: `http://localhost:8081/Mercurius`

## Pruebas (Testing)

Ejecutar `mvn test` requiere una instancia local de PostgreSQL (Dev Services está deshabilitado, no se usa Docker):

- **Instancia**: PostgreSQL local en el puerto `5433`
- **Bases de datos**: `mercurius` (aplicación) y `mercurius_test` (pruebas)
- **Usuario**: `mercurius` / contraseña `Mercurius@1!`

Las pruebas de autenticación usan form-cookie auth (login real vía `/Mercurius/j_security_check`) contra la base `mercurius_test`, con las credenciales sembradas por `src/test/resources/import-test.sql`: usuario `admin` / contraseña `admin123`.

## Seguridad

Variables de entorno sensibles (ver `src/main/resources/application.properties`):

- **`AUTH_SESSION_KEY`** - Clave de cifrado de la cookie de sesión (mínimo 32 bytes). Establecer por entorno; el valor por defecto es desechable y solo para `%dev`/`%test`.
- **`HACIENDA_ENCRYPTION_KEY`** - Clave para derivar la clave AES-256-GCM que cifra en reposo `HaciendaApiKey` y `certificadoPassword` en la base de datos. Generar con `openssl rand -hex 32`.
- **`DB_PASSWORD`** / **`DB_URL`** - Sobrescrituras por entorno de la contraseña y URL JDBC de la base de datos, sin editar `application.properties`.

## Tecnologías Utilizadas

### Backend
- **Java 25** - Última versión de Java con soporte para virtual threads y mejoras de rendimiento
- **Quarkus 3.36.2** - Framework Java nativo en la nube para alto rendimiento y bajo consumo de memoria
- **Apache MyFaces 4.1.3** - Implementación de Jakarta Faces para JSF
- **PrimeFaces 4.15.16** - Framework UI component para aplicaciones web Java con tema Bootstrap
- **PostgreSQL** - Base de datos relacional para persistencia de datos
- **Hibernate ORM** - Mapeo objeto-relacional integrado con Quarkus
- **Maven** - Herramienta de gestión de dependencias y construcción

### Frontend
- **HTMX 2.0.10** - Interactividad AJAX declarativa directamente en HTML
- **Alpine.js 3.16.1** - Reactividad ligera del lado del cliente
- **Bulma.io 1.0.4** - Framework CSS moderno basado en Flexbox
- **Qute** - Motor de plantillas nativo de Quarkus para el renderizado del lado del servidor

### Librerías Adicionales
- **Lombok 1.18.46** - Reducción de código boilerplate mediante anotaciones
- **BCrypt 0.10.2** - Hashing seguro de contraseñas
- **Apache POI 5.2.5** - Generación de archivos Excel (XLS/XLSX)
- **OpenPDF 2.0.2** - Generación de documentos PDF
- **Jackson 2.17.1** - Procesamiento JSON y XML
- **Apache PDFBox 3.0.6** - Manipulación de documentos PDF
- **Jakarta Mail 2.1.5** - Envío de correos electrónicos

### Plataforma
- **Quarkus Scheduler** - Tareas programadas automatizadas
- **Quarkus Security (quarkus-security)** - Autenticación y autorización seguras (reemplaza a elytron-security-jdbc) 

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


