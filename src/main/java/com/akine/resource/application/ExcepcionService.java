package com.akine.resource.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import com.akine.platform.spi.transaccion.FilaLockPrevia;
import com.akine.resource.domain.DisponibilidadExcepcion;
import com.akine.resource.domain.PermissionCodes;
import com.akine.resource.domain.TipoExcepcion;
import com.akine.resource.domain.exception.BloqueoConTurnosAfectadosException;
import com.akine.resource.domain.exception.ConsultorioNotAccessibleException;
import com.akine.resource.domain.exception.ConsultorioNotOperableException;
import com.akine.resource.domain.exception.ExcepcionInactivaException;
import com.akine.resource.domain.exception.ExcepcionNotAccessibleException;
import com.akine.resource.domain.exception.ProfesionalNoVinculadoException;
import com.akine.resource.domain.exception.ProfesionalNotAccessibleException;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.CalendarioSedeRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.DisponibilidadExcepcionRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Cierres y aperturas puntuales de disponibilidad (M05, RF-M05-004).
 *
 * <h2>Las dos autorizaciones son las mismas que las del horario semanal</h2>
 *
 * <p>Mutaciones: {@code consultorio:manage}. Lecturas: {@code colaborador:read}. Sin codigos
 * nuevos y por el mismo razonamiento que {@code DisponibilidadService} documenta: la matriz
 * seccion 6 le niega {@code consultorio:manage} a {@code PROFESIONAL} y {@code ADMINISTRATIVO},
 * y esa sola linea es la que implementa "el profesional no se carga sus propias ausencias".
 *
 * <h2>El alcance de sede es el caso peligroso, no el caso raro</h2>
 *
 * <p>Una excepcion con {@code membershipId == null} es de la SEDE ENTERA y afecta a TODOS los
 * profesionales. Un CIERRE de sede es lo esperable —un corte de luz, un feriado local—, pero una
 * APERTURA de sede en un feriado <b>reemplaza el horario base de todos ellos</b> (diseno §4): el
 * dia entero pasa a ser esa apertura. Por eso el alta de una excepcion de sede se registra en la
 * auditoria con su alcance explicito, y por eso {@link ExcepcionAltaCommand} nombra el caso en su
 * javadoc en vez de dejarlo como un {@code null} mas.
 *
 * <h2>Por que el alta toma el lock de la sede si no valida solapamiento</h2>
 *
 * <p>Dos excepciones que se pisan no son un conflicto: dos cierres superpuestos cierran lo mismo,
 * y el calculador los aplica en un orden total y explicito. Lo que si hace falta serializar es la
 * IDEMPOTENCIA de CA-M05-004-05: sin el lock, dos reintentos de red simultaneos comprueban los dos
 * que la excepcion no existe y los dos la insertan. El lock es el mismo {@code FOR UPDATE} sobre
 * {@code consultorio_calendario} que usan los bloques, y se toma ANTES de la primera lectura: leer
 * primero y bloquear despues es una escalada S-&gt;X, o sea un deadlock y no una espera.
 *
 * <h2>Auditoria y baja</h2>
 *
 * <p>La auditoria se escribe DENTRO de la transaccion del negocio. La baja es LOGICA con motivo
 * obligatorio: la excepcion deja de computar y conserva intacta su historia (RN-M05-003).
 */
@Service
public class ExcepcionService {

	private static final Logger log = LoggerFactory.getLogger(ExcepcionService.class);

	private final DisponibilidadExcepcionRepositoryPort excepciones;
	private final CalendarioSedeRepositoryPort calendarios;
	private final CalendarioSedeIniciador calendarioIniciador;
	private final ConsultorioDirectory consultorioDirectory;
	private final ConsultorioMembershipDirectory membershipDirectory;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;
	private final SimuladorDeImpacto simulador;
	private final AccountIdentityDirectory identidades;
	private final DisponibilidadEntreSedes entreSedes;
	private final TransactionTemplate transaccionReadCommitted;

	/** Sin nombres de autor: los dobles de test anteriores a la agenda operativa. */
	public ExcepcionService(
			DisponibilidadExcepcionRepositoryPort excepciones,
			CalendarioSedeRepositoryPort calendarios,
			CalendarioSedeIniciador calendarioIniciador,
			ConsultorioDirectory consultorioDirectory,
			ConsultorioMembershipDirectory membershipDirectory,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail,
			SimuladorDeImpacto simulador,
			PlatformTransactionManager transactionManager) {
		this(excepciones, calendarios, calendarioIniciador, consultorioDirectory, membershipDirectory,
				permissionGuard, auditTrail, simulador, null, transactionManager);
	}

	/** Sin validacion entre sedes: los dobles de test anteriores a la disponibilidad central (0.158.0). */
	public ExcepcionService(
			DisponibilidadExcepcionRepositoryPort excepciones,
			CalendarioSedeRepositoryPort calendarios,
			CalendarioSedeIniciador calendarioIniciador,
			ConsultorioDirectory consultorioDirectory,
			ConsultorioMembershipDirectory membershipDirectory,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail,
			SimuladorDeImpacto simulador,
			AccountIdentityDirectory identidades,
			PlatformTransactionManager transactionManager) {
		this(excepciones, calendarios, calendarioIniciador, consultorioDirectory, membershipDirectory,
				permissionGuard, auditTrail, simulador, identidades, transactionManager, null);
	}

	@Autowired
	@SuppressWarnings("checkstyle:ParameterNumber")
	public ExcepcionService(
			DisponibilidadExcepcionRepositoryPort excepciones,
			CalendarioSedeRepositoryPort calendarios,
			CalendarioSedeIniciador calendarioIniciador,
			ConsultorioDirectory consultorioDirectory,
			ConsultorioMembershipDirectory membershipDirectory,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail,
			SimuladorDeImpacto simulador,
			AccountIdentityDirectory identidades,
			PlatformTransactionManager transactionManager,
			DisponibilidadEntreSedes entreSedes) {

		this.entreSedes = entreSedes;
		this.identidades = identidades;
		this.excepciones = excepciones;
		this.calendarios = calendarios;
		this.calendarioIniciador = calendarioIniciador;
		this.consultorioDirectory = consultorioDirectory;
		this.membershipDirectory = membershipDirectory;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
		this.simulador = simulador;
		this.transaccionReadCommitted = FilaLockPrevia.transaccionDeNegocio(
				transactionManager, Isolation.READ_COMMITTED);
	}

	// =================================================================================
	// Alta (RF-M05-004)
	// =================================================================================

	/**
	 * Da de alta un cierre o una apertura.
	 *
	 * <pre>
	 *   1. contexto y sede de la ruta        -&gt; 403 sin contexto, 404 si es de otro tenant
	 *   2. consultorio:manage sobre la sede  -&gt; 403
	 *   3. si el alcance es un profesional, que el vinculo lo habilite en esa sede Y que atienda
	 *   4. lockByScope                       &lt;- ANTES de leer ninguna excepcion
	 *   5. coincidencia exacta -&gt; devolver esa excepcion (CA-M05-004-05)
	 *   6. guardar y auditar EN LA MISMA TRANSACCION
	 * </pre>
	 *
	 * <p><b>Una excepcion de SEDE no exige ninguna membership vigente</b> y el paso 3 se saltea:
	 * el cierre de un feriado local no depende de que haya alguien vinculado hoy.
	 *
	 * @throws ConsultorioNotAccessibleException si la sede no existe o es de otro tenant (404)
	 * @throws ConsultorioNotOperableException si la sede esta dada de baja (409)
	 * @throws ProfesionalNotAccessibleException si la membership no existe en el tenant (404)
	 * @throws ProfesionalNoVinculadoException si la membership no habilita en esa sede o si su rol
	 *         no atiende pacientes (409)
	 * @throws IllegalArgumentException si el rango de fechas o el horario son incoherentes (400)
	 */
	public ExcepcionView crear(
			OperatingActor actor, long consultorioId, ExcepcionAltaCommand command) {

		long organizationId = exigirContextoDeLaSede(actor, consultorioId);
		exigirSedeOperable(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId, command.membershipId());

		// Las filas-lock ANTES de abrir la transaccion (FilaLockPrevia): la de la sede, la de las otras
		// sedes que una sustitucion va a cerrar, y la de la persona (disponibilidad central, 0.158.0).
		Set<Long> sedes = sedesQueSeEscriben(organizationId, consultorioId, command.membershipId(),
				command.tipo() == TipoExcepcion.APERTURA && command.sustituirDisponibilidadSuperpuesta());
		sedes.forEach(sede -> calendarioIniciador.asegurar(organizationId, sede));
		if (deUnaPersona(command.membershipId())) {
			entreSedes.asegurarPersona(organizationId, command.membershipId());
		}

		return conNombreDelAutor(organizationId, transaccionReadCommitted.execute(estado -> crearEnTransaccion(
				actor, consultorioId, command, organizationId, sedes)));
	}

	private boolean deUnaPersona(Long membershipId) {
		return membershipId != null && entreSedes != null;
	}

	/** La sede de la operacion y, si se sustituye, las otras sedes de la persona. Ascendente. */
	private Set<Long> sedesQueSeEscriben(long organizationId, long consultorioId, Long membershipId, boolean sustituye) {
		Set<Long> sedes = new java.util.TreeSet<>();
		sedes.add(consultorioId);
		if (sustituye && deUnaPersona(membershipId)) {
			sedes.addAll(entreSedes.otrasSedesDeLaPersona(organizationId, consultorioId, membershipId));
		}
		return sedes;
	}

	/**
	 * Locks en orden fijo: las sedes por id ascendente y despues la persona. Es el orden sede → persona de
	 * los turnos y las clases. Devuelve las memberships de la persona, o vacio si es de sede.
	 */
	private Set<Long> bloquear(long organizationId, Set<Long> sedes, Long membershipId) {
		sedes.forEach(sede -> bloquearLaSede(organizationId, sede));
		return deUnaPersona(membershipId) ? entreSedes.bloquearPersona(organizationId, membershipId) : Set.of();
	}

	private ExcepcionView crearEnTransaccion(
			OperatingActor actor, long consultorioId, ExcepcionAltaCommand command,
			long organizationId, Set<Long> sedes) {

		Instant ahora = Instant.now();
		Long membershipId = command.membershipId();
		if (membershipId != null) {
			exigirProfesionalDeLaSede(organizationId, consultorioId, membershipId, ahora);
		}

		Set<Long> mismaPersona = bloquear(organizationId, sedes, membershipId);

		Optional<DisponibilidadExcepcion> yaCargada =
				buscarCoincidenciaExacta(organizationId, consultorioId, command);
		if (yaCargada.isPresent()) {
			// Reintento de red. Devolver la fila que ya existe —y NO auditar de nuevo— deja el
			// estado y el historial exactamente como los dejo el primer intento.
			log.info("Alta de excepcion idempotente: consultorioId={} excepcionId={}",
					consultorioId, yaCargada.get().getId());
			return ExcepcionView.de(yaCargada.get());
		}

		// TURNO.md §22: un cierre que deja turnos en conflicto no se guarda en silencio. Va bajo el lock
		// de la sede y despues de la idempotencia: el reintento de un cierre ya cargado no se rechaza.
		if (command.tipo() == TipoExcepcion.CIERRE && !command.confirmaTurnosAfectados()) {
			DisponibilidadExcepcion propuesta = new DisponibilidadExcepcion(
					organizationId, consultorioId, membershipId, command.tipo(), command.motivo(),
					command.fechaDesde(), command.fechaHasta(), command.horaDesde(), command.horaHasta(),
					command.feriadoId(), command.notes());
			ConsultorioSnapshot sede =
					AutorizacionDeSede.exigirSedeDelTenant(consultorioDirectory, organizationId, consultorioId);
			ImpactoDeDisponibilidad impacto = simulador.deAltaDeExcepcion(sede, propuesta, ahora);
			// Disponibilidad central (0.158.0): las clases que quedan sin instructor, con la misma confirmacion.
			List<BloqueoConTurnosAfectadosException.Clase> clases = clasesAfectadas(sede, propuesta, ahora);
			if (impacto.hayAlgo() || !clases.isEmpty()) {
				// Con la lista: la pantalla arma la tabla de afectados con el 409 (checklist §6.2).
				throw impacto.comoRechazo().conClases(clases);
			}
		}

		DisponibilidadExcepcion nueva = new DisponibilidadExcepcion(
				organizationId,
				consultorioId,
				membershipId,
				command.tipo(),
				command.motivo(),
				command.fechaDesde(),
				command.fechaHasta(),
				command.horaDesde(),
				command.horaHasta(),
				command.feriadoId(),
				command.notes());
		nueva.registrarAutor(actor.accountId());
		DisponibilidadExcepcion guardada = excepciones.save(nueva);

		// Disponibilidad central (0.158.0): una apertura de profesional no puede pisar lo reservado en otra
		// sede (siempre 409) ni ofrecer doble disponibilidad (409 salvo sustitucion explicita). Despues de
		// guardar, para que los cierres de la sustitucion nombren la apertura; un rechazo deshace todo.
		List<DisponibilidadExcepcion> sustituidas = validarAperturaEntreSedes(
				actor, organizationId, consultorioId, guardada, mismaPersona,
				command.sustituirDisponibilidadSuperpuesta(), ahora);

		Map<String, String> detalles = new LinkedHashMap<>();
		if (!sustituidas.isEmpty()) {
			detalles.put("cierresPorSustitucion", sustituidas.stream()
					.map(cierre -> String.valueOf(cierre.getId())).collect(java.util.stream.Collectors.joining(",")));
		}
		detalles.put("alcance", membershipId == null ? "SEDE" : "PROFESIONAL");
		if (membershipId != null) {
			detalles.put("membershipId", String.valueOf(membershipId));
		}
		detalles.put("tipo", command.tipo().name());
		detalles.put("motivo", command.motivo().name());
		detalles.put("fechas", command.fechaDesde() + " -> " + command.fechaHasta());
		detalles.put("horario", command.horaDesde() == null
				? "DIA COMPLETO"
				: command.horaDesde() + " -> " + command.horaHasta());
		ResumenDeAgendaParaAuditoria.con(detalles, null, ResumenDeAgendaParaAuditoria.de(guardada));
		auditar(AuditEvents.DISPONIBILIDAD_EXCEPCION_CREATED, guardada, actor.accountId(),
				null, "ACTIVO", null, AuditEvents.conActor(actor, guardada.getMembershipId(), detalles), ahora);

		log.info("Excepcion de disponibilidad creada: consultorioId={} excepcionId={} alcance={}",
				consultorioId, guardada.getId(), membershipId == null ? "SEDE" : membershipId);

		// nueva = true SOLO en este camino: el controller responde 201 aca y 200 en el reintento
		// idempotente de mas arriba.
		return ExcepcionView.nueva(guardada);
	}

	// =================================================================================
	// Edicion (checklist de agenda §2.5)
	// =================================================================================

	/**
	 * Edita una excepcion activa: motivo, fechas, horario y notas. Mismo patron que la edicion de un
	 * bloque ({@code DisponibilidadService#editar}): lock de la sede antes de leer, version comparada
	 * antes de mutar, impacto calculado contra la disponibilidad efectiva antes y despues, y auditoria
	 * con el antes y el despues de cada campo que cambio.
	 *
	 * <p>Si el cambio deja turnos pendientes afuera y no se confirmo, 409
	 * {@code bloqueo-con-turnos-afectados} con la lista, igual que el alta. Confirmado, los turnos no se
	 * tocan: quedan reservados y la agenda los marca fuera de disponibilidad.
	 *
	 * @throws ExcepcionNotAccessibleException     no existe, es de otra sede o de otro tenant (404)
	 * @throws ExcepcionInactivaException          ya estaba dada de baja (409)
	 * @throws org.springframework.dao.OptimisticLockingFailureException la version quedo vieja (409)
	 * @throws com.akine.resource.domain.exception.MotivoOtroSinNotasException motivo OTRO sin notas (400)
	 */
	public ExcepcionView editar(
			OperatingActor actor, long consultorioId, long excepcionId, ExcepcionEdicionCommand command) {

		long organizationId = exigirContextoDeLaSede(actor, consultorioId);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestionDeExcepcion(actor, organizationId, consultorioId, excepcionId);

		// Las filas-lock ANTES de abrir la transaccion (FilaLockPrevia). El profesional y el tipo salen de
		// una lectura sin lock: no se editan, asi que no cambian entre esta lectura y la transaccion.
		Optional<DisponibilidadExcepcion> previa =
				excepciones.findByIdScoped(excepcionId, organizationId, consultorioId);
		Long membershipId = previa.map(DisponibilidadExcepcion::getMembershipId).orElse(null);
		boolean sustituye = command.sustituirDisponibilidadSuperpuesta()
				&& previa.map(e -> e.getTipo() == TipoExcepcion.APERTURA).orElse(false);
		Set<Long> sedes = sedesQueSeEscriben(organizationId, consultorioId, membershipId, sustituye);
		sedes.forEach(sede -> calendarioIniciador.asegurar(organizationId, sede));
		if (deUnaPersona(membershipId)) {
			entreSedes.asegurarPersona(organizationId, membershipId);
		}

		ExcepcionView editada = transaccionReadCommitted.execute(estado -> editarEnTransaccion(
				actor, consultorioId, excepcionId, command, organizationId, sedes, membershipId));
		return conNombreDelAutor(organizationId, editada);
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	private ExcepcionView editarEnTransaccion(
			OperatingActor actor, long consultorioId, long excepcionId, ExcepcionEdicionCommand command,
			long organizationId, Set<Long> sedes, Long membershipId) {

		Instant ahora = Instant.now();
		Set<Long> mismaPersona = bloquear(organizationId, sedes, membershipId);

		DisponibilidadExcepcion excepcion = excepciones
				.findByIdScoped(excepcionId, organizationId, consultorioId)
				.orElseThrow(() -> new ExcepcionNotAccessibleException(excepcionId));
		if (!excepcion.isOperable()) {
			throw new ExcepcionInactivaException(excepcionId);
		}
		if (excepcion.getVersion() != command.expectedVersion()) {
			throw new org.springframework.dao.OptimisticLockingFailureException(
					"La excepcion de disponibilidad fue modificada por otra operacion");
		}

		// Transitoria: valida fechas, horario y notas igual que el alta, y es la que se simula.
		DisponibilidadExcepcion propuesta = new DisponibilidadExcepcion(
				organizationId, consultorioId, excepcion.getMembershipId(), excepcion.getTipo(),
				command.motivo(), command.fechaDesde(), command.fechaHasta(),
				command.horaDesde(), command.horaHasta(), excepcion.getFeriadoId(), command.notes());

		ConsultorioSnapshot sede =
				AutorizacionDeSede.exigirSedeDelTenant(consultorioDirectory, organizationId, consultorioId);
		ImpactoDeDisponibilidad impacto = simulador.deEdicionDeExcepcion(sede, excepcion, propuesta, ahora);
		// Disponibilidad central (0.158.0): solo las clases que la edicion suma, no las que ya alcanzaba.
		List<BloqueoConTurnosAfectadosException.Clase> clases = new java.util.ArrayList<>(
				clasesAfectadas(sede, propuesta, ahora));
		clases.removeAll(clasesAfectadas(sede, excepcion, ahora));
		if ((impacto.hayAlgo() || !clases.isEmpty()) && !command.confirmaTurnosAfectados()) {
			throw impacto.comoRechazo().conClases(clases);
		}

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("alcance", excepcion.esDeSede() ? "SEDE" : "PROFESIONAL");
		if (!excepcion.esDeSede()) {
			detalles.put("membershipId", String.valueOf(excepcion.getMembershipId()));
		}
		detalles.put("tipo", excepcion.getTipo().name());
		anotarCambio(detalles, "motivo", excepcion.getMotivo(), propuesta.getMotivo());
		anotarCambio(detalles, "fechaDesde", excepcion.getFechaDesde(), propuesta.getFechaDesde());
		anotarCambio(detalles, "fechaHasta", excepcion.getFechaHasta(), propuesta.getFechaHasta());
		anotarCambio(detalles, "horario", horarioDe(excepcion), horarioDe(propuesta));
		anotarCambio(detalles, "notes", excepcion.getNotes(), propuesta.getNotes());
		if (impacto.hayAlgo()) {
			detalles.put("turnosAfectados", String.valueOf(impacto.turnosAfectados()));
		}
		ResumenDeAgendaParaAuditoria.con(detalles, ResumenDeAgendaParaAuditoria.de(excepcion),
				ResumenDeAgendaParaAuditoria.de(propuesta));

		excepcion.updateDatos(
				propuesta.getMotivo(), propuesta.getFechaDesde(), propuesta.getFechaHasta(),
				propuesta.getHoraDesde(), propuesta.getHoraHasta(), propuesta.getHoraDesde() == null,
				propuesta.getNotes() == null ? "" : propuesta.getNotes());
		DisponibilidadExcepcion guardada = excepciones.save(excepcion);

		// Disponibilidad central (0.158.0): la apertura editada vuelve a validarse contra las otras sedes.
		List<DisponibilidadExcepcion> sustituidas = validarAperturaEntreSedes(
				actor, organizationId, consultorioId, guardada, mismaPersona,
				command.sustituirDisponibilidadSuperpuesta(), ahora);
		if (!sustituidas.isEmpty()) {
			detalles.put("cierresPorSustitucion", sustituidas.stream()
					.map(cierre -> String.valueOf(cierre.getId())).collect(java.util.stream.Collectors.joining(",")));
		}
		if (!clases.isEmpty()) {
			detalles.put("clasesAfectadas", String.valueOf(clases.size()));
		}

		auditar(AuditEvents.DISPONIBILIDAD_EXCEPCION_UPDATED, guardada, actor.accountId(),
				"ACTIVO", "ACTIVO", null, AuditEvents.conActor(actor, guardada.getMembershipId(), detalles), ahora);

		log.info("Excepcion de disponibilidad editada: consultorioId={} excepcionId={} turnosAfectados={}",
				consultorioId, excepcionId, impacto.turnosAfectados());
		return ExcepcionView.de(guardada);
	}

	/** Ver {@link DisponibilidadEntreSedes#validarApertura}. Solo aperturas de profesional. */
	private List<DisponibilidadExcepcion> validarAperturaEntreSedes(
			OperatingActor actor, long organizationId, long consultorioId, DisponibilidadExcepcion apertura,
			Set<Long> mismaPersona, boolean sustituir, Instant ahora) {

		if (apertura.getTipo() != TipoExcepcion.APERTURA || !deUnaPersona(apertura.getMembershipId())) {
			return List.of();
		}
		ConsultorioSnapshot sede =
				AutorizacionDeSede.exigirSedeDelTenant(consultorioDirectory, organizationId, consultorioId);
		return entreSedes.validarApertura(actor, sede, apertura, mismaPersona, sustituir, ahora);
	}

	/** Ver {@link DisponibilidadEntreSedes#clasesAfectadas}. Vacio en una apertura o sin el validador. */
	private List<BloqueoConTurnosAfectadosException.Clase> clasesAfectadas(
			ConsultorioSnapshot sede, DisponibilidadExcepcion cierre, Instant ahora) {
		if (entreSedes == null || cierre.getTipo() != TipoExcepcion.CIERRE) {
			return List.of();
		}
		return entreSedes.clasesAfectadas(sede, cierre, ahora);
	}

	private static String horarioDe(DisponibilidadExcepcion excepcion) {
		return excepcion.getHoraDesde() == null
				? "DIA COMPLETO"
				: excepcion.getHoraDesde() + " -> " + excepcion.getHoraHasta();
	}

	private static void anotarCambio(Map<String, String> detalles, String campo, Object antes, Object despues) {
		if (!Objects.equals(antes, despues)) {
			detalles.put(campo, antes + " -> " + despues);
		}
	}

	// =================================================================================
	// Baja logica
	// =================================================================================

	/**
	 * Da de baja una excepcion. Motivo obligatorio, sin borrado fisico.
	 *
	 * <p>No exige que la membership de la excepcion siga vinculada: una excepcion de un
	 * profesional que ya se fue tiene que poder ordenarse igual, que es lo mismo que
	 * {@code DisponibilidadService#darDeBaja} sostiene para los bloques (RN-M05-003). Tampoco
	 * exige que la sede este activa: es una de las operaciones con las que se ordena el calendario
	 * de una sede que se esta cerrando.
	 *
	 * @throws ExcepcionNotAccessibleException si no existe, es de otra sede o de otro tenant (404)
	 * @throws ExcepcionInactivaException si ya estaba dada de baja (409)
	 * @throws IllegalArgumentException si no se declaro un motivo (400)
	 */
	public ExcepcionView darDeBaja(
			OperatingActor actor, long consultorioId, long excepcionId, String motivo) {

		long organizationId = exigirContextoDeLaSede(actor, consultorioId);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestionDeExcepcion(actor, organizationId, consultorioId, excepcionId);

		// La fila-lock de la sede, ANTES de abrir la transaccion (FilaLockPrevia).
		calendarioIniciador.asegurar(organizationId, consultorioId);

		return transaccionReadCommitted.execute(estado -> darDeBajaEnTransaccion(
				actor, consultorioId, excepcionId, motivo, organizationId));
	}

	private ExcepcionView darDeBajaEnTransaccion(
			OperatingActor actor, long consultorioId, long excepcionId, String motivo,
			long organizationId) {

		exigirMotivo(motivo);

		Instant ahora = Instant.now();
		bloquearLaSede(organizationId, consultorioId);

		DisponibilidadExcepcion excepcion = excepciones
				.findByIdScoped(excepcionId, organizationId, consultorioId)
				.orElseThrow(() -> new ExcepcionNotAccessibleException(excepcionId));
		if (!excepcion.isOperable()) {
			throw new ExcepcionInactivaException(excepcionId);
		}

		excepcion.deactivate(ahora, motivo);
		DisponibilidadExcepcion guardada = excepciones.save(excepcion);

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("alcance", guardada.esDeSede() ? "SEDE" : "PROFESIONAL");
		detalles.put("tipo", guardada.getTipo().name());
		ResumenDeAgendaParaAuditoria.con(detalles, ResumenDeAgendaParaAuditoria.de(guardada), null);
		auditar(AuditEvents.DISPONIBILIDAD_EXCEPCION_DEACTIVATED, guardada, actor.accountId(),
				"ACTIVO", "INACTIVO", motivo, AuditEvents.conActor(actor, guardada.getMembershipId(), detalles), ahora);

		log.info("Excepcion de disponibilidad dada de baja: consultorioId={} excepcionId={}",
				consultorioId, excepcionId);

		return ExcepcionView.de(guardada);
	}

	// =================================================================================
	// Lectura
	// =================================================================================

	/**
	 * Que turnos dejaria afuera cargar esta excepcion, <b>sin cargarla</b> (A-11, RN-M05-004).
	 *
	 * <p>Un CIERRE de un profesional evalua sus turnos; uno de sede, los de todos los profesionales
	 * de la sede. Una APERTURA responde cero: solo agrega. Misma autorizacion que el alta
	 * —{@code consultorio:manage}— pero sin lock, sin escritura y sin auditoria. No exige la sede
	 * activa ni el vinculo vigente: eso lo rechaza el alta, y la consulta previa no tiene por que
	 * adelantar ese 409 para contestar cuantos turnos hay.
	 *
	 * @throws IllegalArgumentException si las fechas o el horario son incoherentes (400)
	 */
	@Transactional(readOnly = true)
	public ImpactoDeDisponibilidad simularAlta(
			OperatingActor actor, long consultorioId, ExcepcionAltaCommand command) {

		long organizationId = exigirContextoDeLaSede(actor, consultorioId);
		var sede = AutorizacionDeSede.exigirSedeDelTenant(
				consultorioDirectory, organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId, command.membershipId());
		if (command.membershipId() != null) {
			exigirProfesionalDelTenant(organizationId, command.membershipId());
		}

		// Transitoria: nunca se guarda. El constructor valida fechas y horario igual que en el alta.
		DisponibilidadExcepcion propuesta = new DisponibilidadExcepcion(
				organizationId, consultorioId, command.membershipId(), command.tipo(),
				command.motivo(), command.fechaDesde(), command.fechaHasta(),
				command.horaDesde(), command.horaHasta(), command.feriadoId(), command.notes());
		return simulador.deAltaDeExcepcion(sede, propuesta, Instant.now());
	}

	/**
	 * Que turnos dejaria afuera dar de baja esta excepcion, <b>sin darla de baja</b> (A-11). Solo
	 * quitar una APERTURA puede dejar turnos afuera; quitar un CIERRE responde cero.
	 *
	 * @throws ExcepcionNotAccessibleException si no existe, es de otra sede o de otro tenant (404)
	 * @throws ExcepcionInactivaException si ya estaba dada de baja (409)
	 */
	@Transactional(readOnly = true)
	public ImpactoDeDisponibilidad simularBaja(
			OperatingActor actor, long consultorioId, long excepcionId) {

		long organizationId = exigirContextoDeLaSede(actor, consultorioId);
		var sede = AutorizacionDeSede.exigirSedeDelTenant(
				consultorioDirectory, organizationId, consultorioId);
		exigirGestionDeExcepcion(actor, organizationId, consultorioId, excepcionId);

		DisponibilidadExcepcion excepcion = excepciones
				.findByIdScoped(excepcionId, organizationId, consultorioId)
				.orElseThrow(() -> new ExcepcionNotAccessibleException(excepcionId));
		if (!excepcion.isOperable()) {
			throw new ExcepcionInactivaException(excepcionId);
		}
		return simulador.deBajaDeExcepcion(sede, excepcion, Instant.now());
	}

	/**
	 * Excepciones ACTIVAS que cubren algun dia de {@code [desde, hasta)}, ordenadas por fecha de
	 * inicio.
	 *
	 * <p><b>{@code membershipId} es opcional y las dos respuestas son distintas de verdad.</b> Con
	 * un profesional se devuelven las suyas MAS las de la sede, que es lo que efectivamente le
	 * aplica. Sin profesional se devuelven SOLO las de la sede, que es la pantalla de calendario
	 * del centro; devolver ahi las de todos los profesionales convertiria una vista de sede en un
	 * listado de ausencias de personas, que es informacion de otra pantalla y de otro permiso.
	 *
	 * <p>Los dos casos van por consultas distintas del puerto y ninguna recibe un id centinela:
	 * ver {@code findDeSedeQueCubren}.
	 */
	@Transactional(readOnly = true)
	public List<ExcepcionView> listar(
			OperatingActor actor,
			long consultorioId,
			LocalDate desde,
			LocalDate hasta,
			Long membershipId) {

		long organizationId = exigirLectura(actor, consultorioId);
		VentanaConsultable.exigirValida(desde, hasta);

		List<DisponibilidadExcepcion> encontradas;
		if (membershipId == null) {
			encontradas = excepciones.findDeSedeQueCubren(organizationId, consultorioId, desde, hasta);
		} else {
			// Solo existencia, no vigencia: RN-M05-003. Un profesional desvinculado conserva sus
			// excepciones y el administrador tiene que poder verlas.
			exigirProfesionalDelTenant(organizationId, membershipId);
			encontradas = excepciones.findQueCubren(
					organizationId, consultorioId, membershipId, desde, hasta);
		}

		Map<Long, String> autores = nombresDeCuentas(encontradas.stream()
				.map(DisponibilidadExcepcion::getCreatedByCuentaId)
				.filter(Objects::nonNull)
				.distinct()
				.toList());
		return encontradas.stream()
				.sorted(Comparator.comparing(DisponibilidadExcepcion::getFechaDesde)
						.thenComparing(DisponibilidadExcepcion::getFechaHasta)
						.thenComparing(DisponibilidadExcepcion::getId,
								Comparator.nullsLast(Comparator.naturalOrder())))
				.map(ExcepcionView::de)
				.map(vista -> vista.createdByCuentaId() == null
						? vista
						: vista.conAutor(autores.get(vista.createdByCuentaId())))
				.toList();
	}

	/** Nombres de las cuentas que cargaron excepciones, en una sola consulta (checklist §5.4). */
	private Map<Long, String> nombresDeCuentas(List<Long> cuentaIds) {
		if (identidades == null || cuentaIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, String> nombres = new java.util.HashMap<>();
		identidades.identidadesDe(cuentaIds).forEach((id, identidad) -> {
			if (identidad != null && identidad.nombreCompleto() != null) {
				nombres.put(id, identidad.nombreCompleto());
			}
		});
		return nombres;
	}

	private ExcepcionView conNombreDelAutor(long organizationId, ExcepcionView vista) {
		if (vista == null || vista.createdByCuentaId() == null) {
			return vista;
		}
		return vista.conAutor(nombresDeCuentas(List.of(vista.createdByCuentaId())).get(vista.createdByCuentaId()));
	}

	// =================================================================================
	// Idempotencia
	// =================================================================================

	/**
	 * Busca una excepcion ACTIVA identica a la pedida: mismo alcance, tipo, motivo, fechas y
	 * horas.
	 *
	 * <p>Es un predicado de COINCIDENCIA EXACTA y no de solapamiento, igual que en el alta de un
	 * bloque y por el mismo motivo: si "coincidir" se implementara como "solapar", cargar una
	 * segunda ausencia dentro de una licencia mas larga devolveria la licencia en vez de crearla,
	 * y el centro perderia el dato sin ningun error a la vista.
	 *
	 * <p>{@code notes} y {@code feriadoId} quedan FUERA de la comparacion a proposito: son
	 * anotaciones sobre el mismo hecho, no otro hecho. Dos altas iguales con una nota distinta
	 * siguen siendo el mismo cierre, y crear dos filas por una coma de diferencia es exactamente
	 * el duplicado que la idempotencia existe para evitar.
	 */
	private Optional<DisponibilidadExcepcion> buscarCoincidenciaExacta(
			long organizationId, long consultorioId, ExcepcionAltaCommand command) {

		Long membershipId = command.membershipId();
		List<DisponibilidadExcepcion> candidatas = membershipId == null
				? excepciones.findDeSedeQueCubren(
						organizationId, consultorioId, command.fechaDesde(), command.fechaHasta())
				: excepciones.findQueCubren(
						organizationId, consultorioId, membershipId,
						command.fechaDesde(), command.fechaHasta());

		return candidatas.stream()
				.filter(DisponibilidadExcepcion::isOperable)
				.filter(otra -> Objects.equals(otra.getMembershipId(), membershipId))
				.filter(otra -> otra.getTipo() == command.tipo())
				.filter(otra -> otra.getMotivo() == command.motivo())
				.filter(otra -> otra.getFechaDesde().equals(command.fechaDesde()))
				.filter(otra -> otra.getFechaHasta().equals(command.fechaHasta()))
				.filter(otra -> Objects.equals(otra.getHoraDesde(), command.horaDesde()))
				.filter(otra -> Objects.equals(otra.getHoraHasta(), command.horaHasta()))
				.findFirst();
	}

	// =================================================================================
	// Concurrencia
	// =================================================================================

	/**
	 * Ver {@link BloqueoDeSede}: mismo lock que los bloques, y por el mismo motivo.
	 *
	 * <p>La fila se asegura en una transaccion aparte y ANTES del lock, por lo mismo que en
	 * {@code DisponibilidadService}: crearla dentro de esta transaccion produce un deadlock entre
	 * las primeras N escrituras de una sede. Ver {@link CalendarioSedeIniciador}.
	 */
	private void bloquearLaSede(long organizationId, long consultorioId) {
		BloqueoDeSede.tomar(calendarios, organizationId, consultorioId);
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	/** Ver {@link AutorizacionDeSede#exigirContextoDeLaSede}. */
	private static long exigirContextoDeLaSede(OperatingActor actor, long consultorioId) {
		return AutorizacionDeSede.exigirContextoDeLaSede(
				actor, consultorioId, "Mutacion de excepciones");
	}

	/** Pertenencia primero, permiso despues: un tenant ajeno sale por 404 y nunca por 403. */
	private long exigirLectura(OperatingActor actor, long consultorioId) {
		long organizationId = AutorizacionDeSede.exigirContexto(actor, "Lectura de excepciones");
		exigirSedeDelTenant(organizationId, consultorioId);

		AutorizacionDeSede.exigirPermiso(permissionGuard, actor,
				PermissionCodes.COLABORADOR_READ, organizationId, consultorioId);
		return organizationId;
	}

	/**
	 * {@code agenda:excepciones} sobre la sede y, con alcance propio (el {@code PROFESIONAL}), sobre
	 * SU agenda: {@code membershipId} tiene que ser suyo, y una excepcion de sede ({@code null}) no
	 * se le permite. Agenda de profesionales, checklist §14.4.
	 */
	private void exigirGestion(
			OperatingActor actor, long organizationId, long consultorioId, Long membershipId) {
		AutorizacionDeSede.exigirPermisoSobreProfesional(permissionGuard, membershipDirectory, actor,
				PermissionCodes.AGENDA_EXCEPCIONES, organizationId, consultorioId, membershipId);
	}

	/**
	 * {@link #exigirGestion} sobre una excepcion ya cargada: el profesional es el suyo. Se lee sin
	 * lock solo para decidir el permiso; si no existe en la sede, se exige el permiso sin recorte y
	 * el 404 lo da la operacion, igual que antes.
	 */
	private void exigirGestionDeExcepcion(
			OperatingActor actor, long organizationId, long consultorioId, long excepcionId) {
		Optional<DisponibilidadExcepcion> excepcion =
				excepciones.findByIdScoped(excepcionId, organizationId, consultorioId);
		if (excepcion.isEmpty()) {
			AutorizacionDeSede.exigirPermiso(permissionGuard, actor,
					PermissionCodes.AGENDA_EXCEPCIONES, organizationId, consultorioId);
			return;
		}
		exigirGestion(actor, organizationId, consultorioId, excepcion.get().getMembershipId());
	}

	/**
	 * Exige que la membership habilite en la sede Y que su rol atienda pacientes.
	 *
	 * <p>Es el mismo control que el alta de un bloque, y por el mismo motivo: una excepcion con
	 * alcance de profesional recorta o amplia <b>la disponibilidad de esa persona</b>, asi que
	 * quien no puede ser sujeto de disponibilidad tampoco puede serlo de una excepcion suya. Ver
	 * {@link AutorizacionDeSede#exigirProfesionalQueAtiende} para por que el criterio no es
	 * {@code roleCode == PROFESIONAL}.
	 */
	private void exigirProfesionalDeLaSede(
			long organizationId, long consultorioId, long membershipId, Instant ahora) {

		AutorizacionDeSede.exigirProfesionalQueAtiende(
				membershipDirectory, organizationId, consultorioId, membershipId, ahora);
	}

	/** Solo existencia, sin vigencia ni rol: RN-M05-003, la historia se puede seguir leyendo. */
	private void exigirProfesionalDelTenant(long organizationId, long membershipId) {
		AutorizacionDeSede.exigirVinculoDelTenant(membershipDirectory, organizationId, membershipId);
	}

	private void exigirSedeDelTenant(long organizationId, long consultorioId) {
		AutorizacionDeSede.exigirSedeDelTenant(consultorioDirectory, organizationId, consultorioId);
	}

	/**
	 * La sede tiene que estar ACTIVA para recibir una excepcion nueva. Ver
	 * {@link AutorizacionDeSede#exigirSedeOperable}.
	 */
	private void exigirSedeOperable(long organizationId, long consultorioId) {
		AutorizacionDeSede.exigirSedeOperable(consultorioDirectory, organizationId, consultorioId);
	}

	// =================================================================================
	// Auxiliares
	// =================================================================================

	private void auditar(
			String eventType,
			DisponibilidadExcepcion excepcion,
			long actorAccountId,
			String previousState,
			String newState,
			String motivo,
			Map<String, String> details,
			Instant ahora) {

		auditTrail.record(new AuditEntry(
				excepcion.getOrganizationId(),
				excepcion.getConsultorioId(),
				actorAccountId,
				eventType,
				AuditEvents.ENTITY_EXCEPCION_DISPONIBILIDAD,
				excepcion.getId(),
				previousState,
				newState,
				details,
				motivo,
				AuditEvents.correlationId(),
				ahora));
	}

	private static void exigirMotivo(String motivo) {
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException(
					"Se exige un motivo declarado para la baja de una excepcion de disponibilidad: "
							+ "sin el, la auditoria no responde por que seis meses despues");
		}
	}
}
