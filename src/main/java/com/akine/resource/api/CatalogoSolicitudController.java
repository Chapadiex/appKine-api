package com.akine.resource.api;

import com.akine.resource.api.dto.CatalogoSolicitudResponse;
import com.akine.resource.api.dto.CreateCatalogoSolicitudRequest;
import com.akine.resource.api.dto.ResolveCatalogoSolicitudRequest;
import com.akine.resource.application.CatalogoSolicitudService;
import com.akine.resource.application.CatalogoSolicitudView;
import com.akine.resource.application.SolicitudResolucionCommand;
import com.akine.resource.domain.SolicitudEstado;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Solicitudes de alta de concepto GLOBAL (RF-M06-005).
 *
 * <h2>El circuito, en tres lineas</h2>
 *
 * <p>Un centro necesita una especialidad que el catalogo de plataforma no tiene. No puede
 * crearla el como global —seria decidir por todos los tenants del SaaS— asi que la pide. La
 * plataforma la aprueba o la rechaza, con motivo.
 *
 * <p><b>Pedir no bloquea nada.</b> Mientras la plataforma decide, el centro crea el concepto
 * como contextual suyo y sigue trabajando. Este circuito existe para que el catalogo comun
 * crezca con criterio, no para frenar a nadie, y ningun endpoint del catalogo consulta si hay
 * una solicitud abierta.
 *
 * <h2>Por que la ruta esta separada de {@code /api/v1/catalogos}</h2>
 *
 * <p>Una solicitud no es un concepto: no tiene vigencia, no se puede elegir en ningun selector y
 * no se da de baja —se resuelve—. Colgarla de {@code /catalogos/{tipo}} la habria metido en el
 * mismo segmento de ruta que los tres conceptos administrables, con el riesgo de que
 * {@code solicitudes} compitiera con {@code especialidades} por el mismo {@code {tipo}}. Un
 * recurso distinto, una raiz distinta.
 *
 * <h2>Quien ve que</h2>
 *
 * <ul>
 *   <li>Un administrador de tenant ve <b>las suyas</b> y crea las suyas.</li>
 *   <li>Un administrador de plataforma ve <b>la bandeja completa</b> y es el unico que
 *       resuelve. Es la unica consulta cross-tenant de todo el modulo.</li>
 * </ul>
 *
 * <p>Interinamente crear exige {@code consultorio:manage}; el codigo propio,
 * {@code catalogo:manage}, esta propuesto en {@code docs/seguridad/matriz-permisos-minima.md}
 * §11 y todavia no aprobado. Los codigos HTTP no cambian cuando se apruebe.
 */
@RestController
@RequestMapping(path = "/api/v1/catalogo-solicitudes",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Catalogo clinico",
		description = "Especialidades, practicas, nomencladores y sus vigencias (M06)")
public class CatalogoSolicitudController {

	private final CatalogoSolicitudService solicitudService;
	private final ApiActor apiActor;

	public CatalogoSolicitudController(
			CatalogoSolicitudService solicitudService, ApiActor apiActor) {

		this.solicitudService = solicitudService;
		this.apiActor = apiActor;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createCatalogoSolicitud",
			summary = "Pedir el alta de un concepto global",
			description = """
					Registra el pedido de un centro para que la plataforma incorpore una \
					especialidad, una practica o un nomenclador al catalogo comun (RF-M06-005).

					La justificacion es obligatoria: sin ella la plataforma no puede decidir y la \
					solicitud es ruido.

					Un segundo pedido identico —mismo tenant, mismo tipo, mismo nombre— mientras \
					el primero sigue PENDIENTE responde 409 catalogo-solicitud-duplicada. Es la \
					idempotencia frente a un reintento por timeout de red, y la sostiene un \
					unique de la base, no una comprobacion previa: entre un SELECT y un INSERT \
					caben dos requests. Volver a pedir algo ya RECHAZADO si esta permitido, con \
					argumentos nuevos.

					La administracion de plataforma no puede usar este endpoint: no se pide \
					conceptos a si misma, los crea.

					Exige consultorio:manage sobre la sede del contexto.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Solicitud registrada",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CatalogoSolicitudResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el tipo, el nombre propuesto o la justificacion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo, sin consultorio:manage, o es un "
							+ "administrador de plataforma",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Ya hay una solicitud PENDIENTE por ese mismo concepto "
							+ "(catalogo-solicitud-duplicada)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CatalogoSolicitudResponse> create(
			@Valid @RequestBody CreateCatalogoSolicitudRequest request) {

		CatalogoSolicitudView creada = solicitudService.crear(
				apiActor.current(),
				request.tipo(),
				request.nombrePropuesto(),
				request.codigoPropuesto(),
				request.justificacion());

		return ResponseEntity
				.created(URI.create("/api/v1/catalogo-solicitudes/" + creada.id()))
				.body(CatalogoSolicitudResponse.from(creada));
	}

	@GetMapping
	@Operation(
			operationId = "listCatalogoSolicitudes",
			summary = "Solicitudes de alta de catalogo",
			description = """
					Un administrador de tenant ve LAS SUYAS; un administrador de plataforma ve \
					la bandeja completa de todos los tenants. Es la unica consulta cross-tenant \
					del modulo, y por eso el rol se verifica antes de elegir la consulta y no \
					filtrando el resultado despues.

					El filtro estado es opcional: sin el, todas. Ordenadas de la mas reciente a \
					la mas vieja.

					No hay paginado, y es una decision consciente: la cantidad de conceptos que \
					un centro pide agregar al catalogo comun se cuenta con los dedos. Si algun \
					dia no fuera asi, eso seria el problema.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Solicitudes visibles. Lista vacia si no hay ninguna",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(schema = @Schema(
									implementation = CatalogoSolicitudResponse.class)))),
			@ApiResponse(
					responseCode = "400",
					description = "El valor de estado no pertenece al catalogo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "No hay sesion autenticada o no hay contexto de trabajo",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<CatalogoSolicitudResponse>> list(
			@Parameter(description = "Solo las solicitudes en ese estado", example = "PENDIENTE")
			@RequestParam(required = false) SolicitudEstado estado) {

		return ResponseEntity.ok(solicitudService.listar(apiActor.current(), estado).stream()
				.map(CatalogoSolicitudResponse::from)
				.toList());
	}

	@PostMapping(path = "/{solicitudId}/resolve", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "resolveCatalogoSolicitud",
			summary = "Aprobar o rechazar una solicitud",
			description = """
					Reservado a la administracion de plataforma. La resolucion es TERMINAL: una \
					solicitud aprobada o rechazada no se reabre. Si la plataforma se equivoco, \
					el centro vuelve a pedir, y esa segunda solicitud es una fila nueva con su \
					propia justificacion y su propia fecha.

					APROBAR PUBLICA EL CONCEPTO GLOBAL, en la misma transaccion, y devuelve su \
					id en conceptoId. El centro propone y la plataforma dispone: codigo, nombre, \
					descripcion y especialidadId del cuerpo son la normalizacion con la que se \
					publica; sin codigo o sin nombre se usan los propuestos, y sin ningun codigo \
					posible la respuesta es 400. especialidadId es obligatorio al aprobar una \
					PRACTICA y tiene que ser una especialidad global. La publicacion pasa por \
					las mismas validaciones que el alta global: si el codigo o el nombre ya \
					estan tomados en el catalogo comun responde 409 catalogo-code-taken o \
					catalogo-name-taken, y la solicitud SIGUE PENDIENTE sin ningun concepto \
					creado. Si el concepto ya existe en el catalogo comun, el desenlace es \
					rechazar con una nota que lo diga. Un rechazo no lleva datos de concepto \
					(400 si los trae).

					La nota es obligatoria en los dos desenlaces. version se compara antes de \
					mutar: dos administradores de plataforma sobre la misma bandeja tienen que \
					enterarse de que el otro llego primero.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Solicitud resuelta",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CatalogoSolicitudResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta la nota o la version, el desenlace es PENDIENTE, se aprueba "
							+ "sin codigo posible o una PRACTICA sin especialidadId, o un rechazo trae "
							+ "datos de concepto",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "La cuenta no administra la plataforma. Nunca 401",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La solicitud no existe, o la especialidad de la practica a "
							+ "publicar no existe en el catalogo global",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Ya estaba resuelta (catalogo-solicitud-ya-resuelta), o "
							+ "version desactualizada (concurrent-modification), o el concepto a "
							+ "publicar choca en el catalogo global (catalogo-code-taken, "
							+ "catalogo-name-taken, catalogo-reference-inactive); en esos casos la "
							+ "solicitud sigue PENDIENTE",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<CatalogoSolicitudResponse> resolve(
			@Parameter(description = "Identificador de la solicitud", example = "1")
			@PathVariable long solicitudId,

			@Valid @RequestBody ResolveCatalogoSolicitudRequest request) {

		CatalogoSolicitudView resuelta = solicitudService.resolver(
				apiActor.current(),
				solicitudId,
				new SolicitudResolucionCommand(
						request.estado(),
						request.nota(),
						request.version(),
						request.codigo(),
						request.nombre(),
						request.descripcion(),
						request.especialidadId()));

		return ResponseEntity.ok(CatalogoSolicitudResponse.from(resuelta));
	}
}
