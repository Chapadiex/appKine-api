# Ficha de ingreso — plan de implementación

> **Para agentes:** sub-skill requerida: superpowers:executing-plans (native) o
> superpowers:subagent-driven-development. Los pasos usan checkbox (`- [ ]`).

**Objetivo:** Ficha de ingreso compacta: ocupación desde un catálogo ampliable, peso, altura (IMC),
actividad física, diez enfermedades para tildar y cirugías con zona, año y lugar. Se declara desde
Datos declarados y la revisa el profesional en la HC.

**Arquitectura:** Todo vive en el módulo `clinical`.
- Una tabla `historia_clinica_ficha`, una fila por HC, con el mismo circuito origen/revisión de DP-47.
- Un catálogo `ocupacion` con `owner_key` (filas globales sembradas más filas del centro).
- Cuatro columnas opcionales nuevas en `historia_clinica_antecedente`.
- En el frontend, un componente `app-ficha-de-ingreso` que usan las dos pantallas.

**Stack:**
- Backend: Java 21, Spring Boot 4.1, PostgreSQL 18, Flyway, springdoc.
- Frontend: Angular 22 zoneless con signals y el cliente generado.

**Spec:** `appKine-api/docs/diseno/AKINE-ficha-de-ingreso.md`

## Restricciones globales

- **Ramas:**
  - Backend: `akine-servicio-personalizado`, en `C:\Users\santo\Desktop\AKINE\appKine-api`.
  - Frontend: `akine-web-estetica-3`, en `C:\Users\santo\Desktop\AKINE\appKine-web`.
  - Sin worktrees nuevos.
- **Sin commits, push ni PR** hasta que el usuario lo pida. Los pasos "commit" de la plantilla no se
  ejecutan.
- **Pocos tests y sin TDD:** un IT de la ficha y del catálogo. No escribir unitarios de DTO ni de
  framework.
- **Migración `V20__ficha_de_ingreso.sql`** (provisoria). Al preparar el PR se renumera junto con
  V15 → V18 y V16 → V19 (spec, "Migración y versión"). Los nombres de constraint llevan la tabla
  adelante.
- **Contrato 0.173.0** en `pom.xml` (`akine.contract.version`) **y** en `application.yml`. Si no se
  suben los dos, la regeneración falla con "versión bifurcada".
- **Rangos:**
  - Peso: 1 a 400 kg, `NUMERIC(5,1)`.
  - Altura: 30 a 250 cm, `NUMERIC(5,1)`.
  - Año: 1900 al año actual.
  - Largos máximos: zona 120, lugar 160, `actividad_detalle` 120, nombre de ocupación 80.
- **Actividad física:** `SEDENTARIO | OCASIONAL | REGULAR | DEPORTISTA`.
- **Enfermedades:** `DIABETES, HIPERTENSION, CARDIOPATIA, MARCAPASOS, OSTEOPOROSIS, ANTICOAGULADO,
  EMBARAZO, CANCER, EPILEPSIA, RESPIRATORIA`. Siempre generan un antecedente de tipo `MEDICO`.
- **IMC:** `peso / (altura/100)²`, redondeado a 1 decimal, y `null` si falta peso o altura. **No se
  persiste.**
- **UI:**
  - Tokens del sistema, claro y oscuro.
  - A 375 px sin desborde y sin scroll de página en 1440×900.
  - Guardado al salir del campo o al tildar.
  - Textos en español rioplatense ("tildá", "agregá").

## Review Focus

1. **Doble alta de la ficha:** dos PUT simultáneos de la primera ficha de una HC. Esperado: el segundo
   recibe 409 `concurrent-modification`, no un 500 por el unique. Test en Task 2.
2. **Ficha a medias:** con altura y sin peso (o al revés), el IMC tiene que ser `null` y no un error.
   Test en Task 2.
3. **Tilde repetido:** tildar dos veces la misma enfermedad (doble click) devuelve el mismo antecedente
   sin crear dos (idempotente). Test en Task 2.
4. **Ocupación duplicada:** "  docente " o "Dócente" ya existe como "Docente" global. Esperado:
   devuelve la existente, sin crear otra ni dar error. Test en Task 2.
5. **Edición de una ficha revisada:** quien declara edita una ficha ya revisada y debe volver a quedar
   sin revisar. Si la edita un profesional, queda revisada. Test en Task 2.

---

### Task 1: Migración, dominio y persistencia

**Archivos:**
- Crear:
  - `src/main/resources/db/migration/V20__ficha_de_ingreso.sql`
  - `src/main/java/com/akine/clinical/domain/FichaDeIngreso.java`
  - `src/main/java/com/akine/clinical/domain/Ocupacion.java`
  - `src/main/java/com/akine/clinical/domain/ActividadFisica.java`
  - `src/main/java/com/akine/clinical/domain/EnfermedadDeIngreso.java`
  - `src/main/java/com/akine/clinical/infrastructure/FichaDeIngresoRepository.java`
  - `src/main/java/com/akine/clinical/infrastructure/OcupacionRepository.java`
- Modificar:
  - `src/main/java/com/akine/clinical/domain/AntecedenteClinico.java`: campos `zona`, `anio`,
    `lugar`, `codigo`.
  - `src/main/java/com/akine/clinical/domain/port/ClinicalRepositoryPorts.java`: puertos nuevos.

**Interfaces que produce:**
- `enum ActividadFisica { SEDENTARIO, OCASIONAL, REGULAR, DEPORTISTA }`
- `enum EnfermedadDeIngreso { DIABETES, …, RESPIRATORIA; String etiqueta(); }`. Etiquetas:
  "Diabetes", "Hipertensión", "Cardiopatía", "Marcapasos", "Osteoporosis", "Anticoagulado",
  "Embarazo", "Cáncer", "Epilepsia", "Asma / EPOC".
- `FichaDeIngreso` (entidad, `@Version`):
  - Constructor `FichaDeIngreso(long organizationId, long historiaClinicaId, Instant ahora, long cargadoPor)`.
  - `actualizar(Long ocupacionId, BigDecimal pesoKg, BigDecimal alturaCm, ActividadFisica actividad,
    String detalle, OrigenDato origen, long cuenta, Instant ahora)`. Con origen `PROFESIONAL` deja
    `revisadoPor = cuenta` y `revisadoEn = ahora`; con `DECLARADO` los pone en `null`.
  - `revisar(long cuenta, Instant ahora)`.
  - `BigDecimal imc()`.
  - Getters.
- `Ocupacion` (entidad):
  - Constructor `Ocupacion(Long organizationId, String nombre)`.
  - `static String normalizar(String)`: minúsculas, sin acentos, espacios colapsados y recortados.
- Puertos:
  - `FichaDeIngresoRepositoryPort`: `Optional<FichaDeIngreso> buscarPorHistoria(long organizationId, long historiaClinicaId)` y `save`.
  - `OcupacionRepositoryPort`: `List<Ocupacion> visibles(long organizationId)` (owner 0 + el
    centro, activas, ordenadas por nombre), `Optional<Ocupacion> buscarVisiblePorNormalizado(long
    organizationId, String normalizado)`, `Optional<Ocupacion> buscarVisible(long organizationId,
    long id)` y `save`.
- `AntecedenteClinico`:
  - `conDetalle(String zona, Short anio, String lugar)` devuelve `this`.
  - `conCodigo(EnfermedadDeIngreso codigo)` devuelve `this`.
  - Getters de los cuatro campos.
- `AntecedenteClinicoRepositoryPort.buscarVigentePorCodigo(long organizationId, long
  historiaClinicaId, EnfermedadDeIngreso codigo)`.

- [ ] **Paso 1: escribir V20.**
  - **`ocupacion`:**
    - Columnas: `id`, `organization_id` nullable, `owner_key bigint GENERATED ALWAYS AS
      (COALESCE(organization_id, 0)) STORED`, `nombre varchar(80)`, `nombre_normalizado
      varchar(80)`, `active`, `created_at`.
    - `UNIQUE (owner_key, nombre_normalizado)`.
    - Seed de ~40 oficios globales con `organization_id NULL`, con el normalizado calculado a mano en
      el INSERT: Docente, Administrativo/a, Comerciante, Albañil, Chofer, Enfermero/a, Médico/a,
      Estudiante, Jubilado/a, Ama de casa, Empleado/a de comercio, Operario/a, Programador/a,
      Contador/a, Abogado/a, Cocinero/a, Mozo/a, Peluquero/a, Mecánico/a, Electricista, Plomero/a,
      Carpintero/a, Agricultor/a, Policía, Militar, Deportista, Bailarín/a, Músico/a, Diseñador/a,
      Ingeniero/a, Arquitecto/a, Vendedor/a, Repartidor/a, Personal de limpieza, Seguridad,
      Odontólogo/a, Kinesiólogo/a, Desempleado/a, Cuidador/a, Otro.
  - **`historia_clinica_ficha`:**
    - Columnas: `id`, `organization_id NOT NULL`, `historia_clinica_id NOT NULL` con FK, `ocupacion_id`
      con FK nullable, `peso_kg numeric(5,1)` y `altura_cm numeric(5,1)` con CHECK de rango,
      `actividad_fisica varchar(16)` con CHECK de los cuatro valores, `actividad_detalle varchar(120)`.
    - Origen y revisión: `origen`, `cargado_por`, `revisado_por`, `revisado_en`, más los mismos tres
      CHECK de V16 con el prefijo `ck_hc_ficha_`.
    - Control: `version`, `created_at`, `updated_at`.
    - `UNIQUE (organization_id, historia_clinica_id)`.
  - **`historia_clinica_antecedente`:**
    - `ADD COLUMN zona varchar(120), anio smallint, lugar varchar(160), codigo varchar(40)`.
    - `CHECK (anio BETWEEN 1900 AND 2100)`. El tope real del año actual lo valida la aplicación.
    - `CHECK (codigo IS NULL OR tipo = 'MEDICO')`.
    - Unique parcial `ux_hc_antecedente_codigo_vigente ON (historia_clinica_id, codigo) WHERE codigo
      IS NOT NULL AND active`.

- [ ] **Paso 2: entidades, enums, repositorios y puertos** con las firmas de arriba. Seguí el estilo
  de `AntecedenteClinico` y `AntecedenteClinicoRepository`. `visibles` es una consulta nativa con
  `owner_key IN (0, :org)`.

- [ ] **Paso 3: compilar.**
  - Correr: `./mvnw -o -q compile`
  - Esperado: BUILD SUCCESS.

### Task 2: Servicios, API, contrato e IT

**Archivos:**
- Crear:
  - `src/main/java/com/akine/clinical/application/FichaDeIngresoService.java`
  - `src/main/java/com/akine/clinical/application/FichaDeIngresoView.java`
  - `src/main/java/com/akine/clinical/application/OcupacionService.java`
  - `src/main/java/com/akine/clinical/api/OcupacionController.java`
  - `src/main/java/com/akine/clinical/api/dto/FichaDeIngresoRequest.java`
  - `src/main/java/com/akine/clinical/api/dto/FichaDeIngresoResponse.java`
  - `src/main/java/com/akine/clinical/api/dto/OcupacionResponse.java`
  - `src/main/java/com/akine/clinical/api/dto/AltaDeOcupacionRequest.java`
  - Test: `src/test/java/com/akine/clinical/FichaDeIngresoIT.java`
- Modificar:
  - `AntecedenteClinicoService`: `declarar` y `registrar` reciben un `DetalleDeAntecedente`.
  - `DatosDeclaradosService` y `DatosDeclaradosController`: `ficha` en la vista y `PUT /ficha`.
  - `HistoriaClinicaController`: `PUT /{id}/ficha`, `POST /{id}/ficha/revision` y `ficha` en
    `HistoriaClinicaResponse`.
  - `HistoriaClinicaService.declaradosSinRevisar`: suma 1 si la ficha está declarada y sin revisar.
  - DTOs: `RegistrarAntecedenteRequest`, `AntecedenteResponse` y `AntecedenteView`.
  - `AuditEvents`: `FICHA_DECLARADA`, `FICHA_REGISTRADA`, `FICHA_REVISADA`, `OCUPACION_AGREGADA`.
  - `pom.xml` y `application.yml` → 0.173.0.
  - `openapi/akine-api.yaml`, que se regenera.

**Interfaces:**
- Consume: todo lo que produce la Task 1.
- Produce el contrato que el frontend regenera:
  - `record DetalleDeAntecedente(String zona, Short anio, String lugar, EnfermedadDeIngreso codigo)`.
    `DetalleDeAntecedente.NINGUNO` tiene todo en `null`.
  - `FichaDeIngresoService`:
    - `FichaDeIngresoView declarar(OperatingActor, long personaId, DatosDeFicha datos, Long version, String justificacion)`:
      autoriza con `HC_DECLARAR` vía `AutorizacionClinica.exigirSobrePersona` y abre la HC con
      `historiaService.asegurar`. Origen `DECLARADO`.
    - `FichaDeIngresoView registrar(OperatingActor, long historiaClinicaId, DatosDeFicha, Long version, String justificacion)`:
      autoriza con `HC_WRITE`. Origen `PROFESIONAL`.
    - `FichaDeIngresoView revisar(OperatingActor, long historiaClinicaId, String justificacion)`:
      autoriza con `HC_WRITE`.
    - `Optional<FichaDeIngresoView> deHistoria(long organizationId, long historiaClinicaId)`.
  - `record DatosDeFicha(Long ocupacionId, BigDecimal pesoKg, BigDecimal alturaCm, ActividadFisica actividad, String detalle)`.
  - **Regla de versión:** si existe ficha y `version` es `null` o distinta, 409
    `concurrent-modification`. Si no existe y llega una `version`, también 409. Un choque del unique
    al insertar se traduce a 409 `concurrent-modification`.
  - `FichaDeIngresoView`: `version`, `ocupacionId`, `ocupacionNombre`, `pesoKg`, `alturaCm`, `imc`,
    `actividad`, `detalle`, `origen`, `cargadoPorNombre`, `revisadoPorNombre`, `revisadoEn`,
    `actualizadaEn`.
  - `OcupacionService`:
    - `List<Ocupacion> visibles(OperatingActor)`: miembro del tenant, sin permiso extra.
    - `Ocupacion agregar(OperatingActor, String nombre)`: exige `HC_DECLARAR` o `HC_WRITE`. Si el
      normalizado ya existe visible, devuelve esa fila (idempotente). Si no, crea la del centro. Un
      nombre en blanco responde 400.
  - Endpoints:
    - `GET /api/v1/ocupaciones` → `OcupacionResponse[]` (`id`, `nombre`, `propia`).
    - `POST /api/v1/ocupaciones` → `{nombre}`. Responde 201 si la creó y 200 si ya existía.
    - `PUT /api/v1/personas/{personaId}/datos-declarados/ficha` → `FichaDeIngresoRequest`
      (`ocupacionId`, `pesoKg`, `alturaCm`, `actividad`, `detalle`, `version`), con Bean
      Validation de los rangos globales. Responde `FichaDeIngresoResponse`.
    - `PUT /api/v1/historias-clinicas/{id}/ficha` y `POST /api/v1/historias-clinicas/{id}/ficha/revision`.
    - `DatosDeclaradosResponse.ficha` y `HistoriaClinicaResponse.ficha`, nullable.
    - `RegistrarAntecedenteRequest` suma `zona`, `anio`, `lugar` y `codigo` opcionales.
      `AntecedenteResponse` los devuelve.
    - Todas las operaciones con `produces` que incluya `application/json` (regla 15 del CLAUDE.md).
  - **Antecedentes con `codigo`:**
    - Si `tipo != MEDICO`, 400.
    - Si ya hay uno vigente con ese código en la HC, se devuelve ese, sin auditar otra alta.
    - Un `anio` mayor que el año actual en la zona de la sede (`ZonaDeSede` o la zona de la
      organización) responde 400.

- [ ] **Paso 1: implementar los servicios, DTOs y endpoints** con las firmas de arriba. La auditoría va
  sin contenido clínico: se audita `historiaClinicaId` y `origen`, nunca peso, ocupación ni texto
  (mismo criterio que `ANTECEDENTE_DECLARADO`). Si un problem type nuevo hiciera falta, se agrega en
  `ProblemType` y se mapea en `ClinicalProblemHandler`, pero no se espera ninguno.

- [ ] **Paso 2: escribir `FichaDeIngresoIT`** (`MockMvc` contra la base en memoria, mismo soporte que
  `DatosDeclaradosIT`):
  - `declararFicha_quedaSinRevisar_conImc`: PUT con peso 72 y altura 168 → `imc == 25.5`, `origen ==
    DECLARADO`, `revisadoEn == null`, y `declaradosSinRevisar` de la HC `>= 1`.
  - `fichaSinPeso_imcNull`: altura 168 y peso null → 200 y `imc` null.
  - `profesionalRevisa_yDeclaranteEdita_vuelveSinRevisar`: revisar → `revisadoEn != null`. Después,
    PUT DECLARADO con la versión nueva → `revisadoEn == null`. Después, PUT PROFESIONAL por la HC →
    revisado.
  - `versionVieja_409`: PUT con la versión anterior → 409 con `type` que termina en
    `concurrent-modification`.
  - `primerAltaConcurrente_unoGana_otro409`: dos PUT sin versión en paralelo (`CarreraDirigida` o dos
    hilos) → un 200 y un 409, nunca 500.
  - `pesoFueraDeRango_400`: peso 0 → 400.
  - `enfermedadTildadaDosVeces_mismoAntecedente`: dos POST de `/antecedentes` con `codigo DIABETES` →
    el mismo `id`.
  - `ocupacionNormalizada_devuelveLaExistente`: POST `/ocupaciones` con `"  dócente "` → 200 y el `id`
    de la global "Docente". POST `"Guardavidas"` → 201 con `propia: true`, y aparece en el GET.

- [ ] **Paso 3: correr el IT.**
  - Correr: `./mvnw -o verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=FichaDeIngresoIT -Djacoco.skip=true`
  - Esperado: 8/8 en verde. Correr también `DatosDeclaradosIT`: 16/16 siguen en verde.

- [ ] **Paso 4: subir a 0.173.0 y regenerar el contrato.**
  - Correr: `./mvnw -o verify -Dakine.contract.update=true -Dit.test=OpenApiContractIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
  - Esperado: verde, y `grep -c "ocupaciones\|/ficha" openapi/akine-api.yaml` mayor que 0.

- [ ] **Paso 5: unitarios afectados** por los constructores cambiados.
  - Correr: `./mvnw -o test -Dtest="com.akine.clinical.**.*Test" -Djacoco.skip=true`
  - Esperado: 0 fallos.

### Task 3: Cliente regenerado y componente `app-ficha-de-ingreso`

**Archivos:**
- Regenerar: `src/app/api/generated/` con `npm run api:generate`. Llevar `contractVersion` a 0.173.0
  en los dos `environment*.ts`.
- Crear: `src/app/features/clinical/components/ficha-de-ingreso/ficha-de-ingreso.{ts,html,css}`.
- Modificar:
  - `src/app/features/clinical/pages/datos-declarados/datos-declarados.api.ts`: agregar
    `guardarFicha` y `ocupaciones`, y `agregarOcupacion`.
  - `src/app/features/clinical/services/historia-clinica-api.ts`: agregar `guardarFicha`,
    `revisarFicha` y `ocupaciones`, y `agregarOcupacion`.

**Interfaces:**
- Consume: el cliente generado (`OcupacionesService` o el nombre que salga del tag,
  `FichaDeIngresoResponse`, `FichaDeIngresoRequest`).
- Produce `FichaDeIngresoComponent`, selector `app-ficha-de-ingreso`:
  - Entradas:
    - `ficha = input<FichaDeIngresoResponse | null>()`
    - `ocupaciones = input<readonly OcupacionResponse[]>([])`
    - `enfermedadesTildadas = input<ReadonlySet<string>>(new Set())`: códigos vigentes.
    - `enfermedadesRevisadas = input<ReadonlySet<string>>(new Set())`: códigos que quedan fijos.
    - `guardando = input(false)`
  - Salidas:
    - `guardar = output<FichaDeIngresoRequest>()`: la ficha entera, con `version`.
    - `agregarOcupacion = output<string>()`
    - `tildar = output<{ codigo: string; etiqueta: string; marcado: boolean }>()`
- Layout según el bloque "Pantalla" de la spec:
  - **Línea 1:** ocupación con `app-buscador-con-alta` (`opciones` = nombres; al elegir un nombre
    existente se emite `guardar` con su `id`, y al elegir uno nuevo se emite `agregarOcupacion`),
    peso, altura, IMC de solo lectura y actividad (select más texto corto).
  - **Líneas 2 y 3:** "¿Tiene…?" con diez checkboxes con `label`. Los revisados van `disabled` con
    `title="Revisado: se cambia desde la historia clínica"`.
  - **Estado:** una marca "Guardado" o "Guardando…" con `role="status"`, y una pastilla "Sin revisar"
    o "Revisado".
  - **Guardado:** `guardar` se emite en `(change)` o `(blur)` de cada campo, solo si el valor cambió.
  - **Responsive:** grilla con `flex-wrap`. A 375 px cada campo ocupa el ancho.
- El componente no sabe de API: las páginas resuelven las salidas.

- [ ] **Paso 1: regenerar el cliente y compilar.**
  - Correr: `npm run api:generate && npx tsc -p tsconfig.app.json --noEmit`
  - Esperado: sin errores salvo los que arregla la Task 4.

- [ ] **Paso 2: escribir el componente** según la interfaz de arriba.

- [ ] **Paso 3: lint del archivo.**
  - Correr: `npx eslint src/app/features/clinical/components/ficha-de-ingreso`
  - Esperado: limpio.

### Task 4: Las dos pantallas y la cirugía en línea

**Archivos:**
- Modificar `src/app/features/clinical/pages/datos-declarados/datos-declarados-page.{ts,html,css}`:
  - La ficha arriba de `.declarados`.
  - Cuando `tipoNuevo() === 'QUIRURGICO'`, el alta de antecedente muestra tres campos más en la misma
    `.declarados__linea`: Zona, Año (`type="number"`, `min=1900`, `max` el año actual) y Dónde.
  - La lista muestra el detalle "Rodilla der. · 2019 · Hospital X" a continuación del texto.
- Modificar `src/app/features/clinical/pages/historia-clinica/historia-clinica-page.{ts,html,css}`:
  - La ficha arriba del resumen, editable con `hc:write`, más el botón "Revisado" si está declarada y
    sin revisar.
  - El alta de antecedente suma los mismos tres campos para Quirúrgico.

**Interfaces:**
- Consume: `FichaDeIngresoComponent` de la Task 3 y los métodos de los dos `*.api.ts`.

- [ ] **Paso 1: Datos declarados.**
  - `guardar` hace PUT de la ficha: actualiza el signal con la respuesta, y si hay 409 recarga y
    muestra "Otra persona cambió la ficha, revisá los datos".
  - `agregarOcupacion` hace POST y después `guardar` con el `id` devuelto.
  - `tildar` marcado declara un antecedente `MEDICO` con `codigo` y `descripcion = etiqueta`. Al
    destildar, si sigue sin revisar, se da de baja con el motivo fijo "Destildado en la ficha de
    ingreso".
  - Las enfermedades tildadas salen de `antecedentes` con `codigo` vigentes.

- [ ] **Paso 2: HC page**, igual pero con los endpoints de la HC y "Revisado" → `revisarFicha`.

- [ ] **Paso 3: unitarios de las dos carpetas.**
  - Correr: `npx ng test --watch=false --no-coverage --include "src/app/features/clinical/pages/{datos-declarados,historia-clinica}/**/*.spec.ts"`
  - Esperado: verdes. Si un spec viejo choca con el DOM nuevo, ajustar el selector y no la conducta.

- [ ] **Paso 4: verificar en la app real.**
  - Pila: `akine-api-qa` en 8080 (reiniciar para que aplique V20) y `akine-web-dev` en 4200.
  - **Como Ana, en Datos declarados de una persona:**
    - Elegir "Docente"; agregar "Guardavidas" y comprobar que queda elegido; cargar peso 72 y altura
      168 → IMC 25,5.
    - Tildar Diabetes; cargar una cirugía "Rodilla der. · 2019 · Hospital Privado".
    - Recargar y comprobar que todo persiste.
  - **Como profesional, en la HC:**
    - Ver la ficha "sin revisar" y apretar Revisado.
    - Como Ana, cambiar el peso y comprobar que vuelve a "sin revisar".
  - **En el navegador:** claro y oscuro; 375 px sin desborde; 1440×900 sin scroll de página.
  - **En la base** (validación de persistencia del §6): `SELECT` sobre `historia_clinica_ficha` y
    sobre `ocupacion WHERE organization_id IS NOT NULL`.
  - Esperado: todo lo anterior se cumple, con captura como prueba.
