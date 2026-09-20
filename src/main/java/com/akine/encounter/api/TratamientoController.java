package com.akine.encounter.api;

import com.akine.encounter.api.dto.DarDeBajaTratamientoRequest;
import com.akine.encounter.api.dto.RegistrarTratamientoRequest;
import com.akine.encounter.api.dto.TratamientoResponse;
import com.akine.encounter.application.TratamientoService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Tratamientos realmente aplicados en una sesion y espacios realmente utilizados (M14/M04/M06).
 *
 * <h2>La ruta esta anidada bajo la sesion, a diferencia del Caso y del Plan</h2>
 *
 * <p>Un tratamiento <b>no tiene identidad fuera de su sesion</b>: no se consulta suelto, no se
 * mueve de una atencion a otra y su {@code orden} solo significa algo dentro de la visita. El
 * control de sede ya esta en el prefijo, igual que en {@link SesionController}.
 *
 * <h2>El DELETE declara `application/json` en sus `produces`, y no es cosmetico</h2>
 *
 * <p>Responde <b>204 sin cuerpo</b>. Si la clase declarara solo {@code application/problem+json},
 * el cliente generado mandaria {@code Accept: application/problem+json} y el {@code produces} de
 * clase lo cortaria con <b>406 antes de entrar al metodo</b>. Ya rompio la activacion de cuenta y
 * la recuperacion de contraseña, y ningun test lo agarro: los unitarios del frontend usan
 * {@code HttpTestingController}, que no negocia contenido, y los de integracion mandan el
 * {@code Accept} de MockMvc.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/sesiones/{sesionId}/tratamientos",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(
		name = "Tratamientos realizados",
		description = "Que se aplico REALMENTE en la atencion, donde, por quien y con que "
				+ "parametros (M14, RF-M14-005 y RF-M04-005)")
public class TratamientoController {

	private final TratamientoService tratamientoService;
	private final EncounterApiActor apiActor;

	public TratamientoController(
			TratamientoService tratamientoService, EncounterApiActor apiActor) {

		this.tratamientoService = tratamientoService;
		this.apiActor = apiActor;
	}

	@PostMapping
	@Operation(
			summary = "Registrar una intervencion aplicada",
			description = """
					Asienta **que se hizo realmente** en esta atencion (RF-M14-005).

					PLANIFICADO NO EQUIVALE A REALIZADO (RN-M14-004). Esta operacion **no mueve \
					el Plan de Tratamiento** ni marca ningun item como cumplido: el avance del \
					plan se deriva al leer, contando sesiones cerradas, y atarlos haria que \
					registrar una intervencion moviera lo planificado.

					EL ORDEN LO ASIGNA EL SERVIDOR. Es la secuencia **cronologica** de la visita, \
					no una preferencia de presentacion, y **no se reutiliza**: dar de baja una \
					intervencion no libera su numero. No hay operacion de reordenar.

					LA SESION TIENE QUE ESTAR ABIERTA. Una sesion cerrada no se edita: se \
					enmienda, y la enmienda es una etapa aparte. Responde 409 `sesion-cerrada`.

					ESCRIBIR EN LA ATENCION AJENA ES 409, NO 403: la propiedad no es un permiso. \
					Dos profesionales de la misma sede tienen el mismo `sesion:register`.

					CO-ATENCION: `profesionalMembershipId` declara que **otro** profesional aplico \
					esta intervencion. **No le da permiso de escritura**: quien escribe sigue \
					siendo el dueño de la atencion.

					EL ESPACIO ES EL REALMENTE UTILIZADO (RF-M04-005) y puede diferir del \
					reservado en el turno. **No se valida ocupacion ni capacidad**: esto registra \
					un hecho consumado, no una reserva. Su vigencia se evalua en el instante de \
					la **atencion**, no en el de la carga.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Intervencion registrada"),
			@ApiResponse(
					responseCode = "400",
					description = "Un parametro esta mal tipado, o la lateralidad viene sin zona",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `sesion:register` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sesion, la sede, la practica o el espacio no existen o son "
							+ "de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La sesion esta cerrada o es de otro profesional; la practica no "
							+ "es vigente; el espacio no estaba operable; el co-atendiente no "
							+ "tiene membership vigente; o la version quedo vieja",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<TratamientoResponse> registrar(
			@PathVariable long consultorioId,
			@PathVariable long sesionId,
			@Valid @RequestBody RegistrarTratamientoRequest request) {

		TratamientoResponse creado = TratamientoResponse.de(tratamientoService.registrar(
				apiActor.current(), consultorioId, sesionId,
				request.aDominio(), request.version()));

		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId + "/sesiones/"
						+ sesionId + "/tratamientos/" + creado.id()))
				.body(creado);
	}

	@GetMapping
	@Operation(
			summary = "Listar las intervenciones de la atencion",
			description = """
					Devuelve las intervenciones **vigentes**, en orden cronologico, con sus \
					parametros.

					El codigo y el nombre de la practica y el nombre del espacio son los del \
					**momento en que se registro**, no los actuales: una sesion de marzo leida en \
					septiembre dice que practica fue en marzo (RN-M06-002, RN-M04-003).

					**La lectura queda auditada.** DP-03 no distingue entre leer y escribir en una \
					historia clinica, y un tratamiento realizado es contenido clinico: dice que se \
					le hizo al paciente y en que zona.

					No exige ser el profesional de la atencion: leer lo que se le hizo al paciente \
					es lo que necesita quien continua su tratamiento.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Intervenciones de la atencion"),
			@ApiResponse(
					responseCode = "404",
					description = "La sesion o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<TratamientoResponse>> listar(
			@PathVariable long consultorioId,
			@PathVariable long sesionId) {

		return ResponseEntity.ok(
				tratamientoService.listar(apiActor.current(), consultorioId, sesionId).stream()
						.map(TratamientoResponse::de)
						.toList());
	}

	@PutMapping("/{tratamientoId}")
	@Operation(
			summary = "Reemplazar una intervencion",
			description = """
					Reemplaza la intervencion **completa, con sus parametros**.

					Es un reemplazo total y no un parche, y por eso **no hay un endpoint por \
					parametro**: los parametros son hijos sin identidad propia hacia afuera y se \
					borran y reinsertan en la misma transaccion.

					**No cambia el `orden` ni la autoria del registro**: son la identidad del \
					hecho. Lo que cambia es QUE se hizo, no CUANDO en la secuencia ni QUIEN lo \
					asento.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Intervencion reemplazada"),
			@ApiResponse(
					responseCode = "400",
					description = "Un parametro esta mal tipado, o la lateralidad viene sin zona",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La intervencion no existe o no es de esa sesion",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La sesion esta cerrada o es de otro profesional, o la version "
							+ "quedo vieja",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<TratamientoResponse> reemplazar(
			@PathVariable long consultorioId,
			@PathVariable long sesionId,
			@PathVariable long tratamientoId,
			@Valid @RequestBody RegistrarTratamientoRequest request) {

		return ResponseEntity.ok(TratamientoResponse.de(tratamientoService.reemplazar(
				apiActor.current(), consultorioId, sesionId, tratamientoId,
				request.aDominio(), request.version())));
	}

	@DeleteMapping("/{tratamientoId}")
	@Operation(
			summary = "Dar de baja una intervencion",
			description = """
					**Baja LOGICA con motivo obligatorio**, nunca un borrado (regla maestra 10).

					El motivo es el mismo un dato clinico —"se suspendio la electroterapia porque \
					el paciente refirio molestia"— y sin el la auditoria no responde por que seis \
					meses despues.

					**El `orden` no se libera**: la proxima intervencion sigue tomando el siguiente \
					numero. Es idempotente: dar de baja dos veces no cambia el motivo ni el \
					instante originales.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Intervencion dada de baja"),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La intervencion no existe o no es de esa sesion",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La sesion esta cerrada o es de otro profesional, o la version "
							+ "quedo vieja",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> darDeBaja(
			@PathVariable long consultorioId,
			@PathVariable long sesionId,
			@PathVariable long tratamientoId,
			@Valid @RequestBody DarDeBajaTratamientoRequest request) {

		tratamientoService.darDeBaja(apiActor.current(), consultorioId, sesionId, tratamientoId,
				request.motivo(), request.version());
		return ResponseEntity.noContent().build();
	}
}
