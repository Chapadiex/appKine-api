package com.akine.contracting.api;

import com.akine.contracting.api.dto.AdopcionDeFinanciadorResponse;
import com.akine.contracting.api.dto.CatalogoFinanciadorResponse;
import com.akine.contracting.application.AdopcionDeFinanciadorService;
import com.akine.contracting.application.AdopcionView;
import com.akine.contracting.application.CatalogoDeFinanciadoresService;
import com.akine.contracting.application.EstadoFiltro;
import com.akine.contracting.application.FinanciadorBusqueda;
import com.akine.contracting.domain.TipoFinanciador;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * El catalogo global de financiadores visto desde un centro, y la adopcion (M15, DP-43).
 *
 * <p>La organizacion sale del contexto, nunca de la ruta: la adopcion crea la copia en el tenant
 * que el filtro revalido. Leer exige {@code convenio:read} y adoptar {@code convenio:manage} sobre
 * la sede del contexto, los mismos permisos que leer y dar de alta financiadores propios.
 */
@RestController
@RequestMapping(path = "/api/v1/catalogo-financiadores", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Catalogo global de financiadores",
		description = "Financiadores y planes que la plataforma ofrece para que los centros adopten (DP-43)")
public class CatalogoFinanciadorController {

	private final CatalogoDeFinanciadoresService catalogo;
	private final AdopcionDeFinanciadorService adopcion;
	private final ContractingApiActor apiActor;

	public CatalogoFinanciadorController(
			CatalogoDeFinanciadoresService catalogo,
			AdopcionDeFinanciadorService adopcion,
			ContractingApiActor apiActor) {

		this.catalogo = catalogo;
		this.adopcion = adopcion;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			operationId = "listCatalogoFinanciadores",
			summary = "Buscar en el catalogo global de financiadores",
			description = """
					Lo que el centro puede adoptar: las entradas ACTIVAS del catalogo de la \
					plataforma, con sus planes ACTIVOS, ordenadas por nombre. q busca por nombre, \
					codigo y CUIT.

					Por cada entrada, financiadorAdoptadoId es la copia VIGENTE que este centro ya \
					adopto (null si no la adopto), y actualizacionDisponible avisa que la entrada \
					cambio despues de adoptarla. Es solo informativo: la copia no se actualiza sola.

					Exige convenio:read con la sede del contexto.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Entradas activas del catalogo",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(schema = @Schema(
									implementation = CatalogoFinanciadorResponse.class)))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo activo o sin convenio:read",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<CatalogoFinanciadorResponse>> list(
			@Parameter(description = "Texto a buscar en nombre, codigo y CUIT", example = "osde")
			@RequestParam(required = false) String q,
			@Parameter(description = "Filtro por clasificacion. Si se omite, todos")
			@RequestParam(required = false) TipoFinanciador tipo) {

		return ResponseEntity.ok(catalogo
				.buscarParaElCentro(apiActor.current(), new FinanciadorBusqueda(q, EstadoFiltro.ACTIVO, tipo))
				.stream()
				.map(CatalogoFinanciadorResponse::de)
				.toList());
	}

	@PostMapping(path = "/{catalogoId}/adopcion")
	@Operation(
			operationId = "adoptarFinanciadorDelCatalogo",
			summary = "Adoptar un financiador del catalogo global",
			description = """
					Crea en la organizacion del contexto una COPIA PROPIA del financiador y de sus \
					planes ACTIVOS, con catalogoId apuntando al origen. La copia es un financiador \
					del centro como cualquier otro: se edita, se da de baja y se usa en \
					coberturas y convenios sin tocar el catalogo, y editar el catalogo despues no \
					la cambia.

					IDEMPOTENTE: si el centro ya tiene una copia vigente de esa entrada, responde \
					200 con esa copia y no escribe nada; la primera vez responde 201. Dos llamadas \
					simultaneas dejan una sola copia. Dada de baja la copia, se puede volver a \
					adoptar.

					Si el centro ya cargo a mano un financiador vigente con el mismo codigo, nombre \
					o CUIT, responde 409 con el type de siempre y no crea nada.

					Exige convenio:manage con la sede del contexto. Sin cuerpo.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Copia creada",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AdopcionDeFinanciadorResponse.class))),
			@ApiResponse(responseCode = "200", description = "El centro ya la habia adoptado: la "
					+ "copia vigente, sin cambios",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AdopcionDeFinanciadorResponse.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo activo o sin convenio:manage",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La entrada del catalogo no existe",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "La entrada esta dada de baja "
					+ "(financiador-inactivo), o el centro ya tiene un financiador vigente con ese "
					+ "codigo (financiador-codigo-taken), nombre (financiador-nombre-taken) o CUIT "
					+ "(financiador-cuit-taken)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AdopcionDeFinanciadorResponse> adoptar(
			@Parameter(description = "Identificador en el catalogo", example = "7") @PathVariable long catalogoId) {

		AdopcionView resultado = adopcion.adoptar(apiActor.current(), catalogoId);
		AdopcionDeFinanciadorResponse cuerpo = AdopcionDeFinanciadorResponse.de(resultado);
		if (!resultado.creado()) {
			return ResponseEntity.ok(cuerpo);
		}
		return ResponseEntity
				.created(URI.create("/api/v1/financiadores/" + resultado.financiador().id()))
				.body(cuerpo);
	}
}
