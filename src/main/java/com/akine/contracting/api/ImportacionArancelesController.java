package com.akine.contracting.api;

import com.akine.contracting.api.dto.FilaImportacionArancelRequest;
import com.akine.contracting.api.dto.ImportacionArancelesResponse;
import com.akine.contracting.api.dto.ImportarArancelesRequest;
import com.akine.contracting.application.ImportacionAranceles.Fila;
import com.akine.contracting.application.ImportacionAranceles.Resultado;
import com.akine.contracting.application.ImportacionArancelesService;
import com.akine.contracting.application.OperatingActor;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Importacion masiva de aranceles de un convenio, con vista previa (B-7, RF-M16-007).
 *
 * <p>Una sola operacion con {@code modo}, y no dos rutas: el preview y la confirmacion reciben
 * EXACTAMENTE el mismo cuerpo, y la pantalla manda el mismo lote dos veces cambiando una palabra.
 * El backend no recibe archivos: el frontend parsea la planilla y manda las filas en JSON.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
		name = "Convenios y aranceles",
		description = "Convenios de la sede con planes de financiadores, y sus aranceles (M16)")
public class ImportacionArancelesController {

	private static final Logger log = LoggerFactory.getLogger(ImportacionArancelesController.class);

	private final ImportacionArancelesService importacion;
	private final ContractingApiActor apiActor;

	public ImportacionArancelesController(
			ImportacionArancelesService importacion, ContractingApiActor apiActor) {

		this.importacion = importacion;
		this.apiActor = apiActor;
	}

	@PostMapping(path = "/convenios/{convenioId}/aranceles/importacion",
			consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "importArancelesDeConvenio",
			summary = "Previsualizar o confirmar una importacion masiva de aranceles",
			description = """
					RF-M16-007. Exige convenio:manage sobre la sede de la ruta, en los dos modos.

					Cada fila se valida con LAS MISMAS reglas que el alta unitaria de un arancel: \
					practica visible para el tenant (por practicaId o por codigoPractica), importes \
					que cuadran, vigencia contenida en la del convenio, oferta de la sede que admite \
					obra social y declara la practica, y que no se pise con un arancel vigente del \
					mismo grupo (convenio, practica, oferta). Ademas, dos filas del mismo lote \
					tampoco pueden pisarse entre si.

					PREVIEW responde 200 con el desenlace de cada fila: ALTA, o RECHAZADA con el \
					problemType que daria el alta unitaria. No escribe nada ni toma el lock: es una \
					prediccion, no una reserva.

					CONFIRMAR es TODO O NADA. Revalida el lote entero bajo el lock del convenio —lo \
					que se cargo despues del preview cuenta— y, si todas las filas entran, las \
					inserta y responde 200 con aplicada = true y el arancelId de cada una. Si una \
					sola no entra, 409 importacion-aranceles-rechazada con el desenlace de cada \
					fila en la propiedad filas, y ninguna fila escrita.

					Reintentar una confirmacion ya aplicada no duplica nada: sus filas chocan \
					contra los aranceles que ella misma creo y responde 409.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200",
					description = "Preview, o confirmacion aplicada",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ImportacionArancelesResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "Falta el modo, el lote esta vacio o supera las 500 filas",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo activo o sin convenio:manage en esa sede",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La sede o el convenio no son accesibles",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "Convenio dado de baja (convenio-inactivo), o confirmacion con "
							+ "alguna fila que no entra (importacion-aranceles-rechazada, con "
							+ "filas en el cuerpo)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ImportacionArancelesResponse> importar(

			@Parameter(description = "Identificador de la sede", example = "20")
			@PathVariable long consultorioId,

			@Parameter(description = "Identificador del convenio", example = "140")
			@PathVariable long convenioId,

			@Valid @RequestBody ImportarArancelesRequest request) {

		log.info("Importacion de aranceles solicitada: convenioId={} modo={} filas={}",
				convenioId, request.modo(), request.filas().size());

		OperatingActor actor = apiActor.current();
		List<Fila> filas = request.filas().stream()
				.map(ImportacionArancelesController::fila)
				.toList();
		Resultado resultado = switch (request.modo()) {
			case PREVIEW -> importacion.previsualizar(actor, consultorioId, convenioId, filas);
			case CONFIRMAR -> importacion.confirmar(actor, consultorioId, convenioId, filas);
		};
		return ResponseEntity.ok(ImportacionArancelesResponse.de(resultado));
	}

	private static Fila fila(FilaImportacionArancelRequest fila) {
		return fila == null ? null : new Fila(
				fila.practicaId(),
				fila.codigoPractica(),
				fila.ofertaId(),
				fila.importeTotal(),
				fila.importeFinanciador(),
				fila.coseguro(),
				fila.vigenciaDesde(),
				fila.vigenciaHasta());
	}
}
