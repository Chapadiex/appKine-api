# 09 · Reportes — P2

Módulo `reporting` (M23, G-1, G-8). Los reportes **no tienen tablas**: se calculan al leer desde las
tablas de los otros módulos. El QA de este circuito es comparar el número de la pantalla con el
número de la base, y comprobar el recorte por rol (DP-15). `ReporteReadIT` lo cubre por servicio;
**nada lo cubre por pantalla**.

Reportes: `OPERATIVO`, `TURNOS`, `CLINICO`, `ECONOMICO`, `FINANCIADORES`, con `desde` y `hasta`
(fechas **locales** de la sede, `hasta` incluido). Pantalla: `/reportes`.

> **Cómo se recorta** (DP-15, matriz §14): cada sección exige el permiso de su fuente. Si el rol no
> lo tiene, la sección **no viene** y aparece en `omitidas` con su `permisoRequerido` —recorta, no
> rechaza—. El profesional ve **solo su actividad** (alcance `ACTIVIDAD_PROPIA`) y la respuesta lo
> avisa con la advertencia `alcance-actividad-propia`. El paciente: 403.

Para comparar contra la base, fijá el período en UTC según la zona de la sede (UTC−3):

```sql
SET @rep_desde := CONVERT_TZ(CONCAT(DATE_FORMAT(UTC_TIMESTAMP() - INTERVAL 3 HOUR, '%Y-%m-01'), ' 00:00:00'), '-03:00', '+00:00');
SET @rep_hasta := UTC_TIMESTAMP();
SELECT @rep_desde, @rep_hasta;
```

---

### REP-01 · Turnos del período

**Pantalla:** admin-a → `/reportes` → Turnos, del 1 del mes a hoy.

```sql
SELECT estado, COUNT(*) AS turnos
FROM turno
WHERE consultorio_id = @sede_a AND inicio >= @rep_desde AND inicio < @rep_hasta
GROUP BY estado;

SELECT COUNT(*) AS en_espera_ahora FROM recepcion WHERE consultorio_id = @sede_a AND estado = 'EN_ESPERA';
```

Esperado: los mismos conteos por estado que la pantalla, `CANCELADO` incluido. `turnos-en-espera`
sale de la **recepción**, no del turno (defecto 6 del 07/10).

> **Corregido en appKine-api #86:** el indicador **Cancelados daba siempre 0**, porque la consulta
> del reporte filtraba `deleted_at IS NULL` y cancelar llena `deleted_at` (05.03). Ahora los
> cancelados cuentan en su indicador, en el total y en el denominador del ausentismo; los
> reprogramados que después se cancelaron también cuentan como reprogramados. Esperado: tantos
> cancelados como turnos `CANCELADO` del período en la base. Si da 0, es una regresión.

---

### REP-02 · Económico

**Pantalla:** Económico, mismo período.

```sql
SELECT responsable, concepto, estado, COUNT(*) AS n, SUM(importe_original) AS devengado, SUM(saldo) AS pendiente
FROM obligacion
WHERE consultorio_id = @sede_a AND devengada_en >= @rep_desde AND devengada_en < @rep_hasta
GROUP BY responsable, concepto, estado;

SELECT COALESCE(SUM(m.importe), 0) AS cobrado_en_caja
FROM cobro c JOIN cobro_medio m ON m.cobro_id = c.id
WHERE c.consultorio_id = @sede_a AND c.deleted_at IS NULL AND c.cobrado_en >= @rep_desde AND c.cobrado_en < @rep_hasta;
```

Esperado: devengado, cobrado y pendiente coinciden con la pantalla. El indicador de **anulados**
suma solo obligaciones `ANULADA` vigentes, no las dadas de baja (defecto 1 del 06/10).

---

### REP-03 · Financiadores

**Pantalla:** Financiadores, mismo período.

```sql
SELECT o.financiador_id, f.nombre, COUNT(*) AS prestaciones, SUM(o.importe_original) AS prestado, SUM(o.saldo) AS pendiente
FROM obligacion o JOIN financiador f ON f.id = o.financiador_id
WHERE o.consultorio_id = @sede_a AND o.responsable = 'FINANCIADOR' AND o.estado <> 'ANULADA'
  AND o.devengada_en >= @rep_desde AND o.devengada_en < @rep_hasta
GROUP BY o.financiador_id, f.nombre;
```

Esperado: el detalle por financiador **suma** al indicador `prestado` (defecto 4 del 06/10).

---

### REP-04 · Clínico: la consulta queda auditada

**Pantalla:** admin-a (con `hc:read` por grant de [ORG-07](02-organizacion-sedes-colaboradores.md))
y profesional → Clínico.

**Esperado:** admin-a con el grant ve casos y sesiones; sin el grant, las dos secciones vienen en
`omitidas` (`hc:read`, `sesion:register`). El profesional ve solo lo suyo. **Una consulta que trae
contenido clínico deja `REPORTE_CONSULTADO`**; los demás reportes no auditan.

```sql
SELECT a.id, c.email, a.details, a.occurred_at
FROM audit_event a JOIN cuenta c ON c.id = a.actor_account_id
WHERE a.organization_id = @org_a AND a.event_type = 'REPORTE_CONSULTADO' AND a.occurred_at >= @desde
ORDER BY a.id DESC LIMIT 10;

SELECT COUNT(*) AS sesiones_cerradas, SUM(asistencia = 'PRESENTE') AS presentes, SUM(asistencia = 'AUSENTE') AS ausentes
FROM sesion WHERE consultorio_id = @sede_a AND estado = 'CERRADA' AND cerrada_en >= @rep_desde AND cerrada_en < @rep_hasta;
```

Esperado: un `REPORTE_CONSULTADO` por consulta clínica con `details.reporte = CLINICO`, el período
y `alcance` (`SEDE` o `ACTIVIDAD_PROPIA`); **nunca** contenido clínico en `details`.

---

### REP-05 · El mismo reporte con cada rol

Con administrativo, profesional y paciente, el reporte Económico y el Clínico.

**Esperado** (verificado al validar esta guía):

| Rol | Económico | Clínico |
|---|---|---|
| `ORG_ADMIN` | completo | casos y sesiones solo con grant `hc:read` |
| `ADMINISTRATIVO` | completo (operativos y caja) | `omitidas`: `casos` (`hc:read`), `sesiones` (`sesion:register`) |
| `PROFESIONAL` | `omitidas`: `economia` (`cobro:register`) + advertencia `alcance-actividad-propia` | solo su actividad |
| `PACIENTE` | **403** | **403** |

Para el profesional, el número de sesiones tiene que ser solo el suyo:

```sql
SELECT COUNT(*) AS mis_sesiones
FROM sesion WHERE consultorio_id = @sede_a AND profesional_membership_id = @membership_profesional
  AND estado = 'CERRADA' AND cerrada_en >= @rep_desde AND cerrada_en < @rep_hasta;
```

Cada 403 deja `PERMISSION_DENIED` con `permissionCode = reporte:read`.

---

### REP-06 · Exportar a CSV

**Pantalla:** exportar el Operativo.

**Esperado:** descarga `reporte-operativo-<desde>_<hasta>.csv` (`Content-Disposition: attachment`,
`text/csv; charset=UTF-8`), con las mismas cifras que la pantalla y las secciones omitidas
**también omitidas** en el archivo. Abrilo en un editor de texto: los acentos se leen bien. Solo
lectura: no escribe nada.
