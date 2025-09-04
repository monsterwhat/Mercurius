# Mercurius

![Screenshot](Mercurius.png)

## Descripción

Mercurius es un programa de inventarios diseñado para ayudar a las empresas a gestionar sus productos de manera eficiente. Ofrece una interfaz intuitiva y una variedad de funcionalidades para facilitar el seguimiento y control de inventarios.

## Tecnologías Utilizadas

- MYSQL
- PRIMEFACES
- JakartaEE 10
- Jakarta Faces 4.0
- Java 21

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


