package com.akine.person.api;

import com.akine.person.api.dto.AdjuntoPageResponse;
import com.akine.person.api.dto.AdjuntoResponse;
import com.akine.person.api.dto.BajaDeAdjuntoRequest;
import com.akine.person.api.dto.ClasificarAdjuntoRequest;
import com.akine.person.application.AdjuntoAltaCommand;
import com.akine.person.application.AdjuntoService;
import com.akine.person.application.AdjuntoService.AdjuntoAlta;
import com.akine.person.application.ContenidoDeAdjunto;
import com.akine.person.domain.CategoriaAdjunto;
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
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Adjuntos administrativos de una Persona (M07/M25, AKINE-03.02).
 *
 * <h2>Por que cuelga de la persona y no es un servicio de archivos generico</h2>
 *
 * <p>La etapa promete "servicio transversal de adjuntos" como contexto para las siguientes, y esta
 * ruta lo entrega <b>a traves de la entidad</b>: {@code /personas/&#123;id&#125;/adjuntos} y no
 * {@code /adjuntos?entidad=persona&amp;id=...}. La diferencia no es estetica. RN-M25-003 dice que
 * el acceso hereda permisos de la entidad asociada; con la entidad en la ruta, <b>no hay forma de
 * pedir un adjunto sin nombrar la ficha cuya autorizacion se evalua</b>. Con un endpoint generico,
 * el id de la entidad seria un parametro mas y el dia que alguien lo omita el control desaparece.
 *
 * <p>Cuando 03.03 necesite adjuntos de un convenio, la ruta va a ser
 * {@code /convenios/&#123;id&#125;/adjuntos} y lo transversal va a ser el puerto de almacenamiento
 * y el patron, no un controller compartido.
 *
 * <h2>Autorizacion, en una linea</h2>
 *
 * <ul>
 *   <li><b>Listar y descargar</b>: {@code paciente:read}, igual que leer la ficha (DP-22).</li>
 *   <li><b>Subir, reclasificar y dar de baja</b>: {@code paciente:manage} sobre la sede del
 *       contexto, igual que editarla.</li>
 * </ul>
 *
 * <h2>Lo que la descarga NO hace: URLs temporales firmadas</h2>
 *
 * <p>La etapa las menciona. <b>No se implementaron, y es una decision, no un olvido.</b> Una URL
 * firmada tiene sentido cuando el binario lo sirve otro sistema —almacenamiento de objetos, CDN—
 * y lo que se quiere evitar es que el trafico pase por la aplicacion. Con un adaptador de sistema
 * de archivos local, el binario pasa por la aplicacion de todos modos, y firmar una URL solo
 * agregaria <b>un segundo camino de autorizacion, mas debil que el primero</b>: un token en la
 * query string que se copia, se comparte, queda en los logs del proxy y en el historial del
 * navegador, y que no se puede revocar cuando al usuario se le quita el permiso.
 *
 * <p>Lo que si se cumple es la regla que las URLs temporales existen para cumplir —RN-M25-002, no
 * exponer rutas internas—: el contenido se entrega por este endpoint, que autoriza cada llamada, y
 * la {@code storageKey} no sale del backend por ningun campo. El dia que haya almacenamiento de
 * objetos, la URL firmada entra como una optimizacion con su propio diseño.
 */
@RestController
@RequestMapping(path = "/api/v1/personas/{personaId}/adjuntos")
@Tag(name = "Adjuntos", description = "Documentos administrativos de una persona (M07/M25)")
public class AdjuntoController {

	private static final Logger log = LoggerFactory.getLogger(AdjuntoController.class);

	private static final String PAGINA_POR_DEFECTO = "0";
	private static final String TAMANO_POR_DEFECTO = "20";
	private static final int TAMANO_MAXIMO = 100;

	private final AdjuntoService adjuntoService;
	private final PersonApiActor apiActor;

	public AdjuntoController(AdjuntoService adjuntoService, PersonApiActor apiActor) {
		this.adjuntoService = adjuntoService;
		this.apiActor = apiActor;
	}

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "listarAdjuntosDePersona",
			summary = "Listar los adjuntos de una persona",
			description = """
					RF-M25-005. Devuelve la METADATA, nunca el contenido: para descargar hay un \
					endpoint propio que autoriza cada llamada.

					Por defecto trae solo los VIGENTES. Los dados de baja se piden con \
					incluirDadosDeBaja y siguen resolviendo, que es RN-M07-004 aplicada al \
					documento en vez de a la persona.

					Una persona INACTIVA lista igual: sus documentos siguen siendo consultables.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Pagina de adjuntos",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AdjuntoPageResponse.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto de trabajo activo, o sin paciente:read",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La persona no existe, o es de otra "
					+ "organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public AdjuntoPageResponse listar(

			@Parameter(description = "Persona duenia de los adjuntos", example = "42")
			@PathVariable long personaId,

			@Parameter(description = "Filtro por clasificacion. Sin el, todas las categorias")
			@RequestParam(required = false) CategoriaAdjunto categoria,

			@Parameter(description = "Incluir tambien los dados de baja")
			@RequestParam(defaultValue = "false") boolean incluirDadosDeBaja,

			@Parameter(description = "Pagina, base cero")
			@RequestParam(defaultValue = PAGINA_POR_DEFECTO) int page,

			@Parameter(description = "Tamano de pagina, acotado a 100")
			@RequestParam(defaultValue = TAMANO_POR_DEFECTO) int size) {

		int pagina = Math.max(page, 0);
		int tamano = Math.min(Math.max(size, 1), TAMANO_MAXIMO);

		return AdjuntoPageResponse.of(
				adjuntoService.listar(apiActor.current(), personaId, categoria,
						incluirDadosDeBaja, pagina, tamano),
				pagina,
				tamano);
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "subirAdjuntoDePersona",
			summary = "Subir un documento administrativo",
			description = """
					RF-M25-001. El tipo se decide POR LOS BYTES y el Content-Type declarado se \
					descarta: lo elige quien sube, asi que no valida nada. Se aceptan PDF, PNG y \
					JPEG; todo lo demas es 400 archivo-no-aceptado con motivo TIPO_NO_PERMITIDO. \
					El tope de tamano da el mismo type con motivo DEMASIADO_GRANDE.

					ES IDEMPOTENTE. Subir dos veces el mismo archivo a la misma persona devuelve \
					200 con el adjunto que ya existe, no 201 y no 409. Una subida es la operacion \
					mas expuesta a reintentos del producto —mostrador, varios MB, timeouts— y un \
					reintento no deberia dejar dos filas que despues alguien desempata a ojo.

					UN ADJUNTO NO REEMPLAZA UN DATO ESTRUCTURADO (RN-M25-004): cargar la foto de \
					una credencial no carga la cobertura, y cargar el DNI escaneado no completa el \
					documento de la ficha.

					NO ES UN CONTENEDOR CLINICO (RN-M25-005). Las categorias son todas \
					administrativas y no hay ninguna clinica, a proposito: un estudio o un informe \
					vive en M09 con sus propios controles.

					Una persona dada de baja no admite documentos nuevos: 409 persona-inactiva. \
					Los que ya tiene se siguen descargando.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Adjunto creado",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AdjuntoResponse.class))),
			@ApiResponse(responseCode = "200",
					description = "El mismo archivo ya estaba cargado: se devuelve el existente",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AdjuntoResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "Tipo no permitido o archivo demasiado grande",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo activo, o sin paciente:manage",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La persona no existe, o es de otra "
					+ "organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "La persona esta dada de baja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AdjuntoResponse> subir(

			@Parameter(description = "Persona a la que se vincula el documento", example = "42")
			@PathVariable long personaId,

			@Parameter(description = "El archivo. PDF, PNG o JPEG")
			@RequestPart("archivo") MultipartFile archivo,

			@Parameter(description = "Clasificacion administrativa del documento")
			@RequestParam CategoriaAdjunto categoria,

			@Parameter(description = "Titulo con el que describirlo. Opcional")
			@RequestParam(required = false) String titulo) {

		AdjuntoAlta resultado = adjuntoService.subir(
				apiActor.current(),
				personaId,
				new AdjuntoAltaCommand(
						categoria,
						titulo,
						nombreDe(archivo),
						archivo.getContentType(),
						bytesDe(archivo)));

		AdjuntoResponse cuerpo = AdjuntoResponse.from(resultado.adjunto());
		if (!resultado.creado()) {
			// 200 y no 201: no se creo nada, este archivo ya estaba. Ver el javadoc del servicio.
			return ResponseEntity.ok(cuerpo);
		}

		log.debug("Adjunto creado por API: adjuntoId={}", cuerpo.id());
		return ResponseEntity
				.created(URI.create("/api/v1/personas/" + personaId + "/adjuntos/" + cuerpo.id()))
				.body(cuerpo);
	}

	@GetMapping(path = "/{adjuntoId}/contenido",
			produces = {MediaType.APPLICATION_OCTET_STREAM_VALUE,
					MediaType.APPLICATION_PROBLEM_JSON_VALUE})
	@Operation(
			operationId = "descargarAdjuntoDePersona",
			summary = "Descargar el contenido de un adjunto",
			description = """
					RF-M25-002. Entrega los bytes con el tipo DETECTADO al subir y el nombre \
					original en Content-Disposition.

					NO DEVUELVE NINGUNA RUTA INTERNA ni redirige a una URL firmada (RN-M25-002): \
					la autorizacion se evalua en cada llamada. El por que no hay URLs temporales \
                    esta en el javadoc de esta clase.

					SE DESCARGA AUNQUE EL ADJUNTO O LA PERSONA ESTEN DADOS DE BAJA. Es el caso \
					borde "descarga tras baja" y la respuesta correcta es permitirla: una baja \
					logica dice "esto ya no corresponde para operar", no "esto nunca existio".

					Si el almacenamiento perdio el binario, es 409 adjunto-no-disponible y no 404: \
					la metadata existe y quien pregunta la esta viendo en la lista.

					Cada descarga se AUDITA. Es el unico evento de lectura que este modulo \
					registra: un listado no entrega contenido, una descarga si.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El contenido del archivo",
					content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE)),
			@ApiResponse(responseCode = "403", description = "Sin contexto de trabajo activo, o sin paciente:read",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La persona o el adjunto no existen, o son de otra organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "El almacenamiento no tiene el contenido",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<byte[]> descargar(
			@PathVariable long personaId, @PathVariable long adjuntoId) {

		ContenidoDeAdjunto contenido =
				adjuntoService.contenido(apiActor.current(), personaId, adjuntoId);

		// attachment y NUNCA inline: servido inline, un archivo del mismo origen que la aplicacion
		// puede ejecutar script en su contexto. El sniffing de tipo ya cierra la puerta principal,
		// y esto es el cerrojo de atras.
		ContentDisposition disposicion = ContentDisposition.attachment()
				.filename(contenido.nombreArchivo(), StandardCharsets.UTF_8)
				.build();

		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, disposicion.toString())
				// X-Content-Type-Options impide que el navegador ignore el tipo que declaramos y
				// "adivine" uno ejecutable. Sin esto, la deteccion del servidor se puede saltear
				// del lado del cliente.
				.header("X-Content-Type-Options", "nosniff")
				.contentType(MediaType.parseMediaType(contenido.contentType()))
				.body(contenido.contenido());
	}

	@PatchMapping(path = "/{adjuntoId}", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "clasificarAdjuntoDePersona",
			summary = "Reclasificar un adjunto",
			description = """
					RF-M25-003. Edicion parcial: lo que no viene, no se toca.

					LO UNICO EDITABLE ES COMO ESTA DESCRIPTO. El contenido, su nombre y su tipo son \
					inmutables: reemplazar el archivo de una fila reescribiria un hecho. Para \
					reemplazar un documento se da de baja el viejo y se sube el nuevo, que deja \
					las dos versiones consultables.

					Un adjunto dado de baja responde 409: se sigue descargando, no se reclasifica.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Adjunto reclasificado",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AdjuntoResponse.class))),
			@ApiResponse(responseCode = "400", description = "Datos invalidos",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo activo, o sin paciente:manage",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La persona o el adjunto no existen, o son de otra organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "El adjunto esta dado de baja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public AdjuntoResponse clasificar(
			@PathVariable long personaId,
			@PathVariable long adjuntoId,
			@Valid @RequestBody ClasificarAdjuntoRequest request) {

		return AdjuntoResponse.from(adjuntoService.reclasificar(
				apiActor.current(), personaId, adjuntoId,
				request.categoria(), request.titulo()));
	}

	@DeleteMapping(path = "/{adjuntoId}", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "darDeBajaAdjuntoDePersona",
			summary = "Dar de baja un adjunto",
			description = """
					RF-M25-004. Baja LOGICA con motivo obligatorio.

					EL BINARIO NO SE BORRA. Es lo que hace que la baja sea reversible en los \
					hechos y lo que permite que el historico siga resolviendo: el contenido se \
					sigue pudiendo descargar. Un job de limpieza sobre contenidos realmente \
					huerfanos es otra cosa y otra etapa.

					Un adjunto ya dado de baja responde 409.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Adjunto dado de baja",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AdjuntoResponse.class))),
			@ApiResponse(responseCode = "400", description = "Falta el motivo de la baja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403",
					description = "Sin contexto de trabajo activo, o sin paciente:manage",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La persona o el adjunto no existen, o son de otra organizacion",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "El adjunto ya estaba dado de baja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public AdjuntoResponse darDeBajaAdjunto(
			@PathVariable long personaId,
			@PathVariable long adjuntoId,
			@Valid @RequestBody BajaDeAdjuntoRequest request) {

		log.info("Baja de adjunto solicitada: personaId={} adjuntoId={}", personaId, adjuntoId);
		return AdjuntoResponse.from(adjuntoService.darDeBaja(
				apiActor.current(), personaId, adjuntoId, request.motivo()));
	}

	/**
	 * El nombre original del archivo, saneado.
	 *
	 * <p>Se quedan solo el ultimo segmento y los caracteres razonables de un nombre. <b>No es lo
	 * que impide el path traversal</b> —eso lo garantiza que la ruta en disco se componga
	 * exclusivamente con la {@code storageKey}, que genera el servidor—: esto evita que un nombre
	 * con saltos de linea o comillas rompa la cabecera {@code Content-Disposition} de la descarga.
	 */
	private static String nombreDe(MultipartFile archivo) {
		String original = archivo.getOriginalFilename();
		if (original == null || original.isBlank()) {
			return "adjunto";
		}
		String ultimo = original.replace('\\', '/');
		ultimo = ultimo.substring(ultimo.lastIndexOf('/') + 1);
		String saneado = ultimo.replaceAll("[^A-Za-z0-9._ -]", "_").strip();
		if (saneado.isBlank()) {
			return "adjunto";
		}
		return saneado.length() > 255 ? saneado.substring(0, 255) : saneado;
	}

	/**
	 * Los bytes de la parte multipart.
	 *
	 * <p>Un fallo de lectura aca es un problema de infraestructura del request, no una regla de
	 * negocio: se propaga como {@link UncheckedIOException} y termina en el 500 generico del
	 * handler global, sin exponer nada.
	 */
	private static byte[] bytesDe(MultipartFile archivo) {
		try {
			return archivo.getBytes();
		} catch (IOException falla) {
			throw new UncheckedIOException("No se pudo leer el archivo subido", falla);
		}
	}
}
