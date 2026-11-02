package com.akine.encounter.api;

import com.akine.encounter.api.dto.ContextoDeProximaSesionResponse;
import com.akine.encounter.api.dto.CambiarCasoDeSesionRequest;
import com.akine.encounter.api.dto.CerrarSesionRequest;
import com.akine.encounter.api.dto.EnmendarSesionRequest;
import com.akine.encounter.api.dto.GuardarBorradorRequest;
import com.akine.encounter.api.dto.GuardarEvaluacionRequest;
import com.akine.encounter.api.dto.IniciarSesionSinTurnoRequest;
import com.akine.encounter.api.dto.ReemplazarProfesionalDeSesionRequest;
import com.akine.encounter.api.dto.SesionResponse;
import com.akine.encounter.api.dto.SesionVersionResponse;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.application.SesionView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Inicio de la atencion y autosave del borrador (M14).
 *
 * <p>Una Sesion <b>no es un Turno</b>: DP-05 les da maquinas de estado independientes, y ninguna
 * transicion administrativa prueba por si sola que la prestacion ocurrio.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/sesiones",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(name = "Sesiones", description = "Atencion real: inicio idempotente y guardado de borrador (M14)")
public class SesionController {

	private final SesionService sesionService;
	private final EncounterApiActor apiActor;

	public SesionController(SesionService sesionService, EncounterApiActor apiActor) {
		this.sesionService = sesionService;
		this.apiActor = apiActor;
	}

	@PostMapping("/turnos/{turnoId}")
	@Operation(
			operationId = "iniciarSesion",
			summary = "Iniciar la atencion de un turno",
			description = """
					Abre la atencion, o **devuelve la que ya estaba abierta**.

					El doble inicio es idempotente a proposito: un profesional que aprieta dos \
					veces o recarga la pantalla es el caso normal, y un 409 le exigiria a la \
					pantalla distinguir dos situaciones que para el usuario son la misma \
					(RN-M14-001, un turno produce como mucho una sesion).

					La Historia Clinica del paciente **se crea si no existia**. Exige perfil de \
					paciente vigente: una atencion sobre alguien que solo es "persona" falla, y \
                    no se crea historia clinica a nombre de quien no es paciente.

                    Quien atiende es el profesional del turno. Si el turno tiene uno asignado y \
					no es quien inicia, es 409: dejar que otro abra la sesion de un turno ajeno \
					rompe la propiedad antes de que la sesion exista.

					EL CASO CLINICO ES OPCIONAL SALVO QUE LA OFERTA LO EXIJA (RF-M10-007, DP-43). \
					Si la oferta del turno tiene `requiereCasoClinico`, iniciar sin casoId \
					responde 409 `caso-clinico-requerido` y no abre historia ni sesion; en las \
					demas ofertas la atencion se registra igual sin caso. Las sesiones ya \
					existentes sin caso no se tocan. Cuando viene, tiene que ser un caso ACTIVO de la MISMA historia \
					clinica: uno de otro paciente responde 404 —indistinguible de "no existe", \
					para no poder censar casos ajenos probando ids— y uno cerrado responde 409, \
					porque lleva a otra accion, que es reabrirlo.

					LA IDEMPOTENCIA MANDA SOBRE EL CASO: si la sesion del turno ya existe se \
					devuelve tal cual, con el caso que tenga, aunque esta llamada traiga otro. \
					Reasignar el caso de una atencion ya empezada no es "iniciar": es \
					`PUT .../sesiones/{sesionId}/caso`.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Atencion iniciada"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `sesion:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "El turno no habilita una atencion, la persona no es paciente, o la "
							+ "oferta exige caso y no vino (`caso-clinico-requerido`)",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SesionResponse> iniciar(
			@PathVariable long consultorioId,
			@PathVariable long turnoId,
			@RequestParam(required = false) Long casoId) {

		SesionView vista =
				sesionService.iniciar(apiActor.current(), consultorioId, turnoId, casoId);
		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId + "/sesiones/" + vista.id()))
				.body(SesionResponse.de(vista));
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "iniciarSesionSinTurno",
			summary = "Iniciar una atencion sin turno",
			description = """
					Abre la atencion de un paciente **sin reserva** (RF-M14-002, DP-43). Exige \
					`sesion:register` en la sede, como la atencion de turno, y quien atiende es \
					quien inicia.

					**El motivo es obligatorio**: sin turno nada explica por que el paciente estaba \
					ahi. No va a la auditoria (es texto libre sobre un paciente); vuelve en \
					`motivoSinTurno`.

					**La oferta la elige el cliente**, asi que se valida: de esta sede (404 si no), \
					vigente hoy en la zona de la sede (409 `oferta-no-vigente`) y con quien atiende \
					habilitado (409 `profesional-no-habilitado-en-oferta`; una oferta sin \
					habilitaciones declaradas admite a todos). La persona tiene que ser paciente \
					vigente; la historia clinica se crea si no existia.

					**El caso** es obligatorio solo si la oferta tiene `requiereCasoClinico` (409 \
					`caso-clinico-requerido`). Cuando viene, mismas reglas que en la atencion de \
					turno: activo y de la misma historia (404 si es de otro paciente, 409 si esta \
					cerrado).

					**Manda `idempotencyKey`.** Sin turno no hay reserva que ancle el doble click: \
					con la misma clave y el mismo pedido se devuelve la atencion ya creada, con \
					otro pedido 409 `idempotency-key-conflict`. Sin clave cada llamada abre una \
					atencion nueva.

					No ocupa ni libera agenda: Turno es reserva y Sesion es atencion (DP-05).""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Atencion iniciada, o la ya creada con "
					+ "la misma clave"),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo, la persona o la oferta",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `sesion:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede, la oferta, la persona o el caso no existen, son de otro "
							+ "tenant, o el caso es de otra historia clinica",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La oferta exige caso (`caso-clinico-requerido`), no esta vigente "
							+ "(`oferta-no-vigente`) o quien atiende no esta habilitado "
							+ "(`profesional-no-habilitado-en-oferta`); la persona no es paciente; "
							+ "el caso esta cerrado; o la clave ya se uso con otro pedido "
							+ "(`idempotency-key-conflict`)",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SesionResponse> iniciarSinTurno(
			@PathVariable long consultorioId,
			@RequestBody @Valid IniciarSesionSinTurnoRequest request) {

		SesionView vista = sesionService.iniciarSinTurno(
				apiActor.current(), consultorioId, request.aDominio(), request.idempotencyKey());
		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId + "/sesiones/" + vista.id()))
				.body(SesionResponse.de(vista));
	}

	@PutMapping(path = "/{sesionId}/profesional", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "reemplazarProfesionalDeSesion",
			summary = "Reemplazar al profesional de una atencion abierta",
			description = """
					Otro profesional continua la atencion (DP-43). **Solo el administrador de la \
					sede** (`sesion:reassign`, del `CONSULTORIO_ADMIN`), con motivo obligatorio, y \
					queda auditado (`SESION_PROFESIONAL_REEMPLAZADO`) con un registro propio de \
					quien reemplazo a quien y por que.

					**No es un permiso para escribir en la atencion ajena**: escribir en la atencion \
					de otro sigue siendo 409 `sesion-ajena`. Esta operacion cambia al dueño; desde \
					ahi, el anterior recibe `sesion-ajena` y el nuevo guarda normalmente.

					**Solo con la sesion abierta** (cerrada: 409 `sesion-cerrada`: la atencion ya \
					ocurrio y cambiar quien la presto seria reescribirla). El profesional nuevo \
					tiene que tener membership vigente en la sede y `sesion:register` (409 \
					`profesional-no-asignable`) y estar habilitado para la oferta (409 \
					`profesional-no-habilitado-en-oferta`). **El turno no se toca.**

					**Hay que mandar la `version` que se leyo.** Pedir el profesional que ya tiene \
					no escribe ni avanza la version.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Profesional reemplazado. Devuelve la "
					+ "sesion con la version nueva"),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo, el profesional o la version",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `sesion:reassign` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sesion o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La sesion esta cerrada, la version quedo vieja "
							+ "(`concurrent-modification`), el profesional no puede atender en la "
							+ "sede (`profesional-no-asignable`) o no esta habilitado para la oferta "
							+ "(`profesional-no-habilitado-en-oferta`)",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SesionResponse> reemplazarProfesional(
			@PathVariable long consultorioId,
			@PathVariable long sesionId,
			@RequestBody @Valid ReemplazarProfesionalDeSesionRequest request) {

		return ResponseEntity.ok(SesionResponse.de(sesionService.reemplazarProfesional(
				apiActor.current(), consultorioId, sesionId, request.profesionalId(),
				request.motivo(), request.version())));
	}

	@GetMapping("/{sesionId}")
	@Operation(
			summary = "Ver una sesion",
			description = "Devuelve la sesion con su borrador y su **version**, que es la que hay "
					+ "que devolver al guardar.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Sesion"),
			@ApiResponse(
					responseCode = "404",
					description = "La sesion o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SesionResponse> verSesion(
			@PathVariable long consultorioId,
			@PathVariable long sesionId) {

		return ResponseEntity.ok(SesionResponse.de(
				sesionService.ver(apiActor.current(), consultorioId, sesionId)));
	}

	@GetMapping("/{sesionId}/proxima-sesion")
	@Operation(
			operationId = "contextoDeProximaSesion",
			summary = "Lo que se sabe para programar la proxima sesion",
			description = """
					Paciente, caso, plan, prestacion y profesional de esta atencion: lo que hace falta para \
					programar la proxima sin volver a preguntar nada (TURNO - Tecnico §76). **Nada clinico.** \
					Exige `sesion:register`, igual que ver la sesion.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El contexto"),
			@ApiResponse(
					responseCode = "404",
					description = "La sesion o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ContextoDeProximaSesionResponse> contextoDeProximaSesion(
			@PathVariable long consultorioId,
			@PathVariable long sesionId) {

		return ResponseEntity.ok(ContextoDeProximaSesionResponse.de(
				sesionService.contextoDeProximaSesion(apiActor.current(), consultorioId, sesionId)));
	}

	@PutMapping("/{sesionId}/borrador")
	@Operation(
			summary = "Guardar el borrador de la atencion",
			description = """
					Guarda el contenido parcial de la atencion. Pensado para llamarse seguido, \
					desde el autosave de la pantalla.

					**Hay que mandar la `version` que se leyo.** Dos pestanas del mismo \
					profesional sobre la misma sesion son el caso normal; sin la version la \
					segunda pisa a la primera en silencio y el profesional pierde lo que \
					escribio. Un 409 `concurrent-modification` significa recargar.

					**Solo el profesional de la sesion puede guardar.** No es cuestion de \
					permiso —dos profesionales de la misma sede tienen el mismo \
					`sesion:register`— sino de propiedad de esa atencion, y por eso el rechazo \
					es 409 y no 403.

					El contenido es **opaco**: que campos tiene una evaluacion lo define \
					AKINE-06.02. Esta etapa garantiza que se guarda, se versiona y no se pierde.

					**Lo unico que se exige es que sea JSON.** Un `contenido` que no parsea, vacio \
					o anidado mas alla de 99 niveles responde **400 `validation-error`** con \
					`errors.contenido`; reintentar el mismo cuerpo falla igual. `null` vacia el \
					borrador.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Borrador guardado"),
			@ApiResponse(
					responseCode = "400",
					description = "El contenido no es un documento JSON valido, o falta la version",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sesion o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La sesion la atiende otro profesional, o la version quedo vieja",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SesionResponse> guardarBorrador(
			@PathVariable long consultorioId,
			@PathVariable long sesionId,
			@RequestBody @Valid GuardarBorradorRequest request) {

		return ResponseEntity.ok(SesionResponse.de(sesionService.guardarBorrador(
				apiActor.current(), consultorioId, sesionId,
				request.contenido(), request.version())));
	}

	@PutMapping("/{sesionId}/caso")
	@Operation(
			operationId = "cambiarCasoDeSesion",
			summary = "Asociar, cambiar o quitar el caso clinico de una atencion abierta",
			description = """
					Deja en la sesion el caso que viene en `casoId`, o ninguno si viene nulo. Es \
					la unica forma de corregir el caso despues de iniciar: el inicio es \
					idempotente y devuelve la sesion con el caso que ya tenia.

					**Solo con la sesion abierta.** Una sesion cerrada ya tiene su numero dentro \
					del caso, y cambiarlo seria renumerar historia clinica (409 `sesion-cerrada`).

					El caso tiene que ser **activo y de la misma historia clinica**: uno de otro \
					paciente responde 404 —indistinguible de "no existe"— y uno cerrado 409 \
					`caso-clinico-cerrado`. Quitar el caso se puede salvo que la oferta lo exija \
					(409 `caso-clinico-requerido`, RF-M10-007, DP-43).

					**Hay que mandar la `version` que se leyo**, y **solo el profesional de la \
					sesion puede cambiarlo**: escribir en la atencion ajena es 409, no 403. Deja \
					evento de auditoria. Pedir el caso que ya tiene no escribe ni avanza la \
					version.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Caso asignado. Devuelve la sesion "
					+ "con la version nueva"),
			@ApiResponse(
					responseCode = "400",
					description = "Falta la version o el caso no es un id valido",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `sesion:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sesion, la sede o el caso no existen, son de otro tenant, o "
							+ "el caso es de otra historia clinica",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La sesion esta cerrada, la atiende otro profesional, la version "
							+ "quedo vieja (`concurrent-modification`), el caso esta cerrado, la "
							+ "sesion es de origen grupal y su caso lo fija la derivacion "
							+ "(`caso-de-atencion-grupal`, AKINE-08.05), o se quiso quitar el caso "
							+ "de una oferta que lo exige (`caso-clinico-requerido`)",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SesionResponse> cambiarCaso(
			@PathVariable long consultorioId,
			@PathVariable long sesionId,
			@RequestBody @Valid CambiarCasoDeSesionRequest request) {

		return ResponseEntity.ok(SesionResponse.de(sesionService.cambiarCaso(
				apiActor.current(), consultorioId, sesionId,
				request.casoId(), request.version())));
	}

	@PutMapping("/{sesionId}/evaluacion")
	@Operation(
			summary = "Guardar la evaluacion base",
			description = """
					Guarda dolor, evolucion, objetivo y limitacion funcional como **datos \
					tipados y consultables** (RF-M14-003). Es lo que distingue esta operacion del \
					borrador de AKINE-06.01, que es JSON opaco: una evolucion clinica que no se \
					puede comparar entre sesiones no sirve para nada.

					**Ningun campo clinico es obligatorio.** "Seguimiento no exige examen \
					completo": una sesion de seguimiento carga dolor y evolucion y nada mas, y esa \
					es la mayoria de las sesiones de un tratamiento. Exigirlos obligaria a inventar \
					datos clinicos para poder guardar.

					Lo unico que se rechaza es lo que seria **falso**: un dolor fuera de la escala \
					0-10, y una lateralidad sin zona —"derecha" de que—. Los dos son 400: no \
					dependen de nada que pueda cambiar entre dos peticiones, asi que reintentar no \
					los arregla.

					La respuesta incluye `previa`, la evaluacion de la sesion anterior del mismo \
					paciente, para poder mostrar el cambio.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Evaluacion guardada"),
			@ApiResponse(
					responseCode = "400",
					description = "Dolor fuera de la escala, o lateralidad sin zona",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sesion o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La sesion la atiende otro profesional, o la version quedo vieja",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SesionResponse> evaluar(
			@PathVariable long consultorioId,
			@PathVariable long sesionId,
			@RequestBody @Valid GuardarEvaluacionRequest request) {

		return ResponseEntity.ok(SesionResponse.de(sesionService.evaluar(
				apiActor.current(), consultorioId, sesionId,
				request.aDominio(), request.version())));
	}

	@PostMapping("/{sesionId}/cierre")
	@Operation(
			operationId = "cerrarSesion",
			summary = "Cerrar la atencion",
			description = """
					Cierra la sesion y le asigna su **correlativo por historia clinica** — "la \
					sesion numero 8 de este paciente".

					**Es idempotente.** Cerrar dos veces devuelve el mismo resultado con el mismo \
					numero y no renumera: apretar dos veces "cerrar" es el caso normal, y \
					renumerar una sesion cerrada seria reescribir historia clinica. La \
					idempotencia se evalua ANTES de pedir un numero, para que un reintento no \
					consuma un correlativo que despues nadie usa y deje huecos que parecen \
					sesiones borradas.

					**Cerrar no cobra.** DP-06: el cierre clinico no depende del pago. La deuda \
					(obligacion del paciente y, si hay convenio, del financiador) se devenga en \
					la misma transaccion del cierre.

					**Pero toda prestacion cerrada genera deuda** (DP-17). Si el paciente asistio \
					y su deuda es el precio particular —recepcion resuelta como Particular, oferta \
					que no admite obra social, o sin cobertura con convenio y arancel aplicables— \
					y la oferta no tiene precio vigente el dia del cierre (en la zona de la sede), \
					responde **409 `oferta-sin-precio`** con `ofertaId`, `dia` y `motivo`. La \
					sesion sigue abierta, sin numero y sin nada consumido: se carga el precio en \
					la oferta y se reintenta. Con cobertura el coseguro sale del arancel y no hace \
					falta precio particular.

					**Una sesion cerrada no se edita: se enmienda.** Corregirla exige motivo y \
					deja una version en el historial (`POST .../enmiendas`). El cierre ademas \
					**inaugura ese historial**: la version 1 es lo que se acaba de asentar.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Atencion cerrada, o ya lo estaba"),
			@ApiResponse(
					responseCode = "400",
					description = "Falta la asistencia, o la nota de cierre con el paciente presente",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sesion o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La sesion la atiende otro profesional, la version quedo vieja, "
							+ "la deuda del paciente es el precio particular y la oferta no "
							+ "tiene precio ese dia (oferta-sin-precio), o la oferta exige caso, "
							+ "el paciente asistio y la sesion no tiene caso "
							+ "(`caso-clinico-requerido`: se asigna con PUT .../caso y se reintenta)",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SesionResponse> cerrar(
			@PathVariable long consultorioId,
			@PathVariable long sesionId,
			@RequestBody @Valid CerrarSesionRequest request) {

		return ResponseEntity.ok(SesionResponse.de(sesionService.cerrar(
				apiActor.current(), consultorioId, sesionId,
				request.aDominio(), request.version())));
	}

	@PostMapping("/{sesionId}/enmiendas")
	@Operation(
			summary = "Enmendar una sesion cerrada",
			description = """
					Corrige el contenido clinico de una atencion **ya cerrada**, escribiendo una \
					**version nueva** que deja la anterior intacta y consultable (RF-M14-010).

					**Enmendar no es editar.** RN-M14-006 no prohibe corregir una sesion cerrada: \
					prohibe corregirla *silenciosamente*. Por eso el motivo es obligatorio, la \
					version queda numerada con su autor y su instante, y la operacion deja \
					evento de auditoria.

					**LO QUE ESTA OPERACION NO PUEDE CORREGIR, y conviene saberlo antes de \
					intentarlo:** la **asistencia**, los dos correlativos (`numeroSesion` y \
					`numeroEnCaso`), la oferta, el turno, el profesional y las fechas de la \
					atencion. No estan en el cuerpo del pedido, asi que no hay forma de \
					mandarlos.

					El caso que esto deja afuera es real y esta asumido: una sesion cerrada con \
					`AUSENTE` cuando el paciente vino **no se arregla enmendando**. Cambiar la \
					asistencia es un acto economico —obliga a devengar o anular una obligacion \
					(M18) y a consumir o revertir una unidad de autorizacion (M17)— y esas \
					compensaciones son explicitas y de otros modulos. Lo que si corresponde es \
					enmendar la nota de cierre dejando escrito lo que paso, con ese motivo.

					**La enmienda no vuelve a disparar nada economico.** No se devenga deuda ni \
					se consume autorizacion: ninguno de los campos enmendables los afecta.

					**Tratamientos y mediciones tambien se enmiendan (C-6)**, con `tratamientos` \
					y `mediciones`: listas completas de lo que tiene que quedar. La version nueva \
					lleva la foto de lo que quedo y la anterior conserva lo que habia. Lo que no \
					se puede cambiar es **el conjunto de practicas realizadas**: eligieron al \
					cierre que autorizacion se consumio, y cambiarlas es una compensacion en \
					M17 (409 `enmienda-cambia-practicas`).

					**Es un reemplazo completo, no un parche.** Un campo ausente significa "queda \
					vacio", no "dejalo como estaba": la pantalla manda el formulario entero. La \
					unica excepcion son `tratamientos` y `mediciones`: **ausentes no se tocan**, \
					para que un cliente que no los conoce no pueda vaciarlos por omision.

					**Exige `sesion:amend`** (DP-43; hasta entonces \
					era `sesion:register`), y **solo el profesional de la sesion puede \
					enmendar.** Lo segundo no es cuestion de permiso —dos profesionales de la \
					misma sede tienen el mismo `sesion:amend`— sino de propiedad de esa \
					atencion, y por eso el rechazo es 409 y no 403.

					**Hay ventana temporal** (DP-43): se puede enmendar hasta `enmendableHasta`, \
					el cierre mas los dias que fija la organizacion (30 si no fijo otros, \
					`GET /api/v1/politica-de-enmienda`). Despues, 409 \
					`enmienda-fuera-de-ventana` con `cerradaEn`, `ventanaDias` y \
					`enmendableHasta`. El original queda siempre, con su fecha y su autor.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Enmendada. Devuelve la sesion vigente"),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo, el dolor esta fuera de la escala, la "
							+ "lateralidad no tiene zona, o se vacio la nota de cierre de una "
							+ "sesion con el paciente presente",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sesion o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La sesion todavia esta abierta (`sesion-no-cerrada`: se guarda, "
							+ "no se enmienda), la atiende otro profesional, la version quedo vieja, "
							+ "la enmienda cambiaria las practicas realizadas "
							+ "(`enmienda-cambia-practicas`), o paso la ventana de la organizacion "
							+ "(`enmienda-fuera-de-ventana`)",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `sesion:amend` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<SesionResponse> enmendar(
			@PathVariable long consultorioId,
			@PathVariable long sesionId,
			@RequestBody @Valid EnmendarSesionRequest request) {

		return ResponseEntity.ok(SesionResponse.de(sesionService.enmendar(
				apiActor.current(), consultorioId, sesionId,
				request.aDominio(), request.tratamientosADominio(), request.medicionesADominio(),
				request.motivo(), request.version())));
	}

	@GetMapping("/{sesionId}/versiones")
	@Operation(
			summary = "Historial de versiones de una sesion",
			description = """
					Todas las versiones del contenido, **de la 1 a la ultima** (RF-M24-005). La \
					version 1 es lo que se asento al cerrar; cada enmienda agrego la siguiente \
					con su motivo, su autor y su instante.

					Cada version trae el contenido **completo**, no un diff: lo que hay que poder \
					leer es que decia el registro en ese momento. La comparacion la arma la \
					pantalla, que recibe las dos versiones enteras.

					Lo que las versiones **no** repiten —asistencia, correlativos, fechas de la \
					atencion— es lo que no es enmendable: vale lo mismo en todas y se lee de la \
					sesion.

					**Una sesion abierta devuelve una lista vacia**, no un error: todavia no \
					tiene contenido versionado, y para la pantalla eso no es una condicion \
					excepcional sino el estado normal de la atencion que esta ocurriendo.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Versiones, de la 1 a la ultima"),
			@ApiResponse(
					responseCode = "404",
					description = "La sesion o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<SesionVersionResponse>> versiones(
			@PathVariable long consultorioId,
			@PathVariable long sesionId) {

		return ResponseEntity.ok(
				sesionService.versiones(apiActor.current(), consultorioId, sesionId).stream()
						.map(SesionVersionResponse::de)
						.toList());
	}
}
