package com.akine.clinical.application;

import com.akine.clinical.domain.AdjuntoClinico;
import com.akine.clinical.domain.CategoriaAdjuntoClinico;
import com.akine.clinical.domain.EntradaClinica;
import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.PermissionCodes;
import com.akine.clinical.domain.TipoDeContenidoClinico;
import com.akine.clinical.domain.exception.AdjuntoClinicoInactivoException;
import com.akine.clinical.domain.exception.AdjuntoClinicoNoDisponibleException;
import com.akine.clinical.domain.exception.AdjuntoClinicoNotAccessibleException;
import com.akine.clinical.domain.exception.ArchivoClinicoNoAceptadoException;
import com.akine.clinical.domain.exception.HistoriaClinicaNotAccessibleException;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.AdjuntoClinicoRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.EntradaClinicaRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import com.akine.clinical.domain.port.ContenidoClinicoStoragePort;
import com.akine.clinical.spi.RelacionAsistencialProbe;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * Adjuntos CLINICOS de una Historia Clinica (RF-M25-001..005 aplicados a M09).
 *
 * <h2>Se autoriza como la Historia Clinica, no como la ficha de una Persona</h2>
 *
 * <p>Es la diferencia entera con {@code person.application.AdjuntoService}, y la razon por la que
 * esta tabla existe. RN-M25-003 dice que "el acceso hereda permisos de la entidad asociada", y la
 * entidad asociada aca es la HC: por lo tanto <b>permiso clinico + relacion asistencial o
 * justificacion declarada, y auditoria de la lectura tambien</b> (DP-03). Lo resuelve
 * {@link AutorizacionClinica}, igual que el resto del modulo, con {@code hc:read} para leer y
 * {@code hc:write} para escribir.
 *
 * <p>Un estudio guardado en el contenedor administrativo se habria leido por pertenencia al
 * tenant: sin justificacion, sin relacion asistencial y sin evento de lectura. O sea, una historia
 * clinica paralela sin ninguno de los controles de M09. Eso es lo que RN-M25-005 prohibe.
 *
 * <h2>{@code ADJUNTO_CLINICO_DOWNLOADED} no es opcional</h2>
 *
 * <p>Un estudio descargado y reenviado es la fuga mas barata que tiene un sistema clinico, y sin
 * ese evento no hay forma de revisarla despues. La descarga es el momento en que el contenido
 * <b>sale</b> del sistema, y por eso tiene evento propio y no comparte el de la lectura.
 *
 * <h2>Lo que se valida y en que orden</h2>
 *
 * <ol>
 *   <li><b>Tamano</b> primero, porque es el chequeo barato y porque un archivo de 300 MB no vale
 *       la pena hashearlo para despues rechazarlo.</li>
 *   <li><b>Tipo real por los bytes</b>. El {@code Content-Type} declarado se descarta: lo elige
 *       quien sube. Ver {@link TipoDeContenidoClinico}.</li>
 *   <li><b>La entrada, si viene, es de ESTA historia.</b> La base no lo puede exigir —una FK
 *       simple no compara columnas de otra tabla— y sin este control un pedido sobre la historia
 *       A podria dejar un documento colgando de la evolucion de otro paciente del mismo
 *       centro.</li>
 * </ol>
 *
 * <h2>La subida es idempotente, y eso es una decision</h2>
 *
 * <p>Subir dos veces el mismo archivo a la misma historia devuelve <b>el adjunto que ya existe</b>
 * —200, no 201 y no 409—. Subir es la operacion mas expuesta a reintentos que tiene el producto, y
 * un reintento de un POST que si habia llegado no deberia dejar dos filas que despues alguien
 * desempata a ojo dentro de una historia clinica. El invariante lo hace cumplir el unique de
 * {@code V46}, no el pre-chequeo: el pre-chequeo esta porque ahorra escribir el binario, pero
 * <b>no es el que garantiza nada</b>.
 *
 * <h2>Fila primero, blob despues</h2>
 *
 * <p>La fila se escribe con {@code saveAndFlush} para que el unique decida <b>antes</b> de tocar
 * el disco, y el binario se escribe despues. El orden inverso produciria filas apuntando a nada,
 * que es el unico de los dos errores que el usuario ve. Ver la cabecera de {@code V46}.
 *
 * <p><b>El INSERT corre en su propia transaccion</b>, via
 * {@link AdjuntoClinicoEscrituraAparte}, y no en la del servicio: un choque contra el unique
 * marca {@code rollbackOnly} la transaccion en la que ocurre, y atraparlo no la des-marca. Esa
 * clase explica por que, y cual es la consecuencia asumida.
 */
@Service
public class AdjuntoClinicoService {

	private static final Logger log = LoggerFactory.getLogger(AdjuntoClinicoService.class);

	private static final String MOTIVO_TIPO = "TIPO_NO_PERMITIDO";
	private static final String MOTIVO_TAMANO = "DEMASIADO_GRANDE";

	private final HistoriaClinicaRepositoryPort historias;
	private final EntradaClinicaRepositoryPort entradas;
	private final AdjuntoClinicoRepositoryPort adjuntos;
	private final ContenidoClinicoStoragePort storage;
	private final PermissionGuard permissionGuard;
	private final RelacionAsistencialProbe relaciones;
	private final AuditTrail auditTrail;
	private final ClinicalSupportAccessAuditor supportAccessAuditor;
	private final AdjuntoClinicoEscrituraAparte escrituraAparte;

	@SuppressWarnings("checkstyle:ParameterNumber")
	public AdjuntoClinicoService(
			HistoriaClinicaRepositoryPort historias,
			EntradaClinicaRepositoryPort entradas,
			AdjuntoClinicoRepositoryPort adjuntos,
			ContenidoClinicoStoragePort storage,
			PermissionGuard permissionGuard,
			RelacionAsistencialProbe relaciones,
			AuditTrail auditTrail,
			ClinicalSupportAccessAuditor supportAccessAuditor,
			AdjuntoClinicoEscrituraAparte escrituraAparte) {

		this.historias = historias;
		this.entradas = entradas;
		this.adjuntos = adjuntos;
		this.storage = storage;
		this.permissionGuard = permissionGuard;
		this.relaciones = relaciones;
		this.auditTrail = auditTrail;
		this.supportAccessAuditor = supportAccessAuditor;
		this.escrituraAparte = escrituraAparte;
	}

	// =================================================================================
	// Lecturas — hc:read, y las dos dejan evento
	// =================================================================================

	/**
	 * Los adjuntos de una historia (RF-M25-005), paginados y opcionalmente filtrados.
	 *
	 * <p>Por defecto trae solo los VIGENTES: la ficha que un profesional abre no tiene por que
	 * mostrar lo que alguien dio de baja. Los de baja se piden explicitamente y siguen
	 * resolviendo, que es la regla maestra 10 aplicada al documento.
	 *
	 * <p>Deja {@code HISTORIA_CLINICA_ACCESSED} y no un evento propio: el listado no entrega
	 * contenido, y lo que revela —cuantos estudios tiene este paciente y de que clase— es
	 * exactamente el alcance que ese evento ya registra, con su {@code alcance} para distinguirlo.
	 *
	 * @param entradaClinicaId filtro opcional; {@code null} trae los de toda la historia. No se
	 *                         valida que sea de esta historia: un id ajeno simplemente no devuelve
	 *                         nada, porque la consulta ya acota por historia
	 */
	@SuppressWarnings("checkstyle:ParameterNumber")
	@Transactional
	public AdjuntoClinicoPagina listar(
			OperatingActor actor,
			long historiaClinicaId,
			CategoriaAdjuntoClinico categoria,
			Long entradaClinicaId,
			boolean incluirDadosDeBaja,
			int page,
			int size,
			String justificacion) {

		long organizationId = organizacionDe(actor);
		HistoriaClinica historia = exigirHistoria(organizationId, historiaClinicaId);

		AccesoClinico acceso = AutorizacionClinica.exigir(permissionGuard, relaciones, actor,
				PermissionCodes.HC_READ, historia.getPersonaId(), justificacion,
				"Listar adjuntos clinicos");

		auditarLectura(actor, acceso, historia, "ADJUNTOS", historia.getId(), Instant.now());

		String filtroCategoria = categoria == null ? null : categoria.name();
		int activoFiltro = incluirDadosDeBaja ? -1 : 1;

		List<AdjuntoClinicoView> contenido = adjuntos
				.listar(organizationId, historia.getId(), filtroCategoria, entradaClinicaId,
						activoFiltro, page * size, size)
				.stream()
				.map(AdjuntoClinicoView::de)
				.toList();

		long total = adjuntos.contar(
				organizationId, historia.getId(), filtroCategoria, entradaClinicaId, activoFiltro);
		return new AdjuntoClinicoPagina(contenido, total);
	}

	/**
	 * El contenido de un adjunto, para descargarlo (RF-M25-002).
	 *
	 * <p><b>Se descarga aunque el adjunto este dado de baja.</b> Una baja logica dice "esto ya no
	 * corresponde para operar", no "esto nunca existio": negar la descarga convertiria la baja en
	 * un borrado con otro nombre, que es lo que la regla maestra 10 prohibe.
	 *
	 * <p>Si el almacenamiento no tiene el binario es <b>409 y no 404</b>: la metadata existe y
	 * quien pregunta la esta viendo en la lista. Ademas la fila queda marcada
	 * {@code NO_DISPONIBLE}, para que el problema se vea en el listado y no solo en el momento de
	 * fallar.
	 */
	@Transactional
	public ContenidoDeAdjuntoClinico contenido(
			OperatingActor actor, long historiaClinicaId, long adjuntoId, String justificacion) {

		long organizationId = organizacionDe(actor);
		HistoriaClinica historia = exigirHistoria(organizationId, historiaClinicaId);

		AccesoClinico acceso = AutorizacionClinica.exigir(permissionGuard, relaciones, actor,
				PermissionCodes.HC_READ, historia.getPersonaId(), justificacion,
				"Descargar adjunto clinico");

		AdjuntoClinico adjunto = cargar(organizationId, historia.getId(), adjuntoId);
		byte[] bytes = contenidoDe(organizationId, historia.getId(), adjunto, adjuntoId);

		Instant ahora = Instant.now();
		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("historiaClinicaId", String.valueOf(historia.getId()));
		detalles.put("personaId", String.valueOf(historia.getPersonaId()));
		detalles.put("categoria", adjunto.getCategoria().name());
		// EL NOMBRE DEL ARCHIVO NO VA. Es el mismo argumento con el que reclasificar no copia el
		// titulo, 140 lineas mas abajo, y el que el javadoc de AdjuntoClinicoContributor promete:
		// `audit_event` se consulta con `auditoria:read`, que NO es un permiso clinico, y un
		// nombre como "rmn-rodilla-rotura-menisco.pdf" es el diagnostico. Copiarlo convertiria la
		// auditoria en una via de lectura clinica sin permiso clinico. El equivalente
		// administrativo, person.AdjuntoService, pasa directamente Map.of().
		//
		// El checksum SI se queda: es un hash, no dice nada del contenido, y es lo que permite
		// identificar despues QUE archivo salio del sistema sin tener que nombrarlo.
		detalles.put("checksumSha256", adjunto.getChecksumSha256());

		// El evento que justifica la etapa entera desde el lado de seguridad: es el momento en
		// que un estudio clinico SALE del sistema. No se colapsa con la lectura porque la
		// pregunta que hay que poder responder despues es "quien se llevo este archivo", y un
		// evento de acceso al listado no la responde.
		auditar(AuditEvents.ADJUNTO_CLINICO_DOWNLOADED, adjunto.getId(),
				actor, acceso, null, null, detalles, ahora);
		registrarSoporte(acceso, actor, adjunto, "Descargar adjunto clinico", ahora);

		return new ContenidoDeAdjuntoClinico(
				adjunto.getNombreArchivo(), adjunto.getContentType(), bytes);
	}

	// =================================================================================
	// Mutaciones — hc:write
	// =================================================================================

	/**
	 * Sube un documento clinico (RF-M25-001).
	 *
	 * @return el adjunto creado, o el que ya existia si el contenido es identico (idempotencia)
	 */
	@Transactional
	public AdjuntoClinicoAlta subir(
			OperatingActor actor,
			long historiaClinicaId,
			AdjuntoClinicoAltaCommand command,
			String justificacion) {

		long organizationId = organizacionDe(actor);
		HistoriaClinica historia = exigirHistoria(organizationId, historiaClinicaId);

		AccesoClinico acceso = AutorizacionClinica.exigir(permissionGuard, relaciones, actor,
				PermissionCodes.HC_WRITE, historia.getPersonaId(), justificacion,
				"Subir adjunto clinico");

		byte[] contenido = command.contenido();
		exigirTamanoAceptable(contenido);
		String contentType = exigirTipoPermitido(contenido, command);
		Long entradaId = exigirEntradaDeLaHistoria(
				organizationId, historia.getId(), command.entradaClinicaId());
		String checksum = sha256(contenido);

		Optional<AdjuntoClinico> yaExistente = adjuntos
				.buscarVigentePorChecksum(organizationId, historia.getId(), checksum);
		if (yaExistente.isPresent()) {
			log.debug("Subida clinica idempotente resuelta con el adjunto existente: "
					+ "historiaClinicaId={}", historia.getId());
			return new AdjuntoClinicoAlta(AdjuntoClinicoView.de(yaExistente.get()), false);
		}

		Instant ahora = Instant.now();
		String storageKey = UUID.randomUUID().toString().replace("-", "");

		AdjuntoClinico adjunto = new AdjuntoClinico(
				organizationId,
				historia.getId(),
				entradaId,
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

		AdjuntoClinico guardado;
		try {
			// Fila primero, con flush, para que el unique decida antes de tocar el disco. Y en una
			// TRANSACCION PROPIA: si el flush choca contra el unique, Hibernate marca rollbackOnly
			// antes de que la excepcion salga, y atraparla no des-marca nada. Con el INSERT adentro
			// de esta transaccion, el catch de abajo correria sobre una sesion inutilizable y el
			// commit terminaria en UnexpectedRollbackException: un 500 en vez de la idempotencia
			// que el @Operation promete en mayusculas. Es la regla 2 del Paquete B.
			guardado = escrituraAparte.insertar(adjunto);
		} catch (DataIntegrityViolationException choque) {
			// La que murio fue la transaccion del INSERT, no esta: la consulta de abajo corre sobre
			// una sesion sana.
			log.info("Subida clinica concurrente del mismo contenido resuelta como idempotente: "
					+ "historiaClinicaId={}", historia.getId());
			return adjuntos.buscarVigentePorChecksum(organizationId, historia.getId(), checksum)
					.map(existente -> new AdjuntoClinicoAlta(
							AdjuntoClinicoView.de(existente), false))
					.orElseThrow(() -> choque);
		}

		// Blob despues. Ver la cabecera de la clase y la de V46: el orden inverso produciria filas
		// apuntando a nada, que es el unico de los dos errores que el usuario ve.
		//
		// Como la fila ya esta commiteada, un fallo del blob NO se revierte solo: la fila queda, y
		// si se la dejara DISPONIBLE el listado afirmaria tener un estudio que no se puede
		// descargar. Se la marca NO_DISPONIBLE, que es la verdad, y se propaga el fallo.
		try {
			storage.guardar(storageKey, contenido);
		} catch (RuntimeException falla) {
			escrituraAparte.marcarNoDisponible(
					organizationId, historia.getId(), guardado.getId());
			throw falla;
		}

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("historiaClinicaId", String.valueOf(historia.getId()));
		detalles.put("categoria", guardado.getCategoria().name());
		detalles.put("contentType", contentType);
		detalles.put("tamanoBytes", String.valueOf(contenido.length));
		if (entradaId != null) {
			detalles.put("entradaClinicaId", String.valueOf(entradaId));
		}

		auditar(AuditEvents.ADJUNTO_CLINICO_UPLOADED, guardado.getId(),
				actor, acceso, null, "VIGENTE", detalles, ahora);
		registrarSoporte(acceso, actor, guardado, "Subir adjunto clinico", ahora);

		log.info("Adjunto clinico cargado: adjuntoId={} historiaClinicaId={} organizationId={}",
				guardado.getId(), historia.getId(), organizationId);
		return new AdjuntoClinicoAlta(AdjuntoClinicoView.de(guardado), true);
	}

	/**
	 * Reclasifica un adjunto (RF-M25-003).
	 *
	 * <p>Lo unico editable es COMO esta descripto: categoria y titulo. El contenido, su nombre, su
	 * tipo y la entrada que respalda son inmutables. Reemplazar el archivo de una fila reescribiria
	 * un hecho clinico; la forma de reemplazar un documento es dar de baja el viejo y subir el
	 * nuevo, que deja las dos decisiones consultables.
	 */
	@SuppressWarnings("checkstyle:ParameterNumber")
	@Transactional
	public AdjuntoClinicoView reclasificar(
			OperatingActor actor,
			long historiaClinicaId,
			long adjuntoId,
			CategoriaAdjuntoClinico categoria,
			String titulo,
			String justificacion) {

		long organizationId = organizacionDe(actor);
		HistoriaClinica historia = exigirHistoria(organizationId, historiaClinicaId);

		AccesoClinico acceso = AutorizacionClinica.exigir(permissionGuard, relaciones, actor,
				PermissionCodes.HC_WRITE, historia.getPersonaId(), justificacion,
				"Reclasificar adjunto clinico");

		AdjuntoClinico adjunto = cargar(organizationId, historia.getId(), adjuntoId);
		if (!adjunto.isVigente()) {
			throw new AdjuntoClinicoInactivoException(adjuntoId);
		}

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("historiaClinicaId", String.valueOf(historia.getId()));
		if (categoria != null && categoria != adjunto.getCategoria()) {
			detalles.put("categoriaAnterior", adjunto.getCategoria().name());
			detalles.put("categoria", categoria.name());
		}
		if (titulo != null) {
			// El titulo NO se copia a la auditoria: lo escribe un profesional sobre un documento
			// clinico y puede describir el hallazgo. `audit_event` se consulta con
			// `auditoria:read`, que no es un permiso clinico.
			detalles.put("titulo", "modificado");
		}

		adjunto.reclasificar(categoria, titulo);

		// saveAndFlush y no save, por la regla 5 del repositorio: `save` es un merge y el UPDATE
		// que sube la `version` recien sale al cierre de la transaccion, DESPUES de que esta vista
		// leyo getVersion(). El cliente se llevaria la version vieja y su proxima operacion sobre
		// este adjunto moriria en un 409 que no le echa la culpa a nadie.
		AdjuntoClinico guardado = adjuntos.saveAndFlush(adjunto);

		Instant ahora = Instant.now();
		auditar(AuditEvents.ADJUNTO_CLINICO_RECLASSIFIED, guardado.getId(),
				actor, acceso, null, null, detalles, ahora);
		registrarSoporte(acceso, actor, guardado, "Reclasificar adjunto clinico", ahora);
		return AdjuntoClinicoView.de(guardado);
	}

	/**
	 * Da de baja un adjunto (RF-M25-004). Baja LOGICA con motivo obligatorio.
	 *
	 * <p><b>El binario no se borra</b> (challenge seccion 5). Es lo que hace reversible en los
	 * hechos una baja por error sobre un estudio clinico, y lo que permite que el historico siga
	 * resolviendo. Un job de limpieza sobre contenidos realmente huerfanos es otra cosa y necesita
	 * una politica de retencion que nadie escribio.
	 *
	 * <p>Repetir la baja de un adjunto ya dado de baja <b>no es un conflicto</b>: es el mismo
	 * pedido, y se responde con el adjunto tal como quedo, con su motivo original intacto. Pisarlo
	 * con el nuevo perderia el que explica la baja. Mismo criterio que {@code EntradaClinicaService}.
	 */
	@SuppressWarnings("checkstyle:ParameterNumber")
	@Transactional
	public AdjuntoClinicoView darDeBaja(
			OperatingActor actor,
			long historiaClinicaId,
			long adjuntoId,
			String motivo,
			String justificacion) {

		long organizationId = organizacionDe(actor);
		HistoriaClinica historia = exigirHistoria(organizationId, historiaClinicaId);

		AccesoClinico acceso = AutorizacionClinica.exigir(permissionGuard, relaciones, actor,
				PermissionCodes.HC_WRITE, historia.getPersonaId(), justificacion,
				"Dar de baja un adjunto clinico");

		AdjuntoClinico adjunto = cargar(organizationId, historia.getId(), adjuntoId);
		if (!adjunto.isVigente()) {
			log.debug("Adjunto clinico ya dado de baja: id={}", adjuntoId);
			return AdjuntoClinicoView.de(adjunto);
		}

		Instant ahora = Instant.now();
		adjunto.deactivate(ahora, motivo);

		// Mismo motivo que en reclasificar: la vista devuelve la version YA avanzada.
		AdjuntoClinico guardado = adjuntos.saveAndFlush(adjunto);

		auditar(AuditEvents.ADJUNTO_CLINICO_DEACTIVATED, guardado.getId(),
				actor, acceso, "VIGENTE", "DADO_DE_BAJA",
				// EL MOTIVO DE LA BAJA NO VA, mismo criterio que EntradaClinicaService: es texto
				// libre que escribe un profesional sobre un estudio clinico. "Se da de baja el
				// informe de la biopsia" ya dice de que esta enfermo el paciente, y `audit_event` se
				// consulta con `auditoria:read`, que no es un permiso clinico. El motivo queda en
				// `adjunto_clinico.deactivation_reason`, detras del permiso que corresponde, y el
				// evento registra QUE se dio de baja y QUIEN, que es lo que la auditoria tiene que
				// poder responder.
				Map.of("historiaClinicaId", String.valueOf(historia.getId())),
				ahora);
		registrarSoporte(acceso, actor, guardado, "Dar de baja un adjunto clinico", ahora);

		log.info("Adjunto clinico dado de baja: adjuntoId={} historiaClinicaId={}",
				adjuntoId, historia.getId());
		return AdjuntoClinicoView.de(guardado);
	}

	// =================================================================================
	// Invariantes y utilidades
	// =================================================================================

	/**
	 * La organizacion del contexto, exigiendo que haya contexto.
	 *
	 * <p>Es la misma primera condicion que {@link AutorizacionClinica} evalua, y se repite aca por
	 * la misma razon que en {@code EntradaClinicaService}: hay que resolver de quien es la historia
	 * —y eso ya necesita el tenant— antes de poder preguntar por relacion asistencial.
	 */
	private long organizacionDe(OperatingActor actor) {
		if (actor.contextOrganizationId() == null || actor.consultorioId() == null) {
			log.info("Operacion sobre adjuntos clinicos sin contexto validado: accountId={}",
					actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	private HistoriaClinica exigirHistoria(long organizationId, long historiaClinicaId) {
		return historias.findByIdAndOrganizationId(historiaClinicaId, organizationId)
				.filter(HistoriaClinica::isVigente)
				.orElseThrow(() -> new HistoriaClinicaNotAccessibleException(historiaClinicaId));
	}

	private AdjuntoClinico cargar(long organizationId, long historiaClinicaId, long adjuntoId) {
		return adjuntos.buscarDeLaHistoria(organizationId, historiaClinicaId, adjuntoId)
				.orElseThrow(() -> new AdjuntoClinicoNotAccessibleException(adjuntoId));
	}

	/**
	 * Los bytes del adjunto, marcando la fila si el almacenamiento perdio el binario.
	 *
	 * <p>La marca importa: sin ella el problema solo se ve cuando alguien intenta descargar, y el
	 * listado sigue afirmando que el estudio esta.
	 *
	 * <p><b>Y no se escribe en la transaccion de la descarga</b>, aunque este abierta. La linea
	 * siguiente lanza {@code AdjuntoClinicoNoDisponibleException} dentro de un metodo
	 * {@code @Transactional}: Spring revierte, y el rollback se lleva la marca con el. La columna
	 * se quedaria en {@code DISPONIBLE} para siempre y el listado seguiria afirmando que el
	 * estudio esta, contra lo que prometen el {@code @Operation} del controller y la cabecera de
	 * {@code V46}. Por eso va por {@link AdjuntoClinicoEscrituraAparte}, en una transaccion
	 * propia que commitea sola.
	 */
	private byte[] contenidoDe(
			long organizationId, long historiaClinicaId, AdjuntoClinico adjunto, long adjuntoId) {

		if (!adjunto.isDescargable()) {
			throw new AdjuntoClinicoNoDisponibleException(adjuntoId);
		}
		Optional<byte[]> bytes = storage.leer(adjunto.getStorageKey());
		if (bytes.isEmpty()) {
			log.error("El almacenamiento clinico no tiene el contenido de un adjunto que la base "
					+ "referencia: adjuntoId={}", adjuntoId);
			escrituraAparte.marcarNoDisponible(organizationId, historiaClinicaId, adjuntoId);
			throw new AdjuntoClinicoNoDisponibleException(adjuntoId);
		}
		return bytes.get();
	}

	/**
	 * Que la entrada, si viene, sea de ESTA historia.
	 *
	 * <p>La base no lo puede exigir: un {@code CHECK} no consulta otra tabla y una FK compuesta
	 * obligaria a meter {@code historia_clinica_id} dentro de una clave de {@code entrada_clinica}
	 * solo para esto. El rechazo es "no accesible" —404— y no 400 para no confirmar que ese id de
	 * entrada existe en la historia de otro paciente.
	 *
	 * <p>Una entrada dada de baja <b>si</b> admite adjuntos nuevos, y es deliberado: la baja saca
	 * la entrada del timeline, no la borra, y adjuntarle el informe que explica por que se dio de
	 * baja es un caso legitimo.
	 */
	private Long exigirEntradaDeLaHistoria(
			long organizationId, long historiaClinicaId, Long entradaClinicaId) {

		if (entradaClinicaId == null) {
			return null;
		}
		EntradaClinica entrada = entradas
				.findByIdAndOrganizationId(entradaClinicaId, organizationId)
				.orElseThrow(() -> new AdjuntoClinicoNotAccessibleException(entradaClinicaId));
		if (!entrada.getHistoriaClinicaId().equals(historiaClinicaId)) {
			log.info("Adjunto clinico apuntado a una entrada de otra historia: entradaId={}",
					entradaClinicaId);
			throw new AdjuntoClinicoNotAccessibleException(entradaClinicaId);
		}
		return entrada.getId();
	}

	private void exigirTamanoAceptable(byte[] contenido) {
		if (contenido == null || contenido.length == 0) {
			throw new ArchivoClinicoNoAceptadoException(MOTIVO_TIPO, "El archivo esta vacio.");
		}
		if (contenido.length > storage.tamanoMaximo()) {
			log.info("Adjunto clinico rechazado por tamano: bytes={} tope={}",
					contenido.length, storage.tamanoMaximo());
			throw new ArchivoClinicoNoAceptadoException(MOTIVO_TAMANO,
					"El archivo supera el tamano maximo de " + storage.tamanoMaximo() + " bytes.");
		}
	}

	private static String exigirTipoPermitido(
			byte[] contenido, AdjuntoClinicoAltaCommand command) {

		String detectado = TipoDeContenidoClinico.detectar(contenido);
		if (detectado == null) {
			// El declarado se loguea porque "dijo PDF y era otra cosa" es informacion util de
			// diagnostico. NO se usa para decidir: lo elige quien sube.
			log.info("Adjunto clinico rechazado por tipo: declarado={}",
					command.contentTypeDeclarado());
			throw new ArchivoClinicoNoAceptadoException(MOTIVO_TIPO,
					"El contenido del archivo no es de un tipo permitido. Se aceptan: "
							+ TipoDeContenidoClinico.permitidos() + ".");
		}
		return detectado;
	}

	private static String sha256(byte[] contenido) {
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(contenido));
		} catch (NoSuchAlgorithmException imposible) {
			// SHA-256 es obligatorio en toda JVM (javadoc de MessageDigest). Si falta, el entorno
			// esta roto de una forma que no tiene sentido tratar como caso de negocio.
			throw new IllegalStateException("La JVM no ofrece SHA-256", imposible);
		}
	}

	private void auditarLectura(
			OperatingActor actor,
			AccesoClinico acceso,
			HistoriaClinica historia,
			String alcance,
			long referencia,
			Instant ahora) {

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("personaId", String.valueOf(historia.getPersonaId()));
		detalles.put("alcance", alcance);
		detalles.put("referencia", String.valueOf(referencia));
		detalles.put("viaDeAcceso", acceso.via());

		auditTrail.record(new AuditEntry(
				actor.contextOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				AuditEvents.HISTORIA_CLINICA_ACCESSED,
				AuditEvents.ENTITY_HISTORIA_CLINICA,
				historia.getId(),
				null,
				null,
				detalles,
				acceso.justificacion(),
				AuditEvents.correlationId(),
				ahora));
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	private void auditar(
			String eventType,
			Long adjuntoId,
			OperatingActor actor,
			AccesoClinico acceso,
			String previo,
			String nuevo,
			Map<String, String> detalles,
			Instant ahora) {

		Map<String, String> conVia = new LinkedHashMap<>(detalles);
		conVia.put("viaDeAcceso", acceso.via());

		auditTrail.record(new AuditEntry(
				actor.contextOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_ADJUNTO_CLINICO,
				adjuntoId,
				previo,
				nuevo,
				conVia,
				acceso.justificacion(),
				AuditEvents.correlationId(),
				ahora));
	}

	private void registrarSoporte(
			AccesoClinico acceso,
			OperatingActor actor,
			AdjuntoClinico adjunto,
			String operacion,
			Instant ahora) {

		if (!acceso.viaSupportAccess()) {
			return;
		}
		supportAccessAuditor.record(new AuditEntry(
				adjunto.getOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				"SUPPORT_ACCESS_USED",
				AuditEvents.ENTITY_ADJUNTO_CLINICO,
				adjunto.getId(),
				null,
				null,
				Map.of("operacion", operacion),
				acceso.justificacion(),
				AuditEvents.correlationId(),
				ahora));
	}

	/**
	 * Un adjunto y si la subida lo CREO o resolvio un reintento.
	 *
	 * <p>Existe para que {@code api} pueda distinguir 201 de 200 sin volver a preguntar. El
	 * servicio no puede devolver un codigo HTTP —no conoce HTTP— pero si el hecho que lo decide.
	 */
	public record AdjuntoClinicoAlta(AdjuntoClinicoView adjunto, boolean creado) {
	}

	/** Una pagina de adjuntos clinicos con su total. */
	public record AdjuntoClinicoPagina(List<AdjuntoClinicoView> contenido, long total) {
	}
}
