# AKINE — Datos declarados por el paciente

> Diseño del 14/11/2026. Abarca los dos repos: `appKine-api` (permiso, migración, contrato) y
> `appKine-web` (pestaña de carga, marcas y revisión en la historia). Lo acordó el usuario en la
> conversación del 14/11; lo marcado **[decisión del diseño]** lo completó el diseño y se puede
> corregir.

## 1. Para qué

Cuando un paciente llega, o vuelve con papeles nuevos, cuenta su historia: antecedentes, alergias,
medicación que toma, cirugías, y trae estudios u órdenes. Hoy eso sólo lo puede cargar el
profesional, dentro de la historia clínica, porque la matriz de permisos le niega al
`ADMINISTRATIVO` editar la HC ("Editar HC: No", `RolePermissions.otorgableComoGrant`).

En la práctica del centro, **la secretaria o el profesional piden y cargan esos datos
indistintamente**: es transcribir lo que el paciente dice. La secretaria no indica ni prescribe
nada. Lo que el paciente dice **puede ser falso o impreciso**, así que no es dato validado: queda
marcado como declarado hasta que un profesional lo revisa.

**Éxito:** la secretaria carga en el mostrador lo que el paciente trae, sin ver el contenido
clínico del profesional, y el profesional, al abrir la historia, ve qué datos son declarados y sin
revisar y los marca como revisados.

## 2. Objetivos, no objetivos y antiobjetivos

**Objetivos**

- Una sección **permanente** "Datos declarados por el paciente": se carga en cualquier momento, no
  sólo en el alta (decisión del usuario).
- Puede cargar **la secretaria (`ADMINISTRATIVO`) o el profesional**, con el mismo permiso nuevo.
- Lo declarado son **antecedentes** (los siete tipos actuales) y **adjuntos clínicos** (estudios,
  órdenes, imágenes).
- Cada dato declarado lleva **marca por dato**: "Declarado · sin revisar", con quién lo cargó y
  cuándo, hasta que un profesional lo marca **Revisado** (quién y cuándo).
- La historia del profesional muestra cuántos declarados quedan sin revisar.

**No objetivos**

- La secretaria **no lee la historia**: ni sesiones, ni evoluciones, ni casos, ni el resumen, ni
  los antecedentes o adjuntos que cargó un profesional.
- La secretaria **no escribe el resumen clínico**: es la síntesis del profesional.
- No hay edición del texto de un dato declarado: se corrige dando de baja con motivo y cargándolo de
  nuevo, como hoy.
- No hay aviso al profesional por correo ni notificación: el aviso vive en la historia.
- No hay autoservicio del paciente (alcance `OWN`).

**Antiobjetivos** (lo que haría fracasar esto aunque funcione)

- Que un dato declarado se vea igual que uno validado en la historia.
- Que la secretaria, por la pestaña de declarados, llegue a leer contenido clínico del profesional.
  Lo declarado sí lo puede abrir, revisado o no: es lo que trajo el paciente.
- Que lo declarado quede en una lista aparte y el profesional tenga que copiarlo a la historia.

## 3. Restricciones

- **Cambia la matriz de permisos.** Hoy el `ADMINISTRATIVO` tiene "Editar HC: No" y la §3 de la
  matriz dice "No otorgable". Esto se registra como **decisión del usuario nueva (DP)** en
  `docs/producto/AKINE_IMPLEMENTATION_PLAN.md` §7 antes de escribir código, con el texto: el
  `ADMINISTRATIVO` puede **declarar** antecedentes y adjuntos del paciente en la HC, sin leerla.
- La HC sigue siendo de la **organización** (DP-03). Todo acceso clínico exige relación asistencial
  o **motivo declarado** (`X-Justificacion-Acceso`), y **toda lectura y escritura se audita**.
- **Baja lógica siempre.** Un declarado mal cargado se da de baja con motivo.
- Una persona sin **perfil de paciente vigente** no tiene historia (RF-M07-010): declarar sobre ella
  responde 409, igual que abrir.
- Contrato **aditivo**: minor nueva (la siguiente libre al rebasear; hoy la rama está en 0.171.0).
- Migración reservada: **`V16`** (la `V15` es de servicio personalizado, en la misma rama sin
  mergear). Confirmar al rebasear.

## 4. Enfoques considerados

1. **Origen en las tablas de siempre (elegido).** `historia_clinica_antecedente` y
   `adjunto_clinico` suman `origen`, `revisado_por` y `revisado_en`. Lo declarado vive en la
   historia, marcado. Un antecedente es un antecedente; al revisar no se copia nada; reusa la carga
   de adjuntos y su validación de archivos. Costo: migración aditiva sobre dos tablas clínicas.
2. **Tabla aparte de declaraciones que el profesional "incorpora".** La historia sólo tendría lo
   validado, pero el dato queda en dos lugares, los archivos se duplican o se mueven, y contradice
   lo que pidió el usuario (en la historia, marcado). Descartado.
3. **Ingreso por pasos sin tocar permisos** (la secretaria carga sólo ficha y documentos
   administrativos, el profesional la parte clínica). Respeta la matriz pero la secretaria no puede
   transcribir alergias ni medicación. El usuario lo descartó.

El usuario eligió **B** (darle a la secretaria la carga clínica declarada) con la marca de revisión,
y la implementación **1**.

## 5. Modelo

Migración `V16__datos_declarados.sql`, sobre las dos tablas:

```sql
ALTER TABLE historia_clinica_antecedente
    ADD COLUMN origen varchar(16) NOT NULL DEFAULT 'PROFESIONAL',
    ADD COLUMN revisado_por bigint,
    ADD COLUMN revisado_en timestamptz;
-- CHECK: origen IN ('PROFESIONAL','DECLARADO')
-- CHECK: origen = 'DECLARADO' OR (revisado_por IS NULL AND revisado_en IS NULL)
-- CHECK: (revisado_por IS NULL) = (revisado_en IS NULL)
```

Lo mismo para `adjunto_clinico`. Las filas existentes quedan `PROFESIONAL`: las cargó un
profesional. Nombres de constraint con la tabla adelante (son únicos por esquema).

- **`PROFESIONAL`**: lo cargó un profesional desde la historia, por los caminos de hoy. No lleva
  revisión.
- **`DECLARADO`**: entró por los endpoints de declarados. `revisado_*` en null = sin revisar.

**Quién carga queda en la columna de autor que ya tiene cada tabla** (registrado por / subido por).
El `origen` dice *por qué camino* entró, no *quién* lo cargó: un profesional que carga desde la
pestaña de declarados genera un `DECLARADO` [decisión del diseño: lo que el paciente dice sigue
siendo declarado aunque lo tipee un profesional; si el profesional ya lo verificó, lo carga desde la
historia como siempre].

La revisión se hace con un `UPDATE` condicionado (`WHERE origen = 'DECLARADO' AND revisado_en IS
NULL`) que también avanza `version` si la tabla la tiene (regla de los UPDATE nativos). Es
idempotente: revisar algo ya revisado devuelve el estado actual sin error.

## 6. Permiso

Código nuevo **`hc:declarar`**, en `PermissionCode` y en el catálogo.

- **De base:** `ADMINISTRATIVO`, `PROFESIONAL`, `CONSULTORIO_ADMIN` [decisión del diseño: el
  administrador de consultorio opera el mostrador igual que la secretaria] y `ORG_ADMIN`, con
  alcance de organización [decisión del usuario, 15/11: el dueño también carga; sigue sin leer la
  historia].
- **No otorgable** a `PACIENTE`. ~~Tampoco al `ORG_ADMIN`~~ [reemplazado el 15/11; decía: el dueño del centro sigue
  sin tocar contenido clínico, igual que hoy con `hc:write`].
- **Revisar** exige `hc:write`, que tiene el profesional. Declarar no alcanza para revisar.

`hc:declarar` **no implica** `hc:read`: no abre la pantalla de la historia ni ninguna lectura
clínica existente.

## 7. Contrato

Todas las operaciones de declarar van **por persona**, porque la secretaria puede no conocer, ni
tener, la historia: la primera declaración la abre (`HistoriaClinicaService.asegurar`, que ya
exige paciente vigente y audita la apertura).

| Operación | Ruta | Permiso | Qué hace |
|---|---|---|---|
| `verDatosDeclarados` | `GET /personas/{personaId}/datos-declarados` | `hc:declarar` | Los antecedentes y adjuntos **`DECLARADO` vigentes** de la persona, con su estado de revisión. Nada `PROFESIONAL`. Sin historia: listas vacías y `historiaClinicaId` null, no 404 |
| `declararAntecedente` | `POST /personas/{personaId}/datos-declarados/antecedentes` | `hc:declarar` | `tipo` + `descripcion`. Abre la historia si falta. 201 con el antecedente |
| `declararAdjunto` | `POST /personas/{personaId}/datos-declarados/adjuntos` (multipart) | `hc:declarar` | Archivo + `categoria` + `titulo`, con la misma validación de bytes y tamaño que `subirAdjuntoClinico`. Abre la historia si falta |
| `verContenidoDeAdjuntoDeclarado` | `GET /personas/{personaId}/datos-declarados/adjuntos/{id}/contenido` | `hc:declarar` | Devuelve el archivo para abrirlo o descargarlo, **sólo si el adjunto es `DECLARADO`**, revisado o no. Un `PROFESIONAL`, de otra persona o dado de baja → 404, igual que uno inexistente (no confirma que existe). Mismas cabeceras de descarga segura que `GET .../adjuntos/{id}/contenido` (tipo por bytes, `Content-Disposition`, `nosniff`). Se audita como lectura clínica [decisión del usuario, 14/11: la secretaria puede abrir lo que se declaró, para confirmar que subió el archivo correcto] |
| `darDeBajaDatoDeclarado` | `POST /personas/{personaId}/datos-declarados/{tipoDato}/{id}/baja` con motivo | `hc:declarar` | Sólo un `DECLARADO` **sin revisar**: la secretaria corrige su error. Revisado → 409 `dato-declarado-revisado` (lo da de baja el profesional desde la historia, como hoy) |
| `revisarAntecedente` | `PUT /historias-clinicas/por-persona/{personaId}/antecedentes/{id}/revision` | `hc:write` | Marca revisado. No `DECLARADO` → 409 `dato-no-declarado` |
| `revisarAdjunto` | `PUT /historias-clinicas/{historiaClinicaId}/adjuntos/{id}/revision` | `hc:write` | Ídem |

`{tipoDato}` es `antecedentes` o `adjuntos` [decisión del diseño: dos rutas de baja serían más
explícitas; el builder puede partirlas si el controlador queda más limpio]. Las rutas de revisión
siguen el patrón que ya tiene cada recurso: los antecedentes van por persona
(`/historias-clinicas/por-persona/{personaId}/antecedentes`) y su baja es un `POST .../baja`; los
adjuntos van por `{historiaClinicaId}`. La baja de declarados copia ese `POST .../baja`.

**Categorías al declarar un adjunto** [decisión del diseño]: `ESTUDIO`, `INFORME`, `IMAGEN` y
`OTRO`. `CONSENTIMIENTO_CLINICO` y `EVOLUCION_ESCANEADA` son documentos del profesional y no se
declaran (400). Una orden médica que el paciente trae en papel se declara como `OTRO` con título;
**registrar la orden** para la cobertura sigue siendo la pantalla de Órdenes y autorizaciones
(`contracting`, B-4). No hay categoría nueva ni cambio del CHECK.

**El mismo archivo dos veces** [decisión del diseño]. `subirAdjuntoClinico` es idempotente por
checksum (`uk_adjunto_clinico_contenido_vigente`). Al declarar:

- si el archivo ya está como `DECLARADO` vigente, se devuelve ese adjunto (idempotente, como hoy);
- si ya está como `PROFESIONAL`, se responde **409 `adjunto-ya-en-historia`** sin ningún dato del
  adjunto existente: la secretaria tiene el archivo en la mano, saber que ya está cargado no le
  revela nada, y no recibe la metadata del profesional (antiobjetivo 2).

El unique no cambia.

**Transacciones.** `AdjuntoClinicoService.subir` exige correr sin transacción
(`AlTerminarLaTransaccion.exigirSinTransaccion`) y `asegurar` es `@Transactional`: al declarar un
adjunto, primero se asegura la historia en su propia transacción y después se sube el archivo,
igual que la subida de hoy. Si la subida falla, la historia queda abierta y vacía: es inocuo (una
historia abierta sin datos es lo mismo que abrirla a mano).

**Lo que cambia en respuestas existentes (aditivo):**

- `AntecedenteResponse` y `AdjuntoClinicoResponse`: `origen`, `cargadoPorNombre`,
  `revisadoPorNombre`, `revisadoEn`.
- `HistoriaClinicaResponse`: `declaradosSinRevisar` (entero), para el aviso.

**Motivo de acceso:** las operaciones de declarados exigen el mismo `X-Justificacion-Acceso` que el
resto de lo clínico (DP-03), porque son datos de salud. La pantalla lo pide una vez por visita, con
un texto sugerido ("Carga de datos que declara el paciente") que se puede cambiar [decisión del
diseño].

**Auditoría:** eventos nuevos `ANTECEDENTE_DECLARADO`, `ADJUNTO_DECLARADO`,
`DATO_DECLARADO_DADO_DE_BAJA`, `DATO_DECLARADO_REVISADO`, en la transacción del negocio. La lectura
de `verDatosDeclarados` se audita como acceso clínico.

## 8. Pantallas (`appKine-web`)

### 8.1 Pestaña "Datos declarados" de la persona

Nueva pestaña en `cabecera-de-persona`, visible con `hc:declarar`, ruta
`/pacientes/:id/datos-declarados` [decisión del diseño: vive en la persona y no en
`/historia-clinica`, porque la secretaria no entra a la historia].

```
Ficha · Historia clínica · Datos declarados · Documentos · Coberturas · …

Datos declarados por el paciente  (?)
Lo que el paciente cuenta o trae. Queda sin revisar hasta que lo revisa un profesional.

┌ Antecedentes ─────────────────────────── [+ Agregar] ┐
│ Alergia     Penicilina            Sin revisar · Ana, 14/11  ⋯ │
│ Medicación  Enalapril 10 mg       Revisado · Dr. Pérez, 15/11  │
└──────────────────────────────────────────────────────┘
┌ Estudios y documentos ─────────────────── [Subir] ┐
│ RX rodilla izq.  Imagen  PDF 1,2 MB   Sin revisar    ⋯ │
└──────────────────────────────────────────────────────┘
```

- Alta de antecedente en línea: tipo (select) + descripción. Subida reusa el componente de
  adjuntos de la historia.
- El título de cada adjunto abre el archivo (`verContenidoDeAdjuntoDeclarado`), con la misma vista
  previa segura de la historia, revisado o no.
- "⋯" de una fila sin revisar: dar de baja con motivo. Revisadas: sin acciones, salvo abrir el
  archivo.
- Sin perfil de paciente vigente: estado vacío que explica que primero hay que activarlo, con
  enlace a la ficha.
- Cumple la norma global de diseño (`appKine-web/DESIGN.md`): estados con color + texto + ícono,
  claro y oscuro, 375 px sin desborde.

### 8.2 Historia clínica del profesional

- Aviso arriba si `declaradosSinRevisar > 0`: "N datos declarados por el paciente sin revisar",
  con enlace que filtra o lleva a las secciones.
- En antecedentes y adjuntos, cada `DECLARADO` lleva la marca "Declarado · sin revisar" (o
  "Declarado · revisado por X") y, sin revisar, un botón **Revisado**.
- Los `PROFESIONAL` se ven como hoy, sin marca.

### 8.3 Padrón

- "Abrir HC" (hecho el 14/11) sigue para quien tiene `hc:read` + `hc:write`.
- Quien tiene `hc:declarar` y no `hc:read` (la secretaria) ve **"Cargar datos"**, que lleva a la
  pestaña de declarados.

## 9. Lo que queda al builder

- Cómo reusar el servicio de adjuntos clínicos sin duplicar el storage (condición de salida de
  04.02: no sumar un tercer consumidor del storage duplicado; esto reusa el de `clinical`, no crea
  uno).
- Si el timeline muestra la marca de declarado en sus eventos (aditivo) o sólo en el detalle.
- Tests: los que reproducen las reglas — la secretaria no ve un `PROFESIONAL` por
  `verDatosDeclarados` ni abre su archivo por `verContenidoDeAdjuntoDeclarado` (404); revisar dos veces es idempotente; baja de un revisado → 409; persona sin
  perfil → 409; `hc:declarar` no abre ninguna lectura clínica existente (403). Un IT contra
  PostgreSQL para la migración y los CHECK.
- Regenerar el cliente en `appKine-web` y subir `contractVersion`.

## 10. Design challenge

1. **Ownership:** las dos tablas son de `clinical`; nadie más las toca. El permiso nuevo es de
   `organization` (catálogo) y lo consume `clinical`, como `hc:read`.
2. **Ciclos:** `clinical → person.spi` ya existe (paciente vigente). No hay aristas nuevas.
3. **Tenant:** las tablas ya llevan `organization_id`; las consultas nuevas filtran por él y la
   persona se resuelve en el tenant (otra organización → 404).
4. **Reglas maestras:** no toca Caso, Sesión ni Turno. Declarar no es atender.
5. **Baja lógica:** sí, con motivo.
6. **Contrato:** aditivo, minor.
7. **Ruta crítica:** HC, antecedentes y adjuntos existen desde 04.01/04.02.
8. **El caso que rompe:** la secretaria declara "alergia a penicilina" y el profesional, sin
   revisar, prescribe. Lo mitiga el aviso de declarados sin revisar en la historia y la marca en
   cada fila; **no lo impide** — bloquear la atención hasta revisar no lo pidió nadie y frenaría el
   circuito.
