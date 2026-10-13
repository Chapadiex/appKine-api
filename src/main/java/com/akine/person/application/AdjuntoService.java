package com.akine.person.application;

import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.domain.AdjuntoAdministrativo;
import com.akine.person.domain.CategoriaAdjunto;
import com.akine.person.domain.Persona;
import com.akine.person.domain.TipoDeArchivo;
import com.akine.person.domain.exception.AdjuntoInactivoException;
import com.akine.person.domain.exception.AdjuntoNoDisponibleException;
import com.akine.person.domain.exception.AdjuntoNotAccessibleException;
import com.akine.person.domain.exception.ArchivoNoAceptadoException;
import com.akine.person.domain.exception.PersonaInactivaException;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
import com.akine.person.domain.port.AdjuntoStoragePort;
import com.akine.person.domain.port.PersonRepositoryPorts.AdjuntoRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.transaccion.AlTerminarLaTransaccion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Adjuntos administrativos de una Persona (RF-M07-006, RF-M25-001..005).
 *
 * <h2>Las dos formas de autorizar, y por que no son la misma</h2>
 *
 * <p>RN-M25-003 dice que "el acceso hereda permisos de la entidad asociada", y eso se toma al pie
 * de la letra: <b>leer y descargar un adjunto se autoriza igual que leer la ficha de su
 * persona</b> —{@code paciente:read} desde AKINE-DU-6 (DP-22)— y <b>subir, reclasificar y dar
 * de baja se autoriza igual que editar esa ficha</b>, con {@code paciente:manage} sobre la sede del contexto.
 *
 * <p>La alternativa que se descarto: exigir {@code paciente:manage} tambien para leer. Dejaria al
 * {@code PROFESIONAL} sin poder abrir el consentimiento firmado del paciente que esta por
 * atender, cuando ese mismo profesional puede leer la ficha entera. Un adjunto mas restringido
 * que la entidad de la que cuelga no es "mas seguro": es una regla distinta a la que la
 * especificacion escribio, inventada por una etapa.
 *
 * <h2>Lo que se valida y en que orden</h2>
 *
 * <ol>
 *   <li><b>Tamano</b> primero, porque es el chequeo barato y porque un archivo de 300 MB no vale
 *       la pena hashearlo para despues rechazarlo.</li>
 *   <li><b>Tipo real por los bytes</b>. El {@code Content-Type} declarado se descarta: lo elige
 *       quien sube. Ver {@link TipoDeArchivo}, que explica el caso borde "archivo malicioso".</li>
 *   <li><b>Estado de la persona</b>. Una ficha dada de baja no admite documentos nuevos: seria
 *       seguir operando sobre algo que se declaro cerrado. Lo que si admite es que se DESCARGUEN
 *       los que ya tiene, que es el caso borde "descarga tras baja" de la etapa.</li>
 * </ol>
 *
 * <h2>La subida es idempotente, y eso es una decision</h2>
 *
 * <p>Subir dos veces el mismo archivo a la misma persona devuelve <b>200 con el adjunto que ya
 * existe</b>, no 201 y no 409. Una subida es la operacion mas expuesta a reintentos que tiene el
 * producto —conexion de mostrador, varios MB, timeouts— y un reintento de un POST que si habia
 * llegado no deberia dejar dos filas que despues alguien desempata a ojo. El invariante lo hace
 * cumplir el unique de {@code V40}, no un pre-chequeo: el pre-chequeo esta igual porque ahorra
 * escribir el binario, pero <b>no es el que garantiza nada</b>.
 *
 * <p>Y el INSERT que puede chocar contra ese unique corre en una <b>transaccion propia</b>
 * ({@link AdjuntoEscrituraAparte}): atrapar el choque dentro de la transaccion de negocio no la
 * des-marca, y la idempotencia prometida terminaba siendo un 500. Es la regla 2 del Paquete B.
 */
@Service
public class AdjuntoService {

	private static final Logger log = LoggerFactory.getLogger(AdjuntoService.class);

	private static final String MOTIVO_TIPO = "TIPO_NO_PERMITIDO";
	private static final String MOTIVO_TAMANO = "DEMASIADO_GRANDE";

	private final PersonaRepositoryPort personas;
	private final AdjuntoRepositoryPort adjuntos;
	private final AdjuntoStoragePort storage;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;
	private final PersonSupportAccessAuditor supportAccessAuditor;
	private final AdjuntoEscrituraAparte escrituraAparte;
	private final TransactionTemplate transaccion;

	@SuppressWarnings("checkstyle:ParameterNumber")
	public AdjuntoService(
			PersonaRepositoryPort personas,
			AdjuntoRepositoryPort adjuntos,
			AdjuntoStoragePort storage,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail,
			PersonSupportAccessAuditor supportAccessAuditor,
			AdjuntoEscrituraAparte escrituraAparte,
			PlatformTransactionManager transactionManager) {

		this.personas = personas;
		this.adjuntos = adjuntos;
		this.storage = storage;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
		this.supportAccessAuditor = supportAccessAuditor;
		this.escrituraAparte = escrituraAparte;
		this.transaccion = new TransactionTemplate(transactionManager);
	}

	// =================================================================================
	// Lecturas — paciente:read, igual que la ficha (RN-M25-003, DP-22)
	// =================================================================================

	/**
	 * Los adjuntos de una persona (RF-M25-005), paginados y opcionalmente filtrados por categoria.
	 *
	 * <p>Por defecto trae solo los VIGENTES: la ficha del mostrador no tiene por que mostrar lo que
	 * alguien dio de baja. Los de baja se piden explicitamente y siguen resolviendo, que es
	 * RN-M07-004 aplicada al documento en vez de a la persona.
	 */
	@Transactional(readOnly = true)
	public AdjuntoPagina listar(
			OperatingActor actor,
			long personaId,
			CategoriaAdjunto categoria,
			boolean incluirDadosDeBaja,
			int page,
			int size) {

		long organizationId = AutorizacionDePadron.exigirLecturaDelPadron(
				permissionGuard, actor, "Listar adjuntos");
		exigirPersonaAccesible(organizationId, personaId);

		String filtroCategoria = categoria == null ? null : categoria.name();
		int activoFiltro = incluirDadosDeBaja ? -1 : 1;

		List<AdjuntoView> contenido = adjuntos
				.listar(organizationId, personaId, filtroCategoria, activoFiltro,
						page * size, size)
				.stream()
				.map(AdjuntoView::de)
				.toList();

		long total = adjuntos.contar(organizationId, personaId, filtroCategoria, activoFiltro);
		return new AdjuntoPagina(contenido, total);
	}

	/**
	 * El contenido de un adjunto, para descargarlo (RF-M25-002).
	 *
	 * <p><b>Se descarga aunque el adjunto o la persona esten dados de baja.</b> Es el caso borde
	 * "descarga tras baja" de la etapa y la respuesta correcta es permitirla: una baja logica dice
	 * "esto ya no corresponde para operar", no "esto nunca existio". Negarla convertiria a la baja
	 * en un borrado con otro nombre, que es exactamente lo que RN-M07-004 prohibe.
	 *
	 * <p>Si el almacenamiento no tiene el binario, es <b>409 y no 404</b>: la metadata existe y
	 * quien pregunta la esta viendo en la lista.
	 *
	 * <p><b>No es {@code readOnly}, aunque no modifique el adjunto.</b> Escribe
	 * {@code ADJUNTO_DOWNLOADED}, y {@code AuditTrail.record} es {@code MANDATORY}: se une a esta
	 * transaccion. Con {@code readOnly = true} la conexion sale marcada de solo lectura y MySQL
	 * rechaza el INSERT ("Connection is read-only"): toda descarga respondia 500 desde 03.02
	 * (simulacro de G-13). La auditoria va en la transaccion del negocio, como manda T-2; lo que
	 * se corrige es la transaccion, no la auditoria. Lo fija {@code LecturaAuditadaIT}.
	 */
	@Transactional
	public ContenidoDeAdjunto contenido(OperatingActor actor, long personaId, long adjuntoId) {
		long organizationId = AutorizacionDePadron.exigirLecturaDelPadron(
				permissionGuard, actor, "Descargar adjunto");
		exigirPersonaAccesible(organizationId, personaId);

		AdjuntoAdministrativo adjunto = cargar(organizationId, personaId, adjuntoId);
		if (!adjunto.isDescargable()) {
			throw new AdjuntoNoDisponibleException(adjuntoId);
		}

		byte[] bytes = storage.leer(adjunto.getStorageKey())
				.orElseThrow(() -> new AdjuntoNoDisponibleException(adjuntoId));

		// La descarga se audita: es el momento en que un documento personal sale del sistema, y
		// la pregunta "quien se llevo el DNI de este paciente" tiene que poder responderse. NO se
		// audita el listado, que no entrega ningun contenido y llenaria la tabla con ruido.
		auditar(AuditEvents.ADJUNTO_DOWNLOADED, adjunto, actor, Map.of(), null, Instant.now());

		return new ContenidoDeAdjunto(
				adjunto.getNombreArchivo(), adjunto.getContentType(), bytes);
	}

	/** Cuantos adjuntos vigentes tiene la persona, por categoria. Lo consume el Paciente 360. */
	@Transactional(readOnly = true)
	public Map<String, Long> conteoPorCategoria(long organizationId, long personaId) {
		Map<String, Long> conteo = new LinkedHashMap<>();
		for (Object[] fila : adjuntos.contarVigentesPorCategoria(organizationId, personaId)) {
			conteo.put(String.valueOf(fila[0]), ((Number) fila[1]).longValue());
		}
		return conteo;
	}

	// =================================================================================
	// Mutaciones — paciente:manage sobre la sede del contexto
	// =================================================================================

	/**
	 * Sube un documento administrativo (RF-M25-001).
	 *
	 * <p><b>Sin transaccion de afuera</b>, por la misma causa y con los mismos cuatro pasos que
	 * {@code AdjuntoClinicoService#subir} (docs/carga.md §8): una transaccion que autoriza, valida
	 * y busca por checksum; el INSERT en la suya ({@link AdjuntoEscrituraAparte}); el binario sin
	 * conexion tomada; y la auditoria en una transaccion final. Hasta el 11/10/2026 era una
	 * transaccion en {@code READ_COMMITTED} que envolvia al INSERT en {@code REQUIRES_NEW} y retenia
	 * dos conexiones por subida. La relectura del perdedor de la carrera es ahora una transaccion
	 * nueva, que ve el commit del ganador sin necesitar {@code READ_COMMITTED} —el defecto que
	 * destapo {@code AdjuntoConcurrenteIT} sigue cerrado por otro camino—.
	 *
	 * @return el adjunto creado, o el que ya existia si el contenido es identico (idempotencia)
	 */
	public AdjuntoAlta subir(OperatingActor actor, long personaId, AdjuntoAltaCommand command) {
		AlTerminarLaTransaccion.exigirSinTransaccion("La subida de un adjunto administrativo");
		Subida subida = transaccion.execute(estado -> prepararSubida(actor, personaId, command));
		if (subida.existente() != null) {
			return new AdjuntoAlta(AdjuntoView.de(subida.existente()), false);
		}

		AdjuntoAdministrativo adjunto = subida.nuevo();
		long organizationId = adjunto.getOrganizationId();

		AdjuntoAdministrativo guardado;
		try {
			// Fila primero, con flush, para que el unique decida antes de tocar el disco. En una
			// TRANSACCION PROPIA: si el flush choca contra el unique, Hibernate marca rollbackOnly
			// antes de que la excepcion salga, y atraparla no des-marca nada (regla 2 del Paquete B).
			guardado = escrituraAparte.insertar(adjunto);
		} catch (DataIntegrityViolationException choque) {
			// Murio la transaccion del INSERT y ninguna otra: la relectura abre una nueva.
			log.info("Subida concurrente del mismo contenido resuelta como idempotente: "
					+ "personaId={}", personaId);
			return transaccion.execute(estado -> adjuntos.buscarVigentePorChecksum(
							organizationId, personaId, adjunto.getChecksumSha256()))
					.map(existente -> new AdjuntoAlta(AdjuntoView.de(existente), false))
					.orElseThrow(() -> choque);
		}

		// Blob despues. El orden inverso produciria filas apuntando a nada, que es el unico de los
		// dos errores que el usuario ve. Ver V40.
		//
		// Como la fila ya esta commiteada, un fallo del blob NO se revierte solo: la fila queda, y
		// si se la dejara DISPONIBLE el listado afirmaria tener un documento que no se puede
		// descargar. Se la marca NO_DISPONIBLE, que es la verdad, y se propaga el fallo.
		try {
			storage.guardar(adjunto.getStorageKey(), command.contenido());
		} catch (RuntimeException falla) {
			escrituraAparte.marcarNoDisponible(organizationId, personaId, guardado.getId());
			throw falla;
		}

		transaccion.executeWithoutResult(estado -> {
			Map<String, String> detalles = new LinkedHashMap<>();
			detalles.put("categoria", guardado.getCategoria().name());
			detalles.put("contentType", guardado.getContentType());
			detalles.put("tamanoBytes", String.valueOf(command.contenido().length));
			auditar(AuditEvents.ADJUNTO_UPLOADED, guardado, actor, detalles, null, subida.ahora());
			registrarSoporte(subida.decision(), actor, guardado, subida.ahora(), "Subir adjunto");
		});

		log.info("Adjunto administrativo cargado: adjuntoId={} personaId={} organizationId={}",
				guardado.getId(), personaId, organizationId);
		return new AdjuntoAlta(AdjuntoView.de(guardado), true);
	}

	/** Lo que la primera transaccion de la subida decide: el existente, o el nuevo a insertar. */
	private record Subida(
			AdjuntoAdministrativo existente, AdjuntoAdministrativo nuevo,
			PermissionDecision decision, Instant ahora) {
	}

	/** Paso 1 de {@link #subir}: autoriza, valida y busca por checksum. No escribe nada. */
	private Subida prepararSubida(OperatingActor actor, long personaId, AdjuntoAltaCommand command) {
		PermissionDecision decision =
				AutorizacionDePadron.exigirGestionDelPadron(permissionGuard, actor, "Subir adjunto");
		long organizationId = actor.contextOrganizationId();

		Persona persona = exigirPersonaAccesible(organizationId, personaId);
		if (!persona.isOperable()) {
			throw new PersonaInactivaException(personaId, "adjuntar documentos");
		}

		byte[] contenido = command.contenido();
		exigirTamanoAceptable(contenido, command);
		String contentType = exigirTipoPermitido(contenido, command);
		String checksum = sha256(contenido);

		Optional<AdjuntoAdministrativo> yaExistente =
				adjuntos.buscarVigentePorChecksum(organizationId, personaId, checksum);
		if (yaExistente.isPresent()) {
			log.debug("Subida idempotente resuelta con el adjunto existente: personaId={}",
					personaId);
			return new Subida(yaExistente.get(), null, decision, null);
		}

		Instant ahora = Instant.now();
		String storageKey = UUID.randomUUID().toString().replace("-", "");

		AdjuntoAdministrativo adjunto = new AdjuntoAdministrativo(
				organizationId,
				personaId,
				actor.consultorioId(),
				command.categoria(),
				command.titulo(),
				command.nombreArchivo(),
				contentType,
				contenido.length,
				checksum,
				storageKey,
				actor.accountId(),
				ahora);
		return new Subida(null, adjunto, decision, ahora);
	}

	/**
	 * Reclasifica un adjunto (RF-M25-003).
	 *
	 * <p>Lo unico editable es COMO esta descripto: categoria y titulo. El contenido, su nombre y su
	 * tipo son inmutables —{@code updatable = false} en la entity— porque reemplazar el archivo de
	 * una fila reescribiria un hecho; la forma de reemplazar un documento es dar de baja el viejo y
	 * subir el nuevo, que deja las dos versiones consultables.
	 */
	@Transactional
	public AdjuntoView reclasificar(
			OperatingActor actor,
			long personaId,
			long adjuntoId,
			CategoriaAdjunto categoria,
			String titulo) {

		PermissionDecision decision = AutorizacionDePadron.exigirGestionDelPadron(
				permissionGuard, actor, "Reclasificar adjunto");
		long organizationId = actor.contextOrganizationId();

		exigirPersonaAccesible(organizationId, personaId);
		AdjuntoAdministrativo adjunto = cargar(organizationId, personaId, adjuntoId);
		if (!adjunto.isVigente()) {
			throw new AdjuntoInactivoException(adjuntoId);
		}

		Map<String, String> detalles = new LinkedHashMap<>();
		if (categoria != null && categoria != adjunto.getCategoria()) {
			detalles.put("categoriaAnterior", adjunto.getCategoria().name());
			detalles.put("categoria", categoria.name());
		}
		if (titulo != null) {
			detalles.put("titulo", "modificado");
		}

		adjunto.reclasificar(categoria, titulo);
		AdjuntoAdministrativo guardado = adjuntos.save(adjunto);

		Instant ahora = Instant.now();
		auditar(AuditEvents.ADJUNTO_RECLASSIFIED, guardado, actor, detalles, null, ahora);
		registrarSoporte(decision, actor, guardado, ahora, "Reclasificar adjunto");
		return AdjuntoView.de(guardado);
	}

	/**
	 * Da de baja un adjunto (RF-M25-004). Baja LOGICA con motivo obligatorio.
	 *
	 * <p>El binario NO se borra. Es lo que hace que la baja sea reversible en los hechos y lo que
	 * permite que el historico siga resolviendo; un job de limpieza sobre contenidos realmente
	 * huerfanos es otra cosa y otra etapa.
	 */
	@Transactional
	public AdjuntoView darDeBaja(
			OperatingActor actor, long personaId, long adjuntoId, String motivo) {

		PermissionDecision decision = AutorizacionDePadron.exigirGestionDelPadron(
				permissionGuard, actor, "Dar de baja un adjunto");
		long organizationId = actor.contextOrganizationId();

		exigirPersonaAccesible(organizationId, personaId);
		AdjuntoAdministrativo adjunto = cargar(organizationId, personaId, adjuntoId);
		if (!adjunto.isVigente()) {
			throw new AdjuntoInactivoException(adjuntoId);
		}

		Instant ahora = Instant.now();
		adjunto.deactivate(ahora, motivo);
		AdjuntoAdministrativo guardado = adjuntos.save(adjunto);

		auditar(AuditEvents.ADJUNTO_DEACTIVATED, guardado, actor, Map.of(), motivo, ahora);
		registrarSoporte(decision, actor, guardado, ahora, "Dar de baja un adjunto");

		log.info("Adjunto dado de baja: adjuntoId={} personaId={}", adjuntoId, personaId);
		return AdjuntoView.de(guardado);
	}

	// =================================================================================
	// Invariantes y utilidades
	// =================================================================================

	private Persona exigirPersonaAccesible(long organizationId, long personaId) {
		return personas.findByIdAndOrganizationId(personaId, organizationId)
				.orElseThrow(() -> new PersonaNotAccessibleException(personaId));
	}

	private AdjuntoAdministrativo cargar(long organizationId, long personaId, long adjuntoId) {
		return adjuntos.buscarDeLaPersona(organizationId, personaId, adjuntoId)
				.orElseThrow(() -> new AdjuntoNotAccessibleException(adjuntoId));
	}

	private void exigirTamanoAceptable(byte[] contenido, AdjuntoAltaCommand command) {
		if (contenido == null || contenido.length == 0) {
			throw new ArchivoNoAceptadoException(MOTIVO_TIPO, "El archivo esta vacio.");
		}
		if (contenido.length > storage.tamanoMaximo()) {
			log.info("Adjunto rechazado por tamano: bytes={} tope={}",
					contenido.length, storage.tamanoMaximo());
			throw new ArchivoNoAceptadoException(MOTIVO_TAMANO,
					"El archivo supera el tamano maximo de " + storage.tamanoMaximo() + " bytes.");
		}
	}

	private static String exigirTipoPermitido(byte[] contenido, AdjuntoAltaCommand command) {
		String detectado = TipoDeArchivo.detectar(contenido);
		if (detectado == null) {
			// El declarado se loguea porque "dijo PDF y era otra cosa" es informacion util de
			// diagnostico. NO se usa para decidir: lo elige quien sube.
			log.info("Adjunto rechazado por tipo: declarado={}", command.contentTypeDeclarado());
			throw new ArchivoNoAceptadoException(MOTIVO_TIPO,
					"El contenido del archivo no es de un tipo permitido. Se aceptan: "
							+ TipoDeArchivo.permitidos() + ".");
		}
		return detectado;
	}

	private static String sha256(byte[] contenido) {
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(contenido));
		} catch (NoSuchAlgorithmException imposible) {
			// SHA-256 es obligatorio en toda JVM (JLS / javadoc de MessageDigest). Si falta, el
			// entorno esta roto de una forma que no tiene sentido tratar como caso de negocio.
			throw new IllegalStateException("La JVM no ofrece SHA-256", imposible);
		}
	}

	private void auditar(
			String eventType,
			AdjuntoAdministrativo adjunto,
			OperatingActor actor,
			Map<String, String> detalles,
			String motivo,
			Instant ahora) {

		auditTrail.record(new AuditEntry(
				adjunto.getOrganizationId(),
				// consultorioId NULL, mismo criterio que el resto de M07: el adjunto cuelga de una
				// Persona, que no pertenece a ninguna sede. La sede desde la que se cargo vive en
				// la fila, como dato del hecho.
				null,
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_ADJUNTO,
				adjunto.getId(),
				null,
				null,
				detalles,
				motivo,
				AuditEvents.correlationId(),
				ahora));
	}

	private void registrarSoporte(
			PermissionDecision decision,
			OperatingActor actor,
			AdjuntoAdministrativo adjunto,
			Instant ahora,
			String operacion) {

		if (!decision.viaSupportAccess()) {
			return;
		}
		supportAccessAuditor.record(new AuditEntry(
				adjunto.getOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				"SUPPORT_ACCESS_USED",
				AuditEvents.ENTITY_ADJUNTO,
				adjunto.getId(),
				null,
				null,
				Map.of("operacion", operacion),
				null,
				AuditEvents.correlationId(),
				ahora));
	}

	/**
	 * Un adjunto y si la subida lo CREO o resolvio un reintento.
	 *
	 * <p>Existe para que {@code api} pueda distinguir 201 de 200 sin volver a preguntar. El
	 * servicio no puede devolver un codigo HTTP —no conoce HTTP— pero si el hecho que lo decide.
	 */
	public record AdjuntoAlta(AdjuntoView adjunto, boolean creado) {
	}

	/** Una pagina de adjuntos con su total, misma forma que {@code PersonaPagina}. */
	public record AdjuntoPagina(List<AdjuntoView> contenido, long total) {
	}

}
