package com.akine.clinical.application;

import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.PermissionCodes;
import com.akine.clinical.domain.exception.HistoriaClinicaNotAccessibleException;
import com.akine.clinical.domain.exception.PacienteSinPerfilVigenteException;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.AntecedenteClinicoRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import com.akine.clinical.spi.EventoClinico;
import com.akine.clinical.spi.EventoClinicoContributor;
import com.akine.clinical.spi.RelacionAsistencialProbe;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * La Historia Clinica organizacional: apertura, consulta y resumen (M09, RF-M09-001).
 *
 * <h2>Las tres cosas que este servicio hace cumplir</h2>
 *
 * <ol>
 *   <li><b>Una historia por paciente y organizacion.</b> No la garantiza el {@code SELECT} previo
 *       —que tiene ventana de carrera— sino el unique {@code uk_historia_clinica_persona_vigente}
 *       de V32. Dos aperturas simultaneas no producen dos historias: la segunda choca y se
 *       responde con la que gano. El pre-chequeo existe para ahorrar el viaje en el caso comun,
 *       no como la proteccion.</li>
 *   <li><b>La historia cuelga de un paciente, no de cualquier persona.</b> Es el recableado de
 *       DP-10: 04.01 depende de 03.01 y exige {@code PerfilPaciente} vigente. Sin esa
 *       precondicion, dar de alta a alguien que solo viene a una clase terminaria creandole
 *       historia clinica.</li>
 *   <li><b>Todo acceso queda auditado, incluida la lectura.</b> Ver {@link AuditEvents}.</li>
 * </ol>
 *
 * <h2>Lo que este servicio NO hace</h2>
 *
 * <p>No crea Casos ni Sesiones, y no tiene por donde: la regla maestra 1 los separa y sus etapas
 * (04.03 y 06.01) estan fuera de esta. Tampoco borra nada.
 */
@Service
public class HistoriaClinicaService {

	private static final Logger log = LoggerFactory.getLogger(HistoriaClinicaService.class);

	/**
	 * Tope de eventos que se le pide a cada contribuyente del timeline.
	 *
	 * <p>Desde 04.02 hay cuatro, asi que acota de verdad: sin tope, cada lectura del resumen
	 * traeria la historia entera de un paciente cronico por cuatro fuentes. Es el asomo del
	 * timeline, no el timeline — la linea de tiempo paginada la sirve {@link TimelineService}.
	 */
	private static final int EVENTOS_POR_CONTRIBUYENTE = 50;

	private final HistoriaClinicaRepositoryPort historias;
	private final AntecedenteClinicoRepositoryPort antecedentes;
	private final PacienteDirectory pacientes;
	private final PermissionGuard permissionGuard;
	private final RelacionAsistencialProbe relaciones;
	private final List<EventoClinicoContributor> contribuyentes;
	private final AuditTrail auditTrail;
	private final ClinicalSupportAccessAuditor supportAccessAuditor;

	@SuppressWarnings("checkstyle:ParameterNumber")
	public HistoriaClinicaService(
			HistoriaClinicaRepositoryPort historias,
			AntecedenteClinicoRepositoryPort antecedentes,
			PacienteDirectory pacientes,
			PermissionGuard permissionGuard,
			RelacionAsistencialProbe relaciones,
			List<EventoClinicoContributor> contribuyentes,
			AuditTrail auditTrail,
			ClinicalSupportAccessAuditor supportAccessAuditor) {

		this.historias = historias;
		this.antecedentes = antecedentes;
		this.pacientes = pacientes;
		this.permissionGuard = permissionGuard;
		this.relaciones = relaciones;
		this.contribuyentes = List.copyOf(contribuyentes);
		this.auditTrail = auditTrail;
		this.supportAccessAuditor = supportAccessAuditor;
	}

	// =================================================================================
	// RF-M09-001 — abrir u obtener la historia
	// =================================================================================

	/**
	 * Abre la historia clinica de esa persona, o devuelve la que ya tiene.
	 *
	 * <p>Es <b>idempotente</b>: repetir el pedido no crea una segunda historia ni falla. Un boton
	 * tocado dos veces o un request reintentado tras un timeout son el mismo pedido, no un
	 * conflicto.
	 */
	@Transactional
	public HistoriaClinicaView abrirOObtener(
			OperatingActor actor, long personaId, String justificacion) {

		AccesoClinico acceso = AutorizacionClinica.exigirSobrePersona(permissionGuard, relaciones,
				supportAccessAuditor, pacientes, actor,
				PermissionCodes.HC_WRITE, personaId, justificacion, "Abrir historia clinica");
		long organizationId = actor.contextOrganizationId();

		PacienteSnapshot paciente = exigirPacienteVigente(organizationId, personaId);
		Apertura apertura = abrirIdempotente(organizationId, personaId, actor.accountId());

		if (apertura.reciencreada()) {
			auditar(AuditEvents.HISTORIA_CLINICA_OPENED, AuditEvents.ENTITY_HISTORIA_CLINICA,
					apertura.historia().getId(), actor, acceso, null, "ABIERTA",
					Map.of("personaId", String.valueOf(personaId)), Instant.now());
			registrarSoporte(acceso, actor, apertura.historia(), "Abrir historia clinica",
					Instant.now());
		}
		return armarVista(apertura.historia(), paciente, acceso);
	}

	/**
	 * La historia vigente de esa persona, abriendola si no existe, <b>sin evaluar permisos</b>.
	 *
	 * <h2>Quien puede llamar a esto y por que no lleva actor completo</h2>
	 *
	 * <p>Es la operacion que {@code clinical.spi.HistoriaClinicaDirectory} expone a otros modulos:
	 * cuando 06.01 registre una sesion, la historia tiene que existir y quien registra la sesion ya
	 * demostro su propio permiso ({@code sesion:register}). Exigirle ademas {@code hc:write}
	 * duplicaria la decision de autorizacion en dos modulos.
	 *
	 * <p><b>Lo que NO se saltea es la precondicion de paciente</b>: si la persona no tiene perfil
	 * de paciente vigente, esto falla. Tiene que fallar aca y no solo en el camino humano — si el
	 * spi la salteara, el primer modulo que registre una prestacion sobre alguien que solo es
	 * "persona" le crearia historia clinica sin que nadie lo decidiera, que es RF-M07-010 violada
	 * desde afuera.
	 *
	 * <p>No deja evento de acceso clinico: no hubo lectura de contenido. Lo que si deja, si la
	 * historia se creo, es el evento de apertura, con el actor que la origino.
	 */
	@Transactional
	public HistoriaClinica asegurar(long organizationId, long personaId, long actorAccountId) {
		exigirPacienteVigente(organizationId, personaId);
		Apertura apertura = abrirIdempotente(organizationId, personaId, actorAccountId);

		if (apertura.reciencreada()) {
			auditTrail.record(new AuditEntry(
					organizationId,
					null,
					actorAccountId,
					AuditEvents.HISTORIA_CLINICA_OPENED,
					AuditEvents.ENTITY_HISTORIA_CLINICA,
					apertura.historia().getId(),
					null,
					"ABIERTA",
					Map.of("personaId", String.valueOf(personaId), "viaDeAcceso", "MODULO"),
					null,
					AuditEvents.correlationId(),
					Instant.now()));
		}
		return apertura.historia();
	}

	/** La historia y si esta llamada fue la que la creo, para no auditar dos veces una apertura. */
	private record Apertura(HistoriaClinica historia, boolean reciencreada) {
	}

	/**
	 * Get-or-create de la historia, idempotente contra concurrencia.
	 *
	 * <p>La garantia no la da el {@code SELECT} previo sino el unique
	 * {@code uk_historia_clinica_persona_vigente}: dos aperturas simultaneas no producen dos
	 * historias, la segunda choca y se responde con la que gano. El pre-chequeo esta para ahorrar
	 * el viaje en el caso comun, no como la proteccion.
	 *
	 * <p><b>El INSERT no puede lanzar.</b> Si el choque contra el unique saliera como excepcion,
	 * marcaria esta transaccion {@code rollbackOnly} aunque se la atrapara, y la apertura
	 * concurrente terminaria en {@code UnexpectedRollbackException}: un 500 en la ruta critica del
	 * modulo. Hasta el 11/10/2026 se evitaba con un INSERT en {@code REQUIRES_NEW}, que retenia la
	 * conexion de esta transaccion y pedia otra: muchas primeras aperturas a la vez trababan el
	 * pool (docs/carga.md §8). Ahora es un {@code INSERT IGNORE} en esta misma transaccion, y la
	 * relectura con lock compartido ve a la ganadora aunque la foto de {@code REPEATABLE READ} sea
	 * anterior.
	 *
	 * <p>Como contrapartida buena, la historia y su evento {@code HISTORIA_CLINICA_OPENED} se
	 * confirman juntos: si la transaccion de negocio revierte, no queda una historia sin evento.
	 * Y como contrapartida a nombrar: la fila nueva queda bloqueada hasta que la transaccion de
	 * negocio termina, asi que una segunda apertura simultanea de la MISMA persona espera a la
	 * primera. Si la primera revierte con dos o mas esperando, InnoDB puede elegir una victima de
	 * deadlock entre ellas —el caso conocido del INSERT duplicado tras un rollback, y realista:
	 * {@code SesionService.iniciar} revierte si el caso pedido no es asignable a la historia recien
	 * creada—. La victima sale como {@code CannotAcquireLockException}, que
	 * {@code GlobalExceptionHandler} responde 409 {@code concurrent-modification}: el reintento
	 * encuentra la historia abierta o la abre.
	 *
	 * <p>Si despues del {@code INSERT IGNORE} la relectura no encuentra historia vigente, el IGNORE
	 * se trago otro error que no era el unique —una FK, un {@code NOT NULL}— y es un defecto del
	 * servidor, no un conflicto: sale como {@code IllegalStateException}, o sea 500, con el motivo
	 * en el mensaje. La precondicion de paciente vigente se valida antes, asi que no deberia pasar.
	 */
	private Apertura abrirIdempotente(long organizationId, long personaId, long actorAccountId) {
		var yaExistente = historias.buscarVigentePorPersona(organizationId, personaId);
		if (yaExistente.isPresent()) {
			log.debug("Historia clinica ya abierta: personaId={}", personaId);
			return new Apertura(yaExistente.get(), false);
		}

		boolean laAbrioEsta = historias.abrirSiFalta(organizationId, personaId, actorAccountId) == 1;
		HistoriaClinica vigente = historias.releerVigenteBloqueando(organizationId, personaId)
				.orElseThrow(() -> new IllegalStateException(
						"La historia clinica no quedo abierta: el INSERT IGNORE no inserto y no hay"
						+ " vigente que releer (un error degradado a advertencia que no es el unique)."
						+ " personaId=" + personaId));
		if (laAbrioEsta) {
			log.info("Historia clinica abierta: id={} personaId={} organizationId={}",
					vigente.getId(), personaId, organizationId);
		} else {
			// Otro pedido gano la carrera: es el mismo pedido resuelto por el otro camino.
			log.info("Apertura concurrente de historia clinica resuelta como idempotente: "
					+ "personaId={}", personaId);
		}
		return new Apertura(vigente, laAbrioEsta);
	}

	// =================================================================================
	// Consulta — y su auditoria, que es la mitad del requerimiento
	// =================================================================================

	/**
	 * La historia clinica de esa persona, con sus antecedentes vigentes y su timeline.
	 *
	 * <p><b>Deja un evento de auditoria en cada lectura</b>, con la via por la que se accedio. No
	 * es defensivo de mas: DP-03 exige auditar todo acceso clinico sensible, y en una historia
	 * clinica el riesgo esta mas en quien la lee sin motivo que en quien la modifica.
	 */
	@Transactional
	public HistoriaClinicaView ver(OperatingActor actor, long personaId, String justificacion) {
		AccesoClinico acceso = AutorizacionClinica.exigirSobrePersona(permissionGuard, relaciones,
				supportAccessAuditor, pacientes, actor,
				PermissionCodes.HC_READ, personaId, justificacion, "Ver historia clinica");
		long organizationId = actor.contextOrganizationId();

		HistoriaClinica historia = historias.buscarVigentePorPersona(organizationId, personaId)
				.orElseThrow(() -> new HistoriaClinicaNotAccessibleException(personaId));
		PacienteSnapshot paciente = pacientes.find(organizationId, personaId).orElse(null);

		Instant ahora = Instant.now();
		auditar(AuditEvents.HISTORIA_CLINICA_ACCESSED, AuditEvents.ENTITY_HISTORIA_CLINICA,
				historia.getId(), actor, acceso, null, null,
				Map.of("personaId", String.valueOf(personaId)), ahora);
		registrarSoporte(acceso, actor, historia, "Ver historia clinica", ahora);

		return armarVista(historia, paciente, acceso);
	}

	// =================================================================================
	// RF-M09-001 — resumen clinico minimo
	// =================================================================================

	/**
	 * Reemplaza el resumen clinico minimo.
	 *
	 * <p>{@code expectedVersion} evita que el segundo en guardar pise en silencio lo que escribio
	 * el primero. <b>Aca si protege de verdad</b>, a diferencia de lo que pasaba en 02.07: el
	 * resumen es una columna de {@code historia_clinica}, o sea que la escritura toca la misma
	 * fila que lleva la {@code @Version} y JPA la hace avanzar sola.
	 */
	@Transactional
	public HistoriaClinicaView actualizarResumen(
			OperatingActor actor,
			long personaId,
			String texto,
			long expectedVersion,
			String justificacion) {

		AccesoClinico acceso = AutorizacionClinica.exigirSobrePersona(permissionGuard, relaciones,
				supportAccessAuditor, pacientes, actor,
				PermissionCodes.HC_WRITE, personaId, justificacion, "Actualizar resumen clinico");
		long organizationId = actor.contextOrganizationId();

		HistoriaClinica historia = historias.buscarVigentePorPersona(organizationId, personaId)
				.orElseThrow(() -> new HistoriaClinicaNotAccessibleException(personaId));
		if (historia.getVersion() != expectedVersion) {
			throw new OptimisticLockingFailureException(
					"La historia clinica cambio desde que se leyo: volve a abrirla antes de guardar");
		}

		Instant ahora = Instant.now();
		String anterior = historia.getResumen() == null ? "SIN_RESUMEN" : "CON_RESUMEN";
		historia.actualizarResumen(texto, ahora, actor.accountId());
		HistoriaClinica guardada = historias.save(historia);

		// El texto del resumen NO va en los detalles de la auditoria: es contenido clinico y
		// `audit_event` se consulta con auditoria:read, que no es un permiso clinico. Lo que
		// queda registrado es que hubo escritura, quien y cuando.
		auditar(AuditEvents.HISTORIA_CLINICA_RESUMEN_UPDATED, AuditEvents.ENTITY_HISTORIA_CLINICA,
				guardada.getId(), actor, acceso, anterior,
				guardada.getResumen() == null ? "SIN_RESUMEN" : "CON_RESUMEN",
				Map.of("personaId", String.valueOf(personaId)), ahora);
		registrarSoporte(acceso, actor, guardada, "Actualizar resumen clinico", ahora);

		PacienteSnapshot paciente = pacientes.find(organizationId, personaId).orElse(null);
		return armarVista(guardada, paciente, acceso);
	}

	// =================================================================================
	// Internos
	// =================================================================================

	/**
	 * La persona existe en el tenant, esta activa y es paciente vigente.
	 *
	 * <p>Los dos rechazos son distintos a proposito: <b>no accesible</b> cuando la persona no es
	 * de esta organizacion —que es indistinguible de "no existe", y tiene que serlo— y
	 * <b>sin perfil vigente</b> cuando existe pero su estado no admite la operacion. Colapsarlos
	 * haria imposible que la pantalla ofrezca activar el perfil, que es la accion correcta en el
	 * segundo caso.
	 */
	private PacienteSnapshot exigirPacienteVigente(long organizationId, long personaId) {
		PacienteSnapshot paciente = pacientes.find(organizationId, personaId)
				.orElseThrow(() -> new HistoriaClinicaNotAccessibleException(personaId));
		if (!paciente.activa() || !paciente.esPacienteVigente()) {
			throw new PacienteSinPerfilVigenteException(personaId);
		}
		return paciente;
	}

	private HistoriaClinicaView armarVista(
			HistoriaClinica historia, PacienteSnapshot paciente, AccesoClinico acceso) {

		List<AntecedenteView> vigentes = antecedentes
				.buscarDeHistoria(historia.getOrganizationId(), historia.getId(), null, true)
				.stream()
				.map(AntecedenteView::de)
				.toList();

		return HistoriaClinicaView.de(
				historia, paciente, vigentes, timelineDe(historia), acceso.conRelacionAsistencial());
	}

	/**
	 * El timeline, mezclando lo que aporta cada modulo contribuyente.
	 *
	 * <p>Es el <b>asomo</b> del timeline dentro de la ficha, no el timeline: trae los mas recientes
	 * y no pagina. La linea de tiempo completa, con su cursor, la sirve {@code TimelineService}.
	 *
	 * <p>El tope temporal que 04.02 agrego a la firma del contribuyente se pasa como
	 * {@code Instant.now()}, que es lo que este resumen siempre quiso decir: los ultimos hechos
	 * <b>hasta ahora</b>. Ningun evento puede estar datado en el futuro —una entrada que declara
	 * haber ocurrido mañana se rechaza al registrarse— asi que el comportamiento del resumen no
	 * cambia.
	 *
	 * <p>El filtro por caso que 04.03 agrego a la firma se pasa en {@code null}: este resumen es
	 * el de la <b>ficha del paciente</b>, que es la historia entera y no un caso. Filtrarlo aca
	 * obligaria a elegir un caso por el paciente, y el paciente puede tener varios activos a la vez
	 * (RN-M10-002). El filtro por caso vive en el timeline completo, donde quien mira lo elige.
	 */
	private List<EventoClinico> timelineDe(HistoriaClinica historia) {
		Instant hasta = Instant.now();
		return contribuyentes.stream()
				.flatMap(c -> c.eventosDe(
						historia.getOrganizationId(), historia.getId(), hasta,
						EVENTOS_POR_CONTRIBUYENTE, null)
						.stream())
				.sorted(Comparator.comparing(EventoClinico::ocurrioEn).reversed())
				.limit(EVENTOS_POR_CONTRIBUYENTE)
				.toList();
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
			HistoriaClinica historia,
			String operacion,
			Instant ahora) {

		if (!acceso.viaSupportAccess()) {
			return;
		}
		supportAccessAuditor.record(new AuditEntry(
				historia.getOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				"SUPPORT_ACCESS_USED",
				AuditEvents.ENTITY_HISTORIA_CLINICA,
				historia.getId(),
				null,
				null,
				Map.of("operacion", operacion),
				acceso.justificacion(),
				AuditEvents.correlationId(),
				ahora));
	}
}
