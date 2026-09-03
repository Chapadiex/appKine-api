package com.akine.person.api;

import com.akine.person.api.dto.ElegibilidadResponse;
import com.akine.person.application.ElegibilidadAdministrativaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Elegibilidad administrativa preliminar (M17, RF-M17-007).
 *
 * <h2>Este endpoint es el que cierra el hueco que 03.05 dejo abierto</h2>
 *
 * <p>M16 declaro {@code requiere_orden}, {@code requiere_autorizacion} y
 * {@code requiere_credencial} en el convenio, y su registro de cierre escribio que nadie los
 * interpretaba y que quien lo hiciera seria M17. Esto lo hace: lee el convenio <b>vivo</b> por
 * {@code contracting.spi.ArancelDirectory#resolver}, arma la lista de lo que exige, y la confronta
 * con las ordenes y autorizaciones que el paciente tiene.
 *
 * <h2>Lo que NO hace, y hay que tenerlo presente</h2>
 *
 * <ul>
 *   <li><b>No persiste nada y no consume nada.</b> RN-M17-001: preguntar si se puede atender y
 *       descontar una sesion son cosas distintas. El consumo es RF-M17-004 y llega con la
 *       integracion clinica, que esta etapa declara fuera de alcance. Como consecuencia, esta
 *       consulta es idempotente y se puede llamar tantas veces como la pantalla quiera.
 *   <li><b>No afirma que la prestacion sea facturable</b> (RN-M08-004). Dice si la documentacion
 *       administrativa que el convenio exige esta presente, que es otra cosa.
 *   <li><b>No verifica el tope mensual.</b> Viaja como dato informativo sin veredicto: contarlo
 *       exige contar sesiones ya atendidas, o sea el consumo que no existe.
 * </ul>
 *
 * <h2>Es GET, y por eso vive aparte de las dos rutas de escritura</h2>
 *
 * <p>Colgarlo de {@code /autorizaciones} sugeriria que consulta una autorizacion concreta, y lo
 * que hace es evaluar el conjunto: puede responder que falta la orden sin que ninguna autorizacion
 * participe, y puede responder elegible sin que exista ninguna de las dos.
 */
@RestController
@RequestMapping(
		path = "/api/v1/personas/{personaId}/elegibilidad",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
		name = "Elegibilidad administrativa",
		description = "Que documentacion exige el convenio y si el paciente la tiene (M17). "
				+ "Consulta preliminar: no persiste ni consume nada")
public class ElegibilidadController {

	private final ElegibilidadAdministrativaService elegibilidadService;
	private final PersonApiActor apiActor;

	public ElegibilidadController(
			ElegibilidadAdministrativaService elegibilidadService, PersonApiActor apiActor) {

		this.elegibilidadService = elegibilidadService;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "consultarElegibilidadAdministrativa",
			summary = "Que le falta al paciente para que le atiendan esa practica",
			description = """
					RF-M17-007. Confronta lo que el CONVENIO exige con lo que el paciente tiene, \
					para una cobertura, una practica y un dia.

					LEE EL CONVENIO VIVO, no la copia congelada de ninguna autorizacion. Decidir \
					que se puede hacer hoy se hace con la regla de hoy; la copia congelada existe \
					para explicar el pasado, y usarla aca aplicaria reglas derogadas a una \
					atencion actual.

					LA LISTA VACIA CON elegible = true ES EL CASO NORMAL, no un error. Pasa en \
					cuatro situaciones y las cuatro son frecuentes: cobertura PARTICULAR (no hay \
					financiador), sin convenio vigente de esta sede con ese plan (el paciente se \
					atiende como particular), con convenio pero sin arancel para esa practica \
					(hueco de configuracion del centro, no motivo para negar la atencion), y \
					convenio que no exige nada. RN-M17-005 y RN-M17-006: una actividad no cubierta \
					no debe pedir orden ni autorizacion artificialmente.

					UN REQUISITO FALTANTE NO ES UN ERROR: responde 200 con elegible = false y el \
					detalle de que falta. Un 409 obligaria a la pantalla a tratar el caso mas \
					frecuente —al paciente le falta la orden— como una excepcion.

					ESTO NO CONSUME NADA (RN-M17-001) y no persiste nada. Es idempotente.

					NO AFIRMA QUE LA PRESTACION SEA FACTURABLE (RN-M08-004): dice si la \
					documentacion administrativa esta presente, que es otra cosa.

					limiteSesionesMensual viaja INFORMATIVO y sin veredicto: verificarlo exige \
					contar sesiones ya atendidas, o sea el consumo que esta version no cablea.

					Exige consultorio en el contexto: el convenio que fija los requisitos es de la \
					SEDE (RN-M16-001), asi que la misma cobertura del mismo paciente puede exigir \
					cosas distintas en dos sedes de la misma organizacion.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Requisitos y veredicto. La lista vacia es un estado normal",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ElegibilidadResponse.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo activo, o sin consultorio en el contexto",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La persona o la cobertura no existen, o son de otra organizacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ElegibilidadResponse> consultar(

			@Parameter(description = "Identificador de la persona", example = "1204")
			@PathVariable long personaId,

			@Parameter(description = "Cobertura con la que se atenderia", example = "412")
			@RequestParam long coberturaId,

			@Parameter(description = "Practica que se atenderia", example = "33")
			@RequestParam long practicaId,

			@Parameter(description = "Dia de la atencion. Si se omite, hoy", example = "2026-09-03")
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {

		return ResponseEntity.ok(ElegibilidadResponse.de(elegibilidadService.consultar(
				apiActor.current(), personaId, coberturaId, practicaId, fecha)));
	}
}
