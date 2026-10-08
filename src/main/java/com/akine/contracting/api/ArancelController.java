package com.akine.contracting.api;

import com.akine.contracting.api.dto.ArancelEfectivoResponse;
import com.akine.contracting.api.dto.ArancelResponse;
import com.akine.contracting.api.dto.CreateArancelRequest;
import com.akine.contracting.api.dto.DeactivateContractingRequest;
import com.akine.contracting.api.dto.UpdateArancelRequest;
import com.akine.contracting.application.ArancelCommands.ArancelAltaCommand;
import com.akine.contracting.application.ArancelCommands.ArancelEdicionCommand;
import com.akine.contracting.application.ArancelService;
import com.akine.contracting.application.ArancelView;
import com.akine.contracting.application.EstadoFiltro;
import com.akine.contracting.spi.ResolucionDeArancel;
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
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

/**
 * Aranceles por practica dentro de un convenio, y la resolucion del arancel efectivo (M16).
 *
 * <h2>Dos aranceles de la misma practica conviven; solaparse no</h2>
 *
 * <p>El de 2026 y el de 2027 son el caso normal. Lo que se rechaza con 409
 * {@code arancel-solapado} es que sus periodos se pisen (RN-M16-002) — igual que con los convenios,
 * y por la misma via: un lock, nunca un indice.
 *
 * <h2>{@code GET /aranceles/efectivo} responde 200 aunque no haya arancel</h2>
 *
 * <p>Y eso es deliberado. No encontrar convenio es el desenlace mas frecuente —la mayoria de los
 * pacientes se atienden como particulares— y un 404 obligaria a la pantalla a tratar el caso normal
 * como una excepcion. El cuerpo trae {@code resuelto = false} y el motivo, que distingue "no hay
 * convenio" de "hay convenio pero esa practica no esta tarifada": dos situaciones que mandan al
 * administrador a lugares distintos. RN-M16-005.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}",
		produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
		name = "Convenios y aranceles",
		description = "Convenios de la sede con planes de financiadores, y sus aranceles (M16)")
public class ArancelController {

	private static final Logger log = LoggerFactory.getLogger(ArancelController.class);

	private final ArancelService arancelService;
	private final ContractingApiActor apiActor;

	public ArancelController(ArancelService arancelService, ContractingApiActor apiActor) {
		this.arancelService = arancelService;
		this.apiActor = apiActor;
	}

	@GetMapping("/convenios/{convenioId}/aranceles")
	@Operation(
			operationId = "listAranceles",
			summary = "Listar los aranceles de un convenio",
			description = """
					La grilla de vigencias. Devuelve los aranceles del convenio del mas nuevo al \
					mas viejo.

					estado filtra el CICLO DE VIDA y por defecto trae solo los ACTIVOS. NO filtra \
					por vigencia: un arancel activo cuya ventana ya paso se devuelve con \
					vigente = false, y es justamente lo que hace falta para explicar por que una \
					prestacion de marzo se cobro distinto que una de octubre.

					fecha es el dia contra el que se calcula vigente. Si se omite, hoy.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Aranceles del convenio",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							array = @ArraySchema(
									schema = @Schema(implementation = ArancelResponse.class)))),
			@ApiResponse(responseCode = "403", description = "Sin contexto de trabajo activo",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La sede o el convenio no son accesibles",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<List<ArancelResponse>> list(

			@Parameter(description = "Identificador de la sede", example = "20")
			@PathVariable long consultorioId,

			@Parameter(description = "Identificador del convenio", example = "140")
			@PathVariable long convenioId,

			@Parameter(description = "Filtro por ciclo de vida. Si se omite, ACTIVO")
			@RequestParam(required = false) EstadoFiltro estado,

			@Parameter(description = "Dia contra el que se calcula vigente. Si se omite, hoy")
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {

		return ResponseEntity.ok(
				arancelService.listar(apiActor.current(), consultorioId, convenioId, estado, fecha)
						.stream()
						.map(ArancelResponse::de)
						.toList());
	}

	@PostMapping(path = "/convenios/{convenioId}/aranceles",
			consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "createArancel",
			summary = "Definir el arancel de una practica bajo un convenio",
			description = """
					Exige convenio:manage sobre la sede de la ruta.

					DOS ARANCELES DE LA MISMA PRACTICA EN EL MISMO CONVENIO NO PUEDEN SOLAPARSE: \
					409 arancel-solapado, con el id y el periodo del que choca en el cuerpo del \
					problema. Dos aranceles de la misma practica SI conviven —el de 2026 y el de \
					2027— y esa convivencia es el caso normal.

					LOS TRES IMPORTES TIENEN QUE CUADRAR: importeFinanciador + coseguro = \
					importeTotal, exactamente. No hay porcentaje de cobertura a proposito: un \
					porcentaje obliga a redondear, y el redondeo de un arancel es la diferencia de \
					un centavo que aparece seis meses despues en una presentacion rechazada.

					La moneda NO viaja en el cuerpo: la hereda del convenio.

					La vigencia del arancel tiene que estar CONTENIDA en la del convenio. Fuera de \
					ella nunca podria resolver, porque la resolucion exige primero un convenio \
					aplicable.

					La practica puede ser global de plataforma o propia de la organizacion; una de \
					otro tenant responde 404.

					ARANCEL POR OFERTA (RF-M16-008): con ofertaId, el arancel es el de esa practica \
					cuando se presta dentro de esa oferta, y al resolver con esa oferta manda sobre \
					el general. El general y el de cada oferta conviven en el mismo periodo; dos de \
					la misma oferta no. La oferta tiene que ser de esta sede (404), admitir obra \
					social (409 oferta-sin-obra-social) y declarar la practica (409 \
					practica-no-habilitada-en-oferta).""")
	@ApiResponses({
			@ApiResponse(responseCode = "201",
					description = "Arancel creado. La cabecera Location apunta al recurso",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ArancelResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "Importes que no cuadran, importe negativo, vigencia invertida o "
							+ "vigencia fuera de la del convenio",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo activo o sin convenio:manage en esa sede",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La sede, el convenio, la practica o la oferta no son accesibles",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "Periodo solapado (arancel-solapado), convenio dado de baja "
							+ "(convenio-inactivo), oferta sin obra social (oferta-sin-obra-social) o "
							+ "practica que la oferta no declara (practica-no-habilitada-en-oferta)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ArancelResponse> create(

			@Parameter(description = "Identificador de la sede", example = "20")
			@PathVariable long consultorioId,

			@Parameter(description = "Identificador del convenio", example = "140")
			@PathVariable long convenioId,

			@Valid @RequestBody CreateArancelRequest request) {

		log.info("Alta de arancel solicitada: convenioId={} practicaId={}",
				convenioId, request.practicaId());

		ArancelView creado = arancelService.crear(
				apiActor.current(),
				consultorioId,
				convenioId,
				new ArancelAltaCommand(
						request.practicaId(),
						request.importeTotal(),
						request.importeFinanciador(),
						request.coseguro(),
						request.vigenciaDesde(),
						request.vigenciaHasta(),
						request.ofertaId()));

		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId + "/convenios/"
						+ convenioId + "/aranceles/" + creado.id()))
				.body(ArancelResponse.de(creado));
	}

	@PutMapping(path = "/convenios/{convenioId}/aranceles/{arancelId}",
			consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "updateArancel",
			summary = "Editar un arancel",
			description = """
					Exige convenio:manage sobre la sede de la ruta.

					LA FORMA CORRECTA DE SUBIR UN PRECIO NO ES ESTA OPERACION: es cerrar la \
					vigencia del arancel actual y crear otro. Editar el importe de una ventana ya \
					transcurrida se admite —a veces hay que corregir una carga— y NO reescribe nada \
					de lo ya liquidado, porque eso guardo su propio snapshot congelado \
					(RN-M16-003).

					Los importes se validan como TERNA aunque llegue uno solo: subir el total sin \
					tocar las partes rompe la invariante de que sumen, y se rechaza con 400.

					Mover la vigencia puede producir 409 arancel-solapado.

					Ni la practica ni el convenio se pueden cambiar: no seria editar este arancel, \
					seria inventar otro.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Arancel actualizado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ArancelResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "Importes que no cuadran, vigencia invertida o fuera de la del "
							+ "convenio",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo activo o sin convenio:manage en esa sede",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La sede, el convenio o el arancel no son accesibles",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "Periodo solapado (arancel-solapado), arancel dado de baja "
							+ "(arancel-inactivo) o version desactualizada (concurrent-modification)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ArancelResponse> update(

			@Parameter(description = "Identificador de la sede", example = "20")
			@PathVariable long consultorioId,

			@Parameter(description = "Identificador del convenio", example = "140")
			@PathVariable long convenioId,

			@Parameter(description = "Identificador del arancel", example = "901")
			@PathVariable long arancelId,

			@Valid @RequestBody UpdateArancelRequest request) {

		log.info("Edicion de arancel solicitada: arancelId={}", arancelId);

		ArancelView actualizado = arancelService.editar(
				apiActor.current(),
				consultorioId,
				convenioId,
				arancelId,
				new ArancelEdicionCommand(
						request.importeTotal(),
						request.importeFinanciador(),
						request.coseguro(),
						request.vigenciaDesde(),
						request.vigenciaHasta(),
						request.expectedVersion()));

		return ResponseEntity.ok(ArancelResponse.de(actualizado));
	}

	// PRODUCES EXPLICITO por el 406 del Accept problem+json. Ver el mismo comentario, mas largo, en
	// ConvenioController.deactivate.
	@DeleteMapping(path = "/convenios/{convenioId}/aranceles/{arancelId}",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
	@Operation(
			operationId = "deactivateArancel",
			summary = "Dar de baja un arancel",
			description = """
					Baja LOGICA con motivo obligatorio. Exige convenio:manage sobre la sede de la \
					ruta.

					Libera el PERIODO: el no-solapamiento solo mira los activos, asi que se puede \
					volver a cargar un arancel de esa practica para las mismas fechas.

					Lo ya liquidado con este arancel guarda su propio snapshot congelado y no se \
					toca (RN-M16-003).

					No hay reactivacion.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Arancel dado de baja"),
			@ApiResponse(responseCode = "400", description = "Falta el motivo de la baja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo activo o sin convenio:manage en esa sede",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La sede, el convenio o el arancel no son accesibles",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "El arancel ya estaba dado de baja (arancel-already-inactive)",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> deactivate(

			@Parameter(description = "Identificador de la sede", example = "20")
			@PathVariable long consultorioId,

			@Parameter(description = "Identificador del convenio", example = "140")
			@PathVariable long convenioId,

			@Parameter(description = "Identificador del arancel", example = "901")
			@PathVariable long arancelId,

			@Valid @RequestBody DeactivateContractingRequest request) {

		log.info("Baja de arancel solicitada: arancelId={}", arancelId);

		arancelService.darDeBaja(
				apiActor.current(), consultorioId, convenioId, arancelId, request.reason());

		return ResponseEntity.noContent().build();
	}

	@GetMapping("/aranceles/efectivo")
	@Operation(
			operationId = "resolveArancelEfectivo",
			summary = "Resolver el arancel efectivo de una practica para una fecha",
			description = """
					RF-M16-006 y RF-M16-010. Devuelve UN resultado, explicable y estable en el \
					tiempo.

					RESPONDE 200 AUNQUE NO HAYA ARANCEL, con resuelto = false y el motivo. No \
					encontrar convenio es el desenlace mas frecuente —la mayoria de los pacientes \
					se atienden como particulares— y un 404 obligaria a la pantalla a tratar el \
					caso normal como una excepcion. Los dos motivos son distintos y hacen falta los \
					dos: SIN_CONVENIO_VIGENTE manda a cobrar como particular (RN-M16-005), \
					SIN_ARANCEL_VIGENTE manda a cargar el precio de esa practica.

					El resultado es unico porque no puede haber dos candidatas, no porque haya una \
					regla de prioridad que las desempate: dos convenios del mismo alcance no se \
					pueden solapar, y dos aranceles de la misma practica en el mismo convenio \
					tampoco.

					Cuando se resuelve, viaja tambien la DERIVACION: el convenio, su codigo y las \
					dos vigencias que intervinieron. Sin eso, un numero inesperado no se puede \
					explicar.

					fecha es el dia de la PRESTACION, no el de hoy. Consultar una fecha pasada \
					devuelve el arancel que regia entonces, que es lo que hace que una atencion \
					retroactiva se cobre bien.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200",
					description = "El arancel efectivo, o el motivo por el que no hay",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ArancelEfectivoResponse.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto de trabajo activo",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La sede no existe o es de otra organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ArancelEfectivoResponse> resolver(

			@Parameter(description = "Identificador de la sede", example = "20")
			@PathVariable long consultorioId,

			@Parameter(description = "Financiador de la cobertura del paciente", example = "31")
			@RequestParam long financiadorId,

			@Parameter(description = "Plan de cobertura del paciente", example = "88")
			@RequestParam long planId,

			@Parameter(description = "Practica que se va a prestar", example = "412")
			@RequestParam long practicaId,

			@Parameter(description = "Dia de la PRESTACION. Si se omite, hoy", example = "2026-03-15")
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,

			@Parameter(description = "Oferta dentro de la cual se presta la practica (RF-M16-008). "
					+ "Si se pasa, el arancel especifico de esa oferta manda sobre el general de la "
					+ "practica. Si se omite, solo el general", example = "77")
			@RequestParam(required = false) Long ofertaId) {

		LocalDate contra = fecha == null ? LocalDate.now() : fecha;
		ResolucionDeArancel resolucion = arancelService.resolver(
				apiActor.current(), consultorioId, financiadorId, planId, practicaId, ofertaId,
				contra);

		return ResponseEntity.ok(ArancelEfectivoResponse.de(resolucion, contra));
	}
}
