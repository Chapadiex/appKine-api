package com.akine.offering.api;

import com.akine.offering.api.dto.HabilitacionesResponse;
import com.akine.offering.api.dto.ReemplazarHabilitacionesRequest;
import com.akine.offering.api.dto.ValidacionDeOfertaResponse;
import com.akine.offering.application.OfertaHabilitacionService;
import com.akine.offering.application.OperatingActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Quien puede prestar una Oferta y donde puede prestarse (M27/M04/M05, AKINE-02.07).
 *
 * <h2>Las dos cosas que hay que entender antes de consumir esto</h2>
 *
 * <p><b>Lista vacia significa TODOS, no NINGUNO.</b> Una oferta sin habilitaciones no esta
 * prohibida para nadie: esta sin restringir. Por eso la respuesta trae
 * {@code restringidaPorProfesional} y {@code restringidaPorEspacio} como campos propios, en vez de
 * dejar que el cliente lo deduzca de una lista vacia y lo lea al reves.
 *
 * <p><b>Habilitacion no es permiso.</b> Estas listas no otorgan acceso a nada. Un profesional
 * habilitado sigue necesitando su membership vigente para entrar, y un administrador sin
 * habilitacion sigue pudiendo configurar la oferta aunque no pueda prestarla. Habilitacion
 * responde "puede prestar esto"; permiso responde "puede tocar esto". Se cruzan recien en la
 * agenda, que va a exigir las dos.
 *
 * <h2>Los PUT reemplazan el conjunto completo</h2>
 *
 * <p>No hay endpoints de alta y baja de a uno, a proposito: una grilla de casillas se guarda
 * entera, y obligar al cliente a diffear produce bajas que nadie pidio cuando el diff sale mal. El
 * servidor hace el diff y devuelve la configuracion resultante.
 *
 * <p>Cada reemplazo lleva la {@code expectedVersion} de la <b>oferta</b>, que es lo que serializa
 * a dos administradores editando la misma configuracion. Sin eso, el segundo en guardar borra en
 * silencio lo que agrego el primero, y con 200.
 *
 * <h2>Autorizacion</h2>
 *
 * <ul>
 *   <li><b>Leer y validar</b>: pertenencia a la sede.</li>
 *   <li><b>Reemplazar</b>: {@code consultorio:manage} sobre esa sede.</li>
 * </ul>
 *
 * <p>Esta etapa no crea permisos nuevos: reusa el mismo que ya gobierna los espacios, los horarios
 * y las ofertas de la sede.
 *
 * <h2>Codigos</h2>
 *
 * <ul>
 *   <li><b>404</b> para una oferta, sede, membership o espacio de otro tenant o de otra sede. Un
 *       403 confirmaria que ese id existe y bastaria recorrer numeros para averiguar cuanta gente
 *       y cuantos boxes tiene cada centro del SaaS.</li>
 *   <li><b>403</b> sin contexto o sin permiso. <b>Nunca 401</b>: el interceptor del frontend borra
 *       el token ante cualquier 401 y dejaria al usuario en un bucle de login.</li>
 *   <li><b>409</b> con {@code type} {@code concurrent-modification} si la version quedo vieja, y
 *       {@code oferta-inactiva} si se intenta configurar una oferta dada de baja.</li>
 * </ul>
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/ofertas/{ofertaId}",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
		name = "Servicios y ofertas",
		description = "Catalogo global de servicios y como cada sede los presta (M27)")
public class HabilitacionController {

	private static final Logger log = LoggerFactory.getLogger(HabilitacionController.class);

	private final OfertaHabilitacionService habilitacionService;
	private final OfferingApiActor apiActor;

	public HabilitacionController(
			OfertaHabilitacionService habilitacionService, OfferingApiActor apiActor) {

		this.habilitacionService = habilitacionService;
		this.apiActor = apiActor;
	}

	@GetMapping("/habilitaciones")
	@Operation(
			operationId = "getHabilitaciones",
			summary = "Ver quien puede prestar la oferta y donde",
			description = """
					Devuelve las habilitaciones de profesional y de espacio, ACTIVAS E INACTIVAS, \
					mas la capacidad efectiva.

					LEER ESTO ANTES DE ESCRIBIR LA PANTALLA. Una lista vacia significa que la \
					oferta NO esta restringida: cualquier profesional con vinculo vigente puede \
					prestarla y puede prestarse en cualquier espacio de la sede. NO significa que \
					no pueda nadie. Para eso estan restringidaPorProfesional y \
					restringidaPorEspacio, que lo dicen explicito.

					Las habilitaciones dadas de baja vienen igual, con su motivo. No se esconden: \
					esconderlas dejaria al administrador sin entender por que la capacidad \
					efectiva cambio sola.

					Lo mismo con las que apuntan a un recurso que ya no sirve. vinculoVigente en \
					false es un colaborador que se desvinculo; enServicio en false es un espacio \
					que se dio de baja en su propio modulo. La habilitacion sigue existiendo y la \
					fila lo dice.

					capacidadEfectiva es el minimo entre la capacidad de la oferta y la de los \
					espacios habilitados que estan en servicio. Sin espacios habilitados es igual \
					a la comercial: no hay ningun espacio concreto contra el cual acotarla. \
					espacioQueLimita nombra cual la acota, y mostrar un numero mas chico sin ese \
					nombre es un defecto de la pantalla.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Configuracion de la oferta",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = HabilitacionesResponse.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo, o sin pertenencia a la sede",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La oferta o la sede no existen, o son de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<HabilitacionesResponse> get(

			@Parameter(description = "Sede de la oferta", example = "3")
			@PathVariable long consultorioId,

			@Parameter(description = "Oferta configurada", example = "34")
			@PathVariable long ofertaId) {

		OperatingActor actor = apiActor.current();
		long organizationId = exigirContexto(actor);

		return ResponseEntity.ok(HabilitacionesResponse.de(
				habilitacionService.leer(actor, organizationId, consultorioId, ofertaId)));
	}

	@PutMapping(path = "/habilitaciones/profesionales", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "reemplazarProfesionalesHabilitados",
			summary = "Fijar que profesionales pueden prestar la oferta",
			description = """
					REEMPLAZA EL CONJUNTO COMPLETO. Lo que entra y no estaba se crea, lo que \
					estaba y no entra se da de baja con motivo automatico, y lo que sigue no se \
					toca: conserva su id, su vigencia y su version.

					Mandar una lista VACIA es la operacion legitima de quitar la restriccion, y \
					deja la oferta disponible para cualquier profesional con vinculo vigente. No \
					es un error y no se rechaza. La respuesta devuelve restringidaPorProfesional \
					en false para que la pantalla lo pueda decir con palabras: quien borra la \
					ultima habilitacion creyendo que restringe, abre la oferta a todos.

					Cada id es una MEMBERSHIP, no una cuenta: la misma persona puede ser \
					profesional en un centro y administrativa en otro, y habilitar la cuenta \
					habilitaria a alguien que en esta organizacion no atiende. Se aceptan las \
					memberships de toda la organizacion y las acotadas a ESTA sede; una acotada a \
					otra sede responde 404, porque esa persona no atiende aca.

					expectedVersion es la de la OFERTA. Es lo que impide que el segundo \
					administrador en guardar borre en silencio lo que agrego el primero.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Configuracion resultante",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = HabilitacionesResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta la lista o expectedVersion",
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
					description = "La oferta, la sede o alguna membership no existen, no son de "
							+ "este tenant, o la membership esta acotada a otra sede",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Version desactualizada (concurrent-modification), oferta dada de baja "
							+ "(oferta-inactiva), o sede no operable (consultorio-no-operable)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<HabilitacionesResponse> reemplazarProfesionales(

			@Parameter(description = "Sede de la oferta", example = "3")
			@PathVariable long consultorioId,

			@Parameter(description = "Oferta configurada", example = "34")
			@PathVariable long ofertaId,

			@Valid @RequestBody ReemplazarHabilitacionesRequest request) {

		OperatingActor actor = apiActor.current();
		long organizationId = exigirContexto(actor);

		// Se loguea cuantos, no quienes: los ids de membership de un centro son dato suyo. El
		// detalle por profesional queda en la auditoria, que si tiene control de acceso.
		log.info("Reemplazo de profesionales habilitados: consultorioId={} ofertaId={} cantidad={}",
				consultorioId, ofertaId, request.ids() == null ? 0 : request.ids().size());

		return ResponseEntity.ok(HabilitacionesResponse.de(
				habilitacionService.reemplazarProfesionales(
						actor, organizationId, consultorioId, ofertaId,
						comoConjunto(request.ids()), request.expectedVersion())));
	}

	@PutMapping(path = "/habilitaciones/espacios", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "reemplazarEspaciosHabilitados",
			summary = "Fijar en que espacios puede prestarse la oferta",
			description = """
					REEMPLAZA EL CONJUNTO COMPLETO, con el mismo criterio que el de profesionales.

					Mandar una lista VACIA quita la restriccion y deja que la oferta pueda \
					prestarse en cualquier espacio de la sede.

					Que un espacio sirva para una oferta NO se infiere de su tipo ni de su nombre \
					(RF-M04-008): elegir "Pileta" al dar de alta un espacio no autoriza \
					hidroterapia ahi, lo autoriza estar en esta lista.

					Solo se aceptan espacios de ESTA sede. Un espacio no se muda entre sedes, asi \
					que habilitar el de otra seria configurar algo que no se puede usar nunca: \
					responde 404.

					OJO CON LA CAPACIDAD: habilitar un espacio mas chico que la capacidad \
					comercial de la oferta BAJA la capacidad efectiva, porque es el minimo entre \
					las dos. No es un error y la respuesta lo explica en espacioQueLimita.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Configuracion resultante",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = HabilitacionesResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Falta la lista o expectedVersion",
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
					description = "La oferta, la sede o algun espacio no existen, no son de este "
							+ "tenant, o el espacio es de otra sede",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Version desactualizada (concurrent-modification), oferta dada de baja "
							+ "(oferta-inactiva), o sede no operable (consultorio-no-operable)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<HabilitacionesResponse> reemplazarEspacios(

			@Parameter(description = "Sede de la oferta", example = "3")
			@PathVariable long consultorioId,

			@Parameter(description = "Oferta configurada", example = "34")
			@PathVariable long ofertaId,

			@Valid @RequestBody ReemplazarHabilitacionesRequest request) {

		OperatingActor actor = apiActor.current();
		long organizationId = exigirContexto(actor);

		log.info("Reemplazo de espacios habilitados: consultorioId={} ofertaId={} cantidad={}",
				consultorioId, ofertaId, request.ids() == null ? 0 : request.ids().size());

		return ResponseEntity.ok(HabilitacionesResponse.de(
				habilitacionService.reemplazarEspacios(
						actor, organizationId, consultorioId, ofertaId,
						comoConjunto(request.ids()), request.expectedVersion())));
	}

	@GetMapping("/validacion")
	@Operation(
			operationId = "validarOferta",
			summary = "Saber si esa combinacion puede prestarse, y por que no",
			description = """
					Responde la mitad calculable de RF-M05-008 y RF-M04-009. La otra mitad \
					—reservar de verdad— es de la etapa de agenda, que va a consumir exactamente \
					este endpoint.

					Los dos parametros son opcionales: se puede preguntar solo por el \
					profesional, solo por el espacio, o por los dos. Sin ninguno valida la oferta \
					sola, que sigue siendo una pregunta util: ¿esta oferta se puede usar hoy?

					DEVUELVE TODOS LOS MOTIVOS QUE FALLAN, NO EL PRIMERO. Si al profesional le \
					falta habilitacion y ademas el espacio esta fuera de servicio, arreglar uno \
					solo no alcanza, y decirlo de a uno obliga al usuario a dos vueltas.

					Cada motivo trae un codigo estable para ramificar sin leer prosa. Los ocho \
					posibles son: oferta-inactiva, oferta-fuera-de-vigencia, \
					profesional-no-habilitado, habilitacion-fuera-de-vigencia, \
					vinculo-no-vigente, espacio-no-habilitado, espacio-fuera-de-servicio y \
					capacidad-insuficiente.

					capacidad-insuficiente NO es un rechazo duro: la oferta se puede prestar \
					igual con menos gente. Es informacion para que la agenda decida, y por eso \
					viaja como motivo y no como error.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Resultado de la validacion, con sus motivos",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ValidacionDeOfertaResponse.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo, o sin pertenencia a la sede",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La oferta o la sede no existen, o son de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ValidacionDeOfertaResponse> validar(

			@Parameter(description = "Sede de la oferta", example = "3")
			@PathVariable long consultorioId,

			@Parameter(description = "Oferta a validar", example = "34")
			@PathVariable long ofertaId,

			@Parameter(
					description = "Membership del profesional que prestaria la oferta",
					example = "215")
			@RequestParam(required = false) Long membershipId,

			@Parameter(description = "Espacio donde se prestaria", example = "3")
			@RequestParam(required = false) Long espacioId) {

		OperatingActor actor = apiActor.current();
		long organizationId = exigirContexto(actor);

		return ResponseEntity.ok(ValidacionDeOfertaResponse.de(
				habilitacionService.validar(
						actor, organizationId, consultorioId, ofertaId, membershipId, espacioId)));
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	/**
	 * El tenant del contexto validado, o 403.
	 *
	 * <p><b>403 y no 401</b>: el actor SI esta autenticado, lo que le falta es haber elegido con
	 * que centro trabaja. Un 401 haria que el interceptor del frontend borre el token y lo mande
	 * al login, del que volveria al mismo estado.
	 */
	private static long exigirContexto(OperatingActor actor) {
		Long organizationId = actor.contextOrganizationId();
		if (organizationId == null) {
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo elegido");
		}
		return organizationId;
	}

	/**
	 * La lista del cuerpo como conjunto, sin nulos.
	 *
	 * <p>Se acepta una lista con repetidos y se colapsa en vez de rechazarla: habilitar dos veces
	 * al mismo profesional es la misma intencion que habilitarlo una, y un 400 ahi seria pedantear
	 * sobre algo que no cambia el resultado.
	 */
	private static Set<Long> comoConjunto(List<Long> ids) {
		if (ids == null) {
			return Set.of();
		}
		Set<Long> conjunto = new LinkedHashSet<>();
		for (Long id : ids) {
			if (id != null) {
				conjunto.add(id);
			}
		}
		return conjunto;
	}
}
