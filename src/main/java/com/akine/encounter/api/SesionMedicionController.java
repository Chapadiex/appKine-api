package com.akine.encounter.api;

import com.akine.encounter.api.dto.ComparacionDeMedicionesResponse;
import com.akine.encounter.api.dto.MedicionResponse;
import com.akine.encounter.api.dto.MedicionesDeSesionResponse;
import com.akine.encounter.api.dto.RegistrarMedicionRequest;
import com.akine.encounter.application.MedicionService;
import com.akine.encounter.domain.LateralidadMedicion;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Examen fisico de una sesion: registro, borrado, listado y comparacion (M14, RF-M14-004).
 *
 * <h2>Por que la ruta cuelga de la sede y no es {@code /api/v1/sesiones/{id}/mediciones}</h2>
 *
 * <p>El diseno la escribe corta, pero <b>una sesion no se nombra sin su sede</b> en esta API:
 * {@code SesionController} ya vive en {@code /api/v1/consultorios/{consultorioId}/sesiones} y el
 * servicio resuelve el alcance —y el permiso {@code sesion:register}— contra esa sede. Colgar las
 * mediciones de una ruta sin consultorio obligaria a mandarlo como parametro suelto, que es la
 * misma informacion en un lugar donde el cliente puede olvidarla. Las <b>ocho operaciones</b> del
 * diseno estan, con el prefijo que el resto del modulo ya usa.
 *
 * <h2>El catalogo es de {@code resource}; este modulo solo lo lee</h2>
 *
 * <p>La definicion se valida por {@code resource.spi.MedicionDirectory} y su <b>significado se
 * copia en la fila</b> —codigo, nombre, unidad, tipo y version—. Sin ese snapshot, un
 * {@code UPDATE} sobre el catalogo reescribiria lo que dicen todas las mediciones pasadas sin
 * tocar una sola fila de {@code sesion_medicion}: reescritura de historia clinica por la puerta de
 * atras. Es el mismo snapshot que congela el importe de la obligacion (07.01).
 *
 * <h2>Escribir en la atencion ajena es 409, no 403</h2>
 *
 * <p>Dos profesionales de la misma sede tienen el mismo {@code sesion:register}: lo que impide que
 * uno escriba en la atencion del otro es la <b>propiedad</b> de esa sesion, y un 403 mandaria al
 * usuario a pedir un permiso que ya tiene. Es la regla que 06.01 dejo fijada.
 *
 * <p><b>Leer no exige propiedad</b>: mirar el examen de una atencion de otro profesional del mismo
 * centro es lo normal —una interconsulta, una supervision— y lo que la propiedad protege es la
 * escritura.
 *
 * <h2>Sesion cerrada: se lee, no se escribe</h2>
 *
 * <p>{@code PUT} y {@code DELETE} sobre una sesion cerrada responden <b>409</b>. Corregir una
 * atencion asentada es una enmienda, con su actor y su motivo, y eso es <b>06.06</b>: hasta
 * entonces esto es fail-closed, porque es preferible no poder corregir a corregir sin dejar
 * rastro. Las lecturas siguen funcionando con 200.
 *
 * <h2>Sin permisos nuevos</h2>
 *
 * <p>Registrar una medicion es escribir en la atencion, asi que el permiso es el de la sesion
 * ({@code sesion:register} sobre esa sede). La lectura, igual.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/sesiones/{sesionId}/mediciones",
		produces = {MediaType.APPLICATION_JSON_VALUE,
				MediaType.APPLICATION_PROBLEM_JSON_VALUE})
@Tag(name = "Mediciones de sesion",
		description = "Examen fisico: valores medidos en una atencion y su comparacion contra la "
				+ "sesion cerrada anterior (M14)")
public class SesionMedicionController {

	private static final Logger log = LoggerFactory.getLogger(SesionMedicionController.class);

	private final MedicionService medicionService;
	private final EncounterApiActor apiActor;

	public SesionMedicionController(
			MedicionService medicionService, EncounterApiActor apiActor) {

		this.medicionService = medicionService;
		this.apiActor = apiActor;
	}

	@PutMapping(path = "/{definicionId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "registrarMedicion",
			summary = "Registrar o actualizar una medicion",
			description = """
					Guarda el valor de una medida en esta sesion. **PUT y no POST**: la \
					operacion es idempotente por naturaleza —una medicion por test y por lado— y \
					el autosave del examen la va a repetir. Repetirla ACTUALIZA, no duplica.

					UNA MEDICION BILATERAL SON DOS LLAMADAS, una por lado. BILATERAL no existe: \
					los valores de los dos lados son distintos —ese es el punto de medirlos— y \
					una sola fila obligaria a promediarlos, que es perder exactamente la \
					informacion que la medicion existe para capturar. lateralidad vale NO_APLICA \
					por defecto, que es lo correcto para frecuencia cardiaca, Borg o saturacion.

					Se manda EXACTAMENTE UN valor, el que el tipo de la definicion admite. Que \
					falte deja una medicion que no mide nada; que sobre otro es el principio de \
					una medicion que despues nadie puede comparar. Las dos mitades son 400 \
					medicion-tipo-incompatible.

					EL RANGO SE VALIDA CONTRA LA VERSION VIGENTE EN ESTE MOMENTO, y esa version \
					queda copiada en la fila. Nunca se revalida al leer: una medicion vieja no se \
					vuelve invalida porque el catalogo estreche el rango despues. Un valor fuera \
					de rango es 400 medicion-fuera-de-rango, con el minimo y el maximo en el \
					cuerpo.

					Una definicion dada de baja no admite mediciones nuevas: 409 \
					medicion-definicion-inactiva. Las ya registradas con ella siguen legibles y \
					siguen entrando en la comparacion.

					NO LLEVA version. El control optimista de 06.01 protege el borrador de la \
					sesion, que es un documento entero; aca cada fila es una medida sola y dos \
					escrituras de la misma medida son la misma medida. Exigir una version \
					obligaria al autosave a releer cada valor antes de guardarlo.

					Solo el profesional de la sesion puede escribir, y el rechazo es 409 y no \
					403: no es cuestion de permiso —los dos tienen el mismo sesion:register— \
					sino de propiedad de esa atencion.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Medicion registrada o actualizada",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = MedicionResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "El valor no corresponde al tipo de la medida "
							+ "(medicion-tipo-incompatible) o cae fuera del rango declarado "
							+ "(medicion-fuera-de-rango)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo, o sin sesion:register en esa sede",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede, la sesion o la definicion no existen, o son de otro "
							+ "tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La atencion la lleva otro profesional, la sesion ya se cerro "
							+ "(enmendarla es 06.06), o la definicion esta dada de baja "
							+ "(medicion-definicion-inactiva)",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<MedicionResponse> registrar(

			@Parameter(description = "Sede de la atencion", example = "7")
			@PathVariable long consultorioId,

			@Parameter(description = "Sesion en la que se toma la medida", example = "501")
			@PathVariable long sesionId,

			@Parameter(description = "Definicion del catalogo que se mide", example = "12")
			@PathVariable long definicionId,

			@Parameter(description = "De que lado. Una medicion bilateral son dos llamadas",
					example = "IZQUIERDA")
			@RequestParam(defaultValue = "NO_APLICA") LateralidadMedicion lateralidad,

			@Valid @RequestBody RegistrarMedicionRequest request) {

		// El valor NO se loguea: es contenido clinico de un paciente. Que medida, que lado y en
		// que sesion si, que es lo que permite correlacionar sin filtrar nada.
		log.info("Registro de medicion solicitado: sesionId={} definicionId={} lateralidad={}",
				sesionId, definicionId, lateralidad);

		return ResponseEntity.ok(MedicionResponse.de(medicionService.registrar(
				apiActor.current(),
				consultorioId,
				sesionId,
				definicionId,
				lateralidad,
				request.valor(),
				request.nota())));
	}

	// El 204 declara application/json ADEMAS de problem+json: el cliente generado manda
	// Accept: application/problem+json y un produces que no lo incluyera lo cortaria con 406
	// antes de entrar al metodo. Ya rompio la activacion de cuenta y la recuperacion de
	// contrasena, y ningun test lo agarro.
	@DeleteMapping(path = "/{definicionId}",
			produces = {MediaType.APPLICATION_JSON_VALUE,
					MediaType.APPLICATION_PROBLEM_JSON_VALUE})
	@Operation(
			operationId = "borrarMedicion",
			summary = "Borrar una medicion cargada por error",
			description = """
					Saca del examen una medida que se cargo por error. **Solo sobre una sesion en \
					curso.**

					El borrado es fisico y no contradice la regla de no borrar historia: lo que \
					nunca se cerro no es informacion historica. Es el mismo criterio con el que \
					04.04 admitio borrar items de un plan en BORRADOR.

					SOBRE UNA SESION CERRADA ES 409, y lo decide el servidor, no la pantalla. \
					Corregir una atencion asentada es una enmienda, con su actor y su motivo, y \
					eso es 06.06.

					Que no exista la medicion es 404 y no un 204 silencioso: un borrado que finge \
					haber borrado le oculta a la pantalla que estaba mirando datos viejos, y el \
					profesional se queda creyendo que saco una medicion que en realidad sigue ahi \
					bajo el otro lado.

					lateralidad forma parte de la identidad de la medicion: borrar la izquierda \
					no toca la derecha.""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Medicion borrada"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo, o sin sesion:register en esa sede",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede o la sesion no existen, o no hay una medicion de esa "
							+ "medida y ese lado en esta sesion",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "La atencion la lleva otro profesional, o la sesion ya se "
							+ "cerro",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<Void> borrar(

			@Parameter(description = "Sede de la atencion", example = "7")
			@PathVariable long consultorioId,

			@Parameter(description = "Sesion de la que se saca la medida", example = "501")
			@PathVariable long sesionId,

			@Parameter(description = "Definicion del catalogo que se midio", example = "12")
			@PathVariable long definicionId,

			@Parameter(description = "De que lado. Borrar un lado no toca el otro",
					example = "IZQUIERDA")
			@RequestParam(defaultValue = "NO_APLICA") LateralidadMedicion lateralidad) {

		log.info("Borrado de medicion solicitado: sesionId={} definicionId={} lateralidad={}",
				sesionId, definicionId, lateralidad);

		medicionService.borrar(
				apiActor.current(), consultorioId, sesionId, definicionId, lateralidad);

		return ResponseEntity.noContent().build();
	}

	@GetMapping
	@Operation(
			operationId = "listarMedicionesDeSesion",
			summary = "Mediciones de una sesion",
			description = """
					Devuelve el examen fisico cargado en la sesion, con el informe de \
					completitud.

					SE LEE TAMBIEN SOBRE UNA SESION CERRADA. Lo que una sesion cerrada no admite \
					es escritura; un 404 sobre la lectura de un examen ya asentado seria borrar \
					historia por la puerta de atras.

					Cada medicion dice su nombre, su unidad y su tipo **tal como estaban cuando \
					se tomo**, no como estan hoy en el catalogo. Es lo que hace que un examen de \
					hace seis meses siga significando lo mismo.

					completo SE INFORMA Y NO GATEA NADA: no bloquea el cierre de la sesion. \
					Cerrar sigue siendo la decision del profesional. Es true cuando toda \
					definicion activa y visible para el tenant tiene al menos una medicion aca, \
					y las dos cuentas viajan al lado para poder decir "12 de 18" en vez de un \
					booleano que no explica nada.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Mediciones de la sesion e informe de completitud",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(
									implementation = MedicionesDeSesionResponse.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo, o sin sesion:register en esa sede",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede o la sesion no existen, o son de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<MedicionesDeSesionResponse> listar(

			@Parameter(description = "Sede de la atencion", example = "7")
			@PathVariable long consultorioId,

			@Parameter(description = "Sesion cuyas mediciones se piden", example = "501")
			@PathVariable long sesionId) {

		return ResponseEntity.ok(MedicionesDeSesionResponse.de(
				medicionService.listar(apiActor.current(), consultorioId, sesionId)));
	}

	@GetMapping("/comparacion")
	@Operation(
			operationId = "compararMedicionesDeSesion",
			summary = "Esta sesion contra la cerrada anterior",
			description = """
					Devuelve cada medida con su valor de hoy y el de la sesion cerrada anterior \
					del mismo paciente (RF-M14-004).

					SE CALCULA AL LEER Y NO SE GUARDA NADA. No hay delta persistido ni \
					referencia a "la medicion anterior": guardarlos seria una segunda copia de \
					la verdad que miente el dia que alguien enmiende la sesion anterior.

					EL BASELINE SE ACOTA AL MISMO CASO cuando la sesion tiene caso, y cae a "la \
					anterior del paciente" solo cuando no lo tiene: 04.03 admite varios casos \
					abiertos a la vez —una rodilla y un hombro— y comparar el ROM de rodilla \
					contra la sesion del hombro es comparar contra nada. acotadaAlCaso lo dice.

					SIN BASELINE NO ES UN ERROR: sesionAnteriorId viene ausente y cada fila trae \
					anterior ausente. Una primera evaluacion, o una re-evaluacion cuya sesion \
					previa quedo sin cerrar, son situaciones normales.

					Devuelve la UNION de las medidas de las dos sesiones. Las que se tomaron la \
					vez pasada y todavia no hoy son precisamente las que el profesional va a \
					volver a tomar: esa es la mitad "copiar-previo-y-ajustar" del requisito, y \
					por eso NO HAY NINGUN ENDPOINT DE COPIAR. Escribir el valor es un registro \
					normal, con alguien detras.

					delta VIENE AUSENTE CUANDO NO CORRESPONDE RESTAR: falta un lado, el valor no \
					es numerico, o las unidades difieren. Si el centro cambio la unidad del test \
					entre las dos sesiones, los 90 de marzo y los 90 de septiembre no son el \
					mismo numero; unidadesDifieren viene en true y la pantalla tiene que mostrar \
					las dos sin restar.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Comparacion de medidas. sesionAnteriorId ausente si no hay "
							+ "baseline, que es una respuesta valida",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(
									implementation = ComparacionDeMedicionesResponse.class))),
			@ApiResponse(
					responseCode = "403",
					description = "Sin contexto de trabajo, o sin sesion:register en esa sede",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede o la sesion no existen, o son de otro tenant",
					content = @Content(
							mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<ComparacionDeMedicionesResponse> comparar(

			@Parameter(description = "Sede de la atencion", example = "7")
			@PathVariable long consultorioId,

			@Parameter(description = "Sesion que se compara", example = "501")
			@PathVariable long sesionId) {

		return ResponseEntity.ok(ComparacionDeMedicionesResponse.de(
				medicionService.comparar(apiActor.current(), consultorioId, sesionId)));
	}
}
