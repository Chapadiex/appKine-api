package com.akine.scheduling.application;

import com.akine.offering.spi.PracticasDeOfertaDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.spi.CoberturaAplicable;
import com.akine.person.spi.CoberturasAplicablesDirectory;
import com.akine.person.spi.ElegibilidadAdministrativaDirectory;
import com.akine.person.spi.VeredictoDeElegibilidad;
import com.akine.scheduling.domain.EstadoRecepcion;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.Recepcion;
import com.akine.scheduling.domain.TipoEventoRecepcion;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.RecepcionNotAccessibleException;
import com.akine.scheduling.domain.exception.TransicionDeRecepcionNoPermitidaException;
import com.akine.scheduling.domain.exception.TransicionDeTurnoNoPermitidaException;
import com.akine.scheduling.domain.exception.TurnoNotAccessibleException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.BloqueoDeTurnoPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.RecepcionEventoRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * La maquina de estados de la Recepcion (M13, AKINE E-4, DP-16).
 *
 * <p>Llegada, validacion administrativa, camino Particular, espera, llamado y anulacion. El diseno
 * completo esta en {@code docs/diseno/AKINE-E-4-recepcion.md}; aca van las tres reglas que no se
 * pueden perder de vista:
 *
 * <ol>
 *   <li><b>Ninguna transicion prueba que la prestacion ocurrio</b> (DP-05). La Sesion se abre desde
 *       el turno, con o sin recepcion, y esta clase no sabe que la Sesion existe.</li>
 *   <li><b>La elegibilidad no prueba ni impide la atencion.</b> Lo que no cumple termina en
 *       {@code OBSERVADA}, nunca en un 4xx: que falte la orden es el caso mas frecuente del
 *       mostrador, no una excepcion.</li>
 *   <li><b>La hora la pone el servidor</b> en todas las transiciones (RN-M13-001).</li>
 * </ol>
 *
 * <p>Todas las mutaciones exigen {@code turno:manage} en la sede y las lecturas {@code turno:read}:
 * la matriz §32 no tiene fila de recepcion y la mas cercana —gestionar turnos— tiene exactamente
 * los actores de M13 (administrativo y profesional).
 */
@Service
public class CicloDeRecepcionService {

	private static final Logger log = LoggerFactory.getLogger(CicloDeRecepcionService.class);

	/** Motivos de observacion que calcula el servidor. Viajan al principio del texto. */
	static final String OFERTA_SIN_PRACTICA = "OFERTA_SIN_PRACTICA";
	static final String SIN_COBERTURA_APLICABLE = "SIN_COBERTURA_APLICABLE";
	static final String COBERTURA_NO_APLICABLE = "COBERTURA_NO_APLICABLE";
	static final String DOCUMENTACION_INCOMPLETA = "DOCUMENTACION_INCOMPLETA";
	/** Motivo del evento de espera cuando la oferta exige prepago y no hay anticipo (E-6). */
	static final String PREPAGO_PENDIENTE = "PREPAGO_PENDIENTE";

	private final TurnoRepositoryPort turnos;
	private final BloqueoDeTurnoPort bloqueos;
	private final RegistroDeRecepcion registro;
	private final RecepcionEventoRepositoryPort eventos;
	private final ConsultorioDirectory consultorios;
	private final PermissionGuard permissionGuard;
	private final PracticasDeOfertaDirectory practicas;
	private final CoberturasAplicablesDirectory coberturas;
	private final ElegibilidadAdministrativaDirectory elegibilidad;
	private final PrepagoDeRecepcion prepago;

	@SuppressWarnings("checkstyle:ParameterNumber")
	public CicloDeRecepcionService(
			TurnoRepositoryPort turnos,
			BloqueoDeTurnoPort bloqueos,
			RegistroDeRecepcion registro,
			RecepcionEventoRepositoryPort eventos,
			ConsultorioDirectory consultorios,
			PermissionGuard permissionGuard,
			PracticasDeOfertaDirectory practicas,
			CoberturasAplicablesDirectory coberturas,
			ElegibilidadAdministrativaDirectory elegibilidad,
			PrepagoDeRecepcion prepago) {

		this.turnos = turnos;
		this.bloqueos = bloqueos;
		this.registro = registro;
		this.eventos = eventos;
		this.consultorios = consultorios;
		this.permissionGuard = permissionGuard;
		this.practicas = practicas;
		this.coberturas = coberturas;
		this.elegibilidad = elegibilidad;
		this.prepago = prepago;
	}

	/** Lo que devuelve el check-in: la recepcion, si se creo ahora, y el turno tal como quedo. */
	public record ResultadoDeLlegada(RecepcionView recepcion, boolean creada, TurnoView turno) {
	}

	// =================================================================================
	// Llegada — RF-M13-002
	// =================================================================================

	/**
	 * Registra que la persona llego al turno. <b>Idempotente</b>: con una recepcion vigente la
	 * devuelve sin mover la hora ni registrar un evento.
	 *
	 * <p><b>Concurrencia.</b> {@code READ_COMMITTED} y el turno tomado con {@code FOR UPDATE}: dos
	 * check-in simultaneos se serializan sobre la fila del turno y el segundo encuentra la
	 * recepcion del primero —200, no un 409 por duplicado—. Y cuando crea la recepcion fuerza la
	 * version del turno, para que una cancelacion concurrente que leyo el turno antes pierda su
	 * {@code UPDATE ... WHERE version = ?} en vez de dejar un turno cancelado con alguien en la
	 * sala. Ver el §8 del diseno.
	 *
	 * @throws TurnoNotAccessibleException           el turno no existe en esa sede (404)
	 * @throws TransicionDeTurnoNoPermitidaException el turno esta cancelado o ausente (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ResultadoDeLlegada registrarLlegada(OperatingActor actor, long consultorioId, long turnoId) {
		long organizationId = exigirContexto(actor);
		exigirSede(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		Turno turno = bloqueos.bloquear(organizationId, consultorioId, turnoId)
				.orElseThrow(() -> new TurnoNotAccessibleException(turnoId));

		Optional<Recepcion> vigente = registro.vigente(organizationId, turnoId);
		if (vigente.isPresent() && vigente.get().estaAbierta()) {
			return new ResultadoDeLlegada(
					vista(organizationId, consultorioId, turno, vigente.get()), false, TurnoView.de(turno));
		}
		// Una vigente CERRADA solo existe sobre un turno cancelado, y eso lo rechaza esto mismo.
		turno.exigirAdmiteLlegada();

		Instant ahora = Instant.now();
		bloqueos.forzarVersion(turno);
		Recepcion creada = registro.registrar(
				Recepcion.llegada(turno, ahora, actor.accountId()),
				TipoEventoRecepcion.LLEGADA, null, null, actor.accountId(), ahora);

		log.info("Llegada registrada: turnoId={} recepcionId={} consultorioId={}",
				turnoId, creada.getId(), consultorioId);
		return new ResultadoDeLlegada(
				vista(organizationId, consultorioId, turno, creada), true, TurnoView.de(turno));
	}

	// =================================================================================
	// Validacion administrativa — RF-M13-003 y RF-M13-004
	// =================================================================================

	/**
	 * Valida cobertura y documentacion: VALIDADA si hay cobertura aplicable y elegible, OBSERVADA
	 * con el detalle en cualquier otro caso. Ver el §3 del diseno.
	 *
	 * <p><b>No consume nada</b> (RN-M17-001) ni persiste fuera de la recepcion. El dia que se
	 * evalua es el del turno en la zona de la sede: la cobertura tiene que aplicar el dia de la
	 * prestacion.
	 *
	 * @param coberturaId cobertura que elige el operador; {@code null} usa la primera aplicable
	 *                    (la principal va primero)
	 */
	@Transactional
	public RecepcionView validar(
			OperatingActor actor, long consultorioId, long turnoId, Long coberturaId,
			long expectedVersion) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSede(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);
		Turno turno = exigirTurno(organizationId, consultorioId, turnoId);
		Recepcion recepcion = exigirAbierta(organizationId, turnoId);
		exigirVersion(recepcion, expectedVersion);

		Instant ahora = Instant.now();
		EstadoRecepcion anterior = recepcion.getEstado();
		String motivo = aplicarValidacion(organizationId, sede, turno, recepcion, coberturaId,
				actor.accountId(), ahora);

		Recepcion validada = registro.registrar(recepcion, TipoEventoRecepcion.VALIDACION,
				anterior, motivo, actor.accountId(), ahora);
		log.info("Recepcion validada: turnoId={} estado={}", turnoId, validada.getEstado());
		return vista(organizationId, consultorioId, turno, validada);
	}

	/** Aplica la transicion de validacion y devuelve el motivo que va al evento. */
	private String aplicarValidacion(
			long organizationId, ConsultorioSnapshot sede, Turno turno, Recepcion recepcion,
			Long coberturaElegida, long cuentaId, Instant ahora) {

		long consultorioId = sede.id();
		Optional<Long> practica = practicas.practicaPrincipal(
				organizationId, consultorioId, turno.getOfertaId());
		if (practica.isEmpty()) {
			String observacion = OFERTA_SIN_PRACTICA + ": la oferta no declara ninguna practica, "
					+ "asi que no hay convenio contra el cual validar la cobertura.";
			recepcion.observar(observacion, null, null, null, cuentaId, ahora);
			return observacion;
		}
		long practicaId = practica.get();
		LocalDate dia = turno.getInicio().atZone(ZoneId.of(sede.timezone())).toLocalDate();

		List<CoberturaAplicable> aplicables = coberturas.aplicables(
				organizationId, consultorioId, turno.getPersonaId(), practicaId, turno.getOfertaId(),
				dia);
		if (aplicables.isEmpty()) {
			String observacion = SIN_COBERTURA_APLICABLE + ": la persona no tiene ninguna "
					+ "cobertura que aplique a esta practica el " + dia + ".";
			recepcion.observar(observacion, practicaId, null, null, cuentaId, ahora);
			return observacion;
		}

		Optional<CoberturaAplicable> elegida = coberturaElegida == null
				? Optional.of(aplicables.get(0))
				: aplicables.stream().filter(c -> c.coberturaId() == coberturaElegida).findFirst();
		if (elegida.isEmpty()) {
			String observacion = COBERTURA_NO_APLICABLE + ": la cobertura " + coberturaElegida
					+ " no aplica a esta practica el " + dia + ".";
			recepcion.observar(observacion, practicaId, coberturaElegida, null, cuentaId, ahora);
			return observacion;
		}

		long coberturaId = elegida.get().coberturaId();
		VeredictoDeElegibilidad veredicto = elegibilidad.evaluar(
				organizationId, consultorioId, turno.getPersonaId(), coberturaId, practicaId, dia);
		if (veredicto.elegible()) {
			recepcion.validarConCobertura(practicaId, coberturaId, veredicto.convenioId(), cuentaId, ahora);
			return null;
		}
		String observacion = DOCUMENTACION_INCOMPLETA + ": "
				+ (veredicto.faltantes().isEmpty()
						? String.valueOf(veredicto.motivo())
						: String.join(" | ", veredicto.faltantes()));
		recepcion.observar(observacion, practicaId, coberturaId, veredicto.convenioId(), cuentaId, ahora);
		return observacion;
	}

	// =================================================================================
	// Particular — RF-M13-005, RN-M13-004
	// =================================================================================

	/**
	 * Decision explicita del operador de atender como Particular, con motivo. No toca la
	 * cobertura maestra del paciente (RN-M13-004) y no bloquea la atencion.
	 */
	@Transactional
	public RecepcionView atenderComoParticular(
			OperatingActor actor, long consultorioId, long turnoId, String motivo,
			long expectedVersion) {

		return transicionar(actor, consultorioId, turnoId, expectedVersion,
				TipoEventoRecepcion.PARTICULAR, (prepagoActual) -> motivo,
				(recepcion, ahora) -> recepcion.atenderComoParticular(motivo, actor.accountId(), ahora));
	}

	// =================================================================================
	// Espera, llamado y anulacion
	// =================================================================================

	/**
	 * Pasa a espera. <b>Un prepago pendiente no lo impide</b> (AKINE E-6, DP-06): la politica de
	 * prepago de la oferta alerta, nunca condiciona la atencion. Lo que hace es dejarlo escrito en
	 * el evento de la transicion —motivo {@code PREPAGO_PENDIENTE}—, para que conste que la persona
	 * paso a la sala sin el anticipo que el centro exige.
	 */
	@Transactional
	public RecepcionView pasarAEspera(
			OperatingActor actor, long consultorioId, long turnoId, long expectedVersion) {

		return transicionar(actor, consultorioId, turnoId, expectedVersion,
				TipoEventoRecepcion.ESPERA,
				prepagoActual -> prepagoActual.pendiente()
						? PREPAGO_PENDIENTE + ": la oferta exige prepago y no se registro ningun "
								+ "anticipo para este turno"
						: null,
				(recepcion, ahora) -> recepcion.pasarAEspera(ahora));
	}

	/** Llamar no abre la Sesion: son dos actos de dos personas (DP-05). */
	@Transactional
	public RecepcionView llamar(
			OperatingActor actor, long consultorioId, long turnoId, long expectedVersion) {

		return transicionar(actor, consultorioId, turnoId, expectedVersion,
				TipoEventoRecepcion.LLAMADO, prepagoActual -> null,
				(recepcion, ahora) -> recepcion.llamar(actor.accountId(), ahora));
	}

	/**
	 * Anula un check-in hecho por error. <b>No es idempotente</b>: sin recepcion abierta es 409,
	 * porque entre dos clicks el turno pudo cambiar y un 200 en silencio le haria creer al
	 * operador que revirtio algo.
	 *
	 * @param expectedVersion {@code null} solo desde el endpoint deprecado de 05.04, que no la pedia
	 */
	@Transactional
	public RecepcionView anular(
			OperatingActor actor, long consultorioId, long turnoId, String motivo,
			Long expectedVersion) {

		return transicionar(actor, consultorioId, turnoId, expectedVersion,
				TipoEventoRecepcion.ANULACION, prepagoActual -> motivo,
				(recepcion, ahora) -> recepcion.anular(motivo, actor.accountId(), ahora));
	}

	/**
	 * El {@code DELETE /turnos/{id}/llegada} de 05.04, deprecado: anula la recepcion abierta sin
	 * control de version —aquel endpoint no la pedia— y devuelve el turno, que no cambia.
	 */
	@Transactional
	public TurnoView deshacerLlegadaDeprecada(OperatingActor actor, long consultorioId, long turnoId) {
		anular(actor, consultorioId, turnoId,
				"Check-in deshecho desde el endpoint deprecado de 05.04", null);
		return TurnoView.de(exigirTurno(exigirContexto(actor), consultorioId, turnoId));
	}

	// =================================================================================
	// Lectura
	// =================================================================================

	/** La recepcion vigente del turno, abierta o cerrada. */
	@Transactional(readOnly = true)
	public RecepcionView ver(OperatingActor actor, long consultorioId, long turnoId) {
		long organizationId = exigirContexto(actor);
		exigirSede(organizationId, consultorioId);
		exigirLectura(actor, organizationId, consultorioId);
		Turno turno = exigirTurno(organizationId, consultorioId, turnoId);
		return registro.vigente(organizationId, turnoId)
				.map(recepcion -> vista(organizationId, consultorioId, turno, recepcion))
				.orElseThrow(() -> new RecepcionNotAccessibleException(turnoId));
	}

	/**
	 * Todas las transiciones de todas las recepciones del turno, anuladas incluidas. Se exige el
	 * turno ANTES de leer: un historial vacio con 200 para un turno ajeno confirmaria que el id no
	 * existe aca.
	 */
	@Transactional(readOnly = true)
	public List<EventoDeRecepcionView> historial(OperatingActor actor, long consultorioId, long turnoId) {
		long organizationId = exigirContexto(actor);
		exigirSede(organizationId, consultorioId);
		exigirLectura(actor, organizationId, consultorioId);
		exigirTurno(organizationId, consultorioId, turnoId);
		return eventos.historial(organizationId, turnoId).stream()
				.map(EventoDeRecepcionView::de)
				.toList();
	}

	// =================================================================================
	// Plantilla de transicion
	// =================================================================================

	@FunctionalInterface
	private interface Transicion {
		void aplicar(Recepcion recepcion, Instant ahora);
	}

	/** El motivo del evento, que puede depender del prepago ANTES de la transicion (E-6). */
	@FunctionalInterface
	private interface MotivoDelEvento {
		String de(PrepagoView prepagoAntes);
	}

	private RecepcionView transicionar(
			OperatingActor actor, long consultorioId, long turnoId, Long expectedVersion,
			TipoEventoRecepcion tipo, MotivoDelEvento motivoDe, Transicion transicion) {

		long organizationId = exigirContexto(actor);
		exigirSede(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);
		Turno turno = exigirTurno(organizationId, consultorioId, turnoId);
		Recepcion recepcion = exigirAbierta(organizationId, turnoId);
		if (expectedVersion != null) {
			exigirVersion(recepcion, expectedVersion);
		}

		Instant ahora = Instant.now();
		EstadoRecepcion anterior = recepcion.getEstado();
		String motivo = motivoDe.de(prepago.de(organizationId, consultorioId, turno, recepcion));
		transicion.aplicar(recepcion, ahora);
		Recepcion guardada = registro.registrar(recepcion, tipo, anterior,
				motivo == null || motivo.isBlank() ? null : motivo.strip(), actor.accountId(), ahora);

		log.info("Recepcion {}: turnoId={} {} -> {}", tipo, turnoId, anterior, guardada.getEstado());
		return vista(organizationId, consultorioId, turno, guardada);
	}

	/** La recepcion con su prepago calculado al leer (E-6). */
	private RecepcionView vista(long organizationId, long consultorioId, Turno turno, Recepcion recepcion) {
		return RecepcionView.de(recepcion, prepago.de(organizationId, consultorioId, turno, recepcion));
	}

	// =================================================================================
	// Precondiciones
	// =================================================================================

	private Turno exigirTurno(long organizationId, long consultorioId, long turnoId) {
		return turnos.findByIdInScope(organizationId, consultorioId, turnoId)
				.orElseThrow(() -> new TurnoNotAccessibleException(turnoId));
	}

	private Recepcion exigirAbierta(long organizationId, long turnoId) {
		return registro.vigente(organizationId, turnoId)
				.filter(Recepcion::estaAbierta)
				.orElseThrow(() -> new TransicionDeRecepcionNoPermitidaException(turnoId,
						"no hay ninguna recepcion abierta: primero hay que registrar la llegada"));
	}

	/** Se compara ANTES de mutar: el {@code @Version} solo veria la carrera, no la version vieja. */
	private static void exigirVersion(Recepcion recepcion, long expectedVersion) {
		if (recepcion.getVersion() != expectedVersion) {
			throw new OptimisticLockingFailureException(
					"La recepcion del turno " + recepcion.getTurnoId() + " cambio desde que se leyo: "
							+ "version " + expectedVersion + " contra " + recepcion.getVersion());
		}
	}

	/** Falta de contexto es <b>403 y nunca 401</b>: un 401 deja al frontend en bucle de login. */
	private static long exigirContexto(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("La recepcion requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	private ConsultorioSnapshot exigirSede(long organizationId, long consultorioId) {
		return consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	private void exigirGestion(OperatingActor actor, long organizationId, long consultorioId) {
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), PermissionCodes.TURNO_MANAGE,
				organizationId, consultorioId, null, Instant.now()));
	}

	private void exigirLectura(OperatingActor actor, long organizationId, long consultorioId) {
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), PermissionCodes.TURNO_READ,
				organizationId, consultorioId, null, Instant.now()));
	}
}
