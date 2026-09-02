# AKINE-03.02 — Paciente 360, baja logica y adjuntos administrativos

**Modulos:** `person` (propietario), con contribuciones de `scheduling` y `billing`.
**Migracion:** `V40`. **Contrato:** `0.23.0`.
**Trazabilidad:** RF-M07-003..006, RF-M25-001..005; RN-M07-003, RN-M07-004, RN-M07-006,
RN-M25-001..005.

---

## 1. Las tres piezas y por que van juntas

La etapa entrega tres cosas que a primera vista no se parecen: una ficha consolidada, una baja
logica y un servicio de archivos. Van juntas porque las tres son **lo que le falta a la ficha
administrativa de 03.01 para que un mostrador pueda trabajar con ella**: ver todo de un paciente,
cerrar una ficha sin perderla, y guardarle los papeles.

| Pieza | RF | Donde vive |
|---|---|---|
| Ficha 360 | RF-M07-004 | `ResumenDePersonaService` + `person.spi.ResumenDePersonaContributor` |
| Baja de persona y de perfil | RF-M07-005 | `PersonaService.darDeBaja`, `PerfilPacienteService.desactivar` |
| Adjuntos administrativos | RF-M07-006, RF-M25-001..005 | `AdjuntoService`, `AdjuntoStoragePort`, `V40` |

---

## 2. La decision central: el 360 invierte la dependencia

El reflejo es que `person` le pregunte a `scheduling` y a `billing` por los turnos y la deuda de
una persona. **Eso es un ciclo.** Los dos ya dependen de `person.spi` —un turno y una obligacion
cuelgan de una persona— y `ModuleArchitectureTest.sin_ciclos_entre_modulos` lo rechaza.

Asi que `person` publica `ResumenDePersonaContributor` y **cada modulo rio abajo la implementa**:

```
person.spi.ResumenDePersonaContributor   <-- lo implementan
        ^                                    scheduling.infrastructure.TurnosEnElResumenDePersona
        |                                    billing.infrastructure.EconomiaEnElResumenDePersona
   ResumenDePersonaService inyecta List<...>
```

Es el mismo patron que `clinical.spi.EventoClinicoContributor`, con la diferencia de que aquel
nacio sin implementaciones y este nace con dos.

**Consecuencia buscada:** una seccion nueva del 360 no toca `person`. Cuando 03.03 traiga
financiadores y 03.04 las coberturas, agregan su contributor y la seccion aparece sola, sin
cambiar el DTO ni la version mayor del contrato.

### "Segun permisos" significa recortar, no rechazar

Cada contribuyente **declara su codigo de permiso** —`turno:read`, `cobro:register`— y `person`
lo evalua con una sola llamada a `effectivePermissions`. Si falta, la seccion **no se pide** y
viaja en `seccionesOmitidas` con el codigo que falto.

Las dos mitades importan:

- Si se pidiera igual y se descartara despues, el permiso seria decorativo y la consulta se
  haria de todos modos.
- Si se omitiera en silencio, la pantalla leeria **"sin turnos" donde en realidad dice "no podes
  ver los turnos"**, y alguien tomaria una decision sobre ese vacio.

Devolver 403 sobre la ficha entera tampoco sirve: dejaria al profesional sin poder abrir a ningun
paciente por no poder ver la deuda.

### Lo que el 360 NO muestra, y no es que falte

**Nada clinico.** AKINE-04.01 fijo que todo acceso clinico exige justificacion declarada y queda
auditado. Una ficha de mostrador que muestre casos al abrirla convertiria ese control en un
formalismo: la justificacion dejaria de ser una decision del profesional y pasaria a ser un efecto
de haber hecho click en una persona.

**Coberturas** si faltan, pero por otra razon: son M08/M15 y no existen todavia.

---

## 3. Baja logica: que arrastra y que no

`DELETE /api/v1/personas/{id}` con motivo obligatorio y `expectedVersion`.

| Decision | Por que |
|---|---|
| **Arrastra el perfil de paciente** vigente, en la misma transaccion | Dejarlo vivo produciria una ficha que `PacienteDirectory` sigue reportando como paciente vigente —los consumidores preguntan por `esPacienteVigente`, no por `activa`— y `scheduling` dejaria reservarle turnos a alguien que el padron considera cerrado |
| **Libera el documento** | El unique de `V27` lleva `deleted_key`. Es intencional: permite corregir una ficha creada mal sin borrarla |
| **No borra nada mas** | Turnos, obligaciones y adjuntos siguen existiendo y siguen resolviendo (RN-M07-004) |
| **No valida turnos futuros ni deuda** | Es la diferencia con la baja de un consultorio o de un espacio, que si tienen sonda de referencias. Dar de baja a alguien que se fue debiendo es legitimo y frecuente; bloquearlo obligaria a condonar para poder cerrar la ficha. Un aviso en la pantalla es la respuesta correcta, no un 409 |
| **Devuelve 200 con la ficha**, no 204 | La pantalla necesita la version nueva para seguir operando sin otro GET |

La baja del **perfil solo** (`DELETE /personas/{id}/perfil-paciente`) es una operacion distinta: la
persona sigue vigente y lo unico que deja de ser es paciente, que es exactamente el estado que
RN-M07-006 describe. Es **idempotente y responde 200**, como la activacion.

---

## 4. Adjuntos: las cinco decisiones que importan

### 4.1 El binario no entra a la base

`adjunto_administrativo` guarda **metadata**; el contenido vive detras de `AdjuntoStoragePort`,
cuyo unico adaptador escribe en el sistema de archivos local. Los motivos completos estan en la
cabecera de `V40`; el resumen es que un LONGBLOB de 10 MB por fila arrastra backups, dumps y buffer
pool, y que migrar `storage_key` a almacenamiento de objetos es cambiar un adaptador.

**El orden de escritura es fila primero (con flush) y blob despues, dentro de la misma
transaccion.** Si el blob falla, la transaccion revierte y no queda fila. Si el blob se escribe y
la transaccion revierte despues, queda un archivo **huerfano que nadie referencia**: invisible,
inofensivo y barrible. El orden inverso produciria filas apuntando a nada, que es el unico de los
dos errores que el usuario ve.

### 4.2 El tipo lo deciden los bytes

`TipoDeArchivo` compara la firma binaria contra una lista blanca corta: PDF, PNG, JPEG. **El
`Content-Type` declarado se descarta**, porque lo elige quien sube: un `.pdf` que en realidad es un
HTML con `<script>`, servido despues desde el mismo origen, es un XSS almacenado. Lo que se guarda
y lo que se devuelve al descargar es el tipo **detectado**.

No se usa `Files.probeContentType` ni `guessContentTypeFromStream`: el primero mira la extension en
varias plataformas y el segundo reconoce mas tipos de los que se aceptan, que es lo contrario de lo
que hace falta.

Los formatos ofimaticos quedan afuera a proposito: un `.docx` es un ZIP con macros posibles y no
hay caso de uso administrativo que lo necesite.

### 4.3 El path traversal es imposible, no prevenido

La ruta en disco se compone **exclusivamente** con `storageKey`, un UUID sin guiones que genera el
servidor. Ni el nombre del archivo, ni su extension, ni ningun otro dato de origen externo
participan de ella. No hay ninguna cadena que un atacante controle que llegue a `Path.resolve`.

La validacion de forma en el adaptador es un cinturon contra el dia en que alguien decida que la
clave "podria" traer el nombre del archivo para debuggear mas comodo.

### 4.4 La subida es idempotente

Unique `(organization_id, persona_id, checksum_sha256, deleted_key)`. Subir dos veces el mismo
archivo devuelve **200 con el adjunto que ya existe**, no 201 y no 409.

Una subida es la operacion mas expuesta a reintentos del producto —mostrador, varios MB, timeouts—
y un reintento de un POST que si habia llegado no deberia dejar dos filas que despues alguien
desempata a ojo. Un 409 seria correcto y aun asi inutil: le pediria al operador que resuelva una
carrera que el no produjo.

El pre-chequeo de la aplicacion **no garantiza nada**: dos requests simultaneos lo pasan los dos.
Lo garantiza el unique, y esta verificado contra MySQL real en `AdjuntoMigrationIT`.

### 4.5 No hay URLs temporales firmadas, y es una decision

La etapa las menciona. Una URL firmada tiene sentido cuando el binario lo sirve **otro sistema** y
lo que se quiere evitar es que el trafico pase por la aplicacion. Con un adaptador de sistema de
archivos local, el binario pasa por la aplicacion igual, y firmar una URL solo agregaria **un
segundo camino de autorizacion, mas debil que el primero**: un token en la query string que se
copia, se comparte, queda en los logs del proxy y en el historial del navegador, y que no se puede
revocar cuando al usuario se le quita el permiso.

Lo que si se cumple es la regla que las URLs firmadas existen para cumplir —RN-M25-002, no exponer
rutas internas—: la descarga va por un endpoint que autoriza cada llamada y la `storageKey` no sale
del backend por ningun campo.

---

## 5. Permisos: RN-M25-003 al pie de la letra

| Operacion | Autorizacion |
|---|---|
| Ver la ficha, el 360, listar adjuntos, **descargar** | Pertenencia al tenant, igual que leer la persona |
| Editar, dar de baja, subir, reclasificar | `paciente:manage` sobre la sede del contexto, igual que editar la persona |

"El acceso hereda permisos de la entidad asociada". La alternativa descartada era exigir
`paciente:manage` tambien para leer: dejaria al `PROFESIONAL` sin poder abrir el consentimiento
firmado del paciente que esta por atender, cuando ese mismo profesional puede leer la ficha entera.
Un adjunto mas restringido que la entidad de la que cuelga no es "mas seguro": es una regla
distinta a la que la especificacion escribio, inventada por una etapa.

**No se creo ningun permiso nuevo.** La matriz no se amplia en una etapa, mismo criterio que 02.04,
02.05, 02.06 y 03.01.

---

## 6. Auditoria: la descarga es el unico evento de lectura

`person` audita `PERSONA_DEACTIVATED`, `PERFIL_PACIENTE_DEACTIVATED`, `ADJUNTO_UPLOADED`,
`ADJUNTO_RECLASSIFIED`, `ADJUNTO_DEACTIVATED` y **`ADJUNTO_DOWNLOADED`**.

La asimetria del ultimo es deliberada: el listado no entrega ningun contenido y auditarlo llenaria
la tabla de ruido, mientras que **una descarga es el instante en que un documento personal sale del
sistema**. La pregunta "quien se llevo el DNI de este paciente" no tiene otra forma de responderse.

Ningun detalle de auditoria lleva el documento, el telefono ni el correo — la regla de 03.01 sigue
valiendo: se audita **que** cambio, no **a que** valor.

---

## 7. Casos borde de la etapa, y como quedaron

| Caso | Resolucion |
|---|---|
| **Archivo malicioso** | Deteccion por firma binaria contra lista blanca. El tipo declarado se descarta. Ademas: `Content-Disposition: attachment` y `X-Content-Type-Options: nosniff` en la descarga |
| **Descarga tras baja** | **Se permite**, del adjunto y de la persona dada de baja. Una baja logica dice "esto ya no corresponde para operar", no "esto nunca existio". Negarla convertiria la baja en un borrado con otro nombre |
| **Relaciones inactivas** | Una persona de baja no admite adjuntos nuevos (409 `persona-inactiva`) y si conserva los que tiene |
| **Paciente fusionado** | **NO se implementa, y se declara.** Fusionar exige reapuntar turnos, sesiones, obligaciones y adjuntos en cuatro modulos: es una etapa con su propio diseño. Lo que hay es la deteccion de duplicados del alta (03.01) mas la baja logica de la ficha sobrante, que resuelve el caso practico sin prometer una fusion que no existe |

---

## 8. Lo que esta etapa deja fijado y hereda lo que siga

1. **El 360 crece por contribuyentes, no por campos.** Agregar una fuente no toca `person` ni
   sube la version mayor del contrato.
2. **Un contribuyente declara su propio permiso.** El dia que un modulo cambie con que permiso
   expone sus datos, el 360 lo sigue solo.
3. **Fila antes que blob, siempre.** Un huerfano invisible es preferible a una referencia rota.
4. **El tipo de un archivo lo deciden sus bytes.** Vale para todo adjunto que venga despues.
5. **Dar de baja a una persona da de baja su perfil.** El estado inverso —persona cerrada,
   paciente vigente— no existe.
