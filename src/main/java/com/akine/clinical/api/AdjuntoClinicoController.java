package com.akine.clinical.api;

import com.akine.clinical.api.dto.AdjuntoClinicoPageResponse;
import com.akine.clinical.api.dto.AdjuntoClinicoResponse;
import com.akine.clinical.api.dto.BajaDeAdjuntoClinicoRequest;
import com.akine.clinical.api.dto.ReclasificarAdjuntoClinicoRequest;
import com.akine.clinical.application.AdjuntoClinicoAltaCommand;
import com.akine.clinical.application.AdjuntoClinicoService;
import com.akine.clinical.application.AdjuntoClinicoService.AdjuntoClinicoAlta;
import com.akine.clinical.application.ContenidoDeAdjuntoClinico;
import com.akine.clinical.domain.CategoriaAdjuntoClinico;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Documentos clinicos anclados a una Historia Clinica (M09/M25, AKINE-04.02).
 *
 * <h2>Por que no reusa los adjuntos de {@code person}</h2>
 *
 * <p>RN-M25-005 prohibe que una clase no clinica se convierta en contenedor clinico, y la
 * cabecera de V40 ya lo habia decidido: el dia que hicieran falta categorias clinicas "la tabla
 * es otra y el modulo tambien". Las diferencias no son cosmeticas — el ancla es la historia y no
 * la persona, el permiso es clinico y con acceso justificado, y la descarga se audita como acceso
 * clinico y no solo como descarga.
 *
 * <h2>La historia clinica viaja SIEMPRE, incluso en las rutas planas</h2>
 *
 * <p>Tres operaciones tienen ruta plana —{@code /adjuntos-clinicos/&#123;id&#125;}— y en las tres
 * la historia viaja como parametro obligatorio. <b>No es redundancia.</b> RN-M25-003 dice que el
 * acceso hereda los permisos de la entidad asociada, y la entidad asociada de un adjunto clinico
 * es la historia: es la ficha sobre la que se evalua {@code hc:read}/{@code hc:write}, la relacion
 * asistencial y la justificacion. Sin ella no hay nada contra que autorizar, asi que el parametro
 * es {@code required} y no tiene default.
 *
 * <p>Un adjunto que se pide con la historia equivocada responde <b>404</b>, no 400: un 400
 * distinguiria "ese adjunto no existe" de "existe pero es de otro paciente", que es justamente lo
 * que no se puede confirmar.
 *
 * <h2>La descarga, y las dos cabeceras que la protegen</h2>
 *
 * <p>{@code Content-Disposition: attachment} y {@code X-Content-Type-Options: nosniff}. La
 * primera impide que un archivo servido desde el mismo origen que la aplicacion ejecute script en
 * su contexto; la segunda impide que el navegador ignore el tipo declarado y adivine uno
 * ejecutable. La deteccion de tipo por bytes del servidor cierra la puerta principal y estas dos
 * son el cerrojo de atras.
 *
 * <p><b>No hay URL temporal firmada</b>, y es una decision. Con almacenamiento local el binario
 * pasa por la aplicacion igual, asi que firmar una URL solo agregaria un segundo camino de
 * autorizacion mas debil —un token en la query string que se copia, se comparte, queda en los
 * logs del proxy, y que no se puede revocar— y, sobre todo, <b>una via de descarga que el evento
 * de auditoria no veria</b>. La {@code storageKey} no sale del backend por ningun campo.
 */
@RestController
@Tag(name = "Adjuntos clinicos",
		description = "Estudios, informes y documentos de una Historia Clinica (M09/M25)")
public class AdjuntoClinicoController {

	private static final Logger log = LoggerFactory.getLogger(AdjuntoClinicoController.class);

	private static final String POR_HISTORIA =
			"/api/v1/historias-clinicas/{historiaClinicaId}/adjuntos";
	private static final String POR_ADJUNTO = "/api/v1/adjuntos-clinicos/{adjuntoId}";

	private static final String PAGINA_POR_DEFECTO = "0";
	private static final String TAMANO_POR_DEFECTO = "20";
	private static final int TAMANO_MAXIMO = 100;

	private static final String HISTORIA_DOC =
			"Historia clinica de la que cuelga el adjunto. OBLIGATORIA: es la ficha cuya "
					+ "autorizacion clinica se evalua (RN-M25-003), no un dato de navegacion. Un "
					+ "adjunto pedido con la historia equivocada responde 404";

	private final AdjuntoClinicoService adjuntoService;
	private final ClinicalApiActor apiActor;

	public AdjuntoClinicoController(
			AdjuntoClinicoService adjuntoService, ClinicalApiActor apiActor) {

		this.adjuntoService = adjuntoService;
		this.apiActor = apiActor;
	}

	// =================================================================================
	// Colgados de la historia
	// =================================================================================

	@PostMapping(path = POR_HISTORIA,
			consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "subirAdjuntoClinico",
			summary = "Subir un documento clinico",
			description = """
					RF-M25-001 aplicado a lo clinico. El tipo se decide POR LOS BYTES y el \
					Content-Type declarado se descarta: lo elige quien sube, asi que no valida \
					nada. Lo que no entra es 400 archivo-no-aceptado, con motivo \
					TIPO_NO_PERMITIDO o DEMASIADO_GRANDE.

					ES IDEMPOTENTE. Subir dos veces el mismo archivo a la misma historia \
					devuelve 200 con el adjunto que ya existe, no 201 y no 409. Una subida es la \
					operacion mas expuesta a reintentos del producto —mostrador, varios MB, \
					timeouts— y un reintento no deberia dejar dos estudios que despues alguien \
					desempata a ojo. La idempotencia la decide el SHA-256 del contenido.

					EL ANCLA ES LA HISTORIA, NO LA PERSONA. entradaClinicaId es opcional y \
					vincula el documento a un hecho clinico concreto; si apunta a una entrada de \
					OTRA historia, es 404.

					NO HAY ANALISIS ANTIMALWARE: el plan lo pide "cuando exista infraestructura", \
					y no existe. Lo que si hay es lista blanca de tipos detectados por bytes, \
					tope de tamano, y attachment + nosniff en la descarga.

					Exige hc:write mas relacion asistencial o motivo declarado, y queda auditada \
					como ADJUNTO_CLINICO_UPLOADED.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Adjunto clinico creado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AdjuntoClinicoResponse.class))),
			@ApiResponse(responseCode = "200",
					description = "El mismo archivo ya estaba cargado en esta historia: se "
							+ "devuelve el existente",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AdjuntoClinicoResponse.class))),
			@ApiResponse(responseCode = "400",
					description = "Tipo no permitido, archivo vacio o demasiado grande",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:write, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La historia no es accesible, o la entrada indicada no es de "
							+ "esta historia",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<AdjuntoClinicoResponse> subir(

			@Parameter(description = "Historia clinica a la que se vincula", example = "88")
			@PathVariable long historiaClinicaId,

			@Parameter(description = "El archivo. El tipo lo decide el servidor por los bytes")
			@RequestPart("archivo") MultipartFile archivo,

			@Parameter(description = "Clasificacion clinica del documento")
			@RequestParam CategoriaAdjuntoClinico categoria,

			@Parameter(description = "Entrada clinica que el documento respalda. Opcional")
			@RequestParam(required = false) Long entradaClinicaId,

			@Parameter(description = "Titulo con el que describirlo. Opcional")
			@RequestParam(required = false) String titulo,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		AdjuntoClinicoAlta resultado = adjuntoService.subir(
				apiActor.current(),
				historiaClinicaId,
				new AdjuntoClinicoAltaCommand(
						categoria,
						entradaClinicaId,
						titulo,
						nombreDe(archivo),
						archivo.getContentType(),
						bytesDe(archivo)),
				justificacion);

		AdjuntoClinicoResponse cuerpo = AdjuntoClinicoResponse.from(resultado.adjunto());
		if (!resultado.creado()) {
			// 200 y no 201: no se creo nada, este archivo ya estaba. Ver el javadoc del servicio.
			return ResponseEntity.ok(cuerpo);
		}

		log.debug("Adjunto clinico creado por API: adjuntoId={}", cuerpo.id());
		return ResponseEntity
				.created(URI.create("/api/v1/adjuntos-clinicos/" + cuerpo.id()))
				.body(cuerpo);
	}

	@GetMapping(path = POR_HISTORIA, produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "listarAdjuntosClinicos",
			summary = "Listar los documentos clinicos de una Historia Clinica",
			description = """
					RF-M25-005. Devuelve la METADATA, nunca el contenido: para descargar hay un \
					endpoint propio que autoriza y audita cada llamada.

					Por defecto trae solo los VIGENTES. Los dados de baja se piden con \
					incluirDadosDeBaja y siguen resolviendo (regla maestra 10).

					NO TRAE NINGUNA RUTA NI LA CLAVE DE ALMACENAMIENTO (RN-M25-002).

					Es lectura clinica: exige hc:read mas relacion asistencial o motivo \
					declarado, y queda auditada. Lo que revela —cuantos estudios tiene este \
					paciente y de que clase— ya es informacion sensible aunque no sea contenido.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Pagina de adjuntos clinicos",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(
									implementation = AdjuntoClinicoPageResponse.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:read, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "La historia no es accesible",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	@SuppressWarnings("checkstyle:ParameterNumber")
	public AdjuntoClinicoPageResponse listar(

			@Parameter(description = "Historia clinica cuyos adjuntos se piden", example = "88")
			@PathVariable long historiaClinicaId,

			@Parameter(description = "Filtro por clasificacion. Sin el, todas las categorias")
			@RequestParam(required = false) CategoriaAdjuntoClinico categoria,

			@Parameter(description = "Filtro por la entrada clinica que respaldan")
			@RequestParam(required = false) Long entradaClinicaId,

			@Parameter(description = "Incluir tambien los dados de baja")
			@RequestParam(defaultValue = "false") boolean incluirDadosDeBaja,

			@Parameter(description = "Pagina, base cero")
			@RequestParam(defaultValue = PAGINA_POR_DEFECTO) int page,

			@Parameter(description = "Tamano de pagina, acotado a 100")
			@RequestParam(defaultValue = TAMANO_POR_DEFECTO) int size,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		int pagina = Math.max(page, 0);
		int tamano = Math.min(Math.max(size, 1), TAMANO_MAXIMO);

		return AdjuntoClinicoPageResponse.of(
				adjuntoService.listar(apiActor.current(), historiaClinicaId, categoria,
						entradaClinicaId, incluirDadosDeBaja, pagina, tamano, justificacion),
				pagina,
				tamano);
	}

	// =================================================================================
	// Sobre un adjunto existente
	// =================================================================================

	@GetMapping(path = POR_ADJUNTO + "/contenido",
			produces = {MediaType.APPLICATION_OCTET_STREAM_VALUE,
					MediaType.APPLICATION_PROBLEM_JSON_VALUE})
	@Operation(
			operationId = "descargarAdjuntoClinico",
			summary = "Descargar el contenido de un documento clinico",
			description = """
					RF-M25-002. Entrega los bytes con el tipo DETECTADO al subir y el nombre \
					original en Content-Disposition: attachment, mas X-Content-Type-Options: \
					nosniff.

					NO DEVUELVE NINGUNA RUTA INTERNA ni redirige a una URL firmada (RN-M25-002): \
					la autorizacion se evalua en cada llamada. El por que no hay URLs temporales \
					esta en el javadoc de esta clase.

					SE DESCARGA AUNQUE EL ADJUNTO ESTE DADO DE BAJA. Una baja logica dice "esto \
					ya no corresponde para operar", no "esto nunca existio": negar la descarga \
					convertiria la baja en un borrado con otro nombre.

					Si el almacenamiento perdio el binario es 409 adjunto-clinico-no-disponible y \
					no 404: la metadata existe y quien pregunta la esta viendo en la lista. La \
					fila ademas queda marcada NO_DISPONIBLE, para que el problema se vea en el \
					listado y no solo al intentar descargar.

					CADA DESCARGA SE AUDITA COMO ADJUNTO_CLINICO_DOWNLOADED, y ese evento es el \
					que justifica la etapa entera desde el lado de seguridad: es el momento en \
					que un estudio clinico SALE del sistema. Un estudio descargado y reenviado es \
					la fuga mas barata que tiene un sistema clinico, y sin el evento no hay forma \
					de revisarla despues.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "El contenido del archivo",
					content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE)),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:read, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La historia o el adjunto no son accesibles",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409",
					description = "El almacenamiento no tiene el contenido",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<byte[]> descargar(

			@Parameter(description = "Adjunto clinico a descargar", example = "17")
			@PathVariable long adjuntoId,

			@Parameter(description = HISTORIA_DOC, example = "88")
			@RequestParam long historiaClinicaId,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		ContenidoDeAdjuntoClinico contenido = adjuntoService.contenido(
				apiActor.current(), historiaClinicaId, adjuntoId, justificacion);

		// attachment y NUNCA inline: servido inline, un archivo del mismo origen que la aplicacion
		// puede ejecutar script en su contexto. El sniffing de tipo del servidor ya cierra la
		// puerta principal, y esto es el cerrojo de atras.
		ContentDisposition disposicion = ContentDisposition.attachment()
				.filename(contenido.nombreArchivo(), StandardCharsets.UTF_8)
				.build();

		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, disposicion.toString())
				// Impide que el navegador ignore el tipo que declaramos y "adivine" uno
				// ejecutable. Sin esto, la deteccion del servidor se puede saltear del lado del
				// cliente.
				.header("X-Content-Type-Options", "nosniff")
				.contentType(MediaType.parseMediaType(contenido.contentType()))
				.body(contenido.contenido());
	}

	@PatchMapping(path = POR_ADJUNTO,
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "reclasificarAdjuntoClinico",
			summary = "Reclasificar un documento clinico",
			description = """
					RF-M25-003. Edicion parcial: lo que no viene, no se toca.

					LO UNICO EDITABLE ES COMO ESTA DESCRIPTO —categoria y titulo—. El contenido, \
					su nombre, su tipo y la entrada que respalda son inmutables: reemplazar el \
					archivo de una fila reescribiria un hecho clinico. Para reemplazar un \
					documento se da de baja el viejo y se sube el nuevo, que deja las dos \
					decisiones consultables.

					Un adjunto dado de baja responde 409: se sigue descargando, no se \
					reclasifica.

					EL TITULO NUEVO NO SE COPIA A LA AUDITORIA, solo se registra que cambio. Lo \
					escribe un profesional sobre un documento clinico y puede describir el \
					hallazgo; audit_event se consulta con auditoria:read, que no es un permiso \
					clinico.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Adjunto reclasificado",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AdjuntoClinicoResponse.class))),
			@ApiResponse(responseCode = "400", description = "Datos invalidos",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:write, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La historia o el adjunto no son accesibles",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "409", description = "El adjunto esta dado de baja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public AdjuntoClinicoResponse reclasificar(

			@Parameter(description = "Adjunto clinico a reclasificar", example = "17")
			@PathVariable long adjuntoId,

			@Parameter(description = HISTORIA_DOC, example = "88")
			@RequestParam long historiaClinicaId,

			@Valid @RequestBody ReclasificarAdjuntoClinicoRequest request,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		return AdjuntoClinicoResponse.from(adjuntoService.reclasificar(
				apiActor.current(),
				historiaClinicaId,
				adjuntoId,
				request.categoria(),
				request.titulo(),
				justificacion));
	}

	@DeleteMapping(path = POR_ADJUNTO,
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			operationId = "darDeBajaAdjuntoClinico",
			summary = "Dar de baja un documento clinico",
			description = """
					RF-M25-004. Baja LOGICA con motivo obligatorio.

					EL BINARIO NO SE BORRA DEL DISCO. Es lo que hace reversible en los hechos una \
					baja por error sobre un estudio clinico, y lo que permite que el historico \
					siga resolviendo: el contenido se sigue descargando. Un job de limpieza sobre \
					contenidos realmente huerfanos es otra cosa y necesita una politica de \
					retencion que nadie escribio.

					REPETIR LA BAJA NO ES UN CONFLICTO: es el mismo pedido, y se responde 200 con \
					el adjunto tal como quedo, con su motivo ORIGINAL intacto. Pisarlo con el \
					nuevo perderia el que explica la baja.

					Devuelve 200 con el adjunto y no 204: quien lo da de baja necesita ver como \
					quedo sin tener que pedirlo de nuevo.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Adjunto dado de baja",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AdjuntoClinicoResponse.class))),
			@ApiResponse(responseCode = "400", description = "Falta el motivo de la baja",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Sin contexto, sin hc:write, o sin "
					+ "relacion asistencial ni motivo declarado",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404",
					description = "La historia o el adjunto no son accesibles",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))})
	public AdjuntoClinicoResponse darDeBaja(

			@Parameter(description = "Adjunto clinico a dar de baja", example = "17")
			@PathVariable long adjuntoId,

			@Parameter(description = HISTORIA_DOC, example = "88")
			@RequestParam long historiaClinicaId,

			@Valid @RequestBody BajaDeAdjuntoClinicoRequest request,

			@Parameter(description = AccesoClinicoHeaders.JUSTIFICACION_DOC)
			@RequestHeader(name = AccesoClinicoHeaders.JUSTIFICACION, required = false)
			String justificacion) {

		log.info("Baja de adjunto clinico solicitada: historiaClinicaId={} adjuntoId={}",
				historiaClinicaId, adjuntoId);
		return AdjuntoClinicoResponse.from(adjuntoService.darDeBaja(
				apiActor.current(), historiaClinicaId, adjuntoId, request.motivo(),
				justificacion));
	}

	// =================================================================================
	// Traduccion de la parte multipart
	// =================================================================================

	/**
	 * El nombre original del archivo, saneado.
	 *
	 * <p><b>No es lo que impide el path traversal</b> —eso lo garantiza que la ruta en disco se
	 * componga exclusivamente con la {@code storageKey}, que genera el servidor—: esto evita que
	 * un nombre con saltos de linea o comillas rompa la cabecera {@code Content-Disposition} de
	 * la descarga.
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
	 * <p>Traducir la parte a bytes es trabajo de {@code api} y no del servicio: ArchUnit prohibe
	 * que {@code application} importe {@code org.springframework.web..}, y la regla existe porque
	 * las reglas de negocio tienen que poder ejecutarse desde un job o un consumidor de eventos.
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
