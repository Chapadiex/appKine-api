package com.akine.resource.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.transaccion.FilaLockPrevia;
import com.akine.resource.domain.BloqueDisponibilidad;
import com.akine.resource.domain.DisponibilidadExcepcion;
import com.akine.resource.domain.FranjaHorarioGeneral;
import com.akine.resource.domain.HorarioDeSede;
import com.akine.resource.domain.exception.FranjaFueraDelHorarioDeSedeException;
import com.akine.resource.domain.exception.FranjaFueraDelHorarioDeSedeException.FranjaFuera;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.HorarioGeneralRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.DisponibilidadExcepcionRepositoryPort;
import com.akine.resource.domain.IntervaloLocal;
import com.akine.resource.domain.PermissionCodes;
import com.akine.resource.domain.exception.BloqueInactivoException;
import com.akine.resource.domain.exception.BloqueNotAccessibleException;
import com.akine.resource.domain.exception.BloqueSolapadoException;
import com.akine.resource.domain.exception.ConsultorioNotAccessibleException;
import com.akine.resource.domain.exception.ConsultorioNotOperableException;
import com.akine.resource.domain.exception.ProfesionalNoVinculadoException;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.BloqueDisponibilidadRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.CalendarioSedeRepositoryPort;
import com.akine.resource.spi.BloqueoDeAgendaDeProfesional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Administracion de la disponibilidad semanal de un profesional en una sede (M05).
 *
 * <h2>Las dos autorizaciones, y por que son distintas</h2>
 *
 * <p>Mutaciones: {@code consultorio:manage} con la sede como alcance. La matriz seccion 6 se
 * lo niega a {@code PROFESIONAL} y {@code ADMINISTRATIVO}, y de ahi sale —sin inventar ningun
 * codigo— la politica confirmada: el profesional NO edita su disponibilidad.
 *
 * <p>Lecturas: {@code colaborador:read}, que la matriz seccion 6 le da a {@code PROFESIONAL} y
 * {@code ADMINISTRATIVO} con alcance Consultorio. La disponibilidad de un profesional es
 * informacion de un colaborador, asi que el codigo aplica sin estirarlo. <b>No se usa
 * {@code espacio:read}</b>: ese cubre la disponibilidad del ESPACIO FISICO (M04), que es otra
 * cosa.
 *
 * <p><b>El orden de las comprobaciones no es cosmetico.</b> Primero pertenencia, despues
 * permiso: un tenant ajeno sale por 404 —un 403 confirmaria que existe— y el evaluador de
 * permisos responde 403.
 *
 * <h2>Quien puede ser SUJETO de disponibilidad</h2>
 *
 * <p>Es una pregunta distinta de "quien puede editarla", y hasta el ruling R18 no la contestaba
 * nadie en el backend: el unico lugar del sistema donde vivia la regla era el filtro por
 * {@code PROFESIONAL} de una pantalla. El control esta ahora en
 * {@link AutorizacionDeSede#exigirProfesionalQueAtiende}, con el razonamiento completo de por
 * que <b>no</b> es {@code roleCode == PROFESIONAL}.
 *
 * <h2>El lock, y por que no esta donde uno lo pondria</h2>
 *
 * <p>MySQL 8.4 no tiene exclusion constraints, asi que el solapamiento se valida en
 * aplicacion. Un {@code SELECT ... FOR UPDATE} sobre los bloques existentes NO alcanza:
 * bloquear filas que existen no impide que otra transaccion inserte una fila nueva en el
 * hueco, y dos altas concurrentes terminan pisandose sin que ninguna vea a la otra.
 *
 * <p>Por eso el lock se toma sobre la fila de {@code consultorio_calendario} de la sede, que
 * siempre existe. Serializa los writes de disponibilidad por sede, que es aceptable: editar
 * horarios es una accion administrativa de baja frecuencia, no un camino caliente.
 *
 * <p><b>El lock se toma al PRINCIPIO de la transaccion, antes de leer ningun bloque.</b> Leer
 * primero en modo compartido y bloquear despues es una escalada S-&gt;X: con dos transacciones
 * en el mismo camino no es una espera, es un deadlock.
 *
 * <h2>Idempotencia (CA-M05-003-05)</h2>
 *
 * <p>Sin {@code Idempotency-Key}. Un alta que coincide EXACTO con un bloque activo existente
 * —misma membership, dia, horas y vigencia— devuelve ese bloque, no un duplicado y no un 409.
 * Un alta que solapa SIN coincidir devuelve 409. Un reintento de red cae siempre en el primer
 * caso, que es lo que el criterio pide.
 *
 * <p>Con un {@code vigenciaDesde} nulo —"que rija desde ahora"— la comparacion no exige que el
 * inicio coincida con el dia de hoy, o el reintento que cruza la medianoche recibiria 409. El
 * criterio completo esta en {@link #coincideExacto}.
 *
 * <h2>Quien NO puede llegar aca, dicho para que no se lea como un hueco</h2>
 *
 * <p><b>El {@code PLATFORM_ADMIN} no opera esta API.</b> La matriz seccion 6 le da
 * {@code colaborador:read} con alcance {@code SOPORTE}, pero las rutas de M05 cuelgan de la
 * sede y <b>no llevan {@code organizationId}</b>: la organizacion sale del contexto que
 * {@code TenantContextFilter} revalido, y un administrador de plataforma no tiene contexto de
 * tenant (matriz seccion 1.3). Cae en el 403 por falta de contexto, exista o no la sede
 * —respuesta uniforme, no sirve para enumerar—. Por eso este servicio no tiene camino de
 * soporte ni escribe {@code SUPPORT_ACCESS_USED}: no seria codigo defensivo, seria codigo
 * muerto. Si alguna vez hace falta, la decision es de contrato —agregar la organizacion a la
 * ruta— y no de este archivo.
 *
 * <h2>Auditoria</h2>
 *
 * <p>Se escribe DENTRO de la transaccion del negocio, nunca en un listener post-commit: uno que
 * falla deja la mutacion sin rastro. Corolario incomodo, ya pagado en 01.03: <b>una excepcion
 * de negocio hace rollback de todo lo escrito antes de lanzarla, incluida la auditoria</b>. Por
 * eso ningun rechazo se audita desde aca — el permiso denegado lo registra el evaluador de
 * {@code organization} en su propia transaccion.
 */
@Service
public class DisponibilidadService {

	private static final Logger log = LoggerFactory.getLogger(DisponibilidadService.class);

	private final BloqueDisponibilidadRepositoryPort bloques;
	private final CalendarioSedeRepositoryPort calendarios;
	private final CalendarioSedeIniciador calendarioIniciador;
	private final ConsultorioDirectory consultorioDirectory;
	private final ConsultorioMembershipDirectory membershipDirectory;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;
	private final SimuladorDeImpacto simulador;
	private final BloqueoDeAgendaDeProfesional agendasDeProfesional;
	private final DisponibilidadExcepcionRepositoryPort excepciones;
	private final HorarioGeneralRepositoryPort horarios;
	private final TransactionTemplate transaccionReadCommitted;

	public DisponibilidadService(
			BloqueDisponibilidadRepositoryPort bloques,
			CalendarioSedeRepositoryPort calendarios,
			CalendarioSedeIniciador calendarioIniciador,
			ConsultorioDirectory consultorioDirectory,
			ConsultorioMembershipDirectory membershipDirectory,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail,
			SimuladorDeImpacto simulador,
			PlatformTransactionManager transactionManager,
			BloqueoDeAgendaDeProfesional agendasDeProfesional) {
		this(bloques, calendarios, calendarioIniciador, consultorioDirectory, membershipDirectory, permissionGuard,
				auditTrail, simulador, transactionManager, agendasDeProfesional, null);
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	public DisponibilidadService(
			BloqueDisponibilidadRepositoryPort bloques,
			CalendarioSedeRepositoryPort calendarios,
			CalendarioSedeIniciador calendarioIniciador,
			ConsultorioDirectory consultorioDirectory,
			ConsultorioMembershipDirectory membershipDirectory,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail,
			SimuladorDeImpacto simulador,
			PlatformTransactionManager transactionManager,
			BloqueoDeAgendaDeProfesional agendasDeProfesional,
			DisponibilidadExcepcionRepositoryPort excepciones) {
		this(bloques, calendarios, calendarioIniciador, consultorioDirectory, membershipDirectory, permissionGuard,
				auditTrail, simulador, transactionManager, agendasDeProfesional, excepciones, null);
	}

	@org.springframework.beans.factory.annotation.Autowired
	@SuppressWarnings("checkstyle:ParameterNumber")
	public DisponibilidadService(
			BloqueDisponibilidadRepositoryPort bloques,
			CalendarioSedeRepositoryPort calendarios,
			CalendarioSedeIniciador calendarioIniciador,
			ConsultorioDirectory consultorioDirectory,
			ConsultorioMembershipDirectory membershipDirectory,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail,
			SimuladorDeImpacto simulador,
			PlatformTransactionManager transactionManager,
			BloqueoDeAgendaDeProfesional agendasDeProfesional,
			DisponibilidadExcepcionRepositoryPort excepciones,
			HorarioGeneralRepositoryPort horarios) {

		this.excepciones = excepciones;
		this.horarios = horarios;
		this.bloques = bloques;
		this.calendarios = calendarios;
		this.calendarioIniciador = calendarioIniciador;
		this.consultorioDirectory = consultorioDirectory;
		this.membershipDirectory = membershipDirectory;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
		this.simulador = simulador;
		this.agendasDeProfesional = agendasDeProfesional;
		this.transaccionReadCommitted = FilaLockPrevia.transaccionDeNegocio(
				transactionManager, Isolation.READ_COMMITTED);
	}

	// =================================================================================
	// Alta (RF-M05-003)
	// =================================================================================

	/**
	 * Da de alta un bloque recurrente de atencion.
	 *
	 * <p>El orden de este metodo es el del diseno §5 y no admite reordenarse:
	 *
	 * <pre>
	 *   1. resolver la sede                      -&gt; 404 si es de otro tenant
	 *   2. exigir consultorio:manage sobre ella  -&gt; 403
	 *   3. resolver la membership y verificar que cubra esta sede
	 *   4. crear el calendario de la sede si no existe
	 *   5. lockByScope                           &lt;- ANTES de leer ningun bloque
	 *   6. leer los bloques activos de esa membership
	 *   7. coincidencia exacta -&gt; devolver ese bloque; solapamiento -&gt; 409
	 *   8. guardar
	 *   9. auditar EN LA MISMA TRANSACCION
	 * </pre>
	 *
	 * <p><b>El paso 5 antes del 6, y no al reves.</b> Leer los bloques primero y bloquear
	 * despues es una escalada S-&gt;X que con dos transacciones en este mismo camino termina en
	 * deadlock, no en espera. Hay un test unitario con {@code InOrder} que lo fija.
	 *
	 * <p><b>{@code turnosAfectados} es cero por construccion y no se le pregunta a la sonda.</b>
	 * Un alta AGREGA disponibilidad: ningun turno existente puede quedar afuera de una franja
	 * que antes no existia. El calculo de impacto corresponde en la edicion y en la baja, que si
	 * pueden quitarla.
	 *
	 * @throws ConsultorioNotAccessibleException si la sede no existe o es de otro tenant (404)
	 * @throws ConsultorioNotOperableException si la sede esta dada de baja (409)
	 * @throws ProfesionalNoVinculadoException si la membership no habilita en esa sede o si su rol
	 *         no atiende pacientes (409)
	 * @throws BloqueSolapadoException si se pisa con otro bloque activo (409)
	 */
	public BloqueView crear(
			OperatingActor actor, long consultorioId, long membershipId, BloqueAltaCommand command) {

		long organizationId = exigirContextoDeLaSede(actor, consultorioId);
		ConsultorioSnapshot sede = exigirSedeOperable(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId, membershipId);

		// La fila-lock de la sede, ANTES de abrir la transaccion (FilaLockPrevia).
		calendarioIniciador.asegurar(organizationId, consultorioId);
		// Y la de la persona (V129), que se toma despues de la de la sede: el horario habitual de la
		// misma persona no puede pisarse entre sedes.
		agendasDeProfesional.asegurar(organizationId, List.of(membershipId));

		return transaccionReadCommitted.execute(estado -> {
			AltaAbierta alta = abrirAlta(organizationId, consultorioId, membershipId, sede);
			exigirDentroDelHorarioDeSede(organizationId, consultorioId,
					List.of(new FranjaPedida(command.diaSemana(), command.horaDesde(), command.horaHasta())));
			return altaDeUnDia(actor, alta, command, null);
		});
	}

	/**
	 * Da de alta VARIOS bloques del mismo horario y la misma vigencia, uno por dia, en UNA
	 * transaccion: "de lunes a viernes de 09 a 13" (RF-M05-003, carga rapida del horario).
	 *
	 * <p>Mismo protocolo que {@link #crear}: los locks de la sede y de la persona se toman una vez
	 * y cada dia pasa por exactamente las mismas comprobaciones que un alta suelta —coincidencia
	 * exacta devuelve el bloque que ya existe, solapamiento es 409—. Lo que agrega el lote es la
	 * atomicidad: <b>si un dia no puede guardarse, no se guarda ninguno</b>. Antes, cargar la
	 * semana eran cinco pedidos y el cuarto podia fallar dejando tres, sin que nada lo dijera.
	 *
	 * <p>Los dias se procesan en orden ISO (lunes primero): el 409 de solapamiento nombra el dia
	 * que lo produjo y la pantalla lo senala. Los bloques de distintos dias no se pisan entre si,
	 * asi que el orden no cambia el desenlace, solo cual dia se reporta primero.
	 *
	 * <p>Cada bloque se audita por separado, como siempre: el historial por bloque tiene que leerse
	 * igual haya nacido solo o en lote. El detalle {@code diasDelLote} deja constancia de que fue
	 * una carga rapida.
	 *
	 * @return una vista por dia, en el orden de los dias. {@code nuevo()} dice cuales se crearon y
	 *         cuales ya existian
	 * @throws BloqueSolapadoException si algun dia se pisa con otro bloque activo (409); no se
	 *         guarda ninguno
	 */
	public List<BloqueView> crearVarios(
			OperatingActor actor, long consultorioId, long membershipId, BloquesAltaCommand lote) {

		if (lote.diasSemana() == null || lote.diasSemana().isEmpty()) {
			throw new IllegalArgumentException("El lote tiene que incluir al menos un dia de la semana");
		}

		long organizationId = exigirContextoDeLaSede(actor, consultorioId);
		ConsultorioSnapshot sede = exigirSedeOperable(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId, membershipId);

		calendarioIniciador.asegurar(organizationId, consultorioId);
		agendasDeProfesional.asegurar(organizationId, List.of(membershipId));

		List<Integer> dias = lote.diasSemana().stream().sorted().toList();
		String diasDelLote = dias.stream().map(String::valueOf).collect(Collectors.joining(","));

		return transaccionReadCommitted.execute(estado -> {
			AltaAbierta alta = abrirAlta(organizationId, consultorioId, membershipId, sede);
			// Todos los dias antes de guardar ninguno: si uno no entra en el horario de la sede no se
			// guarda el lote, y el 409 nombra todos los que no entran.
			exigirDentroDelHorarioDeSede(organizationId, consultorioId, dias.stream()
					.map(dia -> new FranjaPedida(dia, lote.horaDesde(), lote.horaHasta()))
					.toList());
			List<BloqueView> resultado = new ArrayList<>(dias.size());
			for (int dia : dias) {
				resultado.add(altaDeUnDia(actor, alta, lote.paraElDia(dia), diasDelLote));
			}
			return resultado;
		});
	}

	/** Lo que un alta —de un dia o de varios— resuelve una sola vez, ya dentro de la transaccion. */
	private record AltaAbierta(
			long organizationId,
			long consultorioId,
			long membershipId,
			Instant ahora,
			LocalDate hoy,
			Set<Long> mismaPersona) {
	}

	/**
	 * Primer tramo del alta dentro de la transaccion: vinculo vigente y los dos locks, en ese orden
	 * (sede y despues persona). Se hace UNA vez por transaccion, tenga uno o siete dias.
	 */
	private AltaAbierta abrirAlta(
			long organizationId, long consultorioId, long membershipId, ConsultorioSnapshot sede) {

		Instant ahora = Instant.now();
		exigirProfesionalDeLaSede(organizationId, consultorioId, membershipId, ahora);

		bloquearLaSede(organizationId, consultorioId);
		Set<Long> mismaPersona = agendasDeProfesional.bloquear(organizationId, membershipId);

		return new AltaAbierta(organizationId, consultorioId, membershipId, ahora,
				LocalDate.ofInstant(ahora, zonaDe(sede)), mismaPersona);
	}

	/**
	 * El alta de UN dia, con los locks ya tomados: leer los activos, coincidencia exacta,
	 * solapamientos, guardar y auditar.
	 *
	 * <p>Los activos se releen en cada dia a proposito. En un lote, el dia anterior acaba de
	 * guardar un bloque en esta misma transaccion, y aunque dos dias distintos no pueden pisarse,
	 * leer siempre el estado actual es lo que mantiene a este metodo identico al alta suelta.
	 *
	 * @param diasDelLote los dias del lote, para la auditoria; {@code null} en un alta suelta
	 */
	private BloqueView altaDeUnDia(
			OperatingActor actor, AltaAbierta alta, BloqueAltaCommand command, String diasDelLote) {

		long organizationId = alta.organizationId();
		long consultorioId = alta.consultorioId();
		long membershipId = alta.membershipId();
		Instant ahora = alta.ahora();
		LocalDate hoy = alta.hoy();
		Set<Long> mismaPersona = alta.mismaPersona();

		int dia = command.diaSemana();
		LocalTime horaDesde = command.horaDesde();
		LocalTime horaHasta = command.horaHasta();
		LocalDate vigenciaDesde =
				command.vigenciaDesde() == null ? hoy : command.vigenciaDesde();
		LocalDate vigenciaHasta = command.vigenciaHasta();

		List<BloqueDisponibilidad> activos =
				bloques.findActivosDe(organizationId, consultorioId, membershipId);

		Optional<BloqueDisponibilidad> yaCargado = activos.stream()
				.filter(existente -> coincideExacto(existente, dia, horaDesde, horaHasta,
						command.vigenciaDesde(), vigenciaHasta, hoy))
				.findFirst();
		if (yaCargado.isPresent()) {
			// Idempotencia sin Idempotency-Key: es el reintento de red. Devolver el bloque que ya
			// existe —y NO auditar de nuevo— deja el estado y el historial exactamente como los
			// dejo el primer intento, que es lo que CA-M05-003-05 pide.
			log.info("Alta de bloque idempotente: consultorioId={} membershipId={} bloqueId={}",
					consultorioId, membershipId, yaCargado.get().getId());
			return BloqueView.de(yaCargado.get());
		}

		exigirSinSolapamiento(consultorioId,
				activos, null, dia, horaDesde, horaHasta, vigenciaDesde, vigenciaHasta);
		exigirSinSolapamiento(consultorioId,
				deLaMismaPersonaEnOtrasSedes(organizationId, consultorioId, membershipId, mismaPersona),
				null, dia, horaDesde, horaHasta, vigenciaDesde, vigenciaHasta);
		exigirSinAperturasEnOtrasSedes(organizationId, consultorioId, mismaPersona, dia, horaDesde, horaHasta,
				vigenciaDesde, vigenciaHasta, hoy);

		BloqueDisponibilidad nuevo = new BloqueDisponibilidad(
				organizationId, consultorioId, membershipId,
				dia, horaDesde, horaHasta, vigenciaDesde, vigenciaHasta);
		nuevo.registrarAutor(actor.accountId());
		BloqueDisponibilidad guardado = bloques.save(nuevo);

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("membershipId", String.valueOf(membershipId));
		detalles.put("diaSemana", String.valueOf(dia));
		detalles.put("horario", horaDesde + " -> " + horaHasta);
		detalles.put("vigenciaDesde", String.valueOf(vigenciaDesde));
		if (diasDelLote != null) {
			detalles.put("diasDelLote", diasDelLote);
		}
		ResumenDeAgendaParaAuditoria.con(detalles, null, ResumenDeAgendaParaAuditoria.de(guardado));
		auditar(AuditEvents.DISPONIBILIDAD_BLOQUE_CREATED, guardado, actor.accountId(),
				null, "ACTIVO", null, AuditEvents.conActor(actor, guardado.getMembershipId(), detalles), ahora);

		log.info("Bloque de disponibilidad creado: consultorioId={} membershipId={} bloqueId={}",
				consultorioId, membershipId, guardado.getId());

		// nuevo = true SOLO en este camino: es lo que le permite al controller responder 201 en
		// el alta real y 200 en el reintento idempotente de mas arriba.
		return BloqueView.nuevo(guardado);
	}

	// =================================================================================
	// Edicion (RF-M05-005)
	// =================================================================================

	/**
	 * Edita dia, horas o vigencia de un bloque vigente, detectando conflictos.
	 *
	 * <p>Mismo protocolo que el alta con dos agregados. Primero, el <b>bloqueo optimista</b>: la
	 * version que el cliente leyo se compara antes de mutar, y una version vieja produce 409
	 * {@code concurrent-modification} en vez de pisar el cambio ajeno en silencio. El lock
	 * pesimista de la sede serializa el ACCESO entre requests concurrentes; la version cubre el
	 * caso en que el competidor ya commiteo y la pantalla del segundo quedo desactualizada. Hacen
	 * falta los dos.
	 *
	 * <p>Segundo, el impacto ({@link SimuladorDeImpacto}): una edicion puede QUITAR
	 * disponibilidad, y los turnos que caian ahi quedan en conflicto (RN-M05-004). Desde A-11 es
	 * el conjunto exacto, no la cota superior de E-1; ver {@link BloqueView}.
	 * <b>El impacto se informa, no bloquea</b>: RN-M05-004 pide que los turnos afectados queden
	 * VISIBLES para su resolucion, y ADR-0011 prohibe justamente decidir por el usuario en
	 * cascada. Quien decide que hacer con esos turnos es la pantalla.
	 *
	 * @throws BloqueNotAccessibleException si el bloque no existe, es de otra sede, de otro
	 *         tenant o de otro profesional (404)
	 * @throws BloqueInactivoException si el bloque esta dado de baja (409)
	 * @throws OptimisticLockingFailureException si la version enviada quedo vieja (409)
	 * @throws BloqueSolapadoException si el bloque editado se pisa con otro activo (409)
	 */
	public BloqueView editar(
			OperatingActor actor,
			long consultorioId,
			long membershipId,
			long bloqueId,
			BloqueEdicionCommand command) {

		long organizationId = exigirContextoDeLaSede(actor, consultorioId);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId, membershipId);

		// La fila-lock de la sede, ANTES de abrir la transaccion (FilaLockPrevia).
		calendarioIniciador.asegurar(organizationId, consultorioId);
		// Y la de la persona (V129), que se toma despues de la de la sede: el horario habitual de la
		// misma persona no puede pisarse entre sedes.
		agendasDeProfesional.asegurar(organizationId, List.of(membershipId));

		return transaccionReadCommitted.execute(estado -> editarEnTransaccion(
				actor, consultorioId, membershipId, bloqueId, command, organizationId, sede));
	}

	private BloqueView editarEnTransaccion(
			OperatingActor actor, long consultorioId, long membershipId, long bloqueId,
			BloqueEdicionCommand command, long organizationId, ConsultorioSnapshot sede) {

		Instant ahora = Instant.now();
		exigirProfesionalDeLaSede(organizationId, consultorioId, membershipId, ahora);

		// El lock ANTES de la primera lectura de bloques, igual que en el alta: el bloque que se
		// esta por editar tambien es un bloque.
		bloquearLaSede(organizationId, consultorioId);
		Set<Long> mismaPersona = agendasDeProfesional.bloquear(organizationId, membershipId);

		BloqueDisponibilidad bloque = cargar(organizationId, consultorioId, membershipId, bloqueId);
		if (!bloque.isOperable()) {
			throw new BloqueInactivoException(bloqueId, BloqueInactivoException.Operacion.EDICION);
		}
		if (bloque.getVersion() != command.expectedVersion()) {
			throw new OptimisticLockingFailureException(
					"El bloque de disponibilidad fue modificado por otra operacion");
		}

		// Antes del impacto: pedir que se confirmen los turnos afectados de una edicion que igual se
		// rechaza seria pedir una decision que no sirve para nada.
		exigirDentroDelHorarioDeSede(organizationId, consultorioId, List.of(new FranjaPedida(
				command.diaSemana() == null ? bloque.getDiaSemana() : command.diaSemana(),
				command.horaDesde() == null ? bloque.getHoraDesde() : command.horaDesde(),
				command.horaHasta() == null ? bloque.getHoraHasta() : command.horaHasta())));

		Map<String, String> detalles = cambios(bloque, command);
		String resumenAntes = ResumenDeAgendaParaAuditoria.de(bloque);

		// El impacto se calcula ANTES de mutar: el simulador compara la disponibilidad con el
		// bloque como esta y con una copia editada, y la ventana llega al fin de vigencia MAS
		// LEJANO de los dos —los turnos que una edicion deja huerfanos son justamente los que caen
		// despues del nuevo fin—. Despues de mutar, "antes" ya no existiria.
		ImpactoDeDisponibilidad impacto = simulador.deEdicion(sede, bloque, command, ahora);
		// Checklist de agenda §6.6: el mismo control que el alta de un cierre. Un cambio de horario que
		// deja turnos afuera no se guarda sin que nadie lo haya visto; vuelve el 409 con la lista.
		if (impacto.hayAlgo() && !command.confirmaTurnosAfectados()) {
			throw impacto.comoRechazo();
		}

		bloque.updateDatos(
				command.diaSemana(), command.horaDesde(), command.horaHasta(),
				command.vigenciaDesde(), command.vigenciaHasta(), command.limpiarVigenciaHasta());

		// El propio bloque se excluye de la comparacion: solapa consigo mismo por definicion.
		exigirSinSolapamiento(consultorioId,
				bloques.findActivosDe(organizationId, consultorioId, membershipId),
				bloqueId,
				bloque.getDiaSemana(), bloque.getHoraDesde(), bloque.getHoraHasta(),
				bloque.getVigenciaDesde(), bloque.getVigenciaHasta());
		exigirSinSolapamiento(consultorioId,
				deLaMismaPersonaEnOtrasSedes(organizationId, consultorioId, membershipId, mismaPersona),
				bloqueId,
				bloque.getDiaSemana(), bloque.getHoraDesde(), bloque.getHoraHasta(),
				bloque.getVigenciaDesde(), bloque.getVigenciaHasta());
		exigirSinAperturasEnOtrasSedes(organizationId, consultorioId, mismaPersona, bloque.getDiaSemana(),
				bloque.getHoraDesde(), bloque.getHoraHasta(), bloque.getVigenciaDesde(), bloque.getVigenciaHasta(),
				LocalDate.ofInstant(ahora, zonaDe(sede)));

		BloqueDisponibilidad guardado = bloques.save(bloque);
		if (impacto.hayAlgo()) {
			detalles.put("turnosAfectados", String.valueOf(impacto.turnosAfectados()));
		}
		ResumenDeAgendaParaAuditoria.con(detalles, resumenAntes, ResumenDeAgendaParaAuditoria.de(guardado));

		auditar(AuditEvents.DISPONIBILIDAD_BLOQUE_UPDATED, guardado, actor.accountId(),
				null, null, null, AuditEvents.conActor(actor, guardado.getMembershipId(), detalles), ahora);

		return BloqueView.de(guardado, impacto);
	}

	// =================================================================================
	// Baja logica
	// =================================================================================

	/**
	 * Da de baja un bloque. Motivo obligatorio, sin borrado fisico.
	 *
	 * <p>El bloque deja de computar en la disponibilidad efectiva y <b>nada de lo que ocurrio en
	 * el se modifica</b>: sigue siendo legible y conserva su motivo. No hay reactivacion —ningun
	 * RF de M05 la pide— y un horario que vuelve es un bloque nuevo, no una baja deshecha.
	 *
	 * <p><b>Devuelve {@link BloqueView} aunque el brief de la tarea declarara {@code void}.</b>
	 * El ruling del controlador R2 pide que la baja consulte la sonda de impacto y devuelva
	 * {@code turnosAfectados} "en la vista de resultado", y una firma {@code void} no tiene donde
	 * ponerlo. Prevalece el ruling, que es posterior y explicito; ademas es la misma forma que
	 * {@code EspacioService.deactivate}, que tambien devuelve la vista.
	 *
	 * @throws BloqueNotAccessibleException si el bloque no es accesible (404)
	 * @throws BloqueInactivoException si ya estaba dado de baja (409)
	 * @throws IllegalArgumentException si no se declaro un motivo (400)
	 */
	public BloqueView darDeBaja(
			OperatingActor actor,
			long consultorioId,
			long membershipId,
			long bloqueId,
			String motivo) {
		// La forma anterior a la agenda operativa: quien la llama ya decidio. La API pasa por la de abajo.
		return darDeBaja(actor, consultorioId, membershipId, bloqueId, motivo, true);
	}

	/**
	 * Da de baja un bloque. Si deja turnos fuera de la disponibilidad y no se confirmo, 409
	 * {@code bloqueo-con-turnos-afectados} con la lista (checklist de agenda §6.6), igual que el alta de
	 * un cierre. Los turnos no se tocan: quedan reservados y la agenda los marca fuera de disponibilidad.
	 */
	public BloqueView darDeBaja(
			OperatingActor actor,
			long consultorioId,
			long membershipId,
			long bloqueId,
			String motivo,
			boolean confirmaTurnosAfectados) {

		long organizationId = exigirContextoDeLaSede(actor, consultorioId);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId, membershipId);

		// La fila-lock de la sede, ANTES de abrir la transaccion (FilaLockPrevia).
		calendarioIniciador.asegurar(organizationId, consultorioId);
		// Y la de la persona (V129), que se toma despues de la de la sede: el horario habitual de la
		// misma persona no puede pisarse entre sedes.
		agendasDeProfesional.asegurar(organizationId, List.of(membershipId));

		return transaccionReadCommitted.execute(estado -> darDeBajaEnTransaccion(
				actor, consultorioId, membershipId, bloqueId, motivo, organizationId, sede,
				confirmaTurnosAfectados));
	}

	private BloqueView darDeBajaEnTransaccion(
			OperatingActor actor, long consultorioId, long membershipId, long bloqueId,
			String motivo, long organizationId, ConsultorioSnapshot sede, boolean confirmaTurnosAfectados) {

		exigirMotivo(motivo);

		Instant ahora = Instant.now();
		// Solo existencia, no vigencia: RN-M05-003. Ver exigirProfesionalDelTenant.
		exigirProfesionalDelTenant(organizationId, membershipId);
		bloquearLaSede(organizationId, consultorioId);

		BloqueDisponibilidad bloque = cargar(organizationId, consultorioId, membershipId, bloqueId);
		if (!bloque.isOperable()) {
			throw new BloqueInactivoException(bloqueId, BloqueInactivoException.Operacion.BAJA);
		}

		// El impacto se calcula ANTES de la baja: despues, la disponibilidad de "antes" ya no
		// contendria la franja y la comparacion perderia su referencia.
		ImpactoDeDisponibilidad impacto = simulador.deBaja(sede, bloque, ahora);
		if (impacto.hayAlgo() && !confirmaTurnosAfectados) {
			throw impacto.comoRechazo();
		}

		bloque.deactivate(ahora, motivo);
		BloqueDisponibilidad guardado = bloques.save(bloque);

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("turnosAfectados", String.valueOf(impacto.turnosAfectados()));
		ResumenDeAgendaParaAuditoria.con(detalles, ResumenDeAgendaParaAuditoria.de(guardado), null);
		auditar(AuditEvents.DISPONIBILIDAD_BLOQUE_DEACTIVATED, guardado, actor.accountId(),
				"ACTIVO", "INACTIVO", motivo, AuditEvents.conActor(actor, guardado.getMembershipId(), detalles), ahora);

		log.info("Bloque de disponibilidad dado de baja: consultorioId={} membershipId={} bloqueId={}",
				consultorioId, membershipId, bloqueId);

		return BloqueView.de(guardado, impacto);
	}

	// =================================================================================
	// Impacto previo, sin aplicar nada (A-11)
	// =================================================================================

	/**
	 * Que turnos dejaria afuera esta edicion, <b>sin aplicarla</b> (A-11, RN-M05-004).
	 *
	 * <p>Misma autorizacion que la edicion —{@code consultorio:manage} sobre la sede—, porque la
	 * respuesta lista turnos de la agenda del profesional. Es una lectura: no toma el lock de la
	 * sede, no compara la version ({@code expectedVersion} del comando se ignora) y no audita. Un
	 * turno reservado entre esta consulta y el {@code PUT} lo informa igual la respuesta del
	 * {@code PUT}, que recalcula con el mismo simulador.
	 *
	 * <p>Exige el profesional en el tenant pero no su vinculo vigente: es lo que exige la baja, y la
	 * pantalla puede querer evaluar el impacto de ordenar el horario de alguien que se fue.
	 *
	 * @throws BloqueNotAccessibleException si el bloque no es accesible (404)
	 * @throws BloqueInactivoException si el bloque esta dado de baja (409)
	 * @throws IllegalArgumentException si la edicion propuesta es incoherente (400)
	 */
	@Transactional(readOnly = true)
	public ImpactoDeDisponibilidad simularEdicion(
			OperatingActor actor,
			long consultorioId,
			long membershipId,
			long bloqueId,
			BloqueEdicionCommand command) {

		BloqueOperable objetivo = bloqueParaSimular(
				actor, consultorioId, membershipId, bloqueId, BloqueInactivoException.Operacion.EDICION);
		return simulador.deEdicion(objetivo.sede(), objetivo.bloque(), command, Instant.now());
	}

	/**
	 * Que turnos dejaria afuera la baja de este bloque, <b>sin aplicarla</b> (A-11). Mismas reglas
	 * que {@link #simularEdicion}.
	 */
	@Transactional(readOnly = true)
	public ImpactoDeDisponibilidad simularBaja(
			OperatingActor actor, long consultorioId, long membershipId, long bloqueId) {

		BloqueOperable objetivo = bloqueParaSimular(
				actor, consultorioId, membershipId, bloqueId, BloqueInactivoException.Operacion.BAJA);
		return simulador.deBaja(objetivo.sede(), objetivo.bloque(), Instant.now());
	}

	private record BloqueOperable(ConsultorioSnapshot sede, BloqueDisponibilidad bloque) {
	}

	private BloqueOperable bloqueParaSimular(
			OperatingActor actor,
			long consultorioId,
			long membershipId,
			long bloqueId,
			BloqueInactivoException.Operacion operacion) {

		long organizationId = exigirContextoDeLaSede(actor, consultorioId);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId, membershipId);
		exigirProfesionalDelTenant(organizationId, membershipId);

		BloqueDisponibilidad bloque = cargar(organizationId, consultorioId, membershipId, bloqueId);
		if (!bloque.isOperable()) {
			throw new BloqueInactivoException(bloqueId, operacion);
		}
		return new BloqueOperable(sede, bloque);
	}

	// =================================================================================
	// Lectura
	// =================================================================================

	/**
	 * Los bloques ACTIVOS de ese profesional en esa sede, ordenados como se leen: por dia y por
	 * hora de inicio.
	 *
	 * <p>Devuelve el horario BASE, no la disponibilidad efectiva: aca no se aplican excepciones
	 * ni feriados. Esa proyeccion es de la tarea 8 y responde otra pregunta —"que dias concretos
	 * atiende"— sobre estos mismos bloques.
	 *
	 * <p>El orden se impone en memoria y no en el puerto a proposito: la lista de bloques de un
	 * profesional en una sede tiene decenas de filas como maximo, y meterlo en la consulta ataria
	 * el puerto a una presentacion que puede cambiar.
	 */
	@Transactional(readOnly = true)
	public List<BloqueView> listar(OperatingActor actor, long consultorioId, long membershipId) {
		long organizationId = exigirLectura(actor, consultorioId);
		// Solo existencia, no vigencia: RN-M05-003. Ver exigirProfesionalDelTenant.
		exigirProfesionalDelTenant(organizationId, membershipId);

		return bloques.findActivosDe(organizationId, consultorioId, membershipId).stream()
				.sorted(Comparator.comparingInt(BloqueDisponibilidad::getDiaSemana)
						.thenComparing(BloqueDisponibilidad::getHoraDesde))
				.map(BloqueView::de)
				.toList();
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	/**
	 * Ver {@link AutorizacionDeSede#exigirContextoDeLaSede}: la sede de la RUTA tiene que ser la
	 * del contexto ya validado, y de ahi sale la organizacion.
	 */
	private static long exigirContextoDeLaSede(OperatingActor actor, long consultorioId) {
		return AutorizacionDeSede.exigirContextoDeLaSede(
				actor, consultorioId, "Mutacion de disponibilidad");
	}

	/**
	 * Exige poder LEER la disponibilidad de esa sede: pertenencia y despues
	 * {@code colaborador:read}.
	 *
	 * <p>En ESE orden. Un tenant ajeno tiene que salir por 404 antes de que el evaluador pueda
	 * contestar 403: un 403 confirmaria que esa sede existe y bastaria recorrer ids para mapear
	 * el SaaS. Dentro del propio tenant, en cambio, quien decide es el permiso, y ahi el 403 no
	 * filtra nada que el actor no sepa.
	 *
	 * <p><b>No exige que la sede de la ruta sea la del contexto</b>, a diferencia de las
	 * mutaciones: la sede viaja como ALCANCE al evaluador, asi que un {@code CONSULTORIO_ADMIN}
	 * de otra sede recibe 403 por la formula del evaluador y no hace falta una segunda regla que
	 * duplique la decision. Es la misma asimetria que {@code EspacioService} ya sostiene.
	 */
	private long exigirLectura(OperatingActor actor, long consultorioId) {
		long organizationId =
				AutorizacionDeSede.exigirContexto(actor, "Lectura de disponibilidad");
		exigirSedeDelTenant(organizationId, consultorioId);

		AutorizacionDeSede.exigirPermiso(permissionGuard, actor,
				PermissionCodes.COLABORADOR_READ, organizationId, consultorioId);
		return organizationId;
	}

	/**
	 * Exige {@code consultorio:manage} SOBRE ESA SEDE, en una MUTACION.
	 *
	 * <p>Como la matriz seccion 6 le niega ese codigo a {@code PROFESIONAL} y
	 * {@code ADMINISTRATIVO}, esta sola linea es la que implementa "el profesional no edita su
	 * propia disponibilidad" sin inventar ningun codigo nuevo. Ver
	 * {@link AutorizacionDeSede#exigirPermiso}.
	 */
	private void exigirGestion(
			OperatingActor actor, long organizationId, long consultorioId, long membershipId) {
		AutorizacionDeSede.exigirPermisoSobreProfesional(permissionGuard, membershipDirectory, actor,
				PermissionCodes.AGENDA_HORARIO, organizationId, consultorioId, membershipId);
	}

	/**
	 * Los bloques activos de la MISMA PERSONA en las otras sedes de la organizacion, o con otra
	 * membership suya en esta (agenda de profesionales §9.3). Los de esta membership en esta sede ya
	 * los mira el control de siempre, que ademas resuelve la idempotencia.
	 */
	private List<BloqueDisponibilidad> deLaMismaPersonaEnOtrasSedes(
			long organizationId, long consultorioId, long membershipId, Set<Long> mismaPersona) {
		return bloques.findActivosDeLasMemberships(organizationId, mismaPersona).stream()
				.filter(otro -> otro.getConsultorioId() != consultorioId
						|| otro.getMembershipId() != membershipId)
				.toList();
	}

	/**
	 * Exige que la membership exista, habilite en esa sede y sea de alguien que ATIENDE.
	 *
	 * <p>Es el control del alta y de la edicion, o sea de todo lo que pone disponibilidad en
	 * efecto hacia adelante. El criterio de "atiende" —y por que NO es
	 * {@code roleCode == PROFESIONAL}— esta en
	 * {@link AutorizacionDeSede#exigirProfesionalQueAtiende}.
	 */
	private void exigirProfesionalDeLaSede(
			long organizationId, long consultorioId, long membershipId, Instant ahora) {

		AutorizacionDeSede.exigirProfesionalQueAtiende(
				membershipDirectory, organizationId, consultorioId, membershipId, ahora);
	}

	/**
	 * Exige solo que la membership EXISTA en el tenant, sin mirar si habilita ni que rol tiene.
	 *
	 * <p>Es la comprobacion de las operaciones que miran hacia atras —listar el horario, darlo de
	 * baja— y la diferencia con {@link #exigirProfesionalDeLaSede} es RN-M05-003: desvincular a un
	 * profesional no borra sus bloques ni su autoria.
	 */
	private void exigirProfesionalDelTenant(long organizationId, long membershipId) {
		AutorizacionDeSede.exigirVinculoDelTenant(membershipDirectory, organizationId, membershipId);
	}

	// =================================================================================
	// Concurrencia
	// =================================================================================

	/**
	 * Ver {@link BloqueoDeSede}: el lock se toma ANTES de leer ningun bloque.
	 *
	 * <p>La fila se asegura en una transaccion aparte y ANTES del lock. Crearla dentro de esta
	 * transaccion —el camino que habia hasta ahora— produce un DEADLOCK entre las primeras N
	 * escrituras concurrentes de una sede, y no la violacion de unique que una pierde
	 * limpiamente: ver {@link CalendarioSedeIniciador}.
	 */
	private void bloquearLaSede(long organizationId, long consultorioId) {
		BloqueoDeSede.tomar(calendarios, organizationId, consultorioId);
	}

	// =================================================================================
	// Solapamiento
	// =================================================================================

	/**
	 * Rechaza el bloque si se pisa con alguno de los activos, salteando el que se esta editando.
	 *
	 * <p>Dos bloques se pisan cuando coinciden en el DIA y sus intervalos horarios y sus ventanas
	 * de vigencia se solapan los dos. Que hagan falta las tres condiciones no es obvio: el mismo
	 * "martes de 09 a 13" cargado para marzo y para septiembre no es un conflicto, es un horario
	 * que cambia de temporada.
	 */
	private void exigirSinSolapamiento(
			long consultorioId,
			List<BloqueDisponibilidad> activos,
			Long bloqueEditadoId,
			int dia,
			LocalTime horaDesde,
			LocalTime horaHasta,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta) {

		IntervaloLocal intervalo = new IntervaloLocal(horaDesde, horaHasta);

		for (BloqueDisponibilidad otro : activos) {
			if (bloqueEditadoId != null && bloqueEditadoId.equals(otro.getId())) {
				continue;
			}
			if (otro.getDiaSemana() != dia) {
				continue;
			}
			// solapaCon usa extremo superior EXCLUSIVO: 09:00-12:00 y 12:00-15:00 NO se pisan.
			// Son la manana y la tarde del mismo profesional y las dos son legitimas.
			if (!intervalo.solapaCon(otro.intervalo())) {
				continue;
			}
			if (!vigenciasSeSolapan(
					vigenciaDesde, vigenciaHasta, otro.getVigenciaDesde(), otro.getVigenciaHasta())) {
				continue;
			}
			// Disponibilidad central (0.158.0): con la sede real del choque, que puede ser otra.
			throw new BloqueSolapadoException(otro.getId(), dia, horaDesde, horaHasta,
					BloqueSolapadoException.Origen.BLOQUE, otro.getConsultorioId(),
					nombreDeSede(otro.getOrganizationId(), otro.getConsultorioId()),
					otro.getConsultorioId() == consultorioId, otro.getHoraDesde(), otro.getHoraHasta(), null);
		}
	}

	/**
	 * Las APERTURAS de la misma persona en OTRAS sedes que caen en ese dia de la semana, dentro de la
	 * vigencia y se pisan con el horario (disponibilidad central, 0.158.0). Un horario habitual en Centro
	 * de lunes de 8 a 14 no puede convivir con una apertura en Norte el lunes 10/03 de 10 a 12: seria
	 * doble oferta. Una apertura de dia completo se pisa con cualquier horario de ese dia.
	 */
	private void exigirSinAperturasEnOtrasSedes(
			long organizationId, long consultorioId, Set<Long> mismaPersona, int dia, LocalTime horaDesde,
			LocalTime horaHasta, LocalDate vigenciaDesde, LocalDate vigenciaHasta, LocalDate hoy) {

		if (excepciones == null) {
			return;
		}
		IntervaloLocal intervalo = new IntervaloLocal(horaDesde, horaHasta);
		for (DisponibilidadExcepcion apertura
				: excepciones.findAperturasActivasDeLasMemberships(organizationId, mismaPersona, hoy)) {
			if (apertura.getConsultorioId() == consultorioId) {
				continue;
			}
			boolean seCruzaEnHorario = apertura.intervalo().map(intervalo::solapaCon).orElse(true);
			if (!seCruzaEnHorario) {
				continue;
			}
			for (LocalDate fecha = apertura.getFechaDesde(); fecha.isBefore(apertura.getFechaHasta());
					fecha = fecha.plusDays(1)) {
				boolean enVigencia = !fecha.isBefore(vigenciaDesde)
						&& (vigenciaHasta == null || fecha.isBefore(vigenciaHasta));
				if (fecha.getDayOfWeek().getValue() == dia && enVigencia && !fecha.isBefore(hoy)) {
					throw new BloqueSolapadoException(apertura.getId(), dia, horaDesde, horaHasta,
							BloqueSolapadoException.Origen.APERTURA, apertura.getConsultorioId(),
							nombreDeSede(organizationId, apertura.getConsultorioId()), false,
							apertura.getHoraDesde() == null ? LocalTime.MIDNIGHT : apertura.getHoraDesde(),
							apertura.getHoraHasta() == null ? LocalTime.MIDNIGHT : apertura.getHoraHasta(), fecha);
				}
			}
		}
	}

	private String nombreDeSede(long organizationId, long consultorioId) {
		return consultorioDirectory.find(organizationId, consultorioId).map(ConsultorioSnapshot::name).orElse(null);
	}

	/**
	 * Dos ventanas de vigencia se solapan. Extremo superior EXCLUSIVO y {@code null} = sin fin,
	 * las dos convenciones de {@code BloqueDisponibilidad}.
	 */
	private static boolean vigenciasSeSolapan(
			LocalDate unaDesde, LocalDate unaHasta, LocalDate otraDesde, LocalDate otraHasta) {

		boolean unaTerminaAntes = unaHasta != null && !unaHasta.isAfter(otraDesde);
		boolean otraTerminaAntes = otraHasta != null && !otraHasta.isAfter(unaDesde);
		return !unaTerminaAntes && !otraTerminaAntes;
	}

	/**
	 * Coincidencia EXACTA: dia, horas y ventana de vigencia iguales.
	 *
	 * <p>Es un predicado distinto del solapamiento y confundirlos rompe la idempotencia en las
	 * dos direcciones: si "coincidir" se implementara como "solapar", un bloque apenas distinto
	 * devolveria el viejo en vez de rechazarse; y si el reintento exacto pasara por el chequeo de
	 * solapamiento, devolveria 409 en vez del bloque que ya existe.
	 *
	 * <p><b>La vigencia forma parte de la comparacion y no es un adorno.</b> Sacarla convertiria
	 * un horario de temporada en un falso positivo: con un bloque "martes 09-12, marzo a junio"
	 * cargado, un alta de "martes 09-12, desde septiembre y sin fin" devolveria el bloque de MARZO
	 * como si fuera el mismo pedido, y el horario de septiembre nunca se crearia.
	 *
	 * <h2>El caso {@code vigenciaDesde} nula, y por que no compara contra hoy</h2>
	 *
	 * <p>Un {@code vigenciaDesde} nulo no dice "empieza el 26 de agosto": dice <b>"que rija desde
	 * ahora"</b>. Resolverlo a la fecha de hoy y despues exigir igualdad rompe la idempotencia
	 * justo en el borde del dia: un POST a las 23:59:58 que sufre timeout y se reintenta a las
	 * 00:00:01 resuelve D+1, no coincide con la fila que dejo el primer intento y recibe 409 — que
	 * es exactamente el caso que CA-M05-003-05 existe para evitar.
	 *
	 * <p>Por eso, cuando el pedido no trae inicio, la comparacion es dia, horas y fin de vigencia,
	 * y ademas se exige que el candidato este ACTIVO y <b>ya rigiendo</b>
	 * ({@code vigenciaDesde <= hoy}). No es una concesion a la comodidad: un pedido de "que rija
	 * desde ahora" queda genuinamente satisfecho por un bloque activo del mismo dia y las mismas
	 * horas que ya cubre este momento. Un bloque de vigencia FUTURA no lo satisface, y por eso el
	 * {@code isAfter} esta ahi.
	 *
	 * <p>La contrapartida, dicha y no tapada: un alta sin inicio que reproduce el horario de un
	 * bloque que ya rige desde marzo devuelve 200 con ese bloque en vez de 409. Antes de este
	 * criterio devolvia 409 por solapamiento. Es un cambio de codigo de respuesta deliberado y
	 * <b>no se crea nada de mas en ninguno de los dos casos</b>, que es lo que el criterio pide.
	 *
	 * @param vigenciaDesdePedida el inicio TAL COMO LLEGO, sin resolver. {@code null} = "desde
	 *                            ahora", y ese matiz es justamente lo que se pierde si se pasa el
	 *                            valor ya resuelto
	 */
	private static boolean coincideExacto(
			BloqueDisponibilidad existente,
			int dia,
			LocalTime horaDesde,
			LocalTime horaHasta,
			LocalDate vigenciaDesdePedida,
			LocalDate vigenciaHasta,
			LocalDate hoy) {

		if (existente.getDiaSemana() != dia
				|| !existente.getHoraDesde().equals(horaDesde)
				|| !existente.getHoraHasta().equals(horaHasta)
				|| !java.util.Objects.equals(existente.getVigenciaHasta(), vigenciaHasta)) {
			return false;
		}
		if (vigenciaDesdePedida == null) {
			return existente.isOperable() && !existente.getVigenciaDesde().isAfter(hoy);
		}
		return existente.getVigenciaDesde().equals(vigenciaDesdePedida);
	}

	// =================================================================================
	// Auxiliares
	// =================================================================================

	/**
	 * Carga el bloque acotado al tenant, a la SEDE y al PROFESIONAL de la ruta, activo o no.
	 *
	 * <p>El puerto acota por tenant y por sede pero no por membership, y la ruta lleva las tres
	 * cosas: la tercera comprobacion va aca. Sin ella, un administrador legitimo podria editar el
	 * bloque de otro profesional escribiendo su id en la URL y la auditoria quedaria contra la
	 * membership equivocada.
	 */
	private BloqueDisponibilidad cargar(
			long organizationId, long consultorioId, long membershipId, long bloqueId) {

		BloqueDisponibilidad bloque = bloques.findByIdScoped(bloqueId, organizationId, consultorioId)
				.orElseThrow(() -> new BloqueNotAccessibleException(bloqueId));
		if (bloque.getMembershipId() != membershipId) {
			throw new BloqueNotAccessibleException(bloqueId);
		}
		return bloque;
	}

	/** Dia y horas de una franja pedida, en el orden del pedido. */
	private record FranjaPedida(int diaSemana, LocalTime horaDesde, LocalTime horaHasta) {
	}

	/**
	 * Regla del 08/11/2026: el horario del profesional queda DENTRO del horario general de la sede.
	 * Se lee bajo el lock de la sede, el mismo que toma el {@code PUT} del calendario para reemplazar
	 * el horario: achicar la sede y cargar una franja no se cruzan.
	 *
	 * <p>Una sede sin horario no limita nada ({@link HorarioDeSede#limita()}). Las horas incoherentes
	 * no se miran aca: las rechaza el bloque con su propio 400.
	 *
	 * @throws FranjaFueraDelHorarioDeSedeException con todas las franjas que no entran (409)
	 */
	private void exigirDentroDelHorarioDeSede(
			long organizationId, long consultorioId, List<FranjaPedida> pedidas) {

		if (horarios == null) {
			return;
		}
		HorarioDeSede horario = HorarioDeSede.de(horarios.findVigentes(organizationId, consultorioId).stream()
				.map(FranjaHorarioGeneral::franja)
				.toList());
		if (!horario.limita()) {
			return;
		}
		List<FranjaFuera> fuera = new ArrayList<>();
		for (int indice = 0; indice < pedidas.size(); indice++) {
			FranjaPedida pedida = pedidas.get(indice);
			if (pedida.horaDesde() == null || pedida.horaHasta() == null
					|| !pedida.horaHasta().isAfter(pedida.horaDesde())) {
				continue;
			}
			if (!horario.admite(pedida.diaSemana(), new IntervaloLocal(pedida.horaDesde(), pedida.horaHasta()))) {
				fuera.add(new FranjaFuera(indice, pedida.diaSemana(), pedida.horaDesde(), pedida.horaHasta(),
						horario.delDia(pedida.diaSemana())));
			}
		}
		if (!fuera.isEmpty()) {
			throw new FranjaFueraDelHorarioDeSedeException(fuera);
		}
	}

	private ConsultorioSnapshot exigirSedeDelTenant(long organizationId, long consultorioId) {
		return AutorizacionDeSede.exigirSedeDelTenant(
				consultorioDirectory, organizationId, consultorioId);
	}

	/**
	 * La sede tiene que estar ACTIVA para recibir horario nuevo: un bloque de disponibilidad
	 * nuevo es un hecho nuevo. Ver {@link AutorizacionDeSede#exigirSedeOperable}.
	 */
	private ConsultorioSnapshot exigirSedeOperable(long organizationId, long consultorioId) {
		return AutorizacionDeSede.exigirSedeOperable(
				consultorioDirectory, organizationId, consultorioId);
	}

	/**
	 * Zona horaria efectiva de la sede. Cae a UTC si la sede no la declara: un huso invalido no
	 * puede tumbar una edicion de horario, y {@code organization} ya valida el valor al darla de
	 * alta (AKINE-02.01).
	 */
	private static ZoneId zonaDe(ConsultorioSnapshot sede) {
		// La politica vive en ZonaSede, que es el unico lugar del modulo donde esta escrita: la
		// tarea 8 sumo un segundo consumidor —DisponibilidadEfectivaService— y dos copias del
		// mismo metodo privado es como se termina con dos comportamientos distintos ante una sede
		// mal cargada.
		return ZonaSede.de(sede);
	}

	private static Map<String, String> cambios(
			BloqueDisponibilidad bloque, BloqueEdicionCommand command) {

		Map<String, String> detalles = new LinkedHashMap<>();
		if (command.diaSemana() != null && command.diaSemana() != bloque.getDiaSemana()) {
			detalles.put("diaSemana", bloque.getDiaSemana() + " -> " + command.diaSemana());
		}
		if (command.horaDesde() != null && !command.horaDesde().equals(bloque.getHoraDesde())) {
			detalles.put("horaDesde", bloque.getHoraDesde() + " -> " + command.horaDesde());
		}
		if (command.horaHasta() != null && !command.horaHasta().equals(bloque.getHoraHasta())) {
			detalles.put("horaHasta", bloque.getHoraHasta() + " -> " + command.horaHasta());
		}
		if (command.vigenciaDesde() != null
				&& !command.vigenciaDesde().equals(bloque.getVigenciaDesde())) {
			detalles.put("vigenciaDesde",
					bloque.getVigenciaDesde() + " -> " + command.vigenciaDesde());
		}
		if (command.limpiarVigenciaHasta()) {
			detalles.put("vigenciaHasta", bloque.getVigenciaHasta() + " -> (sin fin)");
		} else if (command.vigenciaHasta() != null
				&& !command.vigenciaHasta().equals(bloque.getVigenciaHasta())) {
			detalles.put("vigenciaHasta",
					bloque.getVigenciaHasta() + " -> " + command.vigenciaHasta());
		}
		return detalles;
	}

	private void auditar(
			String eventType,
			BloqueDisponibilidad bloque,
			long actorAccountId,
			String previousState,
			String newState,
			String motivo,
			Map<String, String> details,
			Instant ahora) {

		auditTrail.record(new AuditEntry(
				bloque.getOrganizationId(),
				bloque.getConsultorioId(),
				actorAccountId,
				eventType,
				AuditEvents.ENTITY_BLOQUE_DISPONIBILIDAD,
				bloque.getId(),
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
					"Se exige un motivo declarado para la baja de un bloque de disponibilidad: "
							+ "sin el, la auditoria no responde por que seis meses despues");
		}
	}
}
