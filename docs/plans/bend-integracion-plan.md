# Plan: Bend como especificación verificable + Java 25

**Fecha:** 2026-11-17 · **Base:** Mercurius 3.19.14 (Quarkus 3.36.2)

## 0. Estado actual verificado

| Punto | Estado |
|---|---|
| `maven.compiler.release` | **25** (desde 2.5.0) |
| Clases compiladas | major **69** = Java 25 |
| JDK del servidor | `/usr/bin/java` → `java-25-openjdk-amd64` (25.0.5) |
| Quarkus | 3.36.2 |
| JDK local de build | GraalVM 25 |

Mercurius **ya corre en Java 25**. Lo que falta es blindarlo para que no vuelva
atrás sin que nadie lo note (sección B).

---

# Parte A — Bend

## A.1 Qué es Bend y qué no

Bend 2 (`bendlang/bend`) es un lenguaje tipado, afín, con tipos dependientes.
Su propuesta: declarar reglas en `LAWS.bend`, escribir la prueba en
`PROOF.bend`, y que el compilador (`bend PROOF.bend`) se niegue a compilar si
alguna ley se rompe. `--verdict` usa un kernel formalizado en Lean.

**Lo que hace:** demuestra teoremas sobre **código Bend**.
**Lo que no hace:** verificar nuestro Java, nuestro SQL ni nuestros datos.

Por eso Bend no entra en runtime. Entra como **especificación ejecutable** que
detecta divergencias entre la regla y la implementación.

## A.2 Criterio de selección

Se pasa a Bend solo lo que cumpla los cuatro:

1. **Puro** — sin base de datos, HTTP, hora del sistema ni estado.
2. **Crítico** — si se rompe, hay plata o una declaración mal.
3. **Chico** — enunciable y demostrable en una pantalla.
4. **Estable** — reglas que no cambian cada mes.

## A.3 Candidatos en el repo

| Candidato | Puro | Crítico | Chico | Estable | Decisión |
|---|---|---|---|---|---|
| Totales del D-104 (`TributacionResource.monthlyFilas`, `declaracionModel`) | sí | sí | sí | sí | **F1** |
| `DeclaracionIVARowDTO` | sí | sí | sí | sí | **F1** |
| Cálculo de totales de comprobante (`ComprobanteService`) | parcial | sí | mediano | sí | **F1 (recorte)** |
| Totales del API de ventas (`InvoicingService`) | sí | sí | mediano | sí | **F2** |
| Contrato de importación (`scripts/completar_movimientos_desde_lineas.sql`) | sí | sí | sí | sí | **F2** |
| Libro de inventario (`InventarioService`) | parcial | sí | grande | mediano | **F3** |
| Redondeo de línea vs factura | sí | sí | sí | sí | **F1** |
| PDF, XML de Hacienda, CSRF, controllers, POS | no | — | — | — | **no** |
| Reportes, promedios, utilidades | sí | no | — | no | **no** |

## A.4 Cómo conviven los dos

Bend **nunca** corre en producción. Tres modos, de menor a mayor costo:

```
   (1) ORÁCULO                          (2) GATE DE CI
   Bend genera casos de oro             bend PROOF.bend
        │                                     │
        ▼                                     ▼
   test de Java compara                 si falla, no hay build
   (JUnit / jqwik)

   (3) ESPEJO CONTRA DATOS REALES
   export CSV/SQL ──► modelo Bend ──► resultado ──► diff contra Java y contra la base
                                                      │
                                              si difiere, alerta (log/tarea)
```

**Regla de oro:** el modelo Bend nunca es fuente de verdad. Es un **detector de
divergencias**. Si el modelo y la base no coinciden, se investiga cuál está mal;
no se “arregla” el modelo para que calce.

### Dónde vive

```
spec/
  laws/LAWS.bend          # las reglas
  laws/PROOF.bend         # las pruebas
  fixtures/*.json         # casos de oro (entrada + salida esperada)
  export.sql              # consultas que producen los CSV de entrada
  run.sh                  # export → bend → diff → exit code
  README.md               # cómo correrlo
```

`spec/run.sh` se puede llamar desde la tarea diaria (después del import) y desde
CI. Sale con código ≠ 0 si alguna ley o algún caso de oro difiere.

## A.5 Fases

### F0 — Piloto (1–2 días)

Tres leyes sobre un modelo mínimo de comprobante:

```
law neta_mas_iva_es_total:      neta + iva == total
law iva_es_trece_de_base:       iva == round(base * 13 / 113)
law lineas_suman_el_resumen:    sum(lineas.total) == resumen.total
```

**Criterio de salida:** el checker acepta la prueba y **falla** cuando se
introduce a propósito un IVA mal calculado.

Si F0 no se siente usable, se detiene acá y se sigue con property-based tests
(jqwik) sobre el Java.

### F1 — D-104 y redondeo (1 semana)

- Modelo de la declaración mensual: base gravada, exenta, IVA, neto.
- Ley del redondeo: política explícita (por línea vs por factura) y su prueba.
- Casos de oro: los 8 meses de 2026 que ya verificamos al centavo.

**Criterio de salida:** `spec/run.sh` reproduce los totales de la base y detecta
un cambio de un colón.

### F2 — Contrato de importación (1 semana)

- Ley de idempotencia: importar dos veces el mismo lote no cambia el libro.
- Ley de clave natural: “(artículo, fecha, cantidad) no distingue dos ventas” —
  exactamente el error que produjo febrero duplicado y la limpieza de 2024.
- Aplicable al API de ventas (`InvoicingService`) y a los scripts de importación.

**Criterio de salida:** el modelo rechaza la clave que usamos hoy y obliga a
incluir el número de documento o la línea.

### F3 — Libro de inventario (2 semanas, opcional)

- `stock(t) = stock(0) + entradas − ventas ± ajustes`.
- “Nunca negativo salvo que se permita”.
- Es el hueco real: 8.984 artículos con diferencia entre `articulostock` y el
  kardex.

**Criterio de salida:** el modelo explica cada diferencia o la declara como
excepción.

## A.6 Riesgos

| Riesgo | Mitigación |
|---|---|
| Bend es joven (compilador 99 % generado por IA, sin auditar) | No se usa en runtime; solo en CI/local |
| Sin librerías (JSON, HTTP, SQL) | El puente es CSV, no JSON |
| Todo anotado, sin tácticas: las pruebas cuestan | Solo leyes chicas y estables |
| Deriva: el modelo dice A y el Java hace B | Casos de oro contra la base real, no solo pruebas |
| Sin Windows nativo | Corre en `.72` o en un contenedor Linux, nunca en el host de desarrollo |
| Los backends pueden diferir de la semántica probada | Solo se prueban funciones puras, sin F32 ni concurrencia |

## A.7 Definición de éxito

El plan sirve si, al terminar F1:

1. Una regla de negocio queda escrita y probada en un solo lugar.
2. El build falla si alguien la rompe.
3. El costo de mantenerlo es menor que el de los bugs que evita (febrero
   duplicado y 2024 duplicado fueron ~24.000 filas y una declaración mal).

Si no se cumplen, se documenta el piloto, se archiva y se sigue con jqwik.

---

# Parte B — Java 25

## B.1 Verificación (hecha)

```
app/pom.xml:  <maven.compiler.release>25</maven.compiler.release>
clases:       major 69  (Java 25)
/usr/bin/java → /usr/lib/jvm/java-25-openjdk-amd64/bin/java  (25.0.5)
```

No hay referencias a Java 21 en el pom, el README ni los workflows.

## B.2 Blindaje pendiente

1. **Enforcer** en el pom: falla el build si el JDK es menor a 25.
2. **Documentar** el requisito en el README.
3. **Servidor**: `java-21-openjdk-amd64` sigue instalado pero no se usa. Se deja
   (no molesta) salvo que se quiera limpiar; verificar antes que nada más lo use.

## B.3 Despliegue

Sin cambios de comportamiento: el jar ya está compilado para 25 y corre sobre
25. El enforcer solo afecta el build. No requiere reinicio del servicio.
