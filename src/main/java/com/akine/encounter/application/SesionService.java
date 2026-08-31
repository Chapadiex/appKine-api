package com.akine.encounter.application;

import com.akine.clinical.spi.HistoriaClinicaDirectory;
import com.akine.clinical.spi.HistoriaClinicaSnapshot;
import com.akine.encounter.domain.PermissionCodes;
import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.exception.ConsultorioNoAccesibleException;
import com.akine.encounter.domain.exception.SesionNotAccessibleException;
import com.akine.encounter.domain.exception.TurnoNoAtendibleException;
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

	public SesionService(
			SesionRepositoryPort sesiones,
			TurnoDirectory turnos,
			HistoriaClinicaDirectory historias,
			ConsultorioDirectory consultorios,
			ConsultorioMembershipDirectory memberships,
			PermissionGuard permissionGuard) {

		this.sesiones = sesiones;
		this.turnos = turnos;
		this.historias = historias;
		this.consultorios = consultorios;
		this.memberships = memberships;
		this.permissionGuard = permissionGuard;
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
			return SesionView.de(yaAbierta.get());
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

		return SesionView.de(sesion);
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

		if (sesion.getVersion() != expectedVersion) {
			// Se lanza el mismo tipo que JPA usaria, para que el handler global lo mapee igual y
			// el cliente vea un solo comportamiento. La diferencia es que aca se detecta antes de
			// escribir, con un mensaje que nombra la situacion.
			throw new org.springframework.dao.OptimisticLockingFailureException(
					"La sesion " + sesionId + " cambio desde que se leyo: version "
							+ expectedVersion + " contra " + sesion.getVersion());
		}

		sesion.guardarBorrador(contenido, Instant.now());
		return SesionView.de(sesiones.save(sesion));
	}

	/** Lectura de una sesion. Exige {@code sesion:register} igual que la escritura. */
	@Transactional(readOnly = true)
	public SesionView ver(OperatingActor actor, long consultorioId, long sesionId) {
		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirRegistro(actor, organizationId, consultorioId);

		return sesiones.findByIdInScope(organizationId, consultorioId, sesionId)
				.filter(Sesion::estaViva)
				.map(SesionView::de)
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
