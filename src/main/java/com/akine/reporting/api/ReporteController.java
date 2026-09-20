package com.akine.reporting.api;

import com.akine.reporting.api.dto.CatalogoDeReportesResponse;
import com.akine.reporting.api.dto.ReporteResponse;
import com.akine.reporting.application.ExportadorCsv;
import com.akine.reporting.application.ReporteService;
import com.akine.reporting.application.ReporteView;
import com.akine.reporting.spi.ReporteCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
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
 * Reportes y tableros del MVP (M23).
 *
 * <h2>Un reporte es una lectura, y una lectura no se materializa</h2>
 *
 * <p>Ninguna de estas operaciones consulta una tabla de resumen, porque no existe ninguna: el
 * modulo {@code reporting} <b>no tiene una sola tabla propia</b>. Cada seccion la calcula, al
 * momento de leer, el modulo duenio de los datos de los que sale. Es la misma decision que la
 * disponibilidad efectiva, el Paciente 360, el timeline clinico y el motor de slots.
 *
 * <h2>Los cinco conceptos economicos son cinco</h2>
 *
 * <p>Deuda, cobro, caja, presentacion a financiador y egreso se muestran <b>separados, con su
 * fuente declarada, y no existe ningun campo que los sume</b>. Un "ingresos totales" que sumara
 * lo cobrado con los ingresos de caja contaria dos veces cada cobro en efectivo, porque el
 * movimiento de caja de origen {@code COBRO} <i>es</i> ese cobro visto desde el cajon.
 *
 * <h2>Permisos: recortan, no rechazan</h2>
 *
 * <p>Todas las operaciones exigen <b>{@code reporte:read}</b> con la sede como alcance. Cada
 * seccion pide ademas el permiso con el que se lee su fuente, y una seccion sin permiso <b>se
 * omite y se declara</b> en vez de devolver 403 sobre el tablero entero.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/reportes",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(name = "Reportes", description = "Tableros operativos, clinicos y economicos del MVP (M23)")
public class ReporteController {

	private final ReporteService reporteService;
	private final ExportadorCsv exportador;
	private final ReportingApiActor apiActor;

	public ReporteController(
			ReporteService reporteService,
			ExportadorCsv exportador,
			ReportingApiActor apiActor) {

		this.reporteService = reporteService;
		this.exportador = exportador;
		this.apiActor = apiActor;
	}

	@GetMapping
	@Operation(
			summary = "Catalogo de reportes",
			description = """
					Que reportes existen en esta sede y que secciones trae cada uno, con el \
					permiso que cada seccion pide.

					Sirve para dibujar el menu sin ejecutar los cinco reportes, y para poder \
					decirle al usuario **de antemano** por que una seccion no le va a aparecer.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Catalogo"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `reporte:read` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public CatalogoDeReportesResponse catalogoDeReportes(@PathVariable long consultorioId) {
		return CatalogoDeReportesResponse.de(
				reporteService.catalogo(apiActor.current(), consultorioId));
	}

	@GetMapping("/{reporte}")
	@Operation(
			summary = "Generar un reporte",
			description = """
					Calcula el reporte para el periodo pedido (RF-M23-001 a RF-M23-005).

					**El periodo se interpreta en la zona de la sede**, no en la del servidor. Un \
					cobro de las 21:30 en Ushuaia convertido con la zona equivocada cae en el dia \
					siguiente: el reporte no falla, da otro numero.

					**Cada indicador declara su fuente y su criterio de fecha.** Deuda, cobro, \
					caja, presentacion y egreso son cinco conceptos y ningun campo los suma.

					**Lo que no se puede ver se declara**: `omitidas` lleva las secciones cuyo \
					permiso falta, y `advertencias` los indicadores que valen cero por \
					construccion y no por falta de actividad.

					Ventana maxima: 366 dias.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El reporte"),
			@ApiResponse(
					responseCode = "400",
					description = "`rango-de-reporte-invalido`: periodo invertido o mas ancho que "
							+ "la ventana maxima",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `reporte:read` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ReporteResponse generarReporte(
			@PathVariable long consultorioId,
			@PathVariable ReporteCode reporte,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {

		return ReporteResponse.de(
				reporteService.generar(apiActor.current(), consultorioId, reporte, desde, hasta));
	}

	/**
	 * RF-M23-006.
	 *
	 * <p><b>Llama al mismo {@code generar} que la pantalla</b> y serializa su resultado. No tiene
	 * consulta propia: una query "optimizada para el export" garantiza que algun dia el archivo y
	 * la pantalla digan cosas distintas y que nadie sepa cual de las dos miente.
	 */
	@GetMapping(path = "/{reporte}/export", produces = {"text/csv", "application/problem+json"})
	@Operation(
			summary = "Exportar un reporte a CSV",
			description = """
					Genera el archivo con los mismos filtros y **la misma computacion** que la \
					pantalla (RF-M23-006).

					El CSV lleva la fuente y el criterio de fecha de cada indicador, no solo su \
					valor: un archivo sobrevive a la sesion que lo genero —llega por mail, se \
					abre en una planilla— y sin esa columna alguien le suma un cobro a un \
					movimiento de caja.

					**Sincronico y acotado.** El export asincrono con progreso se difiere: sin \
					una medicion contra una base real no hay forma de saber cuando un reporte \
					excede el tiempo interactivo, y acotar la ventana es lo unico honesto \
					mientras tanto.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El archivo CSV"),
			@ApiResponse(
					responseCode = "400",
					description = "`rango-de-reporte-invalido`",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `reporte:read` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<String> exportarReporte(
			@PathVariable long consultorioId,
			@PathVariable ReporteCode reporte,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {

		ReporteView vista =
				reporteService.generar(apiActor.current(), consultorioId, reporte, desde, hasta);

		String nombre = "reporte-" + reporte.name().toLowerCase() + "-"
				+ vista.desde() + "_" + vista.hasta() + ".csv";

		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nombre + "\"")
				.contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
				.body(exportador.aCsv(vista));
	}
}
