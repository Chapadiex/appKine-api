# 06 · Sesión clínica, historia clínica, casos, plan y enmiendas — P1

Módulos `encounter` (M14, sesión) y `clinical` (M11–M13, historia clínica, casos, plan). **No hay
un solo E2E de la vertical clínica**, y la mitad no tiene pantalla en `main` (la historia clínica
está en el PR #4 de `appKine-web`, sin mergear). Es el circuito donde más vale el QA manual: datos
de salud, justificación de acceso y auditoría de **lecturas**.

Pantalla en `main`: **`/atencion/turnos/<turnoId>`** (iniciar, evaluar, guardar borrador,
cerrar) y, desde web #74, la tarjeta de **datos administrativos** de la historia en la ficha de la
persona (CLI-16),
que no muestra contenido clínico. Todo lo demás —tratamientos, mediciones, enmiendas, historia clínica, entradas, adjuntos
clínicos, timeline, casos, planes— va por la API con [`qa-api.ps1`](qa-api.ps1).

Tablas: `sesion`, `sesion_version`, `sesion_version_tratamiento`, `sesion_medicion`,
`tratamiento_realizado`, `tratamiento_parametro`, `sesion_numerador`, `historia_clinica`,
`historia_clinica_antecedente`, `entrada_clinica`, `entrada_clinica_version`, `adjunto_clinico`,
`caso_clinico`, `caso_evento`, `caso_profesional`, `plan_tratamiento`, `plan_tratamiento_version`,
`plan_item`, `plan_evento`, `medicion_definicion`.

## Cómo se audita lo clínico (leer antes de empezar)

- **Toda lectura clínica deja una fila en `audit_event`**, no solo las escrituras (04.01, DP-03).
- El acceso entra por **relación asistencial** (el profesional atiende a esa persona: tiene un turno
  o una sesión con ella) o por **justificación declarada** en el header `X-Justificacion-Acceso`.
  `details.viaDeAcceso` dice cuál: `RELACION_ASISTENCIAL`, `JUSTIFICACION` o `MODULO` (lo hizo el
  sistema, p. ej. abrir la historia al iniciar la primera sesión).
- Con justificación, el texto queda en `reason`. Con relación asistencial, `reason` es NULL.
- Sin relación y sin header, el rechazo es **403** con `requiereJustificacion: true` y
  `cabecera: X-Justificacion-Acceso`.
- `details.historiaClinicaId` se guarda **redactado** (`[REDACTADO]`); el nombre de un archivo
  adjunto **nunca** va a la auditoría.
- **`encounter` no exige justificación** (riesgo residual R2 del threat model): sus filas
  (`SESION_*`, `TRATAMIENTO_*`) salen con `reason` NULL. Es conocido, no se reporta.

La consulta de control para todo el circuito:

```sql
SELECT id, event_type, entity_type, entity_id, actor_account_id, reason,
       JSON_UNQUOTE(JSON_EXTRACT(details, '$.viaDeAcceso')) AS via, LEFT(details, 160) AS details, occurred_at
FROM audit_event
WHERE organization_id = @org_a AND occurred_at >= @desde
  AND event_type REGEXP '^(HISTORIA_CLINICA|ANTECEDENTE|ENTRADA_CLINICA|ADJUNTO_CLINICO|TIMELINE|CASO_|PLAN_|DERIVACION|SESION_|TRATAMIENTO_)'
ORDER BY id DESC LIMIT 25;
```

Requisitos: turno de hoy con recepción llamada ([AGE-09](05-agenda-turnos-recepcion.md)) para la
persona con cobertura, y otro particular ([AGE-10](05-agenda-turnos-recepcion.md)).

---

### CLI-01 · Iniciar la sesión desde el turno

**Pantalla:** profesional → `/agenda` → el turno de AGE-09 → "Atender" (lleva a
`/atencion/turnos/<turnoId>`).

**Esperado:** se abre la atención en borrador. Si la persona no tenía historia clínica, se abre sola.

```sql
SET @sesion := (SELECT id FROM sesion WHERE turno_id = @turno AND deleted_at IS NULL);
SELECT id, turno_id, oferta_id, historia_clinica_id, caso_id, profesional_membership_id, estado, numero_sesion,
       numero_en_caso, iniciada_en, iniciada_por_cuenta_id, version
FROM sesion WHERE id = @sesion;

SET @historia := (SELECT id FROM historia_clinica WHERE organization_id = @org_a AND persona_id = @persona);
SELECT id, persona_id, abierta_en, abierta_por, active, version FROM historia_clinica WHERE id = @historia;
```

Esperado: `estado = BORRADOR`, `numero_sesion` NULL (se numera **al cerrar**), `caso_id` NULL (sin
caso). **Una** historia clínica por persona y organización (DP-03: es de la organización, no de la
sede).

**Auditoría:** `HISTORIA_CLINICA_OPENED` (`viaDeAcceso = MODULO`, sin `consultorio_id`),
`SESION_INICIADA`, `SESION_ACCEDIDA`.

> Otro profesional de la misma sede que intenta escribir en esta sesión recibe **409, no 403**
> (06.01: la propiedad no es un permiso). Si tenés una segunda cuenta profesional, probalo.

---

### CLI-02 · Evaluar y guardar borrador

**Pantalla:** completar motivo de consulta, dolor EVA 6 en rodilla derecha, evolución, objetivo.
Esperar el autosave o guardar. Abrir la misma atención en otra pestaña y guardar en las dos.

**Esperado:** la segunda pestaña rebota con 409 `concurrent-modification` (el control optimista
**es** el autosave, 06.01). Nada se pierde en silencio.

```sql
SELECT estado, modo, motivo_clinico, dolor_eva, dolor_zona, dolor_lateralidad, evolucion, objetivo_sesion,
       evaluada_en, LEFT(borrador, 120) AS borrador, borrador_guardado_en, version
FROM sesion WHERE id = @sesion;
```

Esperado: los campos clínicos con lo escrito; `borrador` con un JSON (opaco para el servidor).
Un EVA fuera de 0–10 es 400.

---

### CLI-03 · Tratamientos realizados (sin pantalla)

```powershell
$P = Entrar 'qa.profesional@ejemplo.test' <org_a>
$s = (Api GET "/api/v1/consultorios/<sede_a>/sesiones/<sesion>" $P).Cuerpo
Api POST "/api/v1/consultorios/<sede_a>/sesiones/<sesion>/tratamientos" $P @{ practicaId = <practica>; version = $s.version; zona = 'Rodilla'; lateralidad = 'DERECHA'; duracionMinutos = 20; tecnica = 'Movilizacion'; parametros = @(@{ clave = 'intensidad'; tipoDato = 'NUMERICO'; valorNumerico = 3; unidad = 'nivel' }) }
```

**Esperado:** 201. Una lateralidad sin zona es 400 ("'derecha' de qué"). Una práctica que **no**
está habilitada en la oferta **no se rechaza**: queda como alerta (DP-11).

```sql
SELECT t.id, t.orden, t.practica_id, t.practica_codigo, t.practica_nombre, t.tecnica, t.zona, t.lateralidad,
       t.duracion_minutos, t.profesional_membership_id, t.active
FROM tratamiento_realizado t WHERE t.sesion_id = @sesion ORDER BY t.orden;

SELECT p.clave, p.tipo_dato, p.valor_numerico, p.valor_texto, p.valor_booleano, p.unidad
FROM tratamiento_parametro p JOIN tratamiento_realizado t ON t.id = p.tratamiento_realizado_id
WHERE t.sesion_id = @sesion;
```

Esperado: la práctica copiada (código y nombre congelados); el parámetro **tipado** (solo
`valor_numerico` lleno).

**Auditoría:** `TRATAMIENTO_REGISTRADO` (`entity_type = TRATAMIENTO_REALIZADO`).

---

### CLI-04 · Mediciones (sin pantalla)

Primero una definición del catálogo de mediciones y después el valor en la sesión:

```powershell
$def = (Api POST '/api/v1/mediciones/definiciones' $A @{ codigo = 'QA-FLEX-RODILLA'; name = 'Flexion de rodilla QA'; tipo = 'NUMERICO'; unidad = 'grados'; minimo = 0; maximo = 160; alcance = 'ORGANIZACION' }).Cuerpo
Api PUT "/api/v1/consultorios/<sede_a>/sesiones/<sesion>/mediciones/$($def.id)?lateralidad=DERECHA" $P @{ valorNumerico = 95; nota = 'QA' }
```

```sql
SELECT id, owner_key, codigo, name, tipo, unidad, minimo, maximo, active FROM medicion_definicion WHERE owner_key = @org_a;
SELECT definicion_codigo, definicion_nombre, definicion_unidad, definicion_tipo, lateralidad, valor_numerico, nota
FROM sesion_medicion WHERE sesion_id = @sesion;
```

Esperado: el valor con la definición **congelada** en la fila. Un valor fuera de 0–160 es 400.
**Auditoría:** `MEDICION_DEFINICION_CREATED`.

---

### CLI-05 · Cerrar la sesión con cobertura: deuda, consumo y numeración

**Pantalla:** cerrar con asistencia **presente**, tolerancia, indicaciones, próxima conducta
"continúa" y nota de cierre.

**Esperado:** la atención queda cerrada y de solo lectura. En la **misma transacción**: se numera,
se devenga la deuda (financiador + coseguro, F-4) y se consume la autorización (DP-12).

```sql
SELECT estado, asistencia, numero_sesion, numero_en_caso, ultimo_numero_version, cerrada_en, cerrada_por_cuenta_id,
       tolerancia, proxima_conducta, nota_de_cierre, version
FROM sesion WHERE id = @sesion;

SELECT ultimo_numero FROM sesion_numerador WHERE historia_clinica_id = @historia;

SELECT id, responsable, concepto, financiador_id, practica_id, importe_original, saldo, estado,
       snapshot_convenio_codigo, snapshot_importe_total, snapshot_importe_financiador, snapshot_coseguro,
       snapshot_requeria_autorizacion, snapshot_vigente_el, alerta_practica_no_habilitada
FROM obligacion WHERE sesion_id = @sesion ORDER BY responsable;
```

Esperado: `CERRADA`, `asistencia = PRESENTE`, `numero_sesion` = siguiente del numerador de la
historia. **Dos** obligaciones: `FINANCIADOR` por 6000 (concepto `FINANCIADOR`, con el financiador)
y `PACIENTE` por 2000 (concepto `COSEGURO`), las dos con el convenio **congelado** en `snapshot_*`.
El consumo de la autorización se verifica en [PER-11](04-personas-coberturas-autorizaciones.md).

**Auditoría:** `SESION_CERRADA` (`ABIERTA → CERRADA`, `entity_type = 'Sesion'`),
`AUTORIZACION_CONSUMIDA`. **El devengo no escribe `audit_event`**: el rastro es la obligación con
`devengada_en`.

> Cerrar **no cobra** (DP-06): la deuda queda `PENDIENTE` hasta [07](07-obligaciones-cobros-caja.md).

---

### CLI-06 · Cerrar la sesión particular

**Pantalla:** profesional → el turno de AGE-10 (Particular) → evaluar mínimo → cerrar presente.

```sql
SET @sesion_part := (SELECT MAX(id) FROM sesion WHERE consultorio_id = @sede_a AND estado = 'CERRADA' AND id <> @sesion);
SELECT o.id, o.responsable, o.concepto, o.importe_original, o.saldo, o.estado, o.snapshot_precio, o.snapshot_convenio_id
FROM obligacion o WHERE o.sesion_id = @sesion_part;
```

Esperado: **una** obligación `PACIENTE`/`PARTICULAR` por el precio de CAT-07 (10000), sin convenio,
**aunque la persona tenga obra social**: el mostrador decidió Particular (DP-17).

---

### CLI-07 · Enmendar una sesión cerrada (sin pantalla)

```powershell
$s = (Api GET "/api/v1/consultorios/<sede_a>/sesiones/<sesion>" $P).Cuerpo
Api POST "/api/v1/consultorios/<sede_a>/sesiones/<sesion>/enmiendas" $P @{ version = $s.version; motivo = 'QA G-12: se corrige el EVA'; dolorEva = 4; dolorZona = 'Rodilla'; dolorLateralidad = 'DERECHA'; notaDeCierre = $s.notaDeCierre }
```

> **La enmienda es un reemplazo completo**: un campo que no mandás queda **vacío** en la versión
> nueva. Mandá todo lo que tiene que quedar. `tratamientos` y `mediciones` son la excepción:
> ausentes, no se tocan.

**Esperado:** 200 y la versión nueva. Cambiar el **conjunto de prácticas** realizadas es 409
`enmienda-cambia-practicas`; enmendar una sesión abierta es 409 `sesion-no-cerrada`; otro
profesional, 409. **No** se vuelve a devengar ni a consumir nada.

```sql
SELECT numero_version, motivo_enmienda, dolor_eva, nota_de_cierre, registrada_en, registrada_por
FROM sesion_version WHERE sesion_id = @sesion ORDER BY numero_version;
SELECT ultimo_numero_version, dolor_eva FROM sesion WHERE id = @sesion;
SELECT COUNT(*) AS obligaciones FROM obligacion WHERE sesion_id = @sesion;
```

Esperado: la versión 1 **intacta** (la original) y la 2 con el motivo; `ultimo_numero_version = 2`;
las mismas dos obligaciones de CLI-05.

**Auditoría:** `SESION_AMENDED`.

---

### CLI-08 · Historia clínica: leer con relación, con justificación y sin nada (sin pantalla)

1. Profesional (atiende a la persona) lee **sin** header → 200.
2. Admin-a sin grant → 403 (`PERMISSION_DENIED`, `hc:read`).
3. Otorgá a admin-a `hc:read` ([ORG-07](02-organizacion-sedes-colaboradores.md)) y leé **sin**
   header → 403 con `requiereJustificacion`.
4. Admin-a **con** `X-Justificacion-Acceso: Auditoria interna QA` → 200.
5. El administrativo → 403 siempre (Ver HC "Limitado" = nunca contenido clínico).

```powershell
Api GET "/api/v1/historias-clinicas/por-persona/<persona>" $P
Api GET "/api/v1/historias-clinicas/por-persona/<persona>" $A
Api GET "/api/v1/historias-clinicas/por-persona/<persona>" $A $null @{ 'X-Justificacion-Acceso' = 'Auditoria interna QA' }
```

```sql
SELECT id, actor_account_id, event_type, reason, details, occurred_at
FROM audit_event
WHERE organization_id = @org_a AND occurred_at >= @desde
  AND (event_type = 'HISTORIA_CLINICA_ACCESSED'
       OR (event_type = 'PERMISSION_DENIED' AND JSON_UNQUOTE(JSON_EXTRACT(details, '$.permissionCode')) = 'hc:read'))
ORDER BY id DESC LIMIT 10;
```

Esperado: un `HISTORIA_CLINICA_ACCESSED` por cada **200**: el del profesional con
`viaDeAcceso = RELACION_ASISTENCIAL` y `reason` NULL, el de admin-a con `JUSTIFICACION` y
`reason = 'Auditoria interna QA'`. Un `PERMISSION_DENIED` por cada 403 por permiso. **Una lectura
200 sin su fila es hallazgo crítico.**

---

### CLI-09 · Resumen, antecedentes, entradas y adjuntos clínicos (sin pantalla)

```powershell
$hc = (Api GET "/api/v1/historias-clinicas/por-persona/<persona>" $P).Cuerpo
Api PUT "/api/v1/historias-clinicas/por-persona/<persona>/resumen" $P @{ texto = 'Gonalgia derecha de 3 meses (sintetico)'; expectedVersion = $hc.version }
Api POST "/api/v1/historias-clinicas/por-persona/<persona>/antecedentes" $P @{ tipo = 'ALERGIA'; descripcion = 'Alergia sintetica QA' }
$e = (Api POST "/api/v1/historias-clinicas/$($hc.id)/entradas" $P @{ tipo = 'EVOLUCION'; cuerpo = 'Evolucion sintetica QA' }).Cuerpo
Api POST "/api/v1/entradas-clinicas/$($e.id)/enmiendas" $P @{ cuerpo = 'Evolucion corregida QA'; motivo = 'Error de tipeo'; expectedVersion = $e.version }
```

El adjunto clínico es `multipart/form-data` (`archivo`). Con `curl.exe` desde PowerShell, con el
token en `$P`:

```powershell
curl.exe -s -H "Authorization: Bearer $P" -F "archivo=@C:\ruta\a\imagen-sintetica.png;type=image/png" "http://localhost:8080/api/v1/historias-clinicas/<historia>/adjuntos?categoria=IMAGEN&titulo=QA"
curl.exe -s -o descarga.png -w "%{http_code}" -H "Authorization: Bearer $P" "http://localhost:8080/api/v1/historias-clinicas/<historia>/adjuntos/<adjunto>/contenido"
```

```sql
SELECT resumen, resumen_actualizado_en, resumen_actualizado_por, version FROM historia_clinica WHERE id = @historia;
SELECT tipo, descripcion, registrado_en, active FROM historia_clinica_antecedente WHERE historia_clinica_id = @historia;

SELECT e.id, e.tipo, e.origen, e.ultimo_numero_version, e.active,
       v.numero_version, LEFT(v.cuerpo, 60) AS cuerpo, v.motivo_enmienda, v.registrada_por
FROM entrada_clinica e JOIN entrada_clinica_version v ON v.entrada_clinica_id = e.id
WHERE e.historia_clinica_id = @historia ORDER BY e.id, v.numero_version;

SELECT id, categoria, titulo, nombre_archivo, content_type, tamano_bytes, checksum_sha256, estado, active
FROM adjunto_clinico WHERE historia_clinica_id = @historia;
```

Esperado: la entrada con **dos** versiones (la 1 intacta, la 2 con motivo); el adjunto
`DISPONIBLE` con su checksum; la descarga 200 y el archivo idéntico.

**Auditoría:** `HISTORIA_CLINICA_RESUMEN_UPDATED`, `ANTECEDENTE_CLINICO_REGISTERED`,
`ENTRADA_CLINICA_REGISTERED`, `ENTRADA_CLINICA_AMENDED`, `ADJUNTO_CLINICO_UPLOADED`,
`ADJUNTO_CLINICO_DOWNLOADED` (con `checksumSha256`, **sin** el nombre del archivo).

---

### CLI-10 · Timeline clínico (sin pantalla) — `[IT TimelineIT]`

```powershell
Api GET "/api/v1/historias-clinicas/<historia>/timeline?limite=2" $P
```

**Esperado:** los hechos clínicos más nuevos primero (sesiones cerradas, entradas, adjuntos,
casos), paginados con `cursor`. **El turno no aparece**: ninguna transición administrativa prueba
una prestación. Recorré todas las páginas con el `cursor`: la cantidad total tiene que coincidir
con lo que cargaste (defecto 6 del 29/09: perdía eventos).

**Auditoría:** un `TIMELINE_ACCESSED` por página (`details.pagina`, `details.eventos`).

---

### CLI-11 · Caso clínico: abrir, equipo, cerrar y reabrir (sin pantalla)

```powershell
$c = (Api POST "/api/v1/historias-clinicas/<historia>/casos" $P @{ diagnosticoPresuntivo = 'Gonalgia derecha (sintetico)'; ofertaId = <oferta>; objetivoTerapeutico = 'Volver a correr'; equipo = @(@{ profesionalMembershipId = <membership_profesional>; rol = 'RESPONSABLE' }) }).Cuerpo
Api POST "/api/v1/casos-clinicos/$($c.id)/cierre" $P @{ expectedVersion = $c.version; motivo = 'Alta QA' }
```

Abrir un segundo caso con el mismo diagnóstico avisa posible duplicado (`confirmaPosibleDuplicado`).
Reabrí el cerrado con motivo.

```sql
SET @caso := (SELECT MAX(id) FROM caso_clinico WHERE historia_clinica_id = @historia);
SELECT id, numero_caso, oferta_id, diagnostico_presuntivo, estado, abierto_en, cerrado_en, motivo_cierre, version
FROM caso_clinico WHERE historia_clinica_id = @historia ORDER BY id;
SELECT tipo, estado_anterior, estado_nuevo, motivo, ocurrio_en FROM caso_evento WHERE caso_id = @caso ORDER BY id;
SELECT profesional_membership_id, rol, desde, hasta FROM caso_profesional WHERE caso_id = @caso;
```

Esperado: `numero_caso` correlativo por historia; eventos `APERTURA`, `CIERRE` (con motivo),
`REAPERTURA` (con motivo); el equipo con el responsable. Cambiar el equipo no borra a nadie: cierra
`hasta`.

**Auditoría:** `CASO_CLINICO_OPENED`, `CASO_CLINICO_CLOSED`, `CASO_CLINICO_REOPENED`,
`CASO_EQUIPO_CHANGED`.

> **Desde #109 `cambiarEquipo` devuelve la versión nueva** (`leída + 1`) y encadenar otra escritura
> con esa versión ya no da 409: se prueba en CLI-17.
> Un 409 al encadenar con la versión de la respuesta **es hallazgo**.

---

### CLI-12 · Sesión dentro de un caso

**Pantalla / API:** reservar otro turno para la persona y, al iniciar la sesión por la API, pasar
el caso: `POST /api/v1/consultorios/<sede_a>/sesiones/turnos/<turno>?casoId=<caso>`. Cerrarla por
pantalla.

```sql
SELECT id, caso_id, numero_sesion, numero_en_caso FROM sesion WHERE caso_id = @caso ORDER BY id;
SELECT ultimo_numero FROM caso_sesion_numerador WHERE caso_id = @caso;
```

Esperado: dos numeraciones independientes: `numero_sesion` (de la historia) y `numero_en_caso`
(del caso). **Auditoría:** `SESION_CERRADA` con `details.numeroEnCaso`.

---

### CLI-13 · Plan de tratamiento: borrador, activo, suspendido, reanudado, finalizado (sin pantalla)

```powershell
$p = (Api POST "/api/v1/casos-clinicos/<caso>/planes" $P @{ objetivos = 'Recuperar movilidad'; frecuenciaSemanal = 2; duracionSemanas = 4; items = @(@{ ofertaId = <oferta>; cantidadPlanificada = 8 }) }).Cuerpo
$p = (Api POST "/api/v1/planes-tratamiento/$($p.id)/activacion" $P @{ expectedVersion = $p.version }).Cuerpo
$p = (Api POST "/api/v1/planes-tratamiento/$($p.id)/suspension" $P @{ expectedVersion = $p.version; motivo = 'Viaje del paciente' }).Cuerpo
$p = (Api POST "/api/v1/planes-tratamiento/$($p.id)/reanudacion" $P @{ expectedVersion = $p.version }).Cuerpo
Api GET "/api/v1/planes-tratamiento/$($p.id)/avance" $P
```

```sql
SET @plan_tratamiento := (SELECT MAX(id) FROM plan_tratamiento WHERE caso_clinico_id = @caso);
SELECT id, numero_plan, estado, ultimo_numero_version, activado_en, suspendido_en, motivo_suspension, finalizado_en, version
FROM plan_tratamiento WHERE caso_clinico_id = @caso;
SELECT tipo, estado_anterior, estado_nuevo, motivo, numero_version FROM plan_evento WHERE plan_tratamiento_id = @plan_tratamiento ORDER BY id;
SELECT v.numero_version, v.objetivos, i.oferta_id, i.oferta_nombre, i.cantidad_planificada, i.autorizacion_id
FROM plan_tratamiento_version v LEFT JOIN plan_item i ON i.plan_tratamiento_version_id = v.id
WHERE v.plan_tratamiento_id = @plan_tratamiento ORDER BY v.numero_version;
```

Esperado: eventos `CREACION` → `ACTIVACION` → `SUSPENSION` (con motivo) → `REANUDACION`. **Un solo
plan vivo por caso**: un suspendido ocupa el lugar del activo (defecto 4 del 29/09); crear y activar
otro mientras éste vive es rechazo. El avance cuenta **por oferta**, no por práctica (conocido).
Modificar el plan (PATCH con motivo) crea la versión 2 y deja la 1.

**Auditoría:** `PLAN_TRATAMIENTO_CREATED`, `_ACTIVATED`, `_SUSPENDED`, `_RESUMED` (y `_AMENDED`,
`_FINALIZED`), más un `PLAN_TRATAMIENTO_ACCESSED` por cada lectura.

---

### CLI-14 · Ausente en la sesión

**Pantalla:** sobre otro turno, iniciar y cerrar con asistencia **ausente**.

```sql
SELECT s.id, s.estado, s.asistencia, (SELECT COUNT(*) FROM obligacion o WHERE o.sesion_id = s.id) AS obligaciones
FROM sesion s WHERE s.consultorio_id = @sede_a AND s.asistencia = 'AUSENTE';
```

Esperado: la sesión cerrada `AUSENTE`; la deuda depende de `devenga_no_show` de la oferta (por
defecto no devenga). Comparalo con `SELECT devenga_no_show FROM oferta_servicio_consultorio WHERE
id = @oferta`.

---

### CLI-15 · Lo clínico no se ve desde roles sin permiso

Con el administrativo y con el paciente: `/atencion/turnos/<id>` lleva a `/sin-permiso`; por la API,
`GET .../sesiones/<sesion>`, `GET /historias-clinicas/por-persona/<persona>` y el timeline son
**403**. Con el token de B, **404** (otra organización). Ninguna de esas lecturas deja
`HISTORIA_CLINICA_ACCESSED` ni `SESION_ACCEDIDA`: una sesión inalcanzable no deja fila (orden
pertenencia → permiso → dato, threat model §2.4).

```sql
SELECT a.event_type, c.email, a.occurred_at
FROM audit_event a JOIN cuenta c ON c.id = a.actor_account_id
WHERE a.organization_id = @org_a AND a.occurred_at >= @desde
  AND a.event_type IN ('HISTORIA_CLINICA_ACCESSED', 'SESION_ACCEDIDA', 'TIMELINE_ACCESSED')
  AND c.email_normalizado IN (@email_administrativo, @email_paciente, @email_admin_b);
```

Esperado: **cero filas**.

---

### CLI-16 · Datos administrativos de la HC para el administrativo (api #109, web #74) — `[IT MatrizDePermisosIT]`

La matriz §32 da al `ADMINISTRATIVO` "Ver Historia Clínica: **Limitado**" = solo metadatos, nunca
contenido. Desde el contrato 0.97.0 existe `GET
/api/v1/historias-clinicas/por-persona/{personaId}/datos-administrativos`: si la persona tiene
historia, cuándo se abrió, sus casos (número, estado, oferta, fechas) y el equipo **vigente** de
cada uno. Se autoriza con **`paciente:read`** (el del padrón, DP-22), **no** pide justificación y
**no** se audita como acceso clínico. `MatrizDePermisosIT` prueba el permiso por rol; la pantalla
no la recorre ningún E2E.

**Pantalla:** administrativo → `/pacientes/<persona>` (la ficha). Para una persona con perfil de
paciente aparece la tarjeta **"Historia clinica: datos administrativos"**:

| Caso de la persona | Lo que muestra |
|---|---|
| Sin historia | "Esta persona todavia no tiene historia clinica." |
| Con historia y sin casos | "Historia abierta el <fecha>" y "La historia no tiene casos abiertos ni cerrados." |
| Con los casos de CLI-11 | Una tabla: N.º, Activo/Cerrado, oferta, abierto, cerrado (o "—"), equipo vigente con su rol; los activos primero |

**Esperado:** en la tarjeta **no** hay resumen, antecedentes, diagnóstico presuntivo, objetivo ni
motivo de cierre, ni en la pantalla ni en la respuesta (DevTools → Network → `datos-administrativos`:
el cuerpo trae solo `personaId`, `tieneHistoriaClinica`, `historiaClinicaId`, `abiertaEn` y `casos`
con `id`, `numeroCaso`, `estado`, `ofertaId`, `ofertaConsultorioId`, `abiertoEn`, `cerradoEn`,
`equipo`). Quien salió del equipo en CLI-11 **no** figura. Una persona **sin** perfil de paciente no
muestra la tarjeta (la pantalla la pide solo si la ficha dice que es paciente).

**Por la API**, con cada rol (`$Adm` administrativo, `$P` profesional, `$Pac` paciente, `$B`
admin-b):

```powershell
Api GET "/api/v1/historias-clinicas/por-persona/<persona>/datos-administrativos" $Adm    # 200
Api GET "/api/v1/historias-clinicas/por-persona/<persona>" $Adm                          # 403: el contenido sigue cerrado (CLI-08)
Api GET "/api/v1/historias-clinicas/por-persona/<persona>/datos-administrativos" $Pac    # 403: el PACIENTE no tiene paciente:read
Api GET "/api/v1/historias-clinicas/por-persona/<persona>/datos-administrativos" $B      # 404: otra organización
Api GET "/api/v1/historias-clinicas/por-persona/<persona_sin_hc>/datos-administrativos" $Adm   # 200, tieneHistoriaClinica = false
Api GET "/api/v1/historias-clinicas/por-persona/<persona sin perfil de paciente>/datos-administrativos" $Adm   # contrato: 404
```

> **Defecto conocido (14/10):** la persona **sin perfil de paciente vigente** —nunca activado o dado
> de baja— responde **200** y no el 404 que declara el contrato; con el perfil dado de baja devuelve
> además la historia y sus casos. Está en [90](90-conocido-y-aceptado.md) §4: vinculalo, no lo
> registres como nuevo.

```sql
SELECT h.id AS historia, h.abierta_en, c.id AS caso, c.numero_caso, c.estado, c.oferta_id, c.abierto_en, c.cerrado_en
FROM historia_clinica h LEFT JOIN caso_clinico c ON c.historia_clinica_id = h.id
WHERE h.organization_id = @org_a AND h.persona_id = @persona
ORDER BY c.estado <> 'ACTIVO', c.id;

SELECT cp.caso_id, cp.profesional_membership_id, cp.rol, cp.desde, cp.hasta
FROM caso_profesional cp JOIN caso_clinico c ON c.id = cp.caso_id
WHERE c.historia_clinica_id = @historia AND cp.hasta IS NULL
ORDER BY cp.caso_id, cp.id;
```

Esperado: la tarjeta y la respuesta coinciden con estas filas (solo los integrantes con `hasta`
NULL). **Auditoría:** las lecturas del administrativo **no** dejan `HISTORIA_CLINICA_ACCESSED`;
el 403 del paciente sí deja `PERMISSION_DENIED` con `permissionCode = paciente:read`:

```sql
SELECT a.event_type, c.email, JSON_UNQUOTE(JSON_EXTRACT(a.details, '$.permissionCode')) AS permiso, a.occurred_at
FROM audit_event a JOIN cuenta c ON c.id = a.actor_account_id
WHERE a.organization_id = @org_a AND a.occurred_at >= @desde
  AND a.event_type IN ('HISTORIA_CLINICA_ACCESSED', 'PERMISSION_DENIED')
  AND c.email_normalizado IN (@email_administrativo, @email_paciente)
ORDER BY a.id DESC LIMIT 10;
```

Esperado: ningún `HISTORIA_CLINICA_ACCESSED` del administrativo; si aparece uno, el endpoint se
está auditando como lectura clínica (hallazgo medio). Un dato clínico en la respuesta es **crítico**.

> Validado sobre el stack aislado (14/10): administrativo, profesional y admin-a 200; el contenido
> de la historia con el administrativo, 403; paciente 403; admin-b 404; una persona propia sin
> historia, 200 con `tieneHistoriaClinica = false`. Ningún `HISTORIA_CLINICA_ACCESSED` del
> administrativo; el 403 del paciente dejó `PERMISSION_DENIED`. Ahí apareció el defecto de la
> persona sin perfil (arriba).

---

### CLI-17 · Cambiar el equipo de un caso devuelve la versión nueva (api #109, sin pantalla) — `[IT CasoClinicoConcurrenteIT]`

Hasta el 12/10 `cambiarEquipo` respondía la versión **leída** y encadenar otra escritura daba 409
(estaba en [90](90-conocido-y-aceptado.md) como criterio aceptado). Desde #109 avanza la versión
del caso con un `UPDATE ... WHERE version = :esperada` propio **antes** de tocar el equipo, y la
respuesta trae `leída + 1`. `CasoClinicoConcurrenteIT` prueba que dos cambios simultáneos con la
misma versión dejan entrar a uno (antes terminaban en deadlock); a mano se prueba el encadenado.

```powershell
$c = (Api GET "/api/v1/casos-clinicos/<caso>" $P).Cuerpo
$r1 = Api PUT "/api/v1/casos-clinicos/<caso>/equipo" $P @{ expectedVersion = $c.version; integrantes = @(@{ profesionalMembershipId = <membership_profesional>; rol = 'RESPONSABLE' }, @{ profesionalMembershipId = <otro profesional de la sede>; rol = 'TRATANTE' }) }
$r1.Status; $r1.Cuerpo.version          # 200 y version = $c.version + 1
$r2 = Api PUT "/api/v1/casos-clinicos/<caso>/equipo" $P @{ expectedVersion = $r1.Cuerpo.version; integrantes = @(@{ profesionalMembershipId = <membership_profesional>; rol = 'RESPONSABLE' }) }
$r2.Status                               # 200: encadenar con la versión de la respuesta ya no choca
Api PUT "/api/v1/casos-clinicos/<caso>/equipo" $P @{ expectedVersion = $c.version; integrantes = @(@{ profesionalMembershipId = <membership_profesional>; rol = 'RESPONSABLE' }) }   # 409 concurrent-modification
```

```sql
SET @caso := (SELECT MAX(id) FROM caso_clinico WHERE historia_clinica_id = @historia AND estado = 'ACTIVO');
SELECT id, estado, version FROM caso_clinico WHERE id = @caso;
SELECT profesional_membership_id, rol, desde, hasta FROM caso_profesional WHERE caso_id = @caso ORDER BY id;
SELECT tipo, detalle, ocurrio_en FROM caso_evento WHERE caso_id = @caso AND tipo = 'CAMBIO_DE_EQUIPO' ORDER BY id;
```

Esperado: `version` = la de `$r2` (avanzó **una** vez por cambio, nunca dos); el que salió en `$r2`
con `hasta` puesto y nadie borrado; dos eventos `CAMBIO_DE_EQUIPO` ("Entraron …, salieron …"). El
409 no deja evento ni cambia filas. Un caso cerrado responde 409 `caso-clinico-cerrado`. **Auditoría:** dos
`CASO_EQUIPO_CHANGED` con `details.entraron`/`details.salieron`, ninguno por el 409.

> Validado sobre el stack aislado (14/10): `r1` 200 con `version` 0 → 1, `r2` encadenado 200 con
> 2, la versión vieja 409 `concurrent-modification`, y dos cambios simultáneos con la misma versión:
> uno 200 y el otro 409, sin deadlock.
