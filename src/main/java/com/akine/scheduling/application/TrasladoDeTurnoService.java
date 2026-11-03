package com.akine.scheduling.application;

import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionDecision;
import com.akine.person.spi.CoberturasAplicablesDirectory;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.transaccion.FilaLockPrevia;
import com.akine.scheduling.domain.EstadoTurno;
import com.akine.scheduling.domain.OrigenDeCancelacion;
import com.akine.scheduling.domain.OrigenDeTurno;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.TipoEventoTurno;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.TurnoEvento;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.OfertaNoAgendableException;
import com.akine.scheduling.domain.exception.OfertaNotAccessibleException;
import com.akine.scheduling.domain.exception.PersonaNotAccessibleException;
import com.akine.scheduling.domain.exception.PersonaSinPerfilPacienteException;
import com.akine.scheduling.domain.exception.SlotNoDisponibleException;
import com.akine.scheduling.domain.exception.TransicionDeTurnoNoPermitidaException;
import com.akine.scheduling.domain.exception.TurnoConAtencionException;
import com.akine.scheduling.domain.exception.TurnoNotAccessibleException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.AgendaSedeRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoEventoRepositoryPort;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import com.akine.scheduling.spi.AtencionProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Cambio de sede de un turno (checklist de agenda §7.4, decision D1).
 *
 * <h2>Por que no es una reprogramacion</h2>
 *
 * <p>Reprogramar <b>mueve el mismo turno</b> (decision 05.03): la Sesion y la obligacion cuelgan de
 * {@code turno_id}. Pero {@code consultorio_id} no es actualizable —el turno, su agenda, su lock y
 * sus ofertas son de una sede— asi que el unico caso que no se puede mover es la sede. Ese caso
 * cancela el original (origen {@code INSTITUCION}, con el motivo) y reserva uno nuevo en la sede de
 * destino, con origen {@code TURNO_ANTERIOR} y {@code turno_origen_id} apuntando al original. No hay
 * estados nuevos (DP-05/DP-16): el original queda {@code CANCELADO} y el nuevo {@code RESERVADO}.
 *
 * <h2>Atomico, y con los dos locks en orden fijo</h2>
 *
 * <p>Las dos cosas pasan en una sola transaccion {@code READ_COMMITTED}: si la sede de destino no tiene
 * lugar, el original no se cancela. Se toman los locks de {@code agenda_sede} de las dos sedes <b>por id
 * ascendente</b>: dos traslados cruzados (A&rarr;B y B&rarr;A) que los tomaran en el orden de la ruta se
 * esperarian mutuamente, que es un deadlock y no una espera. Las dos filas-lock se aseguran antes, cada
 * una en su transaccion (FilaLockPrevia).
 *
 * <p>La sede de origen se bloquea <b>sin</b> exigir que este activa: sacar a los pacientes de una sede
 * que se esta cerrando es justamente uno de los usos. La de destino si tiene que estar activa.
 *
 * <h2>Alcance propio y la persona de destino</h2>
 *
 * <p>{@code turno:manage} se exige en las dos sedes. Si alguna decision viene recortada a la actividad
 * propia (el {@code PROFESIONAL}), el turno de origen tiene que ser suyo y el {@code profesionalId} de
 * destino tambien —su membership en la otra sede, comparada por cuenta—; si no, 403. Despues de los
 * locks de sede se toma el de la persona de destino ({@code agenda_profesional}, V129), en el mismo
 * orden que el resto del modulo, y el revalidador mira el solapamiento contra TODAS las memberships de
 * esa cuenta: la misma persona no queda con dos turnos a la vez en dos sedes.
 *
 * <h2>Lo que se rechaza</h2>
 *
 * <p>Un original con Sesion (409 {@code turno-con-atencion}) o con cualquier recepcion registrada (409
 * {@code turno-transicion-no-permitida}): la llegada consta en esta sede y no se la lleva otra. El
 * destino se revalida entero —oferta vigente, profesional habilitado y en su franja, cupo, solapamiento
 * y box— con {@link RevalidadorDeSlot}, igual que una reserva.
 */
@Service
public class TrasladoDeTurnoService {

	private static final Logger log = LoggerFactory.getLogger(TrasladoDeTurnoService.class);

	/** Ancho de {@code turno.motivo_cancelacion} y de {@code turno_evento.motivo}. */
	private static final int LARGO_DE_MOTIVO = 300;

	private final TurnoRepositoryPort turnos;
	private final TurnoEventoRepositoryPort eventos;
	private final AgendaSedeRepositoryPort agendas;
	private final OfertaDirectory ofertas;
	private final ConsultorioDirectory consultorios;
	private final PacienteDirectory pacientes;
	private final CoberturasAplicablesDirectory coberturas;
	private final SoporteEnAgenda soporte;
	private final AgendaSedeIniciador iniciador;
	private final RevalidadorDeSlot revalidador;
	private final AtencionProbe atenciones;
	private final RegistroDeRecepcion recepciones;
	private final ContextoClinicoDeTurno contextoClinico;
	private final AvisosDeTurno avisos;
	private final AuditTrail auditTrail;
	private final CicloDeTurnoService ciclo;
	private final TransactionTemplate transaccion;

	public TrasladoDeTurnoService(
			TurnoRepositoryPort turnos,
			TurnoEventoRepositoryPort eventos,
			AgendaSedeRepositoryPort agendas,
			OfertaDirectory ofertas,
			ConsultorioDirectory consultorios,
			PacienteDirectory pacientes,
			CoberturasAplicablesDirectory coberturas,
			SoporteEnAgenda soporte,
			AgendaSedeIniciador iniciador,
			RevalidadorDeSlot revalidador,
			AtencionProbe atenciones,
			RegistroDeRecepcion recepciones,
			ContextoClinicoDeTurno contextoClinico,
			AvisosDeTurno avisos,
			AuditTrail auditTrail,
			CicloDeTurnoService ciclo,
			PlatformTransactionManager transactionManager) {

		this.turnos = turnos;
		this.eventos = eventos;
		this.agendas = agendas;
		this.ofertas = ofertas;
		this.consultorios = consultorios;
		this.pacientes = pacientes;
		this.coberturas = coberturas;
		this.soporte = soporte;
		this.iniciador = iniciador;
		this.revalidador = revalidador;
		this.atenciones = atenciones;
		this.recepciones = recepciones;
		this.contextoClinico = contextoClinico;
		this.avisos = avisos;
		this.auditTrail = auditTrail;
		this.ciclo = ciclo;
		this.transaccion = FilaLockPrevia.transaccionDeNegocio(transactionManager, Isolation.READ_COMMITTED);
	}

	/**
	 * Cancela el turno en su sede y reserva uno nuevo en otra, en una sola transaccion.
	 *
	 * @throws ConsultorioNoAccesibleException       alguna de las dos sedes no es del tenant (404)
	 * @throws TurnoNotAccessibleException           el turno no existe en la sede de origen (404)
	 * @throws OptimisticLockingFailureException     la version quedo vieja (409)
	 * @throws TurnoConAtencionException             el original tiene Sesion (409)
	 * @throws TransicionDeTurnoNoPermitidaException tiene recepcion, ya cerro su ciclo o ya empezo (409)
	 * @throws IllegalArgumentException              la sede de destino es la misma (400)
	 */
	public TrasladoView trasladar(
			OperatingActor actor, long consultorioId, long turnoId, TrasladoCommand command) {

		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("El cambio de sede de un turno requiere un contexto de trabajo activo");
		}
		long organizationId = actor.contextOrganizationId();
		if (command.consultorioDestinoId() == consultorioId) {
			throw new IllegalArgumentException(
					"La sede de destino es la misma: para cambiar el horario dentro de la sede se reprograma");
		}
		ConsultorioSnapshot origen = exigirSede(organizationId, consultorioId);
		ConsultorioSnapshot destino = exigirSede(organizationId, command.consultorioDestinoId());
		// Se cancela en una sede y se reserva en otra: hace falta poder hacer las dos cosas.
		PermissionDecision enOrigen = soporte.exigir(
				actor, PermissionCodes.TURNO_MANAGE, organizationId, consultorioId);
		PermissionDecision enDestino = soporte.exigir(
				actor, PermissionCodes.TURNO_MANAGE, organizationId, destino.id());
		// Alcance propio (agenda de profesionales): el PROFESIONAL solo se lleva SU turno y solo a SU
		// agenda en la otra sede. El destino se mira aca; el origen, bajo el lock, con el turno leido.
		soporte.exigirPropio(enDestino, actor, organizationId, command.profesionalId());

		long primera = Math.min(origen.id(), destino.id());
		long segunda = Math.max(origen.id(), destino.id());
		iniciador.asegurar(organizationId, primera);
		iniciador.asegurar(organizationId, segunda);
		// Y la fila de la persona que va a atender en destino (V129), que se bloquea despues de las sedes.
		iniciador.asegurarProfesionales(organizationId, Arrays.asList(command.profesionalId()));

		TrasladoView resultado = transaccion.execute(estado -> {
			for (long sede : new long[] {primera, segunda}) {
				if (sede == destino.id()) {
					BloqueoDeAgenda.tomarParaOcupar(agendas, consultorios, organizationId, sede);
				} else {
					BloqueoDeAgenda.tomar(agendas, organizationId, sede);
				}
			}
			// Orden fijo de todo el modulo: sedes por id ascendente, despues personas por cuenta. El
			// revalidador vuelve a tomar la misma fila (ya es nuestra) y mira el cruce con TODAS las
			// memberships de esa persona: el profesional de destino no queda con dos turnos a la vez en dos
			// sedes.
			if (command.profesionalId() != null) {
				revalidador.bloquearProfesionales(organizationId, List.of(command.profesionalId()));
			}
			return trasladarBajoLock(actor, enOrigen, organizationId, origen, destino, turnoId, command);
		});
		log.info("Turno trasladado de sede: turnoId={} de={} a={} turnoNuevoId={}",
				turnoId, consultorioId, destino.id(), resultado.nuevo().id());
		return resultado;
	}

	private TrasladoView trasladarBajoLock(
			OperatingActor actor, PermissionDecision enOrigen, long organizationId,
			ConsultorioSnapshot origen, ConsultorioSnapshot destino,
			long turnoId, TrasladoCommand command) {

		Turno original = turnos.findByIdInScope(organizationId, origen.id(), turnoId)
				.orElseThrow(() -> new TurnoNotAccessibleException(turnoId));
		soporte.exigirPropio(enOrigen, actor, organizationId, original.getProfesionalMembershipId());
		if (original.getVersion() != command.expectedVersion()) {
			throw new OptimisticLockingFailureException(
					"El turno " + turnoId + " cambio desde que se leyo: version "
							+ command.expectedVersion() + " contra " + original.getVersion());
		}
		if (atenciones.tieneAtencion(organizationId, origen.id(), turnoId)) {
			throw new TurnoConAtencionException(turnoId);
		}
		if (recepciones.vigente(organizationId, turnoId).isPresent()) {
			throw new TransicionDeTurnoNoPermitidaException(turnoId,
					"tiene una recepcion registrada en esta sede: la llegada no se traslada");
		}

		OfertaSnapshot oferta = ofertas.find(organizationId, destino.id(), command.ofertaDestinoId())
				.orElseThrow(() -> new OfertaNotAccessibleException(command.ofertaDestinoId()));
		Instant inicio = command.inicio();
		Instant ahora = Instant.now();
		if (!inicio.isAfter(ahora)) {
			throw new SlotNoDisponibleException("el horario ya paso: un turno se reserva hacia adelante");
		}
		LocalDate fecha = inicio.atZone(ZoneId.of(destino.timezone())).toLocalDate();
		if (!oferta.vigenteEl(fecha)) {
			throw new OfertaNoAgendableException(oferta.id(), oferta.active()
					? "no esta vigente el " + fecha
					: "esta dada de baja");
		}
		exigirPacienteVigente(organizationId, original.getPersonaId());
		ContextoClinicoDeTurno.Resuelto contexto = original.getCasoId() == null
				? ContextoClinicoDeTurno.Resuelto.NINGUNO
				: contextoClinico.resolver(organizationId, original.getPersonaId(), oferta.id(),
						original.getCasoId(), original.getPlanId());

		// 1. Cancelar el original, con el mismo codigo que cualquier cancelacion: evento, recepcion,
		// auditoria y aviso. Primero, para que el profesional o el box que ocupaba no cuenten si el
		// destino comparte alguno; si el destino no tiene lugar, el rollback deshace todo.
		EstadoTurno estadoOriginal = original.getEstado();
		Turno cancelado = ciclo.aplicarCancelacion(actor, origen, original,
				recortar("Cambio de sede a " + destino.name() + ": " + command.motivo()),
				OrigenDeCancelacion.INSTITUCION, ahora);

		// 2. Reservar en el destino, con la revalidacion entera.
		Instant fin = inicio.plusSeconds(oferta.duracionCon(
				ofertas.duracionesPropias(organizationId, destino.id(), oferta.id()), command.profesionalId()) * 60L);
		RevalidadorDeSlot.Asignacion asignacion = revalidador.revalidar(new RevalidadorDeSlot.Pedido(
				organizationId, destino.id(), destino, oferta, inicio, fin,
				command.profesionalId(), null, command.espacioId(), false));

		Turno nuevo = new Turno(organizationId, destino.id(), oferta.id(), original.getPersonaId(),
				asignacion.profesionalId(), asignacion.espacioId(), inicio, fin,
				actor.accountId(), ahora, null, null);
		nuevo.declararOrigen(OrigenDeTurno.TURNO_ANTERIOR);
		nuevo.trasladadoDesde(original.getId());
		// La cobertura elegida es de la persona, no de la sede: viaja si sigue vigente ese dia.
		if (original.getCoberturaId() != null && coberturas.vigenteDeLaPersona(
				organizationId, original.getPersonaId(), original.getCoberturaId(), fecha)) {
			nuevo.elegirCobertura(original.getCoberturaId());
		}
		if (contexto.casoId() != null) {
			nuevo.asociarContexto(contexto.casoId(), contexto.planId());
		}
		nuevo.anotarObservaciones(original.getObservaciones());
		Turno reservado = turnos.save(nuevo);

		eventos.registrar(TurnoEvento.de(reservado, TipoEventoTurno.RESERVA, null,
				recortar("Cambio de sede desde el turno " + original.getId() + " (" + origen.name() + "): "
						+ command.motivo()),
				actor.accountId(), ahora));

		Map<String, String> detallesNuevo = DetallesDeAuditoriaDeTurno.de(actor, reservado);
		auditTrail.record(new AuditEntry(organizationId, destino.id(), actor.accountId(), "TURNO_RESERVADO",
				"Turno", reservado.getId(), null, reservado.getEstado().name(), detallesNuevo,
				command.motivo(), null, ahora));
		Map<String, String> detallesOriginal = DetallesDeAuditoriaDeTurno.de(actor, cancelado);
		detallesOriginal.put("consultorioDestinoId", String.valueOf(destino.id()));
		detallesOriginal.put("turnoNuevoId", String.valueOf(reservado.getId()));
		auditTrail.record(new AuditEntry(organizationId, origen.id(), actor.accountId(), "TURNO_TRASLADADO",
				"Turno", cancelado.getId(), estadoOriginal.name(), cancelado.getEstado().name(), detallesOriginal,
				command.motivo(), null, ahora));

		avisos.avisarReserva(reservado, destino, oferta.nombreComercial());
		return new TrasladoView(TurnoView.de(cancelado), TurnoView.de(reservado));
	}

	private void exigirPacienteVigente(long organizationId, long personaId) {
		PacienteSnapshot paciente = pacientes.find(organizationId, personaId)
				.orElseThrow(() -> new PersonaNotAccessibleException(personaId));
		if (!paciente.activa() || !paciente.esPacienteVigente()) {
			throw new PersonaSinPerfilPacienteException(personaId);
		}
	}

	private ConsultorioSnapshot exigirSede(long organizationId, long consultorioId) {
		return consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	private static String recortar(String texto) {
		return texto.length() <= LARGO_DE_MOTIVO ? texto : texto.substring(0, LARGO_DE_MOTIVO);
	}
}
