package com.akine.resource.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionQuery;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.domain.BloqueDisponibilidad;
import com.akine.resource.domain.CalendarioSede;
import com.akine.resource.domain.IntervaloLocal;
import com.akine.resource.domain.PermissionCodes;
import com.akine.resource.domain.exception.BloqueInactivoException;
import com.akine.resource.domain.exception.BloqueNotAccessibleException;
import com.akine.resource.domain.exception.BloqueSolapadoException;
import com.akine.resource.domain.exception.ConsultorioNotAccessibleException;
import com.akine.resource.domain.exception.ConsultorioNotOperableException;
import com.akine.resource.domain.exception.ProfesionalNoVinculadoException;
import com.akine.resource.domain.exception.ProfesionalNotAccessibleException;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.BloqueDisponibilidadRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.CalendarioSedeRepositoryPort;
import com.akine.resource.spi.DisponibilidadImpactProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

	/**
	 * Ventana hacia adelante que se le pregunta a la sonda de impacto cuando el bloque no tiene
	 * fin de vigencia.
	 *
	 * <p>No sale de ningun RF y es deliberadamente finita: un bloque sin fin previsto no tiene
	 * un "hasta" con el que acotar la pregunta, y {@link DisponibilidadImpactProbe#turnosEn} pide
	 * dos instantes. Noventa dias es el horizonte con el que un centro planifica; que la ventana
	 * correcta sea otra lo va a saber {@code scheduling} cuando exista —su javadoc lo dice— y
	 * cambiarla no toca el contrato.
	 */
	private static final int DIAS_DE_HORIZONTE_DE_IMPACTO = 90;

	private final BloqueDisponibilidadRepositoryPort bloques;
	private final CalendarioSedeRepositoryPort calendarios;
	private final ConsultorioDirectory consultorioDirectory;
	private final ConsultorioMembershipDirectory membershipDirectory;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;
	private final DisponibilidadImpactProbe impactProbe;

	public DisponibilidadService(
			BloqueDisponibilidadRepositoryPort bloques,
			CalendarioSedeRepositoryPort calendarios,
			ConsultorioDirectory consultorioDirectory,
			ConsultorioMembershipDirectory membershipDirectory,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail,
			DisponibilidadImpactProbe impactProbe) {

		this.bloques = bloques;
		this.calendarios = calendarios;
		this.consultorioDirectory = consultorioDirectory;
		this.membershipDirectory = membershipDirectory;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
		this.impactProbe = impactProbe;
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
	 * que antes no existia. La consulta a {@link DisponibilidadImpactProbe} corresponde en la
	 * edicion y en la baja, que si pueden quitarla.
	 *
	 * @throws ConsultorioNotAccessibleException si la sede no existe o es de otro tenant (404)
	 * @throws ConsultorioNotOperableException si la sede esta dada de baja (409)
	 * @throws ProfesionalNoVinculadoException si la membership no habilita en esa sede (409)
	 * @throws BloqueSolapadoException si se pisa con otro bloque activo (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public BloqueView crear(
			OperatingActor actor, long consultorioId, long membershipId, BloqueAltaCommand command) {

		long organizationId = exigirContextoDeLaSede(actor, consultorioId);
		ConsultorioSnapshot sede = exigirSedeOperable(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		Instant ahora = Instant.now();
		exigirProfesionalDeLaSede(organizationId, consultorioId, membershipId, ahora);

		bloquearLaSede(organizationId, consultorioId);

		int dia = command.diaSemana();
		LocalTime horaDesde = command.horaDesde();
		LocalTime horaHasta = command.horaHasta();
		LocalDate hoy = LocalDate.ofInstant(ahora, zonaDe(sede));
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

		exigirSinSolapamiento(
				activos, null, dia, horaDesde, horaHasta, vigenciaDesde, vigenciaHasta);

		BloqueDisponibilidad guardado = bloques.save(new BloqueDisponibilidad(
				organizationId, consultorioId, membershipId,
				dia, horaDesde, horaHasta, vigenciaDesde, vigenciaHasta));

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("membershipId", String.valueOf(membershipId));
		detalles.put("diaSemana", String.valueOf(dia));
		detalles.put("horario", horaDesde + " -> " + horaHasta);
		detalles.put("vigenciaDesde", String.valueOf(vigenciaDesde));
		auditar(AuditEvents.DISPONIBILIDAD_BLOQUE_CREATED, guardado, actor.accountId(),
				null, "ACTIVO", null, detalles, ahora);

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
	 * <p>Segundo, la consulta a {@link DisponibilidadImpactProbe}: una edicion puede QUITAR
	 * disponibilidad, y los turnos que caian ahi quedan en conflicto (RN-M05-004). Hoy la
	 * respuesta es siempre cero porque {@code scheduling} no existe; ver {@link BloqueView}.
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
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public BloqueView editar(
			OperatingActor actor,
			long consultorioId,
			long membershipId,
			long bloqueId,
			BloqueEdicionCommand command) {

		long organizationId = exigirContextoDeLaSede(actor, consultorioId);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		Instant ahora = Instant.now();
		exigirProfesionalDeLaSede(organizationId, consultorioId, membershipId, ahora);

		// El lock ANTES de la primera lectura de bloques, igual que en el alta: el bloque que se
		// esta por editar tambien es un bloque.
		bloquearLaSede(organizationId, consultorioId);

		BloqueDisponibilidad bloque = cargar(organizationId, consultorioId, membershipId, bloqueId);
		if (!bloque.isOperable()) {
			throw new BloqueInactivoException(bloqueId, BloqueInactivoException.Operacion.EDICION);
		}
		if (bloque.getVersion() != command.expectedVersion()) {
			throw new OptimisticLockingFailureException(
					"El bloque de disponibilidad fue modificado por otra operacion");
		}

		Map<String, String> detalles = cambios(bloque, command);

		// El fin de vigencia ANTERIOR se guarda antes de mutar, y es lo que hace correcta la
		// ventana de la sonda. Ver impactoDe: los turnos que una edicion deja huerfanos son
		// justamente los que caen DESPUES del nuevo fin, o sea fuera de la ventana que el estado
		// posterior describe. Preguntando solo por el estado nuevo, la respuesta seria cero
		// exactamente en el caso que la pregunta existe para detectar.
		LocalDate finDeVigenciaPrevio = bloque.getVigenciaHasta();

		bloque.updateDatos(
				command.diaSemana(), command.horaDesde(), command.horaHasta(),
				command.vigenciaDesde(), command.vigenciaHasta(), command.limpiarVigenciaHasta());

		// El propio bloque se excluye de la comparacion: solapa consigo mismo por definicion.
		exigirSinSolapamiento(
				bloques.findActivosDe(organizationId, consultorioId, membershipId),
				bloqueId,
				bloque.getDiaSemana(), bloque.getHoraDesde(), bloque.getHoraHasta(),
				bloque.getVigenciaDesde(), bloque.getVigenciaHasta());

		BloqueDisponibilidad guardado = bloques.save(bloque);
		DisponibilidadImpactProbe.Impacto impacto = impactoDe(
				organizationId, consultorioId, membershipId, sede, ahora,
				finDeVigenciaPrevio, guardado.getVigenciaHasta());
		if (impacto.hayAlgo()) {
			detalles.put("turnosAfectados", String.valueOf(impacto.turnosAfectados()));
		}

		auditar(AuditEvents.DISPONIBILIDAD_BLOQUE_UPDATED, guardado, actor.accountId(),
				null, null, null, detalles, ahora);

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
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public BloqueView darDeBaja(
			OperatingActor actor,
			long consultorioId,
			long membershipId,
			long bloqueId,
			String motivo) {

		long organizationId = exigirContextoDeLaSede(actor, consultorioId);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);
		exigirMotivo(motivo);

		Instant ahora = Instant.now();
		// Solo existencia, no vigencia: RN-M05-003. Ver exigirProfesionalDelTenant.
		exigirProfesionalDelTenant(organizationId, membershipId);
		bloquearLaSede(organizationId, consultorioId);

		BloqueDisponibilidad bloque = cargar(organizationId, consultorioId, membershipId, bloqueId);
		if (!bloque.isOperable()) {
			throw new BloqueInactivoException(bloqueId, BloqueInactivoException.Operacion.BAJA);
		}

		// La sonda se consulta ANTES de la baja: despues, la disponibilidad resultante ya no
		// contiene la franja y la pregunta "que turnos quedan afuera" perderia su referencia.
		DisponibilidadImpactProbe.Impacto impacto = impactoDe(
				organizationId, consultorioId, membershipId, sede, ahora,
				bloque.getVigenciaHasta(), bloque.getVigenciaHasta());

		bloque.deactivate(ahora, motivo);
		BloqueDisponibilidad guardado = bloques.save(bloque);

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("turnosAfectados", String.valueOf(impacto.turnosAfectados()));
		auditar(AuditEvents.DISPONIBILIDAD_BLOQUE_DEACTIVATED, guardado, actor.accountId(),
				"ACTIVO", "INACTIVO", motivo, detalles, ahora);

		log.info("Bloque de disponibilidad dado de baja: consultorioId={} membershipId={} bloqueId={}",
				consultorioId, membershipId, bloqueId);

		return BloqueView.de(guardado, impacto);
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
	 * Exige que la sede de la RUTA sea la del contexto ya validado, y devuelve su organizacion.
	 *
	 * <p>La organizacion no viaja por parametro: sale del contexto que {@code TenantContextFilter}
	 * revalido en este request, nunca del cliente. Comparar ademas la sede es mas estricto de lo
	 * que la matriz exige —un {@code ORG_ADMIN} tiene alcance organizacion— y es deliberado, por
	 * el mismo motivo que en {@code EspacioService}: la sede del contexto es la unica que el
	 * sistema revalido contra la base, y sin la comparacion un administrador con contexto en la
	 * sede A mutaria el horario de la B escribiendo otro numero en la URL.
	 *
	 * <p>Falta de contexto es <b>403 y nunca 401</b>: el interceptor del frontend borra el token
	 * ante cualquier 401 y dejaria al usuario en un bucle de login del que no sale.
	 */
	private long exigirContextoDeLaSede(OperatingActor actor, long consultorioId) {
		if (actor.contextOrganizationId() == null || actor.consultorioId() == null) {
			log.info("Mutacion de disponibilidad sin contexto validado: accountId={}", actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		if (actor.consultorioId() != consultorioId) {
			log.info("Mutacion de disponibilidad fuera del contexto del request: accountId={} consultorioId={}",
					actor.accountId(), consultorioId);
			throw new ConsultorioNotAccessibleException(consultorioId);
		}
		return actor.contextOrganizationId();
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
		if (actor.contextOrganizationId() == null) {
			log.info("Lectura de disponibilidad sin contexto validado: accountId={}", actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		long organizationId = actor.contextOrganizationId();
		exigirSedeDelTenant(organizationId, consultorioId);

		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.COLABORADOR_READ,
				organizationId,
				consultorioId,
				null,
				Instant.now()));
		return organizationId;
	}

	/**
	 * Exige {@code consultorio:manage} SOBRE ESA SEDE, en una MUTACION.
	 *
	 * <p>Con la sede como alcance, un {@code CONSULTORIO_ADMIN} pasa sobre la suya y un
	 * {@code ORG_ADMIN} sobre todas — que es lo que dice la matriz seccion 6, sin ningun caso
	 * especial escrito: es la formula del evaluador operando. Y como la seccion 6 se lo niega a
	 * {@code PROFESIONAL} y {@code ADMINISTRATIVO}, esta sola linea es la que implementa "el
	 * profesional no edita su propia disponibilidad" sin inventar ningun codigo nuevo.
	 */
	private void exigirGestion(OperatingActor actor, long organizationId, long consultorioId) {
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.CONSULTORIO_MANAGE,
				organizationId,
				consultorioId,
				null,
				Instant.now()));
	}

	/**
	 * Exige que la membership exista en el tenant y HABILITE en esa sede.
	 *
	 * <p>Son dos preguntas distintas y las dos hacen falta. Que no resuelva sale por 404, como
	 * cualquier id ajeno o inexistente. Que resuelva pero no habilite —otra sede, vigencia
	 * vencida, SUSPENDIDA o REVOCADA— sale por 409: ver
	 * {@link ProfesionalNoVinculadoException}.
	 */
	private void exigirProfesionalDeLaSede(
			long organizationId, long consultorioId, long membershipId, Instant ahora) {

		ConsultorioMembershipSnapshot profesional =
				exigirProfesionalDelTenant(organizationId, membershipId);

		if (!profesional.validAt(ahora) || !profesional.cubreConsultorio(consultorioId)) {
			throw new ProfesionalNoVinculadoException(membershipId, consultorioId);
		}
	}

	/**
	 * Exige solo que la membership EXISTA en el tenant, sin mirar si habilita.
	 *
	 * <p>Es la comprobacion de las operaciones que miran hacia atras —listar el horario, darlo de
	 * baja— y la diferencia con {@link #exigirProfesionalDeLaSede} es RN-M05-003: desvincular a un
	 * profesional no borra sus bloques ni su autoria. Exigir un vinculo vigente para LEER dejaria
	 * al administrador sin poder revisar el horario de quien acaba de irse, y exigirlo para dar de
	 * baja lo dejaria sin poder ordenar lo que quedo. Lo que si exige vinculo vigente es cargar o
	 * mover horario, que es poner disponibilidad en efecto hacia adelante.
	 */
	private ConsultorioMembershipSnapshot exigirProfesionalDelTenant(
			long organizationId, long membershipId) {

		return membershipDirectory.find(organizationId, membershipId)
				.orElseThrow(() -> new ProfesionalNotAccessibleException(membershipId));
	}

	// =================================================================================
	// Concurrencia
	// =================================================================================

	/**
	 * Toma el {@code FOR UPDATE} sobre la fila de {@code consultorio_calendario} de la sede,
	 * creandola a demanda si es la primera vez.
	 *
	 * <p><b>Se intenta el lock ANTES de comprobar si la fila existe.</b> Preguntar primero con
	 * una lectura comun y bloquear despues seria exactamente la escalada S-&gt;X que este metodo
	 * existe para evitar.
	 *
	 * <p>Queda una ventana angosta y declarada: si dos requests son los PRIMEROS de esa sede al
	 * mismo tiempo, los dos ven la fila ausente y los dos la insertan; el unique de
	 * {@code consultorio_calendario} rechaza a uno y ese request falla. No se atrapa la violacion
	 * porque no serviria de nada — la sesion JPA queda inutilizable despues de un flush fallido y
	 * el {@code lockByScope} siguiente tiraria {@code AssertionFailure} igual—. El remedio es un
	 * reintento del cliente, que ya encuentra la fila creada. Es la unica vez en la vida de una
	 * sede que puede pasar, y la tarea 8 la cierra antes en la practica: el {@code PUT} de
	 * politica de calendario crea la fila en el alta de la sede.
	 */
	private void bloquearLaSede(long organizationId, long consultorioId) {
		if (calendarios.lockByScope(organizationId, consultorioId).isPresent()) {
			return;
		}
		calendarios.save(new CalendarioSede(organizationId, consultorioId));
		calendarios.lockByScope(organizationId, consultorioId)
				.orElseThrow(() -> new IllegalStateException(
						"El calendario de la sede " + consultorioId + " no se pudo bloquear "
								+ "inmediatamente despues de crearlo"));
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
	private static void exigirSinSolapamiento(
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
			throw new BloqueSolapadoException(otro.getId(), dia, horaDesde, horaHasta);
		}
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

	private ConsultorioSnapshot exigirSedeDelTenant(long organizationId, long consultorioId) {
		return consultorioDirectory.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNotAccessibleException(consultorioId));
	}

	/**
	 * La sede tiene que estar ACTIVA para recibir horario nuevo.
	 *
	 * <p>409 y no 404: la sede existe y el actor la puede leer; lo que no admite la operacion es
	 * su estado. Es RN-M03-003 un nivel mas abajo — una sede inactiva no origina hechos nuevos, y
	 * un bloque de disponibilidad nuevo es un hecho nuevo. La EDICION y la BAJA no lo exigen a
	 * proposito: son las dos operaciones con las que se ordena el horario de una sede que se esta
	 * cerrando, y prohibirlas la dejaria congelada.
	 */
	private ConsultorioSnapshot exigirSedeOperable(long organizationId, long consultorioId) {
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		if (!sede.active()) {
			throw new ConsultorioNotOperableException(consultorioId);
		}
		return sede;
	}

	/**
	 * Pregunta a la sonda cuantos turnos futuros deja en conflicto el cambio (RN-M05-004).
	 *
	 * <h2>La ventana sale de la UNION del estado anterior y el nuevo, no del nuevo</h2>
	 *
	 * <p>Es el error que este metodo existe para no cometer, y no se ve hasta que
	 * {@code scheduling} exista. Un bloque "martes 09-12, sin fin" con turnos reservados hasta
	 * diciembre al que el administrador le pone {@code vigenciaHasta = 2026-09-01}: los turnos
	 * que esa edicion deja huerfanos son <b>exactamente los del 2026-09-01 en adelante</b>. Una
	 * ventana derivada del estado POSTERIOR termina el 2026-09-01, o sea que consulta justo el
	 * tramo que la edicion NO rompe y responde cero.
	 *
	 * <p>Por eso se toma el fin MAS LEJANO entre el anterior y el nuevo. Con la union, la ventana
	 * cubre el tramo que se recorta —que es el que interesa— y tambien el caso simetrico de una
	 * edicion que ALARGA la vigencia, donde el conflicto puede estar mas alla del fin viejo. Un
	 * {@code null} de cualquiera de los dos lados significa "sin fin" y gana siempre: se cae al
	 * horizonte.
	 *
	 * <p>Es la misma razon por la que {@link #darDeBaja} consulta la sonda ANTES de desactivar.
	 * Una baja es el caso extremo de un recorte, y ahi los dos extremos coinciden porque la
	 * vigencia no cambia.
	 *
	 * <p>La ventana arranca en AHORA —los turnos pasados no se tocan— y la zona de la sede es la
	 * que traduce el dia local a un instante: usar UTC correria el limite hasta tres horas en
	 * Argentina y dejaria turnos del ultimo dia afuera de la cuenta.
	 *
	 * @param finPrevio fin de vigencia ANTES del cambio, {@code null} si no tenia
	 * @param finNuevo  fin de vigencia DESPUES del cambio, {@code null} si no tiene
	 */
	private DisponibilidadImpactProbe.Impacto impactoDe(
			long organizationId,
			long consultorioId,
			long membershipId,
			ConsultorioSnapshot sede,
			Instant ahora,
			LocalDate finPrevio,
			LocalDate finNuevo) {

		ZoneId zona = zonaDe(sede);
		LocalDate horizonte =
				LocalDate.ofInstant(ahora, zona).plusDays(DIAS_DE_HORIZONTE_DE_IMPACTO);

		LocalDate fin;
		if (finPrevio == null || finNuevo == null) {
			fin = horizonte;
		} else {
			LocalDate masLejano = finPrevio.isAfter(finNuevo) ? finPrevio : finNuevo;
			// El horizonte tambien acota el caso acotado: sin el, un fin de vigencia a diez años
			// convertiria cada edicion en un scan de la agenda entera.
			fin = masLejano.isAfter(horizonte) ? horizonte : masLejano;
		}
		Instant hasta = fin.atStartOfDay(zona).toInstant();

		if (!hasta.isAfter(ahora)) {
			// El bloque ya vencio: no puede haber ningun turno FUTURO amparado por el, y
			// preguntarlo con una ventana invertida seria pedirle a la sonda algo sin sentido.
			return DisponibilidadImpactProbe.Impacto.ninguno();
		}
		return impactProbe.turnosEn(organizationId, consultorioId, membershipId, ahora, hasta);
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
