package com.akine.clinical.application;

import com.akine.clinical.domain.EntradaClinica;
import com.akine.clinical.domain.EntradaClinicaVersion;
import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.OrigenEntradaClinica;
import com.akine.clinical.domain.PermissionCodes;
import com.akine.clinical.domain.TipoEntradaClinica;
import com.akine.clinical.domain.exception.EntradaClinicaInactivaException;
import com.akine.clinical.domain.exception.EntradaClinicaNotAccessibleException;
import com.akine.clinical.domain.exception.HistoriaClinicaNotAccessibleException;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.EntradaClinicaRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.EntradaClinicaVersionRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import com.akine.clinical.spi.RelacionAsistencialProbe;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Entradas clinicas versionadas: registrar, enmendar, consultar y dar de baja (RF-M09-006).
 *
 * <h2>Enmendar, no editar. Dar de baja, no borrar</h2>
 *
 * <p>No hay ninguna operacion que reescriba el texto de una entrada, y la ausencia es el diseño.
 * RF-M09-006 pide que la enmienda <b>preserve el original</b> y que las versiones se puedan
 * consultar: un {@code UPDATE} sobre el cuerpo cumple lo contrario de las dos cosas. Enmendar es
 * escribir una version nueva con motivo, y las dos quedan con su autor y su instante — el mismo
 * criterio con el que {@code AntecedenteClinicoService} no tiene edicion.
 *
 * <p>La baja es logica y saca la entrada del timeline sin borrar ninguna de sus versiones. Una
 * entrada dada de baja sigue siendo consultable por su id, que es lo que distingue "no lo
 * muestres" de "no existio" (regla maestra 10).
 *
 * <h2>La numeracion es el unico punto concurrente</h2>
 *
 * <p>Dos profesionales enmendando la misma entrada a la vez <b>no se pisan</b>: cada enmienda es
 * una fila nueva, que es la ventaja de versionar en filas sobre versionar en columnas. Lo que si
 * hay que serializar es el <b>numero</b>, y sale del contador de la cabecera, nunca de un
 * {@code MAX(numero_version) + 1}. Enmendar ensucia esa cabecera, asi que el {@code UPDATE}
 * versionado de JPA alcanza para que dos enmiendas concurrentes no commiteen las dos: no hace
 * falta {@code OPTIMISTIC_FORCE_INCREMENT} y ponerlo seria contraproducente — ver el javadoc de
 * {@link #enmendar}. El {@code INSERT} de la version y el {@code UPDATE} de la cabecera van en la
 * <b>misma transaccion</b>, y la numeracion se resuelve antes de escribir contenido: es el patron
 * de 06.05 y la regla 2 del Paquete B. El perdedor recibe 409 y reintenta.
 *
 * <h2>Todo acceso se audita, incluida la lectura</h2>
 *
 * <p>DP-03 no distingue entre leer y escribir en una historia clinica, y en este modulo el riesgo
 * esta mas del lado de quien lee sin motivo. Las tres lecturas de este servicio dejan evento. Lo
 * que <b>no</b> queda en la auditoria es el cuerpo de la entrada: es contenido clinico y
 * {@code audit_event} se consulta con {@code auditoria:read}, que no es un permiso clinico —
 * copiarlo ahi convertiria la auditoria en una via de lectura clinica sin permiso clinico.
 */
@Service
public class EntradaClinicaService {

	private static final Logger log = LoggerFactory.getLogger(EntradaClinicaService.class);

	private final HistoriaClinicaRepositoryPort historias;
	private final EntradaClinicaRepositoryPort entradas;
	private final EntradaClinicaVersionRepositoryPort versiones;
	private final PermissionGuard permissionGuard;
	private final RelacionAsistencialProbe relaciones;
	private final AuditTrail auditTrail;
	private final ClinicalSupportAccessAuditor supportAccessAuditor;

	public EntradaClinicaService(
			HistoriaClinicaRepositoryPort historias,
			EntradaClinicaRepositoryPort entradas,
			EntradaClinicaVersionRepositoryPort versiones,
			PermissionGuard permissionGuard,
			RelacionAsistencialProbe relaciones,
			AuditTrail auditTrail,
			ClinicalSupportAccessAuditor supportAccessAuditor) {

		this.historias = historias;
		this.entradas = entradas;
		this.versiones = versiones;
		this.permissionGuard = permissionGuard;
		this.relaciones = relaciones;
		this.auditTrail = auditTrail;
		this.supportAccessAuditor = supportAccessAuditor;
	}

	// =================================================================================
	// RF-M09-006 — registrar
	// =================================================================================

	/**
	 * Registra una entrada clinica nueva en esa historia, con su version 1.
	 *
	 * <p>La cabecera y su primera version se escriben en la <b>misma transaccion</b>. Una cabecera
	 * sin contenido no es un estado que esta etapa admita: seria una fila que el timeline indexa y
	 * que al abrirla no dice nada.
	 *
	 * <p>{@code ocurrioEn} es del hecho clinico y puede ser anterior a ahora —una evolucion se
	 * carga al final del dia—. Lo unico que no se admite es el futuro: una entrada que declara
	 * haber ocurrido mañana desordena el timeline y no hay lectura clinica que la justifique.
	 */
	@Transactional
	public EntradaClinicaView registrar(
			OperatingActor actor,
			long historiaClinicaId,
			TipoEntradaClinica tipo,
			String cuerpo,
			Instant ocurrioEn,
			String justificacion) {

		long organizationId = organizacionDe(actor);
		HistoriaClinica historia = exigirHistoria(organizationId, historiaClinicaId);

		AccesoClinico acceso = AutorizacionClinica.exigir(permissionGuard, relaciones, actor,
				PermissionCodes.HC_WRITE, historia.getPersonaId(), justificacion,
				"Registrar entrada clinica");

		Instant ahora = Instant.now();
		Instant momento = ocurrioEn == null ? ahora : ocurrioEn;
		if (momento.isAfter(ahora)) {
			throw new IllegalArgumentException(
					"Una entrada clinica no puede declarar haber ocurrido en el futuro");
		}

		// saveAndFlush: la version 1 necesita el id de la cabecera, y sin el flush ese id no
		// existe hasta el cierre de la transaccion.
		EntradaClinica entrada = entradas.saveAndFlush(new EntradaClinica(
				organizationId, historia.getId(), tipo, momento,
				OrigenEntradaClinica.MANUAL, null, ahora, actor.accountId()));

		EntradaClinicaVersion original = versiones.save(new EntradaClinicaVersion(
				organizationId, entrada.getId(), 1, cuerpo, null, ahora, actor.accountId()));

		auditar(AuditEvents.ENTRADA_CLINICA_REGISTERED, AuditEvents.ENTITY_ENTRADA_CLINICA,
				entrada.getId(), actor, acceso, null, "VIGENTE",
				Map.of("historiaClinicaId", String.valueOf(historia.getId()),
						"tipo", tipo.name(),
						"numeroVersion", "1"),
				ahora);
		registrarSoporte(acceso, actor, entrada, "Registrar entrada clinica", ahora);

		log.info("Entrada clinica registrada: id={} historiaClinicaId={} tipo={}",
				entrada.getId(), historia.getId(), tipo);
		return EntradaClinicaView.de(entrada, original);
	}

	// =================================================================================
	// RF-M09-006 — enmendar
	// =================================================================================

	/**
	 * Enmienda una entrada: escribe una version nueva y deja intacta la anterior.
	 *
	 * <p>{@code expectedVersion} es la version de la <b>cabecera</b>, no el numero de contenido.
	 * Sirve para que quien enmienda sepa que esta enmendando lo que leyo: si alguien enmendo o dio
	 * de baja la entrada en el medio, esto falla con conflicto en vez de apilar una version sobre
	 * un texto que el autor nunca vio.
	 *
	 * <p><b>La lectura de la cabecera NO lleva {@code OPTIMISTIC_FORCE_INCREMENT}, y sacarlo fue
	 * deliberado.</b> Enmendar ensucia la cabecera: {@code ultimo_numero_version} cambia, asi que
	 * el flush ya emite un {@code UPDATE ... WHERE version = N} versionado. Dos enmiendas
	 * concurrentes leen la misma version, las dos ensucian la fila, las dos emiten ese UPDATE:
	 * una gana y la otra recibe {@code OptimisticLockException}. <b>La garantia ya esta</b>, y el
	 * incremento forzado no agregaba ninguna: agregaba un segundo incremento. Hibernate registra
	 * un {@code EntityIncrementVersionProcess} que corre antes del commit y se suma al UPDATE de
	 * la entidad sucia, dejando en la base {@code leida + 2} mientras esta vista devuelve
	 * {@code leida + 1} — o sea el mismo 409 espurio que el {@code saveAndFlush} de abajo vino a
	 * arreglar, entrando por otra puerta.
	 *
	 * <p><b>Por que esto no contradice la leccion de 02.07</b>, que es lo que va a tentar al
	 * proximo que lea esto: alla —{@code OfertaHabilitacionService}— el reemplazo de
	 * habilitaciones solo tocaba tablas hijas, la fila padre no cambiaba ninguna columna, ningun
	 * UPDATE versionado salia y la comparacion de versiones nunca detectaba nada; el
	 * force-increment era la unica forma de hacer avanzar al padre. Aca el padre <b>si</b> se
	 * toca, porque el contador de versiones vive en el. La regla que queda: <b>force-increment
	 * solo donde la escritura no toca ninguna columna del padre.</b>
	 *
	 * @throws EntradaClinicaInactivaException si la entrada esta dada de baja. Es 409: la entrada
	 *                                         existe y se puede leer, pero no admite contenido
	 *                                         nuevo — dejar constancia es registrar una entrada
	 */
	@Transactional
	public EntradaClinicaView enmendar(
			OperatingActor actor,
			long entradaClinicaId,
			String cuerpo,
			String motivo,
			long expectedVersion,
			String justificacion) {

		long organizationId = organizacionDe(actor);
		EntradaClinica entrada = entradas
				.findByIdAndOrganizationId(entradaClinicaId, organizationId)
				.orElseThrow(() -> new EntradaClinicaNotAccessibleException(entradaClinicaId));
		HistoriaClinica historia = exigirHistoria(organizationId, entrada.getHistoriaClinicaId());

		AccesoClinico acceso = AutorizacionClinica.exigir(permissionGuard, relaciones, actor,
				PermissionCodes.HC_WRITE, historia.getPersonaId(), justificacion,
				"Enmendar entrada clinica");

		if (!entrada.isVigente()) {
			throw new EntradaClinicaInactivaException(entradaClinicaId);
		}
		if (entrada.getVersion() != expectedVersion) {
			throw new OptimisticLockingFailureException(
					"La entrada clinica cambio desde que se leyo: volve a abrirla antes de enmendar");
		}

		Instant ahora = Instant.now();
		int anterior = entrada.getUltimoNumeroVersion();

		// El numero se resuelve ANTES de escribir contenido, y sale del contador de la cabecera:
		// dos MAX(numero_version)+1 simultaneos devuelven el mismo numero. El save de la cabecera
		// y el insert de la version van en esta misma transaccion.
		int numero = entrada.siguienteNumeroDeVersion();

		// saveAndFlush y NO save. `save` es un merge: deja la escritura pendiente y el UPDATE que
		// sube la `version` de la cabecera recien sale al cierre de la transaccion, DESPUES de que
		// esta vista ya leyo getVersion(). El cliente se llevaria la version vieja, la mandaria
		// como expectedVersion en la enmienda siguiente y comeria un 409 del que no puede salir
		// salvo releyendo la entrada. Es la regla 5 del repositorio âsave() antes del flush
		// devuelve la version viejaâ y ya se pago en 02.07.
		EntradaClinica cabecera = entradas.saveAndFlush(entrada);

		// El motivo lo exige la propia version: un camino que no pase por este servicio falla
		// igual, con EnmiendaSinMotivoException, que la capa HTTP mapea a 400 y no a 409.
		EntradaClinicaVersion enmienda = versiones.save(new EntradaClinicaVersion(
				organizationId, cabecera.getId(), numero, cuerpo, motivo, ahora,
				actor.accountId()));

		auditar(AuditEvents.ENTRADA_CLINICA_AMENDED, AuditEvents.ENTITY_ENTRADA_CLINICA,
				cabecera.getId(), actor, acceso,
				"VERSION_" + anterior, "VERSION_" + numero,
				Map.of("historiaClinicaId", String.valueOf(historia.getId()),
						"tipo", cabecera.getTipo().name()),
				ahora);
		registrarSoporte(acceso, actor, cabecera, "Enmendar entrada clinica", ahora);

		log.info("Entrada clinica enmendada: id={} numeroVersion={}", cabecera.getId(), numero);
		return EntradaClinicaView.de(cabecera, enmienda);
	}

	// =================================================================================
	// Baja logica
	// =================================================================================

	/**
	 * Da de baja una entrada, con motivo obligatorio. No borra ninguna de sus versiones.
	 *
	 * <p>Repetir la baja de una entrada ya dada de baja <b>no es un conflicto</b>: es el mismo
	 * pedido, y se responde con la entrada tal como quedo, con su motivo original intacto.
	 * Pisarlo con el nuevo perderia el primero, que es el que explica la baja.
	 */
	@Transactional
	public EntradaClinicaView darDeBaja(
			OperatingActor actor,
			long entradaClinicaId,
			String motivo,
			long expectedVersion,
			String justificacion) {

		long organizationId = organizacionDe(actor);
		EntradaClinica entrada = entradas
				.findByIdAndOrganizationId(entradaClinicaId, organizationId)
				.orElseThrow(() -> new EntradaClinicaNotAccessibleException(entradaClinicaId));
		HistoriaClinica historia = exigirHistoria(organizationId, entrada.getHistoriaClinicaId());

		AccesoClinico acceso = AutorizacionClinica.exigir(permissionGuard, relaciones, actor,
				PermissionCodes.HC_WRITE, historia.getPersonaId(), justificacion,
				"Dar de baja una entrada clinica");

		if (!entrada.isVigente()) {
			log.debug("Entrada clinica ya dada de baja: id={}", entradaClinicaId);
			return EntradaClinicaView.de(entrada, vigenteDe(organizationId, entrada));
		}
		if (entrada.getVersion() != expectedVersion) {
			throw new OptimisticLockingFailureException(
					"La entrada clinica cambio desde que se leyo: volve a abrirla antes de darla de baja");
		}

		Instant ahora = Instant.now();
		entrada.deactivate(ahora, motivo);

		// Mismo motivo que en enmendar: la vista tiene que devolver la version YA avanzada, porque
		// es la que el cliente va a mandar como expectedVersion en su proxima operacion.
		//
		// Ojo con el diagnostico facil: este camino "funcionaba" por accidente. El vigenteDe de
		// la linea de abajo es una consulta JPQL, y una consulta dispara el flush AUTO de la
		// sesion, que a su vez emitia el UPDATE y subia la version justo antes de que se leyera.
		// Depender de eso es depender de que nadie reordene dos lineas, de que nadie toque el
		// FlushModeType y de que la version vigente se siga resolviendo con una consulta y no con
		// una cache. El flush explicito pide lo que el codigo necesita en vez de heredarlo.
		EntradaClinica guardada = entradas.saveAndFlush(entrada);

		auditar(AuditEvents.ENTRADA_CLINICA_DEACTIVATED, AuditEvents.ENTITY_ENTRADA_CLINICA,
				guardada.getId(), actor, acceso, "VIGENTE", "DADA_DE_BAJA",
				Map.of("historiaClinicaId", String.valueOf(historia.getId()),
						"tipo", guardada.getTipo().name()),
				ahora);
		registrarSoporte(acceso, actor, guardada, "Dar de baja una entrada clinica", ahora);

		return EntradaClinicaView.de(guardada, vigenteDe(organizationId, guardada));
	}

	// =================================================================================
	// Lecturas — las tres dejan evento de auditoria
	// =================================================================================

	/** Una entrada con su version vigente. Es lectura clinica y se audita como tal. */
	@Transactional
	public EntradaClinicaView ver(
			OperatingActor actor, long entradaClinicaId, String justificacion) {

		long organizationId = organizacionDe(actor);
		EntradaClinica entrada = entradas
				.findByIdAndOrganizationId(entradaClinicaId, organizationId)
				.orElseThrow(() -> new EntradaClinicaNotAccessibleException(entradaClinicaId));
		HistoriaClinica historia = exigirHistoria(organizationId, entrada.getHistoriaClinicaId());

		AccesoClinico acceso = AutorizacionClinica.exigir(permissionGuard, relaciones, actor,
				PermissionCodes.HC_READ, historia.getPersonaId(), justificacion,
				"Ver entrada clinica");

		Instant ahora = Instant.now();
		auditarLectura(actor, acceso, historia, "ENTRADA", entradaClinicaId, ahora);
		registrarSoporte(acceso, actor, entrada, "Ver entrada clinica", ahora);

		return EntradaClinicaView.de(entrada, vigenteDe(organizationId, entrada));
	}

	/**
	 * Las entradas de una historia, mas recientes primero.
	 *
	 * @param soloVigentes {@code true} deja afuera las dadas de baja; {@code false} las incluye,
	 *                     que es lo que hace consultable el historico (regla maestra 10)
	 */
	@Transactional
	public List<EntradaClinicaView> listar(
			OperatingActor actor,
			long historiaClinicaId,
			boolean soloVigentes,
			String justificacion) {

		long organizationId = organizacionDe(actor);
		HistoriaClinica historia = exigirHistoria(organizationId, historiaClinicaId);

		AccesoClinico acceso = AutorizacionClinica.exigir(permissionGuard, relaciones, actor,
				PermissionCodes.HC_READ, historia.getPersonaId(), justificacion,
				"Listar entradas clinicas");

		Instant ahora = Instant.now();
		auditarLectura(actor, acceso, historia, "ENTRADAS", historia.getId(), ahora);

		List<EntradaClinica> deLaHistoria =
				entradas.buscarDeHistoria(organizationId, historia.getId(), soloVigentes);

		// Una sola consulta para las versiones vigentes de todas las entradas: una por entrada
		// convertiria la ficha de un paciente cronico en cientos de viajes a la base.
		Map<Long, EntradaClinicaVersion> porEntrada = versiones
				.buscarVigentesDe(organizationId, deLaHistoria.stream()
						.map(EntradaClinica::getId)
						.toList())
				.stream()
				.collect(Collectors.toMap(
						EntradaClinicaVersion::getEntradaClinicaId, Function.identity()));

		return deLaHistoria.stream()
				.map(entrada -> {
					EntradaClinicaVersion vigente = porEntrada.get(entrada.getId());
					if (vigente == null) {
						// No deberia poder pasar: cabecera y version 1 se escriben juntas. Si
						// pasa, la fila se omite y queda el log — devolver una entrada sin cuerpo
						// mostraria un hecho clinico vacio, que es peor que no mostrarlo.
						log.warn("Entrada clinica sin ninguna version: id={}", entrada.getId());
						return null;
					}
					return EntradaClinicaView.de(entrada, vigente);
				})
				.filter(Objects::nonNull)
				.toList();
	}

	/**
	 * El historico completo de una entrada, de la version mas nueva a la mas vieja (RF-M09-006).
	 *
	 * <p>Es su propia operacion y su propio evento de auditoria. Leer todas las versiones de una
	 * entrada es un acceso mas amplio que leer la vigente —muestra lo que la historia decia antes
	 * y por que se corrigio— y colapsarlo dentro de la lectura normal esconderia justamente el
	 * acceso que despues alguien quiere revisar.
	 */
	@Transactional
	public List<EntradaClinicaVersionView> versiones(
			OperatingActor actor, long entradaClinicaId, String justificacion) {

		long organizationId = organizacionDe(actor);
		EntradaClinica entrada = entradas
				.findByIdAndOrganizationId(entradaClinicaId, organizationId)
				.orElseThrow(() -> new EntradaClinicaNotAccessibleException(entradaClinicaId));
		HistoriaClinica historia = exigirHistoria(organizationId, entrada.getHistoriaClinicaId());

		AccesoClinico acceso = AutorizacionClinica.exigir(permissionGuard, relaciones, actor,
				PermissionCodes.HC_READ, historia.getPersonaId(), justificacion,
				"Ver versiones de una entrada clinica");

		Instant ahora = Instant.now();
		auditarLectura(actor, acceso, historia, "ENTRADA_VERSIONES", entradaClinicaId, ahora);
		registrarSoporte(acceso, actor, entrada, "Ver versiones de una entrada clinica", ahora);

		return versiones.buscarDeEntrada(organizationId, entradaClinicaId).stream()
				.map(EntradaClinicaVersionView::de)
				.toList();
	}

	// =================================================================================
	// Internos
	// =================================================================================

	/**
	 * La organizacion del contexto, exigiendo que haya contexto.
	 *
	 * <p>Es la misma primera condicion que {@link AutorizacionClinica} evalua, y se repite aca por
	 * una razon concreta: las operaciones sobre una entrada llegan con el id de la entrada y
	 * <b>no</b> con el de la persona, asi que hay que resolver de quien es la historia antes de
	 * poder preguntar por relacion asistencial. Esa resolucion ya necesita el tenant.
	 *
	 * <p>La lectura previa a la autorizacion esta acotada al tenant del actor y no devuelve nada
	 * al cliente: lo unico que puede filtrar es la existencia de una fila, y de eso se encarga
	 * {@link EntradaClinicaNotAccessibleException}, que responde lo mismo para "no existe" y para
	 * "es de otro tenant".
	 */
	private long organizacionDe(OperatingActor actor) {
		if (actor.contextOrganizationId() == null || actor.consultorioId() == null) {
			log.info("Operacion sobre entradas clinicas sin contexto validado: accountId={}",
					actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	/** La historia vigente por id, dentro del tenant. */
	private HistoriaClinica exigirHistoria(long organizationId, long historiaClinicaId) {
		return historias.findByIdAndOrganizationId(historiaClinicaId, organizationId)
				.filter(HistoriaClinica::isVigente)
				.orElseThrow(() -> new HistoriaClinicaNotAccessibleException(historiaClinicaId));
	}

	/**
	 * La version vigente de una entrada.
	 *
	 * <p>Lee el historico y se queda con el numero mas alto en vez de pedirle a la base un
	 * {@code MAX}: una entrada tiene unas pocas versiones —enmendar es excepcional— y la consulta
	 * ya existe para el historico. Si alguna vez una entrada acumulara decenas, esto es lo primero
	 * que hay que cambiar.
	 */
	private EntradaClinicaVersion vigenteDe(long organizationId, EntradaClinica entrada) {
		return versiones.buscarDeEntrada(organizationId, entrada.getId()).stream()
				.max(Comparator.comparingInt(EntradaClinicaVersion::getNumeroVersion))
				.orElseThrow(() -> new IllegalStateException(
						"La entrada clinica " + entrada.getId() + " no tiene ninguna version: "
								+ "cabecera y version 1 se escriben en la misma transaccion"));
	}

	private void auditarLectura(
			OperatingActor actor,
			AccesoClinico acceso,
			HistoriaClinica historia,
			String alcance,
			long referencia,
			Instant ahora) {

		auditar(AuditEvents.HISTORIA_CLINICA_ACCESSED, AuditEvents.ENTITY_HISTORIA_CLINICA,
				historia.getId(), actor, acceso, null, null,
				Map.of("personaId", String.valueOf(historia.getPersonaId()),
						"alcance", alcance,
						"referencia", String.valueOf(referencia)),
				ahora);
	}

	private void auditar(
			String eventType,
			String entityType,
			Long entityId,
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
				entityType,
				entityId,
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
			EntradaClinica entrada,
			String operacion,
			Instant ahora) {

		if (!acceso.viaSupportAccess()) {
			return;
		}
		supportAccessAuditor.record(new AuditEntry(
				entrada.getOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				"SUPPORT_ACCESS_USED",
				AuditEvents.ENTITY_ENTRADA_CLINICA,
				entrada.getId(),
				null,
				null,
				Map.of("operacion", operacion),
				acceso.justificacion(),
				AuditEvents.correlationId(),
				ahora));
	}
}
