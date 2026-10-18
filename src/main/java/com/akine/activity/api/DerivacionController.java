package com.akine.activity.api;

import com.akine.activity.api.dto.DerivacionResponse;
import com.akine.activity.api.dto.DerivarParticipanteRequest;
import com.akine.activity.api.dto.ElegibilidadClinicaResponse;
import com.akine.activity.api.dto.EstadoDeDerivacionResponse;
import com.akine.activity.api.dto.RevertirDerivacionRequest;
import com.akine.activity.application.DerivacionService;
import com.akine.activity.application.DerivacionView;
import com.akine.activity.application.DerivarParticipanteCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Derivacion de un participante de clase al circuito clinico (RF-M28-008, AKINE-08.04).
 *
 * <p>La ruta cuelga de la <b>inscripcion</b>, el recurso con el que la pantalla de la clase ya
 * identifica al participante (08.02). El diseño de 08.04 §8 la escribio como
 * {@code /clases/{c}/participantes/{i}} antes de que las rutas de M28 colgaran de la sede; se
 * sigue la convencion que quedo en el codigo.
 *
 * <p><b>{@code X-Justificacion-Acceso}</b> es la cabecera de 04.01, con el mismo nombre y la misma
 * semantica: quien decide si hacia falta es la politica clinica, y si faltaba el rechazo es 403
 * con {@code requiereJustificacion}, no 400. La elegibilidad no la lee: no toca nada clinico.
 *
 * <p>{@code produces} declara los dos tipos por la regla del 406 con el cliente generado.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/clases/{claseId}/inscripciones/{inscripcionId}",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(
		name = "Derivacion clinica de participantes",
		description = "Vinculo entre la asistencia a una clase y un Caso Clinico (M28, M09, M10)")
public class DerivacionController {

	/** Mismo nombre que {@code clinical.api.AccesoClinicoHeaders.JUSTIFICACION}, que es privado. */
	static final String JUSTIFICACION = "X-Justificacion-Acceso";

	private static final String DESCRIPCION_JUSTIFICACION =
			"Motivo declarado del acceso clinico (DP-03). Lo exige la politica clinica cuando no "
					+ "hay relacion asistencial; si hacia falta y no vino, 403 con "
					+ "`requiereJustificacion`.";

	private final DerivacionService derivacionService;
	private final ActivityApiActor apiActor;

	public DerivacionController(DerivacionService derivacionService, ActivityApiActor apiActor) {
		this.derivacionService = derivacionService;
		this.apiActor = apiActor;
	}

	@GetMapping("/elegibilidad-clinica")
	@Operation(
			operationId = "verElegibilidadClinicaDeParticipante",
			summary = "Si el participante se puede derivar, y por que no",
			description = """
					Veredicto **grupal y sin efectos**: la oferta genera registro clinico, la \
					persona estuvo (`PRESENTE` o `PRESENTE_TARDE`) y si ya es paciente. **No lee \
					nada clinico** y no se audita: el Caso, el Plan, la autorizacion y el permiso \
					clinico los decide la derivacion misma.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El veredicto"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `inscripcion:read` en esa sede, o sin contexto activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede, la clase, la inscripcion, la oferta o la persona no "
							+ "existen en este alcance",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ElegibilidadClinicaResponse elegibilidad(
			@PathVariable long consultorioId,
			@PathVariable long claseId,
			@PathVariable long inscripcionId) {

		return ElegibilidadClinicaResponse.de(derivacionService.elegibilidad(
				apiActor.current(), consultorioId, claseId, inscripcionId));
	}

	@PostMapping("/derivaciones")
	@Operation(
			operationId = "derivarParticipante",
			summary = "Derivar al participante a un Caso Clinico",
			description = """
					Vincula la **asistencia** a un Caso Clinico existente y activo de esa persona \
					(RF-M28-008, RF-M10-008, RF-M11-007). **Derivar no es atender**: no crea \
					Sesion, no numera nada dentro del Caso, no consume autorizacion y no devenga.

					Orden de los gates: la oferta tiene que generar registro clinico \
					(`oferta-no-clinica`); la persona tiene que haber estado \
					(`participacion-sin-asistencia`); si no es paciente, hace falta \
					`activarPerfilPaciente = true` (`persona-sin-perfil-paciente`), que exige \
					`paciente:manage`; despues, lo clinico: `hc:write` con relacion asistencial o \
					justificacion, Caso activo de esa historia (`caso-no-activo`), Plan del Caso y \
					autorizacion que habilite (`autorizacion-no-elegible`).

					**Idempotente por destino**: repetir al mismo Caso devuelve 200 con la \
					derivacion existente y `yaExistia = true`. A otro Caso crea otra.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Derivacion creada"),
			@ApiResponse(
					responseCode = "200",
					description = "Ya estaba derivada a ese Caso: se devuelve la existente"),
			@ApiResponse(
					responseCode = "400",
					description = "Datos invalidos",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `inscripcion:read` en la sede, sin `hc:write`, sin "
							+ "justificacion cuando hacia falta, o sin `paciente:manage` al "
							+ "activar el perfil",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede, la clase, la inscripcion, la persona, el Caso, el Plan "
							+ "o la autorizacion no existen en este alcance",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Oferta no clinica, participante ausente o sin asistencia, "
							+ "persona sin perfil de paciente, Caso no activo o autorizacion "
							+ "que no habilita",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<DerivacionResponse> derivar(
			@PathVariable long consultorioId,
			@PathVariable long claseId,
			@PathVariable long inscripcionId,
			@RequestBody @Valid DerivarParticipanteRequest request,
			@Parameter(description = DESCRIPCION_JUSTIFICACION)
			@RequestHeader(name = JUSTIFICACION, required = false) String justificacion) {

		DerivacionView derivacion = derivacionService.derivar(
				apiActor.current(), consultorioId, claseId, inscripcionId,
				new DerivarParticipanteCommand(
						request.casoClinicoId(),
						request.planTratamientoId(),
						request.autorizacionId(),
						request.motivo(),
						request.activarPerfilPacienteEfectivo(),
						justificacion));

		DerivacionResponse cuerpo = DerivacionResponse.de(derivacion);
		// 200 cuando ya existia: un 201 afirmaria que se creo algo que no se creo.
		if (derivacion.yaExistia()) {
			return ResponseEntity.ok(cuerpo);
		}
		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId + "/clases/" + claseId
						+ "/inscripciones/" + inscripcionId + "/derivaciones/" + derivacion.id()))
				.body(cuerpo);
	}

	@GetMapping("/derivaciones")
	@Operation(
			operationId = "listarDerivacionesDeParticipante",
			summary = "Derivaciones del participante",
			description = """
					Vigentes y revertidas, mas nueva primero, y si la persona ya tiene historia \
					clinica. **Es una lectura clinica**: exige `hc:read` con relacion asistencial \
					o justificacion, y queda auditada. Sin asistencia registrada la lista es vacia.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Las derivaciones"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `inscripcion:read` en la sede, sin `hc:read`, o sin "
							+ "justificacion cuando hacia falta",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede, la clase o la inscripcion no existen en este alcance",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public EstadoDeDerivacionResponse listar(
			@PathVariable long consultorioId,
			@PathVariable long claseId,
			@PathVariable long inscripcionId,
			@Parameter(description = DESCRIPCION_JUSTIFICACION)
			@RequestHeader(name = JUSTIFICACION, required = false) String justificacion) {

		return EstadoDeDerivacionResponse.de(derivacionService.consultar(
				apiActor.current(), consultorioId, claseId, inscripcionId, justificacion));
	}

	@PostMapping("/derivaciones/{derivacionId}/reversion")
	@Operation(
			operationId = "revertirDerivacionDeParticipante",
			summary = "Revertir una derivacion",
			description = """
					Deshace el vinculo con motivo obligatorio. **La fila queda** (RF-M28-008: los \
					historicos permanecen consultables) y se puede volver a derivar al mismo \
					Caso. Revertir dos veces devuelve la misma derivacion sin tocar nada.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Derivacion revertida"),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `inscripcion:read` en la sede, sin `hc:write`, o sin "
							+ "justificacion cuando hacia falta",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La derivacion no existe o no cuelga de esta participacion",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "De la derivacion ya cuelga una atencion clinica "
							+ "(`derivacion-con-sesion`, AKINE-08.05): la Sesion no se borra y el "
							+ "vinculo que explica de donde vino el paciente tampoco",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public DerivacionResponse revertir(
			@PathVariable long consultorioId,
			@PathVariable long claseId,
			@PathVariable long inscripcionId,
			@PathVariable long derivacionId,
			@RequestBody @Valid RevertirDerivacionRequest request,
			@Parameter(description = DESCRIPCION_JUSTIFICACION)
			@RequestHeader(name = JUSTIFICACION, required = false) String justificacion) {

		return DerivacionResponse.de(derivacionService.revertir(
				apiActor.current(), consultorioId, claseId, inscripcionId, derivacionId,
				request.motivo(), justificacion));
	}
}
