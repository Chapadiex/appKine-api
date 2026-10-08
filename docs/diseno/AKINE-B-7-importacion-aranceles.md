# AKINE B-7 — Importación masiva de aranceles de un convenio con vista previa (M16)

> Paquete **B-7** de `docs/fases/01-trabajo-en-paralelo.md`. Implementa **RF-M16-007** tal como lo
> dejó diseñado el §7 de `docs/diseno/AKINE-B-3-cobertura-por-oferta.md`.
> **Sin migración** (la reserva `V85` no se usó). Contrato: **0.71.0** (aditivo; la 0.70.0 es de
> otro paquete).

## 1. Qué se cubre

| Requisito | Qué dice | Cómo se cubre |
|---|---|---|
| **RF-M16-007** | Altas masivas con preview y errores por fila | `POST /consultorios/{id}/convenios/{convenioId}/aranceles/importacion` con `modo = PREVIEW \| CONFIRMAR` |
| CA-M16-007-01 | Happy path | La confirmación inserta todas las filas y devuelve el `arancelId` de cada una |
| CA-M16-007-02 | Sin permiso no modifica | `convenio:manage` sobre la sede de la ruta, **en los dos modos** |
| CA-M16-007-03 | Referencia de otro tenant no se usa | Sede y convenio ajenos: 404 del lote. Práctica u oferta ajenas: la fila sale `RECHAZADA` con `not-found` |
| CA-M16-007-04 | Validación fallida no deja datos parciales | Confirmación **todo o nada**: 409 `importacion-aranceles-rechazada` y cero filas |
| CA-M16-007-05 | Reintentos no duplican | Idempotente **por invariante**: el reintento choca contra sus propias filas (`arancel-solapado`) |
| CA-M16-007-06 | Históricos consultables | La importación sólo inserta: no edita ni da de baja aranceles existentes |

## 2. Contrato

Una sola operación (`importArancelesDeConvenio`) con `modo`, no dos rutas: el preview y la
confirmación reciben **exactamente el mismo cuerpo**, y la pantalla manda el mismo lote dos veces
cambiando una palabra. El backend **no recibe archivos**: el frontend parsea la planilla.

```
ImportarArancelesRequest { modo: PREVIEW|CONFIRMAR, filas: [1..500] }
  fila: practicaId? | codigoPractica?, ofertaId?, importeTotal, importeFinanciador, coseguro,
        vigenciaDesde, vigenciaHasta?

200 ImportacionArancelesResponse { modo, aplicada, totalFilas, filasConAlta, filasRechazadas, filas[] }
  fila: fila (desde 1), estado ALTA|RECHAZADA, practicaId, ofertaId,
        problemType?, detalle?, arancelExistenteId?, filaEnConflicto?, arancelId?
409 importacion-aranceles-rechazada  (sólo CONFIRMAR) — propiedad `filas` con el mismo detalle
```

- **Las filas no llevan Bean Validation.** Un `@NotNull` haría fallar el lote entero con 400 por un
  campo vacío en la fila 87, y el RF pide errores **por fila**. Sólo el lote se valida con 400:
  falta el modo, vacío, o más de 500 filas.
- **El `problemType` de cada fila es el que daría el alta unitaria** de esa fila suelta:
  `validation-error`, `not-found`, `oferta-sin-obra-social`, `practica-no-habilitada-en-oferta` o
  `arancel-solapado`. La pantalla no necesita aprender un vocabulario nuevo.
- **CSV no:** el RF no lo exige y el frontend ya tiene que parsear la planilla (Excel o CSV) para
  mostrarla.

## 3. Algoritmo

`previsualizar` y `confirmar` corren el **mismo** `evaluar`. Por fila, en orden:

```
práctica    por id (visible para el tenant, activa o no — igual que el alta)
            o por código (entre las vigentes del tenant; tiene que resolver a UNA)
            si vienen las dos, el código tiene que ser el de esa práctica
importes    la entidad: no negativos, financiador + coseguro = total exacto; + máximo 2 decimales
vigencia    no invertida y CONTENIDA en la del convenio
oferta      AsociacionDeOferta: de la sede, admite obra social, declara la práctica (la regla
            del alta, extraída a una clase para que las dos no puedan divergir)
solape      contra los aranceles ACTIVOS del convenio, por grupo (convenio, práctica, oferta)
            y contra las filas ANTERIORES del mismo lote, por el mismo grupo
```

- **Preview:** `readOnly`, sin lock, sin escritura, sin auditoría. Es una predicción, no una reserva.
- **Confirmar:** `READ_COMMITTED`; 404 del convenio **antes** de crear la fila-lock (un convenio
  ajeno no deja rastro en `convenio_lock`); `ConvenioLockIniciador#asegurar` en su transacción;
  `FOR UPDATE` sobre `convenio_lock`; **relectura del convenio** (su vigencia o su estado pudieron
  cambiar); evaluación entera; si alguna fila no entra, excepción **antes del primer INSERT**; si
  no, un `saveAndFlush` por fila.
- **Auditoría:** el mismo `ARANCEL_CREATED` del alta por fila, con `origen = IMPORTACION`, más un
  `ARANCELES_IMPORTADOS` por lote sobre el convenio con cantidad, prácticas distintas y rango.
  Una confirmación rechazada no audita: no escribió nada.

### 3.1 Por qué no `Idempotency-Key`

`contracting` no la usa en ninguna escritura (financiador, plan, convenio y arancel lo declaran
así). La importación es idempotente por invariante: reconfirmar un lote aplicado choca contra sus
propias filas y responde 409 sin escribir. Lo que se pierde es el 200 en el reintento, y para eso
haría falta guardar el hash del cuerpo —el escenario 7b, todavía diferido en todo el repo—.

## 4. Design challenge

1. **Ownership.** No hay tabla nueva. Escribe sólo `convenio_arancel`, de `contracting` desde `V43`.
   El catálogo se lee por `resource.spi.CatalogoDirectory` y la oferta por `offering.spi`, las dos
   aristas que ya existían (03.05 y B-3).
2. **Ciclos.** Ninguna arista nueva entre módulos. `ModuleArchitectureTest` 5/5 verde.
3. **Tenant.** Sin tabla nueva. Toda lectura lleva `organization_id`: el convenio por
   `findByIdAndScope(convenio, org, sede)`, los aranceles por `findAllByConvenio(org, convenio)`, la
   práctica por `CatalogoDirectory` con el tenant, la oferta con organización **y** sede.
   Sede ajena: 404 antes de evaluar el permiso; práctica u oferta ajenas: fila rechazada.
4. **Reglas maestras.** Convenio ≠ cobertura del paciente: nada toca coberturas. Lo ya liquidado
   guarda su snapshot (RN-M16-003): la importación sólo agrega vigencias, nunca reescribe importes.
5. **Baja lógica.** No borra ni da de baja nada. Un lote rechazado no deja filas que limpiar.
6. **Contrato.** Aditivo: 0.69.0 → **0.71.0**. Una operación, cinco schemas nuevos y un problem
   type nuevo (`importacion-aranceles-rechazada`). Sin versión mayor.
7. **Ruta crítica.** Los cimientos están: convenio y arancel con lock (03.05), arancel por oferta y
   sus validaciones (B-3). La pantalla queda para otra tanda.
8. **El caso que rompe el diseño.**
   - *Dos administradores confirman a la vez dos planillas que se pisan en una práctica.* El lock
     serializa y el segundo, en `READ_COMMITTED`, ve las filas del primero: su lote entero se
     rechaza —**incluidas las filas que no chocaban**— y no queda ni una. Probado con dos hilos
     contra MySQL (`ImportacionArancelesIT`).
   - *La planilla trae la misma práctica dos veces con períodos que se cruzan.* Ninguna de las dos
     choca contra lo vigente; sin la comparación entre filas, la confirmación insertaría las dos.
     La segunda sale rechazada con `filaEnConflicto`.
   - *Entre el preview y la confirmación alguien carga un arancel suelto que se pisa.* La
     confirmación reevalúa bajo el lock y responde 409 con la fila marcada: el preview no reserva.
   - *El código de la planilla existe como práctica global y como propia.* Ambiguo: la fila se
     rechaza pidiendo `practicaId`, en vez de elegir una en silencio.
   - *Una fila con `1.005` de importe.* La base lo redondearía sin avisar; se rechaza la fila.

## 5. Límites

1. **El código se busca entre las prácticas vigentes, no en el nomenclador del financiador.**
   `CatalogoDirectory#resolverCodigo` necesita un nomenclador y el convenio no lo declara. Si la
   planilla trae códigos de nomenclador distintos del código de la práctica, la pantalla tiene que
   mapearlos a `practicaId`.
2. **Sin modo parcial.** El RF lo permite si se declara; una planilla aplicada a medias deja la mitad
   de las prácticas con el nomenclador nuevo y la otra mitad con el viejo. Se eligió todo o nada.
3. **No cierra las vigencias anteriores.** Cargar el nomenclador de 2028 sobre aranceles abiertos de
   2027 se rechaza por solape: primero se cierra la vigencia del vigente. Hacerlo automático sería
   editar aranceles desde una importación, y el RF habla de altas.
4. **Tope de 500 filas por lote**, holgado para un nomenclador de kinesiología (~150 prácticas).
5. **Sin pantalla.** `appKine-web` regenera el cliente contra 0.71.0 en su rama.
