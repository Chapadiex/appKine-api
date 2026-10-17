package com.akine.activity.application;

import com.akine.activity.domain.AsistenciaActividad;
import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.InscripcionClase;
import com.akine.activity.domain.PermissionCodes;
import com.akine.activity.domain.exception.ClaseNotAccessibleException;
import com.akine.activity.domain.exception.ConsultorioNoAccesibleException;
import com.akine.activity.domain.exception.DerivacionNoAccesibleException;
import com.akine.activity.domain.exception.InscripcionNotAccessibleException;
import com.akine.activity.domain.exception.OfertaNoClinicaException;
import com.akine.activity.domain.exception.OfertaNotAccessibleException;
import com.akine.activity.domain.exception.ParticipacionSinAsistenciaException;
import com.akine.activity.domain.exception.PersonaNoAccesibleException;
import com.akine.activity.domain.exception.PersonaSinPerfilPacienteException;
import com.akine.activity.domain.port.ActivityRepositoryPorts.AsistenciaActividadRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ClaseProgramadaRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.InscripcionClaseRepositoryPort;
import com.akine.clinical.spi.ActorDeDerivacion;
import com.akine.clinical.spi.DerivacionClinicaRegistry;
import com.akine.clinical.spi.DerivacionSnapshot;
import com.akine.clinical.spi.EstadoClinicoDeParticipacion;
import com.akine.clinical.spi.OrigenDeParticipacion;
import com.akine.clinical.spi.ParticipacionDerivable;
import com.akine.clinical.spi.RegistroDeDerivacion;
import com.akine.clinical.spi.ReversionDeDerivacion;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.spi.ActivacionDePerfilPaciente;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.person.spi.PerfilPacienteProvisioning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * El lado GRUPAL de la derivacion de un participante de clase al circuito clinico (RF-M28-008,
 * AKINE-08.04).
 *
 * <h2>El reparto, que es toda la etapa</h2>
 *
 * <p>Este servicio valida lo que {@code activity} sabe —la clase, la inscripcion y la asistencia
 * son de la sede del contexto, la oferta genera registro clinico, la persona <b>estuvo</b>— y lo
 * <b>afirma</b> en {@link ParticipacionDerivable}. {@code clinical}, por
 * {@link DerivacionClinicaRegistry}, valida y autoriza lo clinico —{@code hc:write}, relacion
 * asistencial o justificacion, historia, Caso, Plan, autorizacion—, escribe la fila y la audita en
 * la misma transaccion. Ninguna decision se toma dos veces, y {@code activity} no guarda ni lee un
 * solo dato clinico propio.
 *
 * <h2>El permiso de este lado: {@code inscripcion:read}</h2>
 *
 * <p>La matriz §32 no tiene fila "Derivar participante". La fila mas cercana es <b>Registrar
 * Sesion</b> —la atencion que la derivacion habilita—, y su permiso real, {@code hc:write}, lo
 * evalua {@code clinical}. De este lado solo hace falta poder <b>ver al participante</b>, que es
 * {@code inscripcion:read}: es exactamente lo que el diseño (§3.1) le asigna a {@code activity}.
 * Pedir aca un permiso de escritura grupal (asistencia, inscripcion) le cerraria la derivacion a un
 * profesional que atiende pero no toma lista, sin agregar ninguna proteccion clinica.
 *
 * <h2>Lo que NO hace</h2>
 *
 * <p>No crea Sesiones, no abre Casos, no consume autorizaciones y no devenga: derivar no es
 * atender (08.05). Y no convierte a nadie en paciente de costado: hace falta la bandera explicita,
 * y la activacion la hace {@code person} con su propio {@code paciente:manage}.
 */
@Service
public class DerivacionService {

	private static final Logger log = LoggerFactory.getLogger(DerivacionService.class);

	static final String BLOQUEO_OFERTA_NO_CLINICA = "OFERTA_NO_CLINICA";
	static final String BLOQUEO_SIN_ASISTENCIA = "SIN_ASISTENCIA";
	static final String BLOQUEO_AUSENTE = "AUSENTE";

	private final ClaseProgramadaRepositoryPort clases;
	private final InscripcionClaseRepositoryPort inscripciones;
	private final AsistenciaActividadRepositoryPort asistencias;
	private final OfertaDirectory ofertas;
	private final PacienteDirectory personas;
	private final PerfilPacienteProvisioning perfiles;
	private final ConsultorioDirectory consultorios;
	private final PermissionGuard permissionGuard;
	private final DerivacionClinicaRegistry registro;

	public DerivacionService(
			ClaseProgramadaRepositoryPort clases,
			InscripcionClaseRepositoryPort inscripciones,
			AsistenciaActividadRepositoryPort asistencias,
			OfertaDirectory ofertas,
			PacienteDirectory personas,
			PerfilPacienteProvisioning perfiles,
			ConsultorioDirectory consultorios,
			PermissionGuard permissionGuard,
			DerivacionClinicaRegistry registro) {

		this.clases = clases;
		this.inscripciones = inscripciones;
		this.asistencias = asistencias;
		this.ofertas = ofertas;
		this.personas = personas;
		this.perfiles = perfiles;
		this.consultorios = consultorios;
		this.permissionGuard = permissionGuard;
		this.registro = registro;
	}

	/**
	 * El veredicto grupal, sin efectos y sin lectura clinica. Ver {@link ElegibilidadClinicaView}.
	 */
	@Transactional(readOnly = true)
	public ElegibilidadClinicaView elegibilidad(
			OperatingActor actor, long consultorioId, long claseId, long inscripcionId) {

		Participacion p = resolver(actor, consultorioId, claseId, inscripcionId);
		OfertaSnapshot oferta = exigirOferta(p);
		Optional<AsistenciaActividad> asistencia =
				asistencias.findDeInscripcion(p.organizationId(), inscripcionId);
		PacienteSnapshot persona = exigirPersona(p);

		List<String> motivos = new ArrayList<>();
		if (!oferta.generaRegistroClinico()) {
			motivos.add(BLOQUEO_OFERTA_NO_CLINICA);
		}
		if (asistencia.isEmpty()) {
			motivos.add(BLOQUEO_SIN_ASISTENCIA);
		} else if (!asistencia.get().getResultado().estuvo()) {
			motivos.add(BLOQUEO_AUSENTE);
		}

		return new ElegibilidadClinicaView(
				inscripcionId,
				persona.personaId(),
				asistencia.map(AsistenciaActividad::getId).orElse(null),
				oferta.generaRegistroClinico(),
				oferta.requiereCasoClinico(),
				asistencia.map(a -> a.getResultado().name()).orElse(null),
				persona.esPacienteVigente(),
				!persona.esPacienteVigente(),
				motivos.isEmpty(),
				List.copyOf(motivos));
	}

	/**
	 * Deriva al participante al Caso elegido. Idempotente por destino: el doble submit devuelve la
	 * derivacion existente con {@code yaExistia = true}.
	 *
	 * <p><b>El orden de los gates esta fijado</b> (challenge 08.04, pregunta 4.7): primero la oferta
	 * —una clase no clinica no deja rastro clinico—, despues la asistencia, despues la persona, y
	 * recien al final {@code clinical}. Todo en una transaccion: si {@code clinical} rechaza, la
	 * activacion del perfil que se haya hecho en este pedido se revierte con ella.
	 *
	 * <p><b>Dos derivaciones simultaneas del mismo participante se serializan</b> sobre la fila de
	 * su asistencia, que se lee con {@code FOR UPDATE}, y la transaccion corre en
	 * {@code READ_COMMITTED}: la segunda espera a la primera y su pre-chequeo de idempotencia ya ve
	 * la fila que la primera commiteo, asi que devuelve la existente con {@code yaExistia = true}.
	 * Bajo {@code REPEATABLE READ} el lock no alcanza —la foto se fijo en la primera lectura, antes
	 * del lock— y sin el lock las dos llegaban al INSERT: el perdedor chocaba contra el unique y
	 * recibia un 500 ({@code DerivacionConcurrenteIT}, 15/10/2026). Es la regla de 05.02.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public DerivacionView derivar(
			OperatingActor actor,
			long consultorioId,
			long claseId,
			long inscripcionId,
			DerivarParticipanteCommand comando) {

		Participacion p = resolver(actor, consultorioId, claseId, inscripcionId);

		OfertaSnapshot oferta = exigirOferta(p);
		if (!oferta.generaRegistroClinico()) {
			throw new OfertaNoClinicaException(oferta.id());
		}

		AsistenciaActividad asistencia = asistencias
				.findDeInscripcionParaDerivar(p.organizationId(), inscripcionId)
				.orElseThrow(() -> new ParticipacionSinAsistenciaException(inscripcionId, null));
		if (!asistencia.getResultado().estuvo()) {
			throw new ParticipacionSinAsistenciaException(
					inscripcionId, asistencia.getResultado().name());
		}

		PacienteSnapshot persona = exigirPersona(p);
		if (!persona.esPacienteVigente()) {
			if (!comando.activarPerfilPaciente()) {
				throw new PersonaSinPerfilPacienteException(persona.personaId());
			}
			// La UNICA clase que inserta en perfil_paciente sigue siendo PerfilPacienteService:
			// el puerto delega en ella, con su paciente:manage y su auditoria.
			perfiles.asegurarPerfilVigente(new ActivacionDePerfilPaciente(
					actor.accountId(), actor.platformAdmin(), p.organizationId(), consultorioId,
					persona.personaId(), "Derivacion desde la clase " + claseId));
		}

		LocalDate fechaLocal = p.clase().getInicio().atZone(ZoneId.of(p.sede().timezone())).toLocalDate();
		DerivacionSnapshot derivada = registro.registrar(new RegistroDeDerivacion(
				actorDeDerivacion(actor, p, comando.justificacion()),
				new ParticipacionDerivable(
						OrigenDeParticipacion.CLASE_PROGRAMADA,
						asistencia.getId(),
						persona.personaId(),
						consultorioId,
						oferta.id(),
						oferta.requiereCasoClinico(),
						fechaLocal),
				comando.casoClinicoId(),
				comando.planTratamientoId(),
				comando.autorizacionId(),
				comando.motivo()));

		log.info("Participante derivado: claseId={} inscripcionId={} derivacionId={} yaExistia={}",
				claseId, inscripcionId, derivada.id(), derivada.yaExistia());
		return DerivacionView.de(derivada, inscripcionId);
	}

	/**
	 * Las derivaciones de la participacion. <b>Es una lectura clinica</b>: {@code clinical} la
	 * autoriza ({@code hc:read} + relacion o justificacion) y la audita. Por eso no es
	 * {@code readOnly}: el INSERT de auditoria se une a esta transaccion (ver
	 * {@code LecturaAuditadaIT}).
	 *
	 * <p>Sin asistencia no hay participacion derivable y la lista es vacia sin preguntar a
	 * {@code clinical}: ninguna derivacion puede colgar de una asistencia que no existe.
	 */
	@Transactional
	public EstadoDeDerivacionView consultar(
			OperatingActor actor,
			long consultorioId,
			long claseId,
			long inscripcionId,
			String justificacion) {

		Participacion p = resolver(actor, consultorioId, claseId, inscripcionId);
		Optional<AsistenciaActividad> asistencia =
				asistencias.findDeInscripcion(p.organizationId(), inscripcionId);
		if (asistencia.isEmpty()) {
			return new EstadoDeDerivacionView(null, List.of());
		}

		EstadoClinicoDeParticipacion estado = registro.consultar(
				actorDeDerivacion(actor, p, justificacion),
				OrigenDeParticipacion.CLASE_PROGRAMADA,
				asistencia.get().getId(),
				p.inscripcion().getPersonaId());

		return new EstadoDeDerivacionView(
				estado.historiaClinicaId(),
				estado.derivaciones().stream()
						.map(d -> DerivacionView.de(d, inscripcionId))
						.toList());
	}

	/**
	 * Deshace el vinculo, con motivo obligatorio. La fila queda.
	 *
	 * <p>La derivacion tiene que colgar de <b>esta</b> participacion: una de otra clase de la misma
	 * organizacion es 404 aunque exista. Se comprueba despues de revertir porque {@code clinical} no
	 * expone una lectura sin auditar, y la excepcion revierte la transaccion entera.
	 *
	 * <p><b>AKINE-08.05: serializa sobre la fila de la asistencia</b>, como derivar y como abrir la
	 * atencion del participante, y en {@code READ_COMMITTED}. Sin el lock, una reversion se colaba
	 * entre la lectura de la derivacion vigente y el INSERT de la Sesion: las dos commiteaban y la
	 * atencion quedaba colgada de una derivacion revertida. Con el lock, la que llega segunda ve lo
	 * que la primera commiteo: o la Sesion (409 {@code derivacion-con-sesion}) o la reversion (409
	 * {@code participacion-sin-derivacion}).
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public DerivacionView revertir(
			OperatingActor actor,
			long consultorioId,
			long claseId,
			long inscripcionId,
			long derivacionId,
			String motivo,
			String justificacion) {

		Participacion p = resolver(actor, consultorioId, claseId, inscripcionId);
		AsistenciaActividad asistencia = asistencias
				.findDeInscripcionParaDerivar(p.organizationId(), inscripcionId)
				.orElseThrow(() -> new DerivacionNoAccesibleException(derivacionId));

		DerivacionSnapshot revertida = registro.revertir(new ReversionDeDerivacion(
				actorDeDerivacion(actor, p, justificacion), derivacionId, motivo));

		if (revertida.origen() != OrigenDeParticipacion.CLASE_PROGRAMADA
				|| revertida.participacionId() != asistencia.getId()) {
			throw new DerivacionNoAccesibleException(derivacionId);
		}
		return DerivacionView.de(revertida, inscripcionId);
	}

	// =================================================================================

	/** Lo que las cuatro operaciones resuelven igual: contexto, sede, permiso, clase e inscripcion. */
	private Participacion resolver(
			OperatingActor actor, long consultorioId, long claseId, long inscripcionId) {

		if (actor == null || actor.contextOrganizationId() == null) {
			// 403 y nunca 401: un 401 deja al frontend en bucle de login.
			throw new AccessDeniedException(
					"La derivacion de participantes requiere un contexto de trabajo activo");
		}
		long organizationId = actor.contextOrganizationId();
		ConsultorioSnapshot sede = consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), PermissionCodes.INSCRIPCION_READ, organizationId, consultorioId,
				null, Instant.now()));

		ClaseProgramada clase = clases.findByIdInScope(organizationId, consultorioId, claseId)
				.orElseThrow(() -> new ClaseNotAccessibleException(claseId));
		InscripcionClase inscripcion = inscripciones.findByIdInScope(organizationId, claseId, inscripcionId)
				.orElseThrow(() -> new InscripcionNotAccessibleException(inscripcionId));
		return new Participacion(organizationId, sede, clase, inscripcion);
	}

	/** La oferta se resuelve con el id de la CLASE, nunca con uno del cliente (diseño §7). */
	private OfertaSnapshot exigirOferta(Participacion p) {
		long ofertaId = p.clase().getOfertaId();
		return ofertas.find(p.organizationId(), p.sede().id(), ofertaId)
				.orElseThrow(() -> new OfertaNotAccessibleException(ofertaId));
	}

	private PacienteSnapshot exigirPersona(Participacion p) {
		long personaId = p.inscripcion().getPersonaId();
		return personas.find(p.organizationId(), personaId)
				.orElseThrow(() -> new PersonaNoAccesibleException(personaId));
	}

	private static ActorDeDerivacion actorDeDerivacion(
			OperatingActor actor, Participacion p, String justificacion) {

		return new ActorDeDerivacion(
				actor.accountId(), actor.platformAdmin(), p.organizationId(), p.sede().id(),
				justificacion);
	}

	private record Participacion(
			long organizationId,
			ConsultorioSnapshot sede,
			ClaseProgramada clase,
			InscripcionClase inscripcion) {
	}
}
