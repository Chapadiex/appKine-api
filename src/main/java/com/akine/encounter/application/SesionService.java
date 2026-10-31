package com.akine.encounter.application;

import com.akine.clinical.spi.CasoDirectory;
import com.akine.clinical.spi.HistoriaClinicaDirectory;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.PrecioDeOferta;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.spi.CierreDeSesionObserver;
import com.akine.encounter.spi.OfertaSinPrecioException;
import com.akine.encounter.spi.PrecioParticularDelCierre;
import com.akine.encounter.spi.SesionCerrada;
import com.akine.encounter.spi.TituloDeClaseGrupal;
import com.akine.clinical.spi.HistoriaClinicaSnapshot;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.encounter.domain.EvaluacionBase;
import com.akine.encounter.domain.PermissionCodes;
import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.exception.CasoDeAtencionGrupalException;
import com.akine.encounter.domain.exception.CasoClinicoRequeridoException;
import com.akine.encounter.domain.exception.CasoNoAsignableException;
import com.akine.encounter.domain.exception.EnmiendaFueraDeVentanaException;
import com.akine.encounter.domain.exception.MotivoObligatorioException;
import com.akine.encounter.domain.exception.OfertaNoAtendibleException;
import com.akine.encounter.domain.exception.ProfesionalNoAsignableException;
import com.akine.encounter.domain.exception.SesionCerradaException;
import com.akine.encounter.domain.PoliticaDeEnmienda;
import com.akine.encounter.domain.SesionReemplazoProfesional;
import com.akine.encounter.domain.port.SesionReemplazoProfesionalRepositoryPort;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.PermissionEvaluator;
import com.akine.organization.spi.ZonaDeSede;
import org.springframework.dao.DataIntegrityViolationException;
import com.akine.encounter.domain.exception.ConsultorioNoAccesibleException;
import com.akine.encounter.domain.exception.SesionNotAccessibleException;
import com.akine.encounter.domain.exception.TurnoNoAtendibleException;
import com.akine.encounter.domain.ContenidoDeSesion;
import com.akine.encounter.domain.FotoClinica;
import com.akine.encounter.domain.MedicionEnmendada;
import com.akine.encounter.domain.SesionVersion;
import com.akine.encounter.domain.TratamientoEnmendado;
import com.akine.encounter.domain.port.SesionMedicionRepositoryPort;
import com.akine.encounter.domain.port.TratamientoRepositoryPorts.TratamientoParametroRepositoryPort;
import com.akine.encounter.domain.exception.SesionNoCerradaException;
import com.akine.encounter.domain.port.SesionNumeradorPort;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import com.akine.encounter.domain.port.SesionVersionRepositoryPort;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.identity.AccountIdentity;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import com.akine.encounter.domain.port.TratamientoRepositoryPorts.TratamientoRepositoryPort;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.transaccion.FilaLockPrevia;
import com.akine.scheduling.spi.TurnoDirectory;
import com.akine.scheduling.spi.TurnoSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Inicio de la atencion y autosave del borrador (M14, RF-M14-001, RF-M14-002 y RF-M14-009).
 *
 * <h2>Recableado de DP-10</h2>
 *
 * <p>El plan escribe esta etapa contra el check-in (05.04) y el consumo de autorizaciones (04.05),
 * y hace que la Sesion pertenezca a un Caso (04.03). Las tres etapas quedaron fuera del Paquete B.
 * En consecuencia: la sesion arranca <b>directamente desde el Turno</b>, sin paso de recepcion, y
 * cuelga de la <b>Historia Clinica</b>, que existe desde 04.01.
 *
 * <h2>El doble inicio es idempotente, y no es una concesion</h2>
 *
 * <p>RN-M14-001: un turno produce como mucho una sesion. Un profesional que aprieta dos veces
 * "iniciar" —o que recarga la pantalla— es el caso normal, no el raro. La segunda llamada devuelve
 * la sesion que ya existe, no un 409: el 409 le exigiria a la pantalla distinguir dos situaciones
 * que para el usuario son la misma. El {@code uk_sesion_turno} de V33 lo respalda del lado del
 * motor por si dos llamadas llegan a la vez.
 *
 * <h2>La propiedad no es un permiso</h2>
 *
 * <p>Dos profesionales de la misma sede tienen el mismo {@code sesion:register}. Lo que impide que
 * uno escriba en la atencion del otro no es el evaluador de permisos sino
 * {@code Sesion#exigirPropiedadDe}, y por eso vive en la entidad. Es la instruccion de la etapa:
 * "bloqueo de edicion ajena".
 *
 * <h2>Lo que esta etapa NO hace</h2>
 *
 * <p>No cierra la sesion —06.05— ni modela la evaluacion —06.02—, y el borrador se guarda como
 * contenido opaco: que campos tiene una evaluacion es asunto de etapas que todavia no corrieron, y
 * 06.03 quedo cortada. Tampoco crea obligacion economica: eso es 07.01, y DP-06 ya establecio que
 * el cierre clinico no depende del cobro.
 */
@Service
public class SesionService {

	private static final Logger log = LoggerFactory.getLogger(SesionService.class);

	private final SesionRepositoryPort sesiones;
	private final SesionVersionRepositoryPort versiones;
	private final AuditTrail auditTrail;
	private final TurnoDirectory turnos;
	private final HistoriaClinicaDirectory historias;
	private final CasoDirectory casos;
	private final ConsultorioDirectory consultorios;
	private final ConsultorioMembershipDirectory memberships;
	private final PermissionGuard permissionGuard;
	private final SesionNumeradorPort numerador;
	private final NumeradorIniciador numeradorIniciador;
	private final OfertaDirectory ofertas;

	/**
	 * Solo para leer que practicas se realizaron al notificar el cierre (AKINE-06.04).
	 *
	 * <p>Este servicio <b>no escribe</b> tratamientos: eso es {@code TratamientoService}.
	 */
	private final TratamientoRepositoryPort tratamientos;

	/** C-6: para la foto de tratamientos y mediciones que lleva cada version. */
	private final TratamientoParametroRepositoryPort parametros;
	private final SesionMedicionRepositoryPort mediciones;

	/**
	 * C-6: la enmienda corrige tratamientos y mediciones a traves de sus servicios, que son los
	 * que saben validarlos. Ninguno de los dos depende de este.
	 */
	private final TratamientoService tratamientoService;
	private final MedicionService medicionService;

	private final List<CierreDeSesionObserver> observadores;

	/**
	 * DP-17 (AKINE E-7b): quien decide, antes de numerar, si la deuda del paciente va a ser el
	 * precio particular. Hoy es billing; {@code encounter} no sabe quien.
	 */
	private final List<PrecioParticularDelCierre> requisitosDePrecio;

	/**
	 * AKINE-08.05: como se llama la clase de una Sesion grupal. Lista porque la implementa
	 * {@code activity} y este modulo no puede exigir que exista: sin implementacion, la Sesion viaja
	 * sin {@code claseTitulo} y nada mas.
	 */
	private final List<TituloDeClaseGrupal> titulosDeClase;
	private final TransactionTemplate transaccionReadCommitted;

	/** Nombre visible de quien escribio cada version (solo nombre, por id de cuenta). */
	private final AccountIdentityDirectory identidades;

	/** U-25 (DP-43): la ventana para enmendar de cada organizacion. */
	private final PoliticaDeEnmiendaService politicaDeEnmienda;

	/** U-24 (DP-43): el registro de quien reemplazo a quien. */
	private final SesionReemplazoProfesionalRepositoryPort reemplazos;

	/**
	 * U-24: si el profesional que entra por un reemplazo puede atender. Se evalua sobre SU cuenta y
	 * sin auditar el rechazo: no es un intento de acceso de nadie, es validar un dato del pedido.
	 */
	private final PermissionEvaluator permissionEvaluator;

	@SuppressWarnings("java:S107")
	public SesionService(
			SesionRepositoryPort sesiones,
			SesionVersionRepositoryPort versiones,
			AuditTrail auditTrail,
			TurnoDirectory turnos,
			HistoriaClinicaDirectory historias,
			CasoDirectory casos,
			ConsultorioDirectory consultorios,
			ConsultorioMembershipDirectory memberships,
			PermissionGuard permissionGuard,
			SesionNumeradorPort numerador,
			NumeradorIniciador numeradorIniciador,
			OfertaDirectory ofertas,
			TratamientoRepositoryPort tratamientos,
			TratamientoParametroRepositoryPort parametros,
			SesionMedicionRepositoryPort mediciones,
			TratamientoService tratamientoService,
			MedicionService medicionService,
			List<CierreDeSesionObserver> observadores,
			List<PrecioParticularDelCierre> requisitosDePrecio,
			List<TituloDeClaseGrupal> titulosDeClase,
			AccountIdentityDirectory identidades,
			PlatformTransactionManager transactionManager,
			PoliticaDeEnmiendaService politicaDeEnmienda,
			SesionReemplazoProfesionalRepositoryPort reemplazos,
			PermissionEvaluator permissionEvaluator) {

		this.sesiones = sesiones;
		this.versiones = versiones;
		this.auditTrail = auditTrail;
		this.turnos = turnos;
		this.historias = historias;
		this.casos = casos;
		this.consultorios = consultorios;
		this.memberships = memberships;
		this.permissionGuard = permissionGuard;
		this.numerador = numerador;
		this.numeradorIniciador = numeradorIniciador;
		this.ofertas = ofertas;
		this.tratamientos = tratamientos;
		this.parametros = parametros;
		this.mediciones = mediciones;
		this.tratamientoService = tratamientoService;
		this.medicionService = medicionService;
		this.observadores = List.copyOf(observadores);
		this.requisitosDePrecio = List.copyOf(requisitosDePrecio);
		this.titulosDeClase = List.copyOf(titulosDeClase);
		this.identidades = identidades;
		this.politicaDeEnmienda = politicaDeEnmienda;
		this.reemplazos = reemplazos;
		this.permissionEvaluator = permissionEvaluator;
		this.transaccionReadCommitted = FilaLockPrevia.transaccionDeNegocio(
				transactionManager, Isolation.READ_COMMITTED);
	}

	/**
	 * Abre la atencion de un turno, o devuelve la que ya estaba abierta.
	 *
	 * @throws ConsultorioNoAccesibleException si la sede no existe o es de otro tenant (404)
	 * @throws TurnoNoAtendibleException       si el turno esta de baja o no es de quien atiende (409)
	 * <h2>El caso es opcional salvo que la oferta lo exija (DP-43)</h2>
	 *
	 * <p>{@code casoId} puede venir en {@code null} en las ofertas que no lo exigen: RF-M14-002
	 * admite atencion sin caso. Desde DP-43 (U-09, RF-M10-007) una oferta con
	 * {@code requiereCasoClinico} responde 409 {@code caso-clinico-requerido} sin abrir nada. Las
	 * sesiones viejas sin caso no se migran; la idempotencia las devuelve tal cual.
	 *
	 * <p>Cuando viene, se valida contra {@code clinical.spi.CasoDirectory}: tiene que existir en el
	 * tenant, colgar de <b>la misma historia</b> que la sesion y estar activo. Un caso de otro
	 * paciente es 404 —indistinguible de "no existe", para no poder censar casos ajenos por id— y
	 * uno cerrado es 409, porque lleva a otra accion: reabrirlo.
	 *
	 * <p><b>La idempotencia manda sobre el caso.</b> Si la sesion del turno ya existe se devuelve
	 * tal cual, con el caso que tenga, aunque esta llamada traiga otro: reasignar el caso de una
	 * atencion ya empezada no es "iniciar": es {@link #cambiarCaso}, con su version y su auditoria.
	 *
	 * @throws AccessDeniedException           sin {@code sesion:register} o sin contexto (403)
	 * @throws CasoNoAsignableException        si el caso no existe, es de otra historia o esta
	 *                                         cerrado
	 */
	@Transactional
	public SesionView iniciar(
			OperatingActor actor, long consultorioId, long turnoId, Long casoId) {
		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirRegistro(actor, organizationId, consultorioId);

		// Idempotencia ANTES de cualquier validacion de estado. Un turno cuya sesion ya existe se
		// resuelve igual aunque el turno haya cambiado despues: la atencion ya empezo y negarla
		// ahora perderia el borrador que el profesional venia escribiendo.
		var yaAbierta = sesiones.findVivaPorTurno(organizationId, turnoId);
		if (yaAbierta.isPresent()) {
			// Con la evaluacion previa: la pantalla la necesita apenas abre, no despues.
			return conPrevia(yaAbierta.get(), organizationId);
		}

		TurnoSnapshot turno = turnos.find(organizationId, consultorioId, turnoId)
				.orElseThrow(() -> new TurnoNoAtendibleException(turnoId, "no existe en esta sede"));
		if (!turno.vivo()) {
			throw new TurnoNoAtendibleException(turnoId, "esta dado de baja");
		}

		long profesionalMembershipId = resolverProfesional(actor, organizationId, consultorioId, turno);

		// Si el turno se reservo para un caso, la atencion es de ese caso: el profesional no tiene que
		// volver a elegir lo que la reserva ya dijo (TURNO - Tecnico §76). Lo que manda quien inicia
		// gana, porque es quien esta viendo al paciente.
		//
		// Si ese caso se cerro despues de reservar ya no sirve de dato: se sigue como si no lo hubiera,
		// y si no, la atencion quedaria sin salida (elegir "sin caso" manda null y volveria a tomarlo).
		if (casoId == null && turno.casoId() != null && casos.find(organizationId, turno.casoId())
				.filter(caso -> caso.activo()).isPresent()) {
			casoId = turno.casoId();
		}

		// U-09 (DP-43, RF-M10-007): ANTES de asegurar la historia, para que un rechazo no le abra
		// historia clinica a nadie. Y DESPUES de la idempotencia: una atencion que ya estaba
		// abierta sin caso se devuelve igual, no se le niega al profesional lo que venia escribiendo.
		exigirCasoSiLaOfertaLoRequiere(organizationId, consultorioId, turno.ofertaId(), casoId);

		// La HC se crea si no existia. `asegurar` es de 04.01 y exige perfil de paciente vigente:
		// una atencion sobre alguien que solo es "persona" tiene que fallar aca y no crear una
		// historia clinica a nombre de quien no es paciente.
		HistoriaClinicaSnapshot historia =
				historias.asegurar(organizationId, turno.personaId(), actor.accountId());

		// El caso se valida DESPUES de asegurar la historia: la pertenencia se comprueba contra
		// esa historia, y sin ella no hay contra que comprobar.
		exigirCasoAsignable(organizationId, historia.id(), casoId);

		Sesion sesion = sesiones.save(new Sesion(
				organizationId,
				consultorioId,
				historia.id(),
				casoId,
				turnoId,
				turno.ofertaId(),
				profesionalMembershipId,
				Instant.now(),
				actor.accountId()));

		auditar(organizationId, consultorioId, actor, AuditEvents.SESION_INICIADA, sesion.getId(),
				null, "ABIERTA",
				Map.of("historiaClinicaId", String.valueOf(historia.id()),
						"turnoId", String.valueOf(turnoId),
						"casoId", String.valueOf(casoId)),
				sesion.getIniciadaEn());

		log.info("Sesion iniciada: sesionId={} turnoId={} historiaClinicaId={} casoId={} "
						+ "profesional={}",
				sesion.getId(), turnoId, historia.id(), casoId, profesionalMembershipId);

		return conPrevia(sesion, organizationId);
	}

	// =================================================================================
	// DP-43 (U-24) — atencion sin turno
	// =================================================================================

	/**
	 * Abre una atencion <b>sin turno</b> (RF-M14-002, U-24): el paciente que se atiende sin reserva.
	 *
	 * <p>Mismas reglas que el inicio de turno —{@code sesion:register}, historia que se asegura,
	 * caso opcional salvo que la oferta lo exija— mas lo que el turno resolvia y aca no esta:
	 * <ul>
	 *   <li><b>Motivo obligatorio.</b> Sin reserva nada explica por que el paciente estaba ahi.</li>
	 *   <li><b>La oferta la elige el cliente</b>, asi que se valida: de la sede (404 si no), vigente
	 *       hoy en la zona de la sede (409 {@code oferta-no-vigente}) y con quien atiende habilitado
	 *       (409 {@code profesional-no-habilitado-en-oferta}; una oferta sin habilitaciones declaradas
	 *       admite a todos, V28). En la reserva lo hacia el motor de agenda.</li>
	 *   <li><b>Quien atiende es quien inicia.</b> No hay turno que diga otro profesional.</li>
	 *   <li><b>Idempotencia por {@code Idempotency-Key}</b>, opcional: sin turno no hay
	 *       {@code uk_sesion_turno} que ancle el doble click. Misma clave y mismo pedido devuelve la
	 *       sesion ya creada; otro pedido con la misma clave, 409. Dos pedidos simultaneos con la
	 *       misma clave chocan en {@code uk_sesion_idempotencia} y el perdedor relee en
	 *       {@code READ_COMMITTED} —bajo {@code REPEATABLE READ} no veria la fila del ganador, la
	 *       leccion del adjunto concurrente de 04.02—.</li>
	 * </ul>
	 *
	 * <p>No toca {@code scheduling}: una atencion sin turno no ocupa agenda ni la libera. Turno es
	 * reserva y Sesion es atencion (DP-05); que el profesional estuviera libre no es una regla de la
	 * atencion que ya ocurrio.
	 */
	public SesionView iniciarSinTurno(
			OperatingActor actor, long consultorioId, InicioSinTurno pedido, String idempotencyKey) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirRegistro(actor, organizationId, consultorioId);
		String motivo = MotivoObligatorioException.exigir("motivo", pedido.motivo());

		String clave = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.strip();
		String huella = clave == null ? null : pedido.huella(consultorioId);

		try {
			return transaccionReadCommitted.execute(estado -> iniciarSinTurnoEnTransaccion(
					actor, organizationId, consultorioId, pedido, motivo, clave, huella));
		} catch (DataIntegrityViolationException carrera) {
			if (clave == null) {
				throw carrera;
			}
			// La otra llamada con la misma clave gano el unique. Se relee en una transaccion NUEVA
			// en READ_COMMITTED: la anterior quedo marcada para rollback y su foto no ve al ganador.
			return transaccionReadCommitted.execute(estado ->
					sesiones.findPorIdempotencyKey(organizationId, clave)
							.map(existente -> reintento(existente, clave, huella, organizationId))
							.orElseThrow(() -> carrera));
		}
	}

	private SesionView iniciarSinTurnoEnTransaccion(
			OperatingActor actor, long organizationId, long consultorioId, InicioSinTurno pedido,
			String motivo, String clave, String huella) {

		if (clave != null) {
			var yaCreada = sesiones.findPorIdempotencyKey(organizationId, clave);
			if (yaCreada.isPresent()) {
				return reintento(yaCreada.get(), clave, huella, organizationId);
			}
		}

		Instant ahora = Instant.now();
		OfertaSnapshot oferta = ofertas.find(organizationId, consultorioId, pedido.ofertaId())
				.orElseThrow(() -> new OfertaNoAtendibleException(
						pedido.ofertaId(), OfertaNoAtendibleException.Motivo.NO_EXISTE));
		LocalDate hoy = LocalDate.ofInstant(ahora, ZonaDeSede.de(consultorios, organizationId, consultorioId));
		if (!oferta.vigenteEl(hoy)) {
			throw new OfertaNoAtendibleException(oferta.id(), OfertaNoAtendibleException.Motivo.NO_VIGENTE);
		}

		long profesionalMembershipId = membershipDe(actor, organizationId, consultorioId);
		exigirHabilitado(organizationId, consultorioId, oferta.id(), profesionalMembershipId, ahora);
		exigirCasoSiLaOfertaLoRequiere(organizationId, consultorioId, oferta.id(), pedido.casoId());

		HistoriaClinicaSnapshot historia =
				historias.asegurar(organizationId, pedido.personaId(), actor.accountId());
		exigirCasoAsignable(organizationId, historia.id(), pedido.casoId());

		// saveAndFlush: el choque contra uk_sesion_idempotencia tiene que salir ACA, dentro del
		// execute, para que quien llama lo resuelva releyendo.
		Sesion sesion = sesiones.saveAndFlush(Sesion.sinTurno(
				organizationId, consultorioId, historia.id(), pedido.casoId(), oferta.id(),
				profesionalMembershipId, ahora, actor.accountId(), motivo, clave, huella));

		// El motivo NO va a la auditoria: es texto libre sobre un paciente. Ver la cabecera de V122.
		auditar(organizationId, consultorioId, actor, AuditEvents.SESION_INICIADA, sesion.getId(),
				null, "ABIERTA",
				Map.of("historiaClinicaId", String.valueOf(historia.id()),
						"turnoId", "null",
						"sinTurno", "true",
						"casoId", String.valueOf(pedido.casoId())),
				ahora);

		log.info("Sesion sin turno iniciada: sesionId={} historiaClinicaId={} ofertaId={} casoId={} "
						+ "profesional={}",
				sesion.getId(), historia.id(), oferta.id(), pedido.casoId(), profesionalMembershipId);

		return conPrevia(sesion, organizationId);
	}

	/** Misma clave: mismo pedido devuelve la sesion; otro pedido, 409. */
	private SesionView reintento(Sesion existente, String clave, String huella, long organizationId) {
		if (!huella.equals(existente.getRequestHash())) {
			throw new IdempotencyKeyConflictException(clave);
		}
		return conPrevia(existente, organizationId);
	}

	// =================================================================================
	// DP-43 (U-24) — reemplazo autorizado del profesional
	// =================================================================================

	/**
	 * Reemplaza al profesional de una atencion <b>abierta</b> (U-24): otro profesional la continua.
	 *
	 * <h2>No es un permiso para escribir en la atencion ajena</h2>
	 *
	 * <p>"Escribir en la atencion ajena es 409" sigue intacto: quien reemplaza —el
	 * {@code CONSULTORIO_ADMIN}, con {@code sesion:reassign}— no escribe nada clinico; cambia quien
	 * es el dueño, y desde ese momento el control de propiedad es el mismo contra el dueño nuevo. El
	 * anterior recibe {@code sesion-ajena} en su proximo autosave, que es exactamente lo que tiene
	 * que pasar.
	 *
	 * <p>Exige: motivo, la {@code version} leida, la sesion abierta (cerrada: 409
	 * {@code sesion-cerrada}; la atencion ya ocurrio y cambiar quien la presto seria reescribirla), y
	 * un profesional que pueda atender en la sede —membership vigente que la cubra y
	 * {@code sesion:register} (409 {@code profesional-no-asignable})— y habilitado para la oferta
	 * (409 {@code profesional-no-habilitado-en-oferta}). Deja una fila en
	 * {@code sesion_reemplazo_profesional} con el motivo y {@code SESION_PROFESIONAL_REEMPLAZADO} en
	 * la auditoria, sin el motivo.
	 *
	 * <p><b>El turno no se toca</b>: Turno es reserva y Sesion es atencion (DP-05). Que la reserva
	 * dijera otro profesional es historia de la reserva.
	 *
	 * <p>Pedir el profesional que ya tiene es un no-op: no escribe, no audita y no avanza la version.
	 */
	@Transactional
	@SuppressWarnings("java:S107")
	public SesionView reemplazarProfesional(
			OperatingActor actor, long consultorioId, long sesionId, long nuevoProfesionalMembershipId,
			String motivo, long expectedVersion) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirPermiso(actor, PermissionCodes.SESION_REASSIGN, organizationId, consultorioId);
		String razon = MotivoObligatorioException.exigir("motivo", motivo);

		Sesion sesion = sesiones.findByIdInScope(organizationId, consultorioId, sesionId)
				.filter(Sesion::estaViva)
				.orElseThrow(() -> new SesionNotAccessibleException(sesionId));
		exigirVersion(sesion, expectedVersion);
		if (sesion.estaCerrada()) {
			throw new SesionCerradaException(sesion.getId());
		}
		if (sesion.getProfesionalMembershipId() == nuevoProfesionalMembershipId) {
			return conPrevia(sesion, organizationId);
		}

		Instant ahora = Instant.now();
		exigirProfesionalQueAtiende(organizationId, consultorioId, nuevoProfesionalMembershipId, ahora);
		exigirHabilitado(organizationId, consultorioId, sesion.getOfertaId(),
				nuevoProfesionalMembershipId, ahora);

		long anterior = sesion.reemplazarProfesional(nuevoProfesionalMembershipId);
		// La columna cambia, asi que el flush emite el UPDATE ... WHERE version = N y la version
		// avanza una vez: sin force-increment.
		Sesion guardada = sesiones.saveAndFlush(sesion);
		SesionReemplazoProfesional registro = reemplazos.save(new SesionReemplazoProfesional(
				organizationId, consultorioId, guardada.getId(), anterior, nuevoProfesionalMembershipId,
				razon, actor.accountId(), ahora));

		auditar(organizationId, consultorioId, actor, AuditEvents.SESION_PROFESIONAL_REEMPLAZADO,
				guardada.getId(), "PROFESIONAL_" + anterior, "PROFESIONAL_" + nuevoProfesionalMembershipId,
				Map.of("historiaClinicaId", String.valueOf(guardada.getHistoriaClinicaId()),
						"reemplazoId", String.valueOf(registro.getId()),
						"profesionalAnteriorId", String.valueOf(anterior),
						"profesionalNuevoId", String.valueOf(nuevoProfesionalMembershipId)),
				ahora);

		log.info("Profesional de la sesion reemplazado: sesionId={} anterior={} nuevo={} por={}",
				sesionId, anterior, nuevoProfesionalMembershipId, actor.accountId());

		return conPrevia(guardada, organizationId);
	}

	/**
	 * Quien entra por un reemplazo tiene que poder atender en la sede: membership vigente que la
	 * cubra y {@code sesion:register}. Si no, la atencion quedaria con un dueño que no puede ni
	 * guardar el borrador.
	 */
	private void exigirProfesionalQueAtiende(
			long organizationId, long consultorioId, long membershipId, Instant ahora) {

		var membership = memberships.find(organizationId, membershipId)
				.filter(m -> m.validAt(ahora) && m.cubreConsultorio(consultorioId))
				.orElseThrow(() -> new ProfesionalNoAsignableException(membershipId));
		boolean atiende = permissionEvaluator.evaluate(new PermissionQuery(
				membership.accountId(), PermissionCodes.SESION_REGISTER, organizationId, consultorioId,
				null, ahora)).granted();
		if (!atiende) {
			throw new ProfesionalNoAsignableException(membershipId);
		}
	}

	/**
	 * El profesional esta habilitado para la oferta en este instante. Lista vacia de habilitaciones
	 * significa "todos" (regla de V28), no "ninguno".
	 */
	private void exigirHabilitado(
			long organizationId, long consultorioId, long ofertaId, long membershipId, Instant ahora) {

		var habilitados = ofertas.profesionalesHabilitados(organizationId, consultorioId, ofertaId);
		if (habilitados.isEmpty()) {
			return;
		}
		boolean habilitado = habilitados.stream()
				.anyMatch(h -> h.recursoId() == membershipId && h.vigenteEn(ahora));
		if (!habilitado) {
			throw new OfertaNoAtendibleException(
					ofertaId, OfertaNoAtendibleException.Motivo.PROFESIONAL_NO_HABILITADO);
		}
	}

	/**
	 * U-09 (DP-43, RF-M10-007): si la oferta exige Caso Clinico, la atencion tiene que tener uno.
	 *
	 * <p>Una oferta que no resuelve (de baja despues, o de otra sede) no exige nada: la atencion ya
	 * existe o la va a rechazar otra validacion, y negar por un dato que no se puede leer seria
	 * bloquear por las dudas.
	 */
	private void exigirCasoSiLaOfertaLoRequiere(
			long organizationId, long consultorioId, long ofertaId, Long casoId) {

		if (casoId != null) {
			return;
		}
		boolean requiere = ofertas.find(organizationId, consultorioId, ofertaId)
				.map(OfertaSnapshot::requiereCasoClinico)
				.orElse(false);
		if (requiere) {
			throw new CasoClinicoRequeridoException(ofertaId);
		}
	}

	/** U-25 (DP-43): la enmienda solo dentro de la ventana de la organizacion, desde el cierre. */
	private void exigirDentroDeLaVentana(long organizationId, Sesion sesion, Instant ahora) {
		int ventanaDias = politicaDeEnmienda.ventanaDe(organizationId);
		Instant hasta = PoliticaDeEnmienda.enmendableHasta(sesion.getCerradaEn(), ventanaDias);
		if (ahora.isAfter(hasta)) {
			throw new EnmiendaFueraDeVentanaException(
					sesion.getId(), sesion.getCerradaEn(), ventanaDias, hasta);
		}
	}

	/**
	 * Guarda el borrador de una sesion abierta.
	 *
	 * <p><b>El control optimista es el punto de la operacion, no un chequeo de rutina.</b> Dos
	 * pestanas del mismo profesional sobre la misma sesion son el caso normal; sin la version, la
	 * segunda pisa a la primera en silencio y el profesional pierde lo que escribio. El cliente
	 * manda la version que leyo y un conflicto le dice que recargue.
	 *
	 * <p>La version la controla JPA con {@code @Version}: pasarla explicitamente y compararla a
	 * mano seria un segundo control que puede divergir del que realmente decide.
	 */
	@Transactional
	public SesionView guardarBorrador(
			OperatingActor actor, long consultorioId, long sesionId, String contenido, long expectedVersion) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirRegistro(actor, organizationId, consultorioId);

		Sesion sesion = sesiones.findByIdInScope(organizationId, consultorioId, sesionId)
				.filter(Sesion::estaViva)
				.orElseThrow(() -> new SesionNotAccessibleException(sesionId));

		// Propiedad, no permiso: ver la cabecera de la clase.
		sesion.exigirPropiedadDe(membershipDe(actor, organizationId, consultorioId));

		exigirVersion(sesion, expectedVersion);
		ContenidoJson.exigirValido(contenido);

		sesion.guardarBorrador(contenido, Instant.now());
		return conPrevia(sesiones.saveAndFlush(sesion), organizationId);
	}


	/**
	 * Guarda la evaluacion base de una sesion abierta (RF-M14-003).
	 *
	 * <p>Mismos dos controles que el borrador: <b>propiedad</b> —la atencion es de un profesional, y
	 * eso no es cuestion de permiso— y <b>version</b>, porque dos pestanas sobre la misma sesion son
	 * el caso normal.
	 *
	 * <p>Lo que NO valida es que los campos esten. "Seguimiento no exige examen completo", asi que
	 * una evaluacion con solo dolor y evolucion es valida. Ver {@link EvaluacionBase}.
	 */
	@Transactional
	public SesionView evaluar(
			OperatingActor actor, long consultorioId, long sesionId,
			EvaluacionBase evaluacion, long expectedVersion) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirRegistro(actor, organizationId, consultorioId);

		Sesion sesion = sesiones.findByIdInScope(organizationId, consultorioId, sesionId)
				.filter(Sesion::estaViva)
				.orElseThrow(() -> new SesionNotAccessibleException(sesionId));

		sesion.exigirPropiedadDe(membershipDe(actor, organizationId, consultorioId));
		exigirVersion(sesion, expectedVersion);

		sesion.evaluar(evaluacion, Instant.now());
		return conPrevia(sesiones.saveAndFlush(sesion), organizationId);
	}

	/**
	 * Cierra la atencion y le asigna su correlativo (RF-M14-006..008, RN-M14-005).
	 *
	 * <h2>El orden importa y no es negociable</h2>
	 *
	 * <pre>
	 *   1. idempotencia   &lt;- ANTES de pedir un numero
	 *   2. propiedad y version
	 *   3. minimos del cierre
	 *   3b. precio particular si la deuda lo necesita (DP-17)  &lt;- ANTES de pedir un numero
	 *   4. asegurar el numerador  &lt;- en su PROPIA transaccion
	 *   5. incrementar y leer     &lt;- toma el lock de fila y serializa
	 *   5b. numero DENTRO DEL CASO, si la sesion tiene caso  &lt;- SIEMPRE despues del 5
	 *   6. cerrar
	 * </pre>
	 *
	 * <h2>Los dos numeradores van en este orden y nunca en el otro</h2>
	 *
	 * <p>Desde 04.03 esta transaccion puede tomar <b>dos</b> numeradores: el de la Historia Clinica
	 * (paso 5, {@code sesion_numerador} de V35) y el del Caso (paso 5b,
	 * {@code caso_sesion_numerador} de V47, pedido por {@code clinical.spi.CasoDirectory}).
	 * <b>Es la primera transaccion de este sistema que toma dos</b>, y eso introduce un riesgo que
	 * ninguna etapa anterior tuvo.
	 *
	 * <p>Cada numerador es un lock exclusivo de fila. Si dos cierres concurrentes los tomaran en
	 * orden distinto —uno historia&rarr;caso y el otro caso&rarr;historia— cada uno esperaria el
	 * lock que el otro ya tiene y <b>se bloquearian mutuamente</b>. Pasa apenas dos pacientes
	 * comparten caso, o un paciente tiene dos casos y se cierran dos sesiones a la vez: no hace
	 * falta nada exotico.
	 *
	 * <p>La regla es <b>historia primero, caso despues</b>, siempre, aunque la sesion no tenga
	 * caso y aunque el numero del caso parezca "el importante". Un orden total fijo sobre los
	 * recursos es lo unico que evita el ciclo de espera; no hay reintento que lo arregle sin
	 * devolverle un 409 al profesional. <b>Si alguna vez hay un tercer numerador, entra al final
	 * de esta lista, no en el medio.</b>
	 *
	 * <p>El caso ya fue validado al <b>iniciar</b> la sesion o al cambiarlo ({@link #cambiarCaso}),
	 * asi que el cierre no lo vuelve a validar: un caso que se cerro mientras la atencion
	 * transcurria <b>no</b> impide cerrarla. La atencion ocurrio, y negarle el cierre obligaria a
	 * elegir entre perder el registro clinico o reabrir el caso para poder guardarlo.
	 *
	 * <p><b>El paso 1 va primero</b>: si no, cada reintento consume un correlativo que despues nadie
	 * usa, y la numeracion del paciente queda con huecos que parecen sesiones borradas. Una sesion ya
	 * cerrada se devuelve tal cual, con su numero — RN-M14-005 pide resultado estable ante retry, y
	 * apretar dos veces "cerrar" es el caso normal.
	 *
	 * <p><b>El paso 4 va en una transaccion aparte</b> por la misma razon que en 05.02: crear la fila
	 * dentro de esta produce un deadlock entre los primeros cierres concurrentes de una historia
	 * clinica, y atrapar la excepcion no alcanza porque no des-marca la transaccion.
	 *
	 * <h2>Cerrar no cobra</h2>
	 *
	 * <p>DP-06 y la regla de la etapa: "cierre clinico != cobro". El cierre no exige ningun pago;
	 * la DEUDA la devengan los observadores dentro de esta transaccion (07.01, F-4).
	 *
	 * <p><b>Pero si exige que la deuda se pueda valorizar</b> (DP-17, AKINE E-7b, paso 3b): si la
	 * del paciente es el precio particular y la oferta no lo tiene ese dia, 409
	 * {@code oferta-sin-precio} antes de pedir ningun numero. Lo que antes era "se loguea y no se
	 * devenga" dejaba prestaciones sin deuda, que nadie nota hasta cuadrar la caja del mes.
	 *
	 * <h2>READ_COMMITTED, como toda mutacion que toma un numerador</h2>
	 *
	 * <p>Es la regla que 05.02 dejo fijada: con {@code REPEATABLE READ} InnoDB fija la foto en la
	 * primera lectura consistente, que ocurre <b>antes</b> del lock, asi que las mutaciones que
	 * serializan van en {@code READ_COMMITTED}. Este metodo era la <b>unica</b> de las once que
	 * toman un numerador que no lo declaraba — se detecto al escribir los tests de integracion de
	 * AKINE-04.03, comparandolo contra las otras diez.
	 *
	 * <p>Que el descuido no se notara tiene una explicacion y no sirve como defensa: una
	 * transaccion lee siempre sus propias escrituras, asi que leer el numerador <b>despues</b> de
	 * incrementarlo devuelve el valor nuevo aun bajo {@code REPEATABLE READ}. Depender de eso es
	 * depender del orden de dos lineas dentro del metodo, no de una garantia declarada — y
	 * AKINE-04.03 acaba de convertir este metodo en el que toma <b>dos</b> numeradores.
	 */
	public SesionView cerrar(
			OperatingActor actor, long consultorioId, long sesionId,
			CierreDeSesion cierre, long expectedVersion) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirRegistro(actor, organizationId, consultorioId);

		// PASO 4, ANTES de abrir la transaccion del cierre: las filas de los dos numeradores, cada
		// una en su propia transaccion (FilaLockPrevia). Abrirlas anidadas retenia dos conexiones
		// por pedido y trababa el pool. La sesion se lee aca solo para saber de que historia y de
		// que caso es: la lectura que vale es la de adentro.
		asegurarNumeradores(organizationId, consultorioId, sesionId);

		return transaccionReadCommitted.execute(estado -> cerrarEnTransaccion(
				actor, consultorioId, sesionId, cierre, expectedVersion, organizationId));
	}

	private SesionView cerrarEnTransaccion(
			OperatingActor actor, long consultorioId, long sesionId, CierreDeSesion cierre,
			long expectedVersion, long organizationId) {

		Sesion sesion = sesiones.findByIdInScope(organizationId, consultorioId, sesionId)
				.filter(Sesion::estaViva)
				.orElseThrow(() -> new SesionNotAccessibleException(sesionId));

		sesion.exigirPropiedadDe(membershipDe(actor, organizationId, consultorioId));

		// PASO 1. Antes de tocar el numerador. Ver la cabecera.
		if (sesion.estaCerrada()) {
			return conPrevia(sesion, organizationId);
		}

		exigirVersion(sesion, expectedVersion);
		cierre.exigirMinimos();

		// U-09 (DP-43): "toda prestacion clinica debe resolver Caso antes del cierre" (M27). Solo
		// alcanza a una atencion abierta antes de DP-43 sin caso —las nuevas ya no inician sin el—
		// y no la bloquea: se le asigna el caso (PUT .../caso) y se cierra. Una AUSENCIA se cierra
		// igual: no hubo prestacion, y exigir un caso para registrar que el paciente falto seria el
		// "Caso artificial" que el RF prohibe. La grupal tiene caso siempre (V102).
		if (cierre.asistencia() == Asistencia.PRESENTE) {
			exigirCasoSiLaOfertaLoRequiere(organizationId, sesion.getConsultorioId(),
					sesion.getOfertaId(), sesion.getCasoId());
		}

		Instant ahora = Instant.now();
		long cerradaPor = actor.accountId();

		// PASO 3b. DP-17 (AKINE E-7b): toda prestacion cerrada genera deuda. El hecho se arma
		// ANTES de numerar —todo menos el numero— y, si la deuda del paciente va a ser el precio
		// particular y la oferta no lo tiene ese dia, se corta aca con 409: sin correlativo
		// consumido, sin autorizacion consumida, sin obligacion. Los observadores reciben despues
		// exactamente este mismo hecho, con el numero puesto.
		SesionCerrada hecho = hechoDelCierre(sesion, cierre, ahora, cerradaPor, organizationId);
		exigirPrecioSiLaDeudaEsParticular(hecho);

		// PASO 4 y 5. La fila se asegura afuera; el incremento toma el lock y serializa.
		numerador.incrementar(organizationId, sesion.getHistoriaClinicaId());
		int numero = numerador.leerUltimo(organizationId, sesion.getHistoriaClinicaId());

		// PASO 5b. SIEMPRE despues del 5: ver "Los dos numeradores" en la cabecera del metodo.
		Integer numeroEnCaso = sesion.getCasoId() == null
				? null
				: casos.siguienteNumeroDeSesion(organizationId, sesion.getCasoId());

		sesion.cerrar(cierre, numero, numeroEnCaso, ahora, cerradaPor);

		// PASO 6b. El cierre inaugura el historial de contenido (06.06). La version 1 es lo que se
		// acaba de asentar, con el instante y el autor DEL CIERRE.
		//
		// Se escribe aca y no perezosamente en la primera enmienda, que es la alternativa obvia:
		// asi TODA sesion cerrada tiene historial completo y consultarlo es una sola consulta. Con
		// escritura perezosa, una sesion nunca enmendada no tendria filas y el endpoint de
		// historial tendria que sintetizar la version 1 leyendo la cabecera — un segundo camino de
		// lectura que puede divergir del primero justo en la fila que mas importa, el original.
		// Las sesiones cerradas antes de V53 la reciben por el backfill de esa migracion.
		//
		// Va ANTES de notificar a los observadores a proposito: el registro clinico se asienta
		// primero, y recien despues se deriva lo economico. Si un observador hace fallar el cierre
		// —lo puede hacer, y es intencional— cae todo junto, asi que el orden no cambia el
		// resultado; cambia que lea el codigo.
		//
		// C-6: la v1 lleva tambien la foto de tratamientos y mediciones. Sin ella, la primera
		// enmienda que los corrigiera se llevaria el original.
		versiones.save(SesionVersion.original(sesion, fotoClinica(organizationId, sesionId)));

		// Dentro de la transaccion, a proposito: una prestacion sin deuda NO se nota —nadie
		// reclama una factura que nunca existio— y el centro descubre el agujero cuando cuadra
		// la caja del mes. La contrapartida esta asumida en CierreDeSesionObserver.
		SesionCerrada aviso = hecho.conNumeroSesion(numero);
		observadores.forEach(observador -> observador.alCerrar(aviso));

		auditar(organizationId, consultorioId, actor, AuditEvents.SESION_CERRADA, sesion.getId(),
				"ABIERTA", "CERRADA",
				Map.of("historiaClinicaId", String.valueOf(sesion.getHistoriaClinicaId()),
						"numero", String.valueOf(numero),
						"numeroEnCaso", String.valueOf(numeroEnCaso),
						"asistencia", String.valueOf(cierre.asistencia())),
				ahora);

		log.info("Sesion cerrada: sesionId={} numero={} numeroEnCaso={} historiaClinicaId={} "
						+ "asistencia={}",
				sesionId, numero, numeroEnCaso, sesion.getHistoriaClinicaId(), cierre.asistencia());

		return conPrevia(sesiones.saveAndFlush(sesion), organizationId);
	}

	/**
	 * Las filas de {@code sesion_numerador} y {@code caso_sesion_numerador}, fuera de toda
	 * transaccion. Si la sesion no existe o ya esta cerrada no hay nada que numerar: el cierre lo
	 * resuelve adentro, con su 404 o su reintento estable.
	 */
	private void asegurarNumeradores(long organizationId, long consultorioId, long sesionId) {
		sesiones.findByIdInScope(organizationId, consultorioId, sesionId)
				.filter(sesion -> sesion.estaViva() && !sesion.estaCerrada())
				.ifPresent(sesion -> {
					numeradorIniciador.asegurar(organizationId, sesion.getHistoriaClinicaId());
					if (sesion.getCasoId() != null) {
						casos.asegurarNumeradorDeSesiones(organizationId, sesion.getCasoId());
					}
				});
	}

	// =================================================================================
	// AKINE-06.06 — enmendar una sesion cerrada
	// =================================================================================

	/**
	 * Enmienda una sesion cerrada: escribe una version nueva y deja intacta la anterior
	 * (RF-M14-010).
	 *
	 * <h2>Esto es lo que 06.05 dejo fail-closed, y ahora tiene puerta</h2>
	 *
	 * <p>Escribir sobre una sesion cerrada sigue siendo 409 por los caminos normales —borrador,
	 * evaluacion, segundo cierre—. Este es el unico camino por el que el contenido de una atencion
	 * cerrada cambia, y cambia <b>dejando rastro</b>: version numerada, motivo obligatorio, autor
	 * propio y evento de auditoria. RN-M14-006 no prohibe corregir; prohibe corregir en silencio.
	 *
	 * <h2>LO QUE ESTA OPERACION NO PUEDE CORREGIR, y conviene saberlo antes de intentarlo</h2>
	 *
	 * <p><b>La asistencia.</b> Una sesion cerrada con {@code AUSENTE} cuando el paciente vino —o al
	 * reves— no se arregla enmendando, y es el caso adverso declarado de la etapa. Cambiarla es un
	 * acto <b>economico</b>: obliga a devengar una obligacion que no existe o a anular una que si
	 * (M18), y a consumir o revertir una unidad de autorizacion (M17). Son compensaciones
	 * explicitas con dueño en otros modulos. Lo que si se puede hacer es enmendar la nota de cierre
	 * dejando escrito lo que paso, con ese motivo: el relato clinico queda correcto y trazado, y el
	 * hecho administrativo queda pendiente de quien tenga permiso economico.
	 *
	 * <h2>LOS OBSERVADORES DEL CIERRE NO SE VUELVEN A DISPARAR. NUNCA</h2>
	 *
	 * <p>Ni {@code billing.ObligacionDevengador} ni {@code ConsumoDeAutorizacionEnCierre}. Y el
	 * motivo <b>no</b> es que re-dispararlos duplicaria la deuda: se verifico contra el codigo que
	 * los dos son idempotentes por el hecho de origen. Los motivos reales son dos y mas finos:
	 *
	 * <ol>
	 *   <li><b>Es asimetrico.</b> Re-disparar solo puede AGREGAR efectos economicos, nunca
	 *       sacarlos: no existe ningun observador de "des-cierre", asi que una sesion que pasara a
	 *       ausente se quedaria con la deuda devengada y la unidad consumida.</li>
	 *   <li><b>La idempotencia del consumo es por AUTORIZACION, no por sesion.</b> Si entre el
	 *       cierre y la enmienda cambio cual es la autorizacion elegible, el origen es el mismo
	 *       pero el {@code autorizacion_id} es otro, {@code uk_autorizacion_movimiento_origen} no
	 *       choca y se consume una segunda unidad.</li>
	 * </ol>
	 *
	 * <p>Y ademas no hace falta: los dos observadores leen {@code asistio}, {@code ofertaId} y el
	 * precio de la oferta, y <b>ninguno de los tres es enmendable</b>. Por eso el criterio de
	 * aceptacion "no hay cambios economicos implicitos" es una propiedad del modelo y no una
	 * promesa de este metodo.
	 *
	 * <h2>Concurrencia: sin force-increment y con flush</h2>
	 *
	 * <p>Enmendar <b>ensucia la cabecera</b> —{@code ultimo_numero_version} cambia— asi que el
	 * flush ya emite un {@code UPDATE ... WHERE version = N} versionado. Dos enmiendas concurrentes
	 * leen la misma version, las dos ensucian la fila, una gana y la otra recibe 409. <b>La
	 * garantia ya esta</b>, y {@code OPTIMISTIC_FORCE_INCREMENT} no agregaria ninguna: agregaria un
	 * segundo incremento, dejando la base en {@code leida + 2} mientras la vista devuelve
	 * {@code leida + 1}. La regla es <b>force-increment solo donde la escritura NO toca ninguna
	 * columna del padre</b>, y aca la toca. 04.02 lo pago.
	 *
	 * <p>{@code saveAndFlush} y no {@code save}, por lo mismo que esta escrito en el puerto: la
	 * respuesta lleva la version de la cabecera y el cliente la va a mandar en su proxima enmienda.
	 *
	 * <p><b>{@code @Transactional} normal, no {@code READ_COMMITTED}</b>, al reves que
	 * {@link #cerrar}: aca no se toma el lock de fila de ningun numerador. El control es optimista
	 * de punta a punta, igual que en {@code EntradaClinicaService#enmendar}. La regla de 05.02 es
	 * para las mutaciones que <b>serializan con un lock</b>, y esta no es una.
	 *
	 * @throws SesionNoCerradaException si la sesion sigue abierta (409): eso se guarda, no se
	 *                                  enmienda
	 */
	@Transactional
	public SesionView enmendar(
			OperatingActor actor, long consultorioId, long sesionId,
			ContenidoDeSesion contenido, String motivo, long expectedVersion) {

		return enmendar(actor, consultorioId, sesionId, contenido, null, null, motivo,
				expectedVersion);
	}

	/**
	 * Enmienda una sesion cerrada, incluidos sus tratamientos y sus mediciones (C-6).
	 *
	 * <p>{@code tratamientos} y {@code mediciones} son listas <b>completas</b> de lo que tiene que
	 * quedar, o {@code null} para no tocarlos. Es la unica excepcion al "reemplazo completo" de
	 * 06.06: el contrato es aditivo, y un cliente que no conoce estos campos no puede, por
	 * omitirlos, vaciar los tratamientos de una sesion cerrada. Lista vacia es "no queda ninguno".
	 *
	 * <p>El orden es el que hace correcta la concurrencia: la cabecera se escribe y se flushea
	 * <b>antes</b> de tocar tratamientos o mediciones, asi que una segunda enmienda concurrente se
	 * queda esperando el lock de fila de {@code sesion} y termina en 409 sin haber leido un solo
	 * tratamiento. Y la foto se toma <b>despues</b> de aplicarlos, para que la version diga lo que
	 * quedo.
	 *
	 * @param tratamientos lo que tiene que quedar, o {@code null} para no tocarlos
	 * @param mediciones   lo que tiene que quedar, o {@code null} para no tocarlas
	 */
	@Transactional
	@SuppressWarnings("java:S107")
	public SesionView enmendar(
			OperatingActor actor, long consultorioId, long sesionId,
			ContenidoDeSesion contenido,
			List<TratamientoEnmendado> tratamientosEnmendados,
			List<MedicionEnmendada> medicionesEnmendadas,
			String motivo, long expectedVersion) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		// U-25 (DP-43): permiso propio. Hasta DP-43 era sesion:register.
		exigirPermiso(actor, PermissionCodes.SESION_AMEND, organizationId, consultorioId);

		Sesion sesion = sesiones.findByIdInScope(organizationId, consultorioId, sesionId)
				.filter(Sesion::estaViva)
				.orElseThrow(() -> new SesionNotAccessibleException(sesionId));

		// Propiedad, no permiso: escribir en la atencion ajena es 409 y no 403, y enmendar es
		// escribir. El reemplazo autorizado de DP-43 es solo de atenciones ABIERTAS: una cerrada la
		// enmienda quien la presto.
		sesion.exigirPropiedadDe(membershipDe(actor, organizationId, consultorioId));

		exigirVersion(sesion, expectedVersion);

		// U-25 (DP-43): la ventana se cuenta desde el cierre. Una sesion abierta no tiene cierre y
		// sigue su camino: la rechaza `Sesion#enmendar` con sesion-no-cerrada, que es lo que es.
		if (sesion.estaCerrada()) {
			exigirDentroDeLaVentana(organizationId, sesion, Instant.now());
		}

		int anterior = sesion.getUltimoNumeroVersion();
		// Aplica el contenido y reserva el numero. Valida coherencia clinica y minimos del cierre
		// reusando EvaluacionBase y CierreDeSesion: una enmienda no puede guardar lo que el camino
		// normal habria rechazado.
		int numero = sesion.enmendar(contenido, sesion.getAsistencia());

		// C-6: el motivo se exige ANTES de escribir nada, porque ademas es el motivo de baja de los
		// tratamientos que la enmienda quita. La version lo vuelve a exigir: ningun camino que
		// esquive este servicio puede escribir una enmienda sin decir por que.
		String razon = SesionVersion.exigirMotivoDeEnmienda(sesionId, motivo);

		Instant ahora = Instant.now();
		Sesion cabecera = sesiones.saveAndFlush(sesion);

		// C-6. Despues del flush de la cabecera y sin avanzarVersion: ver el javadoc del metodo.
		if (tratamientosEnmendados != null) {
			tratamientoService.aplicarEnmienda(actor, cabecera, tratamientosEnmendados, razon, ahora);
		}
		if (medicionesEnmendadas != null) {
			medicionService.aplicarEnmienda(actor, cabecera, medicionesEnmendadas, ahora);
		}

		// SesionVersion.enmienda COPIA el contenido de la sesion ya modificada y la foto de lo que
		// quedo: por eso va despues de todo lo anterior y no antes.
		versiones.save(SesionVersion.enmienda(
				cabecera, fotoClinica(organizationId, sesionId), razon, ahora, actor.accountId()));

		// El MOTIVO no va a la auditoria, y es la misma decision que tomo 04.02 con el cuerpo de la
		// entrada clinica: es prosa que el profesional escribe sobre un paciente, y `audit_event`
		// se consulta con `auditoria:read`, que no es un permiso clinico. Va la transicion, que es
		// lo que permite reconstruir el historial y llevar a quien investiga a la fila que tiene el
		// detalle.
		auditTrail.record(new AuditEntry(
				organizationId,
				consultorioId,
				actor.accountId(),
				AuditEvents.SESION_AMENDED,
				AuditEvents.ENTITY_SESION,
				cabecera.getId(),
				"VERSION_" + anterior,
				"VERSION_" + numero,
				Map.of("historiaClinicaId", String.valueOf(cabecera.getHistoriaClinicaId()),
						"numeroSesion", String.valueOf(cabecera.getNumeroSesion()),
						"tratamientosEnmendados", String.valueOf(tratamientosEnmendados != null),
						"medicionesEnmendadas", String.valueOf(medicionesEnmendadas != null)),
				null,
				AuditEvents.correlationId(),
				ahora));

		log.info("Sesion enmendada: sesionId={} numeroVersion={} historiaClinicaId={} "
						+ "tratamientos={} mediciones={}",
				sesionId, numero, cabecera.getHistoriaClinicaId(),
				tratamientosEnmendados != null, medicionesEnmendadas != null);

		return conPrevia(cabecera, organizationId);
	}

	/**
	 * El historial completo de versiones de una sesion, de la 1 a la ultima (RF-M24-005).
	 *
	 * <p>Exige {@code sesion:register} en la sede, igual que leer la sesion: el historial es el
	 * mismo contenido clinico contado en el tiempo, y protegerlo con menos que el original seria
	 * una puerta lateral a lo mismo.
	 *
	 * <p><b>Una sesion abierta devuelve lista vacia y no un error.</b> Todavia no tiene contenido
	 * versionado —la v1 se escribe al cerrar— y para la pantalla "no hay nada que comparar" no es
	 * una condicion excepcional: es el estado normal de la atencion que esta ocurriendo.
	 */
	@Transactional(readOnly = true)
	public List<SesionVersionView> versiones(
			OperatingActor actor, long consultorioId, long sesionId) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirRegistro(actor, organizationId, consultorioId);

		Sesion sesion = sesiones.findByIdInScope(organizationId, consultorioId, sesionId)
				.filter(Sesion::estaViva)
				.orElseThrow(() -> new SesionNotAccessibleException(sesionId));

		List<SesionVersion> historial = versiones.buscarPorSesion(organizationId, sesion.getId());

		// En lote y solo el nombre: la pantalla muestra "quien" sin tener que resolver ids, y el
		// contrato de platform.spi no devuelve nada del paciente. Una cuenta que ya no resuelve
		// deja el nombre ausente, no inventado.
		Map<Long, AccountIdentity> autores = identidades.identidadesDe(
				historial.stream().map(SesionVersion::getRegistradaPor).distinct().toList());

		return historial.stream()
				.map(version -> SesionVersionView.de(version,
						java.util.Optional.ofNullable(autores.get(version.getRegistradaPor()))
								.map(AccountIdentity::nombreCompleto)
								.orElse(null)))
				.toList();
	}

	// =================================================================================
	// Caso de una atencion ya iniciada
	// =================================================================================

	/**
	 * Asocia, cambia o quita el Caso Clinico de una sesion abierta.
	 *
	 * <p>Iniciar la atencion admite el caso, pero la idempotencia del inicio manda sobre el: una
	 * sesion que ya existe se devuelve con el caso que tenga. Sin esta operacion, un profesional
	 * que abrio la atencion sin caso —o con el equivocado— no tenia como corregirlo antes de
	 * cerrar, y el cierre numera dentro del caso que la sesion tenga en ese momento.
	 *
	 * <p>Las reglas son las del inicio mas las de toda escritura sobre la atencion:
	 * <ul>
	 *   <li><b>Abierta.</b> Una sesion cerrada ya tiene su {@code numeroEnCaso}: cambiarle el caso
	 *       seria renumerar historia clinica (409 {@code sesion-cerrada}).</li>
	 *   <li><b>Propiedad, no permiso</b>: escribir en la atencion ajena es 409.</li>
	 *   <li><b>Version</b>: el caso cambia una columna de la sesion, asi que el flush emite el
	 *       {@code UPDATE ... WHERE version = N} y avanza la version una sola vez; sin
	 *       force-increment.</li>
	 *   <li><b>Caso activo de la misma historia</b>: uno de otro paciente es 404 indistinguible de
	 *       "no existe", y uno cerrado es 409.</li>
	 * </ul>
	 *
	 * <p>{@code casoId} nulo quita el caso, salvo que la oferta lo exija (DP-43, RF-M10-007: 409
	 * {@code caso-clinico-requerido}). Pedir el caso que ya tiene es un no-op: no escribe, no audita
	 * y no avanza la version.
	 */
	@Transactional
	public SesionView cambiarCaso(
			OperatingActor actor, long consultorioId, long sesionId, Long casoId,
			long expectedVersion) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirRegistro(actor, organizationId, consultorioId);

		Sesion sesion = sesiones.findByIdInScope(organizationId, consultorioId, sesionId)
				.filter(Sesion::estaViva)
				.orElseThrow(() -> new SesionNotAccessibleException(sesionId));

		sesion.exigirPropiedadDe(membershipDe(actor, organizationId, consultorioId));
		exigirVersion(sesion, expectedVersion);

		Long anterior = sesion.getCasoId();
		if (java.util.Objects.equals(anterior, casoId)) {
			// Aun asi, una sesion cerrada no admite "asignar caso": el no-op no puede tapar eso.
			sesion.asignarCaso(casoId);
			return conPrevia(sesion, organizationId);
		}

		// AKINE-08.05: antes de validar el caso nuevo. Una atencion grupal no cambia de Caso, sea
		// cual sea el pedido: su Caso lo fijo la derivacion.
		if (sesion.esDeOrigenGrupal()) {
			throw new CasoDeAtencionGrupalException(sesion.getId());
		}
		// U-09 (DP-43): quitar el caso de una atencion cuya oferta lo exige deja una atencion que
		// no podria haberse iniciado asi. Cambiarlo por otro si se puede.
		if (!sesion.estaCerrada()) {
			exigirCasoSiLaOfertaLoRequiere(organizationId, sesion.getConsultorioId(),
					sesion.getOfertaId(), casoId);
		}
		exigirCasoAsignable(organizationId, sesion.getHistoriaClinicaId(), casoId);
		sesion.asignarCaso(casoId);
		Sesion guardada = sesiones.saveAndFlush(sesion);

		auditar(organizationId, consultorioId, actor, AuditEvents.SESION_CASO_CAMBIADO,
				guardada.getId(), "CASO_" + anterior, "CASO_" + casoId,
				Map.of("historiaClinicaId", String.valueOf(guardada.getHistoriaClinicaId()),
						"casoAnteriorId", String.valueOf(anterior),
						"casoId", String.valueOf(casoId)),
				Instant.now());

		log.info("Caso de la sesion cambiado: sesionId={} casoAnterior={} casoNuevo={}",
				sesionId, anterior, casoId);

		return conPrevia(guardada, organizationId);
	}

	/**
	 * Adjunta la evaluacion de la sesion ANTERIOR del mismo paciente.
	 *
	 * <p>Es la mitad "cambio" del requisito de la etapa, y viaja con la sesion en vez de en un
	 * endpoint aparte porque la pantalla la necesita en el mismo momento: mostrar "la vez pasada
	 * tenia 7" al lado del campo de dolor es lo que hace que el profesional cargue una evolucion
	 * real en vez de la que recuerda.
	 */
	/**
	 * Arma el hecho del cierre que reciben los observadores, todo menos el numero de sesion.
	 *
	 * <p>El precio se lee de la Oferta ACA y no en el observador: quien reacciona no tiene por que
	 * conocer M27, y si cada observador lo leyera por su cuenta, dos de ellos podrian devengar
	 * contra precios distintos si alguien edita la oferta en el medio.
	 *
	 * <p>La lista puede estar vacia y eso es legitimo: { encounter} no sabe quien lo escucha.
	 */
	private SesionCerrada hechoDelCierre(
			Sesion sesion,
			CierreDeSesion cierre,
			Instant ahora,
			long cerradaPorCuentaId,
			long organizationId) {

		// AKINE B-3 (RF-M16-009): el precio que rige el DIA del cierre en la sede —un precio particular
		// por vigencia si lo hay, si no el de lista—. El devengo lo copia y no lo vuelve a leer.
		var precio = ofertas.precioEn(
				organizationId, sesion.getConsultorioId(), sesion.getOfertaId(), ahora);

		// AKINE-06.04. Que practicas se aplicaron REALMENTE, para que el consumo de autorizaciones
		// deje de poder imputarse a la autorizacion equivocada. Se lee ACA por el mismo motivo por
		// el que se lee el precio: quien reacciona no tiene por que conocer el modelo de la
		// sesion, y si cada observador lo leyera por su cuenta dos de ellos podrian ver conjuntos
		// distintos si alguien edita en el medio.
		//
		// VACIO NO SIGNIFICA "NINGUNA" sino "no se sabe": son todas las sesiones anteriores a
		// 06.04 y las de ofertas que no registran practicas. El consumidor conserva ahi el
		// comportamiento anterior.
		var practicas = java.util.Set.copyOf(
				tratamientos.practicasVigentesDe(organizationId, sesion.getId()));

		return new SesionCerrada(
				sesion.getId(),
				organizationId,
				sesion.getConsultorioId(),
				personaDe(sesion, organizationId),
				sesion.getOfertaId(),
				// Todavia sin numero: se pide despues de validar el precio (DP-17).
				0,
				cierre.asistencia() == Asistencia.PRESENTE,
				ahora,
				cerradaPorCuentaId,
				precio.map(PrecioDeOferta::precioBase).orElse(null),
				precio.map(PrecioDeOferta::moneda).orElse(null),
				practicas,
				// AKINE C-4: el consumo no gasta autorizaciones atadas a otro caso (RF-M17-007).
				sesion.getCasoId(),
				// AKINE F-4: si la oferta no admite obra social, el devengo no busca cobertura.
				precio.map(PrecioDeOferta::admiteObraSocial).orElse(false),
				// AKINE E-6: billing imputa el prepago que la recepcion de ese turno tomo.
				sesion.getTurnoId(),
				// AKINE E-7 (RF-M08-007): si el mostrador resolvio la atencion como Particular, eso
				// manda sobre la cobertura. Se lee ACA, una vez, por el mismo motivo que el precio:
				// el devengo y el consumo tienen que ver la misma decision.
				sesion.getTurnoId() != null && turnos.atendidoComoParticular(
						organizationId, sesion.getConsultorioId(), sesion.getTurnoId()),
				// AKINE-08.05: el origen grupal viaja para que el devengo no cobre dos veces la clase.
				sesion.getClaseId());
	}

	/**
	 * DP-17 (AKINE E-7b): toda prestacion cerrada genera deuda. Si la deuda del paciente va a ser
	 * el precio particular —recepcion Particular, oferta sin obra social o sin cobertura
	 * aplicable— y la oferta no tiene precio el dia del cierre, el cierre se bloquea con 409
	 * {@code oferta-sin-precio} hasta que se cargue el precio.
	 *
	 * <p>Quien decide si hace falta precio particular es quien devenga, con su propia regla: ver
	 * {@link PrecioParticularDelCierre}. Con cobertura, el coseguro sale del arancel y no hace falta.
	 * La pregunta se hace solo si falta el precio: con precio no hay nada que decidir, y asi el
	 * caso comun no paga la busqueda de cobertura dos veces.
	 */
	private void exigirPrecioSiLaDeudaEsParticular(SesionCerrada hecho) {
		boolean tarifada = hecho.precioDeLaOferta() != null && hecho.precioDeLaOferta().signum() > 0;
		if (tarifada) {
			return;
		}
		boolean exige = requisitosDePrecio.stream()
				.anyMatch(requisito -> requisito.exigePrecioParticular(hecho));
		if (exige) {
			LocalDate dia = diaLocalDeLaSede(hecho);
			log.info("Cierre bloqueado por oferta sin precio (DP-17): sesionId={} ofertaId={} dia={}",
					hecho.sesionId(), hecho.ofertaId(), dia);
			throw new OfertaSinPrecioException(hecho.ofertaId(), dia,
					OfertaSinPrecioException.Motivo.de(hecho));
		}
	}

	/** El dia del cierre en la zona de la sede; UTC si la sede no resuelve, como el devengo. */
	private LocalDate diaLocalDeLaSede(SesionCerrada hecho) {
		ZoneId zona = consultorios.find(hecho.organizationId(), hecho.consultorioId())
				.map(ConsultorioSnapshot::timezone)
				.map(declarada -> {
					try {
						return ZoneId.of(declarada);
					} catch (DateTimeException invalida) {
						return (ZoneId) ZoneOffset.UTC;
					}
				})
				.orElse(ZoneOffset.UTC);
		return LocalDate.ofInstant(hecho.cerradaEn(), zona);
	}

	/**
	 * La persona de la sesion, via su Historia Clinica.
	 *
	 * <p>La sesion no guarda { persona_id}: cuelga de la HC, y la HC es de quien es. Duplicar
	 * la persona en la sesion habilitaria que las dos discrepen, y no hay ninguna consulta que lo
	 * justifique.
	 */
	private long personaDe(Sesion sesion, long organizationId) {
		return historias.findPorId(organizationId, sesion.getHistoriaClinicaId())
				.map(HistoriaClinicaSnapshot::personaId)
				.orElseThrow(() -> new IllegalStateException(
						"La sesion " + sesion.getId() + " apunta a una historia clinica que no existe"));
	}

	/**
	 * Lo que la version fotografia de las tablas vivas: tratamientos vigentes con sus parametros
	 * y mediciones (C-6).
	 *
	 * <p>Se lee dentro de la transaccion de quien escribe, despues de sus escrituras: las
	 * consultas disparan el flush automatico y ven lo que quedo.
	 */
	private FotoClinica fotoClinica(long organizationId, long sesionId) {
		List<FotoClinica.Tratamiento> vigentes = tratamientos.listarVigentes(organizationId, sesionId)
				.stream()
				.map(tratamiento -> new FotoClinica.Tratamiento(
						tratamiento, parametros.listarDe(organizationId, tratamiento.getId())))
				.toList();
		return new FotoClinica(vigentes, mediciones.listarDeSesion(organizationId, sesionId));
	}

	private SesionView conPrevia(Sesion sesion, long organizationId) {
		SesionView vista = sinVentana(sesion, organizationId);
		// U-25: solo una sesion cerrada tiene ventana. El autosave de una abierta no paga la lectura
		// de la politica.
		return sesion.getCerradaEn() == null
				? vista
				: vista.conEnmendableHasta(PoliticaDeEnmienda.enmendableHasta(
						sesion.getCerradaEn(), politicaDeEnmienda.ventanaDe(organizationId)));
	}

	private SesionView sinVentana(Sesion sesion, long organizationId) {
		return SesionView.de(sesion, sesiones
				.findPreviaEvaluada(organizationId, sesion.getHistoriaClinicaId(), sesion.getIniciadaEn())
				.map(previa -> new SesionView.EvaluacionPrevia(
						previa.getIniciadaEn(),
						previa.getDolorEva(),
						previa.getEvolucion() == null ? null : previa.getEvolucion().name()))
				.orElse(null),
				claseTitulo(sesion, organizationId));
	}

	/** El nombre de la clase de una Sesion grupal (08.05), calculado al leer. {@code null} si no es. */
	private String claseTitulo(Sesion sesion, long organizationId) {
		Long claseId = sesion.getClaseId();
		if (claseId == null) {
			return null;
		}
		return titulosDeClase.stream()
				.map(titulo -> titulo.de(organizationId, sesion.getConsultorioId(), claseId))
				.flatMap(Optional::stream)
				.findFirst()
				.orElse(null);
	}

	/**
	 * El control optimista, en un solo lugar.
	 *
	 * <p>Se lanza el mismo tipo que JPA usaria para que el handler global lo mapee igual y el
	 * cliente vea un solo comportamiento; la diferencia es que aca se detecta antes de escribir,
	 * con un mensaje que nombra la situacion.
	 */
	private static void exigirVersion(Sesion sesion, long expectedVersion) {
		if (sesion.getVersion() != expectedVersion) {
			throw new OptimisticLockingFailureException(
					"La sesion " + sesion.getId() + " cambio desde que se leyo: version "
							+ expectedVersion + " contra " + sesion.getVersion());
		}
	}

	/**
	 * Lo que se sabe para programar la proxima sesion (TURNO - Tecnico §76). Sin contenido clinico, asi
	 * que no se audita como lectura: es lo que el profesional ve en su agenda.
	 */
	@Transactional(readOnly = true)
	public ContextoDeProximaSesionView contextoDeProximaSesion(
			OperatingActor actor, long consultorioId, long sesionId) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirRegistro(actor, organizationId, consultorioId);
		Sesion sesion = sesiones.findByIdInScope(organizationId, consultorioId, sesionId)
				.filter(Sesion::estaViva)
				.orElseThrow(() -> new SesionNotAccessibleException(sesionId));
		long personaId = historias.findPorId(organizationId, sesion.getHistoriaClinicaId())
				.orElseThrow(() -> new IllegalStateException(
						"La sesion " + sesionId + " cuelga de una historia que no existe"))
				.personaId();
		Long planId = sesion.getTurnoId() == null ? null
				: turnos.find(organizationId, consultorioId, sesion.getTurnoId())
						.map(TurnoSnapshot::planId)
						.orElse(null);
		return new ContextoDeProximaSesionView(personaId, sesion.getCasoId(), planId,
				sesion.getOfertaId(), sesion.getProfesionalMembershipId());
	}

	/**
	 * Lectura de una sesion. Exige {@code sesion:register} igual que la escritura.
	 *
	 * <h2>Se audita, y por eso no es {@code readOnly}</h2>
	 *
	 * <p>AKINE-04.01 fijo que <b>toda lectura clinica se audita</b> y no solo las mutaciones. Esta
	 * operacion devuelve la evolucion entera de una atencion. 07.07 lo habia cerrado
	 * ({@code d6ea5d0}) y la integracion del 29/09 lo perdio al resolver el merge: las tres
	 * constantes quedaron declaradas en {@link AuditEvents} sin un solo uso. G-5 lo restituye.
	 *
	 * <p>Con {@code readOnly} el flush de Hibernate queda en MANUAL y la fila de auditoria no
	 * llegaria nunca a la base: es el mismo motivo por el que las lecturas de {@code clinical}
	 * tampoco lo son. {@code SesionAuditadaIT} lo verifica contra MySQL.
	 */
	@Transactional
	public SesionView ver(OperatingActor actor, long consultorioId, long sesionId) {
		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirRegistro(actor, organizationId, consultorioId);

		Sesion sesion = sesiones.findByIdInScope(organizationId, consultorioId, sesionId)
				.filter(Sesion::estaViva)
				.orElseThrow(() -> new SesionNotAccessibleException(sesionId));

		// Despues de resolver: auditar un id que no existe o que es de otro tenant construiria
		// dentro de audit_event el padron de existencia que el 404 uniforme existe para no entregar.
		auditar(organizationId, consultorioId, actor, AuditEvents.SESION_ACCEDIDA, sesion.getId(),
				null, null,
				Map.of("historiaClinicaId", String.valueOf(sesion.getHistoriaClinicaId()),
						"casoId", String.valueOf(sesion.getCasoId()),
						"cerrada", String.valueOf(sesion.estaCerrada())),
				Instant.now());

		return conPrevia(sesion, organizationId);
	}

	/**
	 * Una fila de auditoria de inicio, lectura o cierre.
	 *
	 * <p><b>El {@code reason} va nulo</b>, y es una carencia declarada: {@code encounter} no pide
	 * la justificacion de acceso que {@code clinical} exige por {@code X-Justificacion-Acceso}.
	 * Sumarla es un header obligatorio nuevo en operaciones que el frontend ya consume (decision
	 * pendiente de G-5). Los detalles llevan ids y banderas, nunca contenido clinico, y el prefijo
	 * {@code SESION_} esta en el vocabulario clinico: se redactan para quien no tiene
	 * {@code auditoria:read-clinica}.
	 */
	private void auditar(
			long organizationId, long consultorioId, OperatingActor actor, String eventType,
			Long sesionId, String estadoAnterior, String estadoNuevo,
			Map<String, String> detalles, Instant ahora) {

		auditTrail.record(new AuditEntry(
				organizationId,
				consultorioId,
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_SESION,
				sesionId,
				estadoAnterior,
				estadoNuevo,
				detalles,
				null,
				AuditEvents.correlationId(),
				ahora));
	}

	// =================================================================================
	// Precondiciones
	// =================================================================================

	/**
	 * Quien atiende: el profesional del turno, y si el turno no tiene uno, quien inicia.
	 *
	 * <p>Una oferta sin profesional requerido produce turnos sin profesional asignado —lo permite
	 * M27— pero una ATENCION siempre la da alguien. Cuando el turno lo trae, se exige que coincida
	 * con quien inicia: dejar que otro abra la sesion de un turno ajeno rompe la propiedad antes de
	 * que la sesion exista, y el control de {@code Sesion#exigirPropiedadDe} ya no podria salvarla.
	 */
	private long resolverProfesional(
			OperatingActor actor, long organizationId, long consultorioId, TurnoSnapshot turno) {

		long propio = membershipDe(actor, organizationId, consultorioId);
		if (turno.profesionalMembershipId() == null) {
			return propio;
		}
		if (turno.profesionalMembershipId() != propio) {
			throw new TurnoNoAtendibleException(turno.id(),
					"lo atiende otro profesional");
		}
		return propio;
	}

	/**
	 * La membership del actor en esta sede.
	 *
	 * <p>Es lo que identifica al profesional, y no la cuenta: la misma persona puede ser
	 * profesional en un centro y administrativa en otro. Es la misma decision que V23 tomo para la
	 * disponibilidad y V28 para las habilitaciones.
	 */
	/**
	 * El caso tiene que existir, ser de esta historia y estar activo (04.03).
	 *
	 * <p>Se resuelve por {@code clinical.spi.CasoDirectory} y no leyendo {@code caso_clinico}: esa
	 * tabla es de {@code clinical} y la regla 1 de AGENT.md seccion 4 no admite que otro modulo la
	 * toque. El spi responde existencia, pertenencia y vigencia, y <b>no contenido clinico</b>.
	 *
	 * <p>El rechazo lo produce este modulo, no aquel: el spi no autoriza nada. Es la misma division
	 * que {@code HistoriaClinicaDirectory} ya tenia.
	 */
	private void exigirCasoAsignable(long organizationId, long historiaClinicaId, Long casoId) {
		if (casoId == null) {
			return;
		}
		var caso = casos.find(organizationId, casoId)
				.orElseThrow(() -> new CasoNoAsignableException(
						casoId, CasoNoAsignableException.Motivo.NO_ACCESIBLE));

		if (!caso.perteneceAHistoria(historiaClinicaId)) {
			throw new CasoNoAsignableException(
					casoId, CasoNoAsignableException.Motivo.DE_OTRA_HISTORIA);
		}
		if (!caso.activo()) {
			throw new CasoNoAsignableException(casoId, CasoNoAsignableException.Motivo.CERRADO);
		}
	}

	private long membershipDe(OperatingActor actor, long organizationId, long consultorioId) {
		return memberships.findByAccount(organizationId, actor.accountId()).stream()
				.filter(ConsultorioMembershipSnapshot::active)
				.filter(membership -> membership.cubreConsultorio(consultorioId))
				.map(ConsultorioMembershipSnapshot::membershipId)
				.findFirst()
				.orElseThrow(() -> new AccessDeniedException(
						"La cuenta no tiene un vinculo activo con la sede " + consultorioId));
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	/** Falta de contexto es <b>403 y nunca 401</b>: un 401 deja al frontend en bucle de login. */
	private static long exigirContexto(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("La atencion requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	private void exigirSedeDelTenant(long organizationId, long consultorioId) {
		consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	private void exigirRegistro(OperatingActor actor, long organizationId, long consultorioId) {
		exigirPermiso(actor, PermissionCodes.SESION_REGISTER, organizationId, consultorioId);
	}

	private void exigirPermiso(
			OperatingActor actor, String permiso, long organizationId, long consultorioId) {
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				permiso,
				organizationId,
				consultorioId,
				null,
				Instant.now()));
	}
}
