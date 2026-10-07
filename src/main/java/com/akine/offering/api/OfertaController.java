package com.akine.offering.api;

import com.akine.offering.api.dto.CreateOfertaRequest;
import com.akine.offering.api.dto.DeactivateOfferingRequest;
import com.akine.offering.api.dto.OfertaResponse;
import com.akine.offering.api.dto.PoliticaDePrepagoRequest;
import com.akine.offering.api.dto.UpdateOfertaRequest;
import com.akine.offering.application.OfertaAltaCommand;
import com.akine.offering.application.OfertaEdicionCommand;
import com.akine.offering.application.OfertaEstadoFiltro;
import com.akine.offering.application.OfertaService;
import com.akine.offering.application.OfertaView;
import com.akine.offering.application.OperatingActor;
import com.akine.offering.domain.EsquemaCobro;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Ofertas de servicio de una sede: como ESTE centro presta un servicio global (M27).
 *
 * <h2>Por que la ruta lleva la sede y no la organizacion</h2>
 *
 * <p>Al reves que {@link ServicioController}, aca la fila SI tiene duenio: la tabla lleva
 * {@code organization_id NOT NULL} y todos sus indices empiezan por el. Aun asi la ruta es
 * {@code /api/v1/consultorios/{consultorioId}/ofertas} y no cuelga de {@code /organizations},
 * por la misma razon que la disponibilidad de 02.04: <b>el tenant sale del contexto validado del
 * request</b>, no de la URL. {@code TenantContextFilter} ya lo revalido contra la base en esta
 * llamada, y dejar que el cliente lo nombre en la ruta seria darle un lugar donde afirmar una
 * pertenencia que el servidor tiene que verificar igual.
 *
 * <p>Consecuencia directa: <b>sin contexto de trabajo elegido, todas estas operaciones responden
 * 403</b>. No es un caso de error raro, es el estado de un usuario recien logueado con
 * membresias en varios centros.
 *
 * <h2>Autorizacion</h2>
 *
 * <ul>
 *   <li><b>Leer</b>: pertenencia a la sede. Cualquier membership vigente que alcance ese
 *       consultorio.</li>
 *   <li><b>Mutar</b>: {@code consultorio:manage} sobre esa sede. Pasan {@code ORG_ADMIN} y
 *       {@code CONSULTORIO_ADMIN}.</li>
 * </ul>
 *
 * <p>Esta etapa no crea permisos nuevos (diseno §1): reusa {@code consultorio:manage}, que es el
 * mismo que ya gobierna los espacios y los horarios de la sede.
 *
 * <h2>Codigos, y por que no son intercambiables</h2>
 *
 * <ul>
 *   <li><b>404 para una oferta o una sede de otro tenant</b>, nunca 403. Un 403 confirmaria que
 *       ese id existe, y bastaria recorrer numeros para averiguar que servicios presta cada
 *       centro del SaaS —que es informacion comercial suya—.</li>
 *   <li><b>403 cuando falta contexto o falta el permiso</b>, nunca 401. El interceptor del
 *       frontend borra el token ante cualquier 401 y dejaria al usuario en un bucle de login.</li>
 *   <li><b>200 para una oferta INACTIVA.</b> RN-M27-007: una oferta dada de baja conserva sus
 *       historicos y tiene que seguir resolviendo.</li>
 *   <li><b>409</b> para los invariantes: nombre comercial repetido entre las vigentes de esa
 *       sede, oferta ya inactiva, servicio dado de baja, sede no operable, o version
 *       desactualizada. El de concurrencia llega con {@code type} <b>{@code conflict}</b>, no
 *       {@code concurrent-modification}.</li>
 * </ul>
 *
 * <h2>Lo que esta etapa deliberadamente NO trae</h2>
 *
 * <ul>
 *   <li><b>Que profesionales y que espacios habilita cada oferta</b> (RF-M03-006 paso 7). Es de
 *       la etapa de agenda: sin Turno no hay nada que habilitar.</li>
 *   <li><b>La relacion Oferta ↔ Practica</b> (RF-M06-008). Va con el modulo clinico.</li>
 *   <li><b>Resolver el esquema de cobro.</b> Se guarda como texto declarado y nadie lo
 *       interpreta: los modulos de facturacion y cobros no existen (RN-M27-006).</li>
 * </ul>
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/ofertas",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
		name = "Servicios y ofertas",
		description = "Catalogo global de servicios y como cada sede los presta (M27)")
public class OfertaController {

	private static final Logger log = LoggerFactory.getLogger(OfertaController.class);

	private final OfertaService ofertaService;
	private final OfferingApiActor apiActor;

	public OfertaController(OfertaService ofertaService, OfferingApiActor apiActor) {
		this.ofertaService = ofertaService;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "listOfertas",
			summary = "Listar las ofertas de la sede",
			description = """
					Devuelve las ofertas de esa sede ordenadas por nombre comercial. Exige \
					pertenencia a la sede.

					estado filtra por ciclo de vida y por defecto trae solo las ACTIVAS. \
					servicioId, si viene, acota a las ofertas de ese servicio.

					OJO CON ESTADO Y VIGENTEHOY: son dos cosas distintas. estado dice si la \
					oferta fue dada de baja; vigenteHoy dice si ADEMAS hoy cae dentro de su \
					ventana de vigencia, calculado en la zona horaria DE LA SEDE y no en la del \
					servidor ni en la del navegador. Una oferta ACTIVA que arranca el mes que \
					viene tiene estado=ACTIVO y vigenteHoy=false, y es correcto que no aparezca \
					todavia en un selector de reserva. Es la misma distincion que espacio hace \
					entre activo y en servicio.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Ofertas de la sede, ordenadas por nombre comercial",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(
									schema = @Schema(implementation = OfertaResponse.class)))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo elegido, o sin pertenencia a la sede",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe o es de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<OfertaResponse>> list(

			@Parameter(description = "Sede cuyas ofertas se listan", example = "3")
			@PathVariable long consultorioId,

			@Parameter(description = "Filtro por ciclo de vida. Si se omite, ACTIVO")
			@RequestParam(required = false) OfertaEstadoFiltro estado,

			@Parameter(
					description = "Acota a las ofertas de ese servicio global. Si se omite, todas",
					example = "12")
			@RequestParam(required = false) Long servicioId) {

		OperatingActor actor = apiActor.current();
		long organizationId = exigirContexto(actor);

		List<OfertaView> ofertas = servicioId == null
				? ofertaService.listar(
						actor,
						organizationId,
						consultorioId,
						estado == null ? OfertaEstadoFiltro.ACTIVO : estado)
				: ofertaService.listarPorServicio(actor, organizationId, consultorioId, servicioId);

		return ResponseEntity.ok(ofertas.stream().map(OfertaResponse::de).toList());
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createOferta",
			summary = "Dar de alta una oferta en la sede",
			description = """
					Declara que esta sede presta un servicio del catalogo global, y en que \
					condiciones. Exige consultorio:manage sobre esa sede.

					El servicio tiene que estar VIGENTE: sobre uno dado de baja responde 409 \
					servicio-inactivo. Es el unico efecto que la baja de un servicio global \
					tiene sobre los centros; las ofertas que ya existian siguen operando.

					LO QUE SE OMITE, HEREDA. modalidad, requiereCasoClinico y \
					generaRegistroClinico toman el default del servicio si no vienen. Omitirlos \
					NO es apagarlos.

					El nombre comercial es unico entre las ofertas VIGENTES de esa sede, \
					comparado sin distinguir mayusculas ni acentos. El de una oferta dada de \
					baja si se puede reusar.

					El esquema de cobro se guarda como texto declarado y NADIE lo resuelve: los \
					modulos que lo interpretarian no existen todavia (RN-M27-006). Se pide para \
					no perder el dato, no para ramificar por el.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Oferta creada. La cabecera Location apunta al recurso",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = OfertaResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos, o ventana de vigencia incoherente",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo, o sin consultorio:manage en la sede",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede o el servicio no existen, o la sede es de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Nombre comercial repetido "
							+ "(oferta-nombre-comercial-taken), servicio dado de baja "
							+ "(servicio-inactivo), o sede no operable "
							+ "(consultorio-no-operable)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<OfertaResponse> create(

			@Parameter(description = "Sede donde se da de alta la oferta", example = "3")
			@PathVariable long consultorioId,

			@Valid @RequestBody CreateOfertaRequest request) {

		OperatingActor actor = apiActor.current();
		long organizationId = exigirContexto(actor);

		// Ni el nombre comercial ni el precio entran al log: son dato comercial del cliente. El
		// servicio y la sede si, que es lo que permite correlacionar con la fila de auditoria.
		log.info("Alta de oferta solicitada: consultorioId={} servicioId={}",
				consultorioId, request.servicioId());

		OfertaView creada = ofertaService.crear(
				actor,
				organizationId,
				consultorioId,
				new OfertaAltaCommand(
						request.servicioId(),
						request.nombreComercial(),
						request.descripcion(),
						request.modalidad(),
						request.duracionMinutos(),
						request.capacidad(),
						request.precioBase(),
						request.moneda(),
						esquemaCobro(request.esquemaCobro()),
						request.admiteObraSocial(),
						request.requiereCasoClinico(),
						request.generaRegistroClinico(),
						request.requiereProfesional(),
						request.requiereEspacio(),
						request.vigenciaDesde(),
						request.vigenciaHasta()));

		return ResponseEntity
				.created(URI.create(
						"/api/v1/consultorios/" + consultorioId + "/ofertas/" + creada.id()))
				.body(OfertaResponse.de(creada));
	}

	@PutMapping(path = "/{ofertaId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "updateOferta",
			summary = "Editar una oferta de la sede",
			description = """
					Exige consultorio:manage sobre esa sede.

					El servicio NO se puede cambiar y por eso no esta en el cuerpo: cambiarlo \
					convertiria esta oferta en otra cosa mientras conserva su id y sus \
					historicos. Lo que corresponde es dar de baja esta y crear la que si presta \
					el otro servicio.

					Los campos en null NO se tocan. Por eso existen limpiarPrecio, \
					limpiarEsquemaCobro y limpiarVigenciaHasta: sin ellos no habria forma de \
					expresar "sacale el precio", porque mandar precioBase: null es \
					indistinguible de no mandarlo. Cuando un limpiar viene en true, el valor \
					correspondiente se ignora.

					Una oferta dada de baja no admite ediciones: 409 oferta-inactiva.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Oferta actualizada",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = OfertaResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Campos invalidos o ventana de vigencia incoherente",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo, o sin consultorio:manage en la sede",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La oferta o la sede no existen, o son de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Nombre comercial repetido "
							+ "(oferta-nombre-comercial-taken), oferta dada de baja "
							+ "(oferta-inactiva), o version desactualizada (conflict)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<OfertaResponse> update(

			@Parameter(description = "Sede de la oferta", example = "3")
			@PathVariable long consultorioId,

			@Parameter(description = "Identificador de la oferta", example = "34")
			@PathVariable long ofertaId,

			@Valid @RequestBody UpdateOfertaRequest request) {

		OperatingActor actor = apiActor.current();
		long organizationId = exigirContexto(actor);

		log.info("Edicion de oferta solicitada: consultorioId={} ofertaId={}",
				consultorioId, ofertaId);

		OfertaView actualizada = ofertaService.editar(
				actor,
				organizationId,
				consultorioId,
				ofertaId,
				new OfertaEdicionCommand(
						request.nombreComercial(),
						request.descripcion(),
						request.modalidad(),
						request.duracionMinutos(),
						request.capacidad(),
						request.precioBase(),
						request.moneda(),
						Boolean.TRUE.equals(request.limpiarPrecio()),
						esquemaCobro(request.esquemaCobro()),
						Boolean.TRUE.equals(request.limpiarEsquemaCobro()),
						request.admiteObraSocial(),
						request.requiereCasoClinico(),
						request.generaRegistroClinico(),
						request.requiereProfesional(),
						request.requiereEspacio(),
						request.vigenciaDesde(),
						request.vigenciaHasta(),
						Boolean.TRUE.equals(request.limpiarVigenciaHasta()),
						request.expectedVersion()));

		return ResponseEntity.ok(OfertaResponse.de(actualizada));
	}

	// PRODUCES EXPLICITO, por lo mismo que en ServicioController.deactivate: el 204 no lleva
	// cuerpo, el contrato solo declara problem+json, y sin declarar tambien application/json el
	// produces de clase lo rechaza con 406 antes de entrar al metodo.
	@DeleteMapping(path = "/{ofertaId}", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = { MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE })
	@Operation(
			operationId = "deactivateOferta",
			summary = "Dar de baja una oferta de la sede",
			description = """
					Baja LOGICA con motivo obligatorio. Exige consultorio:manage sobre esa sede.

					RN-M27-007: la oferta deja de admitir reservas nuevas y CONSERVA sus \
					historicos. RF-M27-002 prohibe el borrado fisico cuando hay referencias.

					El motivo es obligatorio: sin el, la auditoria no responde por que seis \
					meses despues.

					No hay reactivacion. Una oferta que vuelve es una ventana de vigencia \
					nueva, no una baja deshecha. Si lo que se quiere es sacarla de circulacion \
					un tiempo, el camino es editar vigenciaHasta, no dar de baja.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "204",
					description = "Oferta dada de baja"),
			@ApiResponse(
					responseCode = "400",
					description = "Falta el motivo de la baja",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo, o sin consultorio:manage en la sede",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La oferta o la sede no existen, o son de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La oferta ya estaba dada de baja (oferta-already-inactive)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> deactivate(

			@Parameter(description = "Sede de la oferta", example = "3")
			@PathVariable long consultorioId,

			@Parameter(description = "Identificador de la oferta", example = "34")
			@PathVariable long ofertaId,

			@Valid @RequestBody DeactivateOfferingRequest request) {

		OperatingActor actor = apiActor.current();
		long organizationId = exigirContexto(actor);

		// El motivo no se loguea: es texto libre del cliente.
		log.info("Baja de oferta solicitada: consultorioId={} ofertaId={}",
				consultorioId, ofertaId);

		ofertaService.darDeBaja(actor, organizationId, consultorioId, ofertaId, request.reason());

		return ResponseEntity.noContent().build();
	}

	@PutMapping(path = "/{ofertaId}/politica-de-prepago", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "updatePoliticaDePrepagoDeOferta",
			summary = "Cambiar la politica de prepago de una oferta",
			description = """
					Exige consultorio:manage sobre esa sede. AKINE E-6, DP-06 / ADR-0013.

					Con exigePrepago en true, la recepcion de los turnos de esta oferta muestra 					el prepago como PENDIENTE hasta que se registre un anticipo con turnoId. Es 					una ALERTA: nunca impide pasar a espera, atender ni cerrar la sesion.

					Una oferta dada de baja no admite cambios: 409 oferta-inactiva.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Politica actualizada",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = OfertaResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Cuerpo invalido",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo, o sin consultorio:manage en la sede",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La oferta o la sede no existen, o son de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Oferta dada de baja (oferta-inactiva) o version desactualizada "
							+ "(conflict)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<OfertaResponse> updatePoliticaDePrepago(

			@Parameter(description = "Sede de la oferta", example = "3")
			@PathVariable long consultorioId,

			@Parameter(description = "Identificador de la oferta", example = "34")
			@PathVariable long ofertaId,

			@Valid @RequestBody PoliticaDePrepagoRequest request) {

		OperatingActor actor = apiActor.current();
		long organizationId = exigirContexto(actor);

		return ResponseEntity.ok(OfertaResponse.de(ofertaService.cambiarPoliticaDePrepago(
				actor, organizationId, consultorioId, ofertaId, request.exigePrepago(),
				request.expectedVersion())));
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	/**
	 * El tenant del contexto validado, o 403.
	 *
	 * <p><b>403 y no 401</b>, aunque "no elegiste contexto" se parezca a "no estas autenticado":
	 * el actor SI esta autenticado, lo que le falta es haber elegido con que centro trabaja. Un
	 * 401 haria que el interceptor del frontend borre el token y lo mande al login, del que
	 * volveria al mismo estado — un bucle.
	 */
	private static long exigirContexto(OperatingActor actor) {
		Long organizationId = actor.contextOrganizationId();
		if (organizationId == null) {
			throw new AccessDeniedException(
					"La operacion requiere un contexto de trabajo elegido");
		}
		return organizationId;
	}

	/**
	 * El esquema de cobro declarado, o {@code null} si no vino.
	 *
	 * <p>Se construye aca y no en el record de request porque {@link EsquemaCobro} valida en su
	 * constructor compacto: dejarlo entrar como tipo del DTO haria que un valor demasiado largo
	 * reviente en la deserializacion de Jackson —un 400 sin campo ni mensaje util— en vez de en
	 * la validacion, que si sabe decir cual campo y por que.
	 */
	private static EsquemaCobro esquemaCobro(String valor) {
		return valor == null || valor.isBlank() ? null : new EsquemaCobro(valor);
	}
}
