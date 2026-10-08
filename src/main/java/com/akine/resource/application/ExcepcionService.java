package com.akine.resource.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.domain.DisponibilidadExcepcion;
import com.akine.resource.domain.PermissionCodes;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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

	public ExcepcionService(
			DisponibilidadExcepcionRepositoryPort excepciones,
			CalendarioSedeRepositoryPort calendarios,
			CalendarioSedeIniciador calendarioIniciador,
			ConsultorioDirectory consultorioDirectory,
			ConsultorioMembershipDirectory membershipDirectory,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail,
			SimuladorDeImpacto simulador) {

		this.excepciones = excepciones;
		this.calendarios = calendarios;
		this.calendarioIniciador = calendarioIniciador;
		this.consultorioDirectory = consultorioDirectory;
		this.membershipDirectory = membershipDirectory;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
		this.simulador = simulador;
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
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ExcepcionView crear(
			OperatingActor actor, long consultorioId, ExcepcionAltaCommand command) {

		long organizationId = exigirContextoDeLaSede(actor, consultorioId);
		exigirSedeOperable(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		Instant ahora = Instant.now();
		Long membershipId = command.membershipId();
		if (membershipId != null) {
			exigirProfesionalDeLaSede(organizationId, consultorioId, membershipId, ahora);
		}

		bloquearLaSede(organizationId, consultorioId);

		Optional<DisponibilidadExcepcion> yaCargada =
				buscarCoincidenciaExacta(organizationId, consultorioId, command);
		if (yaCargada.isPresent()) {
			// Reintento de red. Devolver la fila que ya existe —y NO auditar de nuevo— deja el
			// estado y el historial exactamente como los dejo el primer intento.
			log.info("Alta de excepcion idempotente: consultorioId={} excepcionId={}",
					consultorioId, yaCargada.get().getId());
			return ExcepcionView.de(yaCargada.get());
		}

		DisponibilidadExcepcion guardada = excepciones.save(new DisponibilidadExcepcion(
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
				command.notes()));

		Map<String, String> detalles = new LinkedHashMap<>();
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
		auditar(AuditEvents.DISPONIBILIDAD_EXCEPCION_CREATED, guardada, actor.accountId(),
				null, "ACTIVO", null, detalles, ahora);

		log.info("Excepcion de disponibilidad creada: consultorioId={} excepcionId={} alcance={}",
				consultorioId, guardada.getId(), membershipId == null ? "SEDE" : membershipId);

		// nueva = true SOLO en este camino: el controller responde 201 aca y 200 en el reintento
		// idempotente de mas arriba.
		return ExcepcionView.nueva(guardada);
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
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ExcepcionView darDeBaja(
			OperatingActor actor, long consultorioId, long excepcionId, String motivo) {

		long organizationId = exigirContextoDeLaSede(actor, consultorioId);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);
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
		auditar(AuditEvents.DISPONIBILIDAD_EXCEPCION_DEACTIVATED, guardada, actor.accountId(),
				"ACTIVO", "INACTIVO", motivo, detalles, ahora);

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
		exigirGestion(actor, organizationId, consultorioId);
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
		exigirGestion(actor, organizationId, consultorioId);

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

		return encontradas.stream()
				.sorted(Comparator.comparing(DisponibilidadExcepcion::getFechaDesde)
						.thenComparing(DisponibilidadExcepcion::getFechaHasta)
						.thenComparing(DisponibilidadExcepcion::getId,
								Comparator.nullsLast(Comparator.naturalOrder())))
				.map(ExcepcionView::de)
				.toList();
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
		calendarioIniciador.asegurar(organizationId, consultorioId);
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

	private void exigirGestion(OperatingActor actor, long organizationId, long consultorioId) {
		AutorizacionDeSede.exigirPermiso(permissionGuard, actor,
				PermissionCodes.CONSULTORIO_MANAGE, organizationId, consultorioId);
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
