package com.akine.encounter.application;

import com.akine.clinical.spi.HistoriaClinicaDirectory;
import com.akine.clinical.spi.HistoriaClinicaSnapshot;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.encounter.domain.EvaluacionBase;
import com.akine.encounter.domain.PermissionCodes;
import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.exception.ConsultorioNoAccesibleException;
import com.akine.encounter.domain.exception.SesionNotAccessibleException;
import com.akine.encounter.domain.exception.TurnoNoAtendibleException;
import com.akine.encounter.domain.port.SesionNumeradorPort;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.scheduling.spi.TurnoDirectory;
import com.akine.scheduling.spi.TurnoSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

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
	private final TurnoDirectory turnos;
	private final HistoriaClinicaDirectory historias;
	private final ConsultorioDirectory consultorios;
	private final ConsultorioMembershipDirectory memberships;
	private final PermissionGuard permissionGuard;
	private final SesionNumeradorPort numerador;
	private final NumeradorIniciador numeradorIniciador;

	public SesionService(
			SesionRepositoryPort sesiones,
			TurnoDirectory turnos,
			HistoriaClinicaDirectory historias,
			ConsultorioDirectory consultorios,
			ConsultorioMembershipDirectory memberships,
			PermissionGuard permissionGuard,
			SesionNumeradorPort numerador,
			NumeradorIniciador numeradorIniciador) {

		this.sesiones = sesiones;
		this.turnos = turnos;
		this.historias = historias;
		this.consultorios = consultorios;
		this.memberships = memberships;
		this.permissionGuard = permissionGuard;
		this.numerador = numerador;
		this.numeradorIniciador = numeradorIniciador;
	}

	/**
	 * Abre la atencion de un turno, o devuelve la que ya estaba abierta.
	 *
	 * @throws ConsultorioNoAccesibleException si la sede no existe o es de otro tenant (404)
	 * @throws TurnoNoAtendibleException       si el turno esta de baja o no es de quien atiende (409)
	 * @throws AccessDeniedException           sin {@code sesion:register} o sin contexto (403)
	 */
	@Transactional
	public SesionView iniciar(OperatingActor actor, long consultorioId, long turnoId) {
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

		// La HC se crea si no existia. `asegurar` es de 04.01 y exige perfil de paciente vigente:
		// una atencion sobre alguien que solo es "persona" tiene que fallar aca y no crear una
		// historia clinica a nombre de quien no es paciente.
		HistoriaClinicaSnapshot historia =
				historias.asegurar(organizationId, turno.personaId(), actor.accountId());

		Sesion sesion = sesiones.save(new Sesion(
				organizationId,
				consultorioId,
				historia.id(),
				turnoId,
				turno.ofertaId(),
				profesionalMembershipId,
				Instant.now(),
				actor.accountId()));

		log.info("Sesion iniciada: sesionId={} turnoId={} historiaClinicaId={} profesional={}",
				sesion.getId(), turnoId, historia.id(), profesionalMembershipId);

		return conPrevia(sesion, organizationId);
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

		sesion.guardarBorrador(contenido, Instant.now());
		return conPrevia(sesiones.save(sesion), organizationId);
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
		return conPrevia(sesiones.save(sesion), organizationId);
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
	 *   4. asegurar el numerador  &lt;- en su PROPIA transaccion
	 *   5. incrementar y leer     &lt;- toma el lock de fila y serializa
	 *   6. cerrar
	 * </pre>
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
	 * <p>DP-06 y la regla de la etapa: "cierre clinico != cobro". Este metodo no crea ninguna
	 * obligacion economica; la deriva despues AKINE-07.01 leyendo las sesiones cerradas. Atarlas
	 * haria que un problema de facturacion bloquee una historia clinica.
	 */
	@Transactional
	public SesionView cerrar(
			OperatingActor actor, long consultorioId, long sesionId,
			CierreDeSesion cierre, long expectedVersion) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirRegistro(actor, organizationId, consultorioId);

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

		// PASO 4 y 5. La fila se asegura afuera; el incremento toma el lock y serializa.
		numeradorIniciador.asegurar(organizationId, sesion.getHistoriaClinicaId());
		numerador.incrementar(organizationId, sesion.getHistoriaClinicaId());
		int numero = numerador.leerUltimo(organizationId, sesion.getHistoriaClinicaId());

		sesion.cerrar(cierre, numero, Instant.now(), actor.accountId());

		log.info("Sesion cerrada: sesionId={} numero={} historiaClinicaId={} asistencia={}",
				sesionId, numero, sesion.getHistoriaClinicaId(), cierre.asistencia());

		return conPrevia(sesiones.save(sesion), organizationId);
	}

	/**
	 * Adjunta la evaluacion de la sesion ANTERIOR del mismo paciente.
	 *
	 * <p>Es la mitad "cambio" del requisito de la etapa, y viaja con la sesion en vez de en un
	 * endpoint aparte porque la pantalla la necesita en el mismo momento: mostrar "la vez pasada
	 * tenia 7" al lado del campo de dolor es lo que hace que el profesional cargue una evolucion
	 * real en vez de la que recuerda.
	 */
	private SesionView conPrevia(Sesion sesion, long organizationId) {
		return SesionView.de(sesion, sesiones
				.findPreviaEvaluada(organizationId, sesion.getHistoriaClinicaId(), sesion.getIniciadaEn())
				.map(previa -> new SesionView.EvaluacionPrevia(
						previa.getIniciadaEn(),
						previa.getDolorEva(),
						previa.getEvolucion() == null ? null : previa.getEvolucion().name()))
				.orElse(null));
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

	/** Lectura de una sesion. Exige {@code sesion:register} igual que la escritura. */
	@Transactional(readOnly = true)
	public SesionView ver(OperatingActor actor, long consultorioId, long sesionId) {
		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirRegistro(actor, organizationId, consultorioId);

		return sesiones.findByIdInScope(organizationId, consultorioId, sesionId)
				.filter(Sesion::estaViva)
				.map(sesion -> conPrevia(sesion, organizationId))
				.orElseThrow(() -> new SesionNotAccessibleException(sesionId));
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
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.SESION_REGISTER,
				organizationId,
				consultorioId,
				null,
				Instant.now()));
	}
}
