---
id: reportes
titulo: Reportes
grupo: Economía
rutas: ["/reportes"]
permisos: [reporte:read]
relacionadas: [caja-diaria, egresos, presentaciones, asistente-de-datos]
palabras: [informe, estadísticas, indicadores, exportar csv, ingresos, turnos del mes, rentabilidad, gráfico, números de la sede]
---

## Para qué sirve

Muestra los números de **la sede en la que estás** para un período: turnos, actividad clínica,
economía, financiadores, clases, pases, continuidad e ingresos por servicio. Cada número dice de
dónde sale y con qué fecha se cortó.

> [!importante]
> **Los números no se suman entre sí.** Una deuda, un cobro y un movimiento de caja pueden ser la
> misma plata contada en tres lugares. Los montos van en pesos corrientes, sin ajuste por
> inflación.

## Tareas

### Generar un reporte

![Los filtros del reporte, con el tipo de reporte, la fecha desde y el botón Generar reporte marcados](captura:reportes/generar)

1. Entrá a :icono[economia]: **Economía → Reportes** y elegí el **Reporte**.
2. Completá **Desde** y **Hasta**. Los dos días se incluyen, hasta 366 días.
3. Tocá **Generar reporte**.

Antes de generar, **Este reporte trae** te dice qué secciones vas a ver y cuáles no por falta de
permiso. Los reportes disponibles son **Operativo de la sede**, **Turnos**, **Actividad clínica**,
**Economico**, **Financiadores**, **Clases**, **Continuidad del circuito de salud**, **Ingresos
por servicio** y **Pases**.

### Leer un indicador

1. Buscá la sección del reporte.
2. Mirá el número y, debajo, la **Fuente** y la **Fecha de corte**.
3. Si aparece **Para leer bien estos números**, leelo antes de sacar conclusiones.

Un período que incluye hoy puede cambiar entre consultas.

### Ver la evolución en un gráfico

1. Generá el reporte.
2. En **Evolución por día** (o **por semana**, en períodos de más de dos meses), elegí el
   **Indicador**.

Cada barra es un día de la sede o una semana de lunes a domingo, y las barras suman el número del
reporte. Los saldos de hoy y los porcentajes no tienen gráfico.

### Descargar el reporte

1. Generá el reporte.
2. Tocá **Descargar CSV**.

## Problemas comunes

- **«Ves solo tu propia actividad»**: tu rol ve los turnos, sesiones y casos que atendiste o te
  asignaron, no los de toda la sede.
- **Falta una sección**: aparece en **Secciones que no se muestran** con el permiso que hace falta.
- **«Este reporte no tiene ninguna sección que puedas ver»**: pedí el permiso a quien administra
  el centro o elegí otro reporte.
- **El rango no se acepta**: no puede superar 366 días ni terminar antes de empezar.
