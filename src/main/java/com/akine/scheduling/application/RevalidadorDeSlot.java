package com.akine.scheduling.application;

import com.akine.offering.spi.HabilitacionSnapshot;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.resource.spi.DisponibilidadDirectory;
import com.akine.resource.spi.EspacioDirectory;
import com.akine.resource.spi.EspacioSnapshot;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.exception.RecursoOcupadoException;
import com.akine.scheduling.domain.exception.SlotCompletoException;
import com.akine.scheduling.domain.exception.SlotNoDisponibleException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Revalida que un intervalo se pueda ocupar, y elige el espacio. <b>Se ejecuta siempre bajo el lock
 * de {@code agenda_sede}</b>, dentro de la transaccion que escribe.
 *
 * <h2>Por que existe como clase aparte</h2>
 *
 * <p>Reservar (05.02) y reprogramar (05.03) son la misma escritura de agenda: las dos ocupan un
 * intervalo que hasta ese momento estaba libre, y las dos compiten contra cualquier otra reserva de
 * la sede. Si cada una tuviera su copia de estos controles, la segunda copia envejeceria —una regla
 * nueva de habilitacion se aplicaria al reservar y no al mover— y el sintoma seria una agenda con
 * dos turnos encima.
 *
 * <h2>Lo que revalida, y por que no alcanza con lo que mando el cliente</h2>
 *
 * <p>Entre que la pantalla dibujo la agenda y el usuario confirmo pudo cambiar todo: el horario del
 * profesional, un feriado, la vigencia de la oferta o de la habilitacion. El motor de 05.01 no
 * persiste slots, asi que la unica forma de saber que el hueco sigue existiendo es recalcularlo
 * DENTRO de esta transaccion. RN-M12-004.
 */
@Component
class RevalidadorDeSlot {

	private final TurnoRepositoryPort turnos;
	private final OfertaDirectory ofertas;
	private final DisponibilidadDirectory disponibilidad;
	private final EspacioDirectory espacios;

	RevalidadorDeSlot(
			TurnoRepositoryPort turnos,
			OfertaDirectory ofertas,
			DisponibilidadDirectory disponibilidad,
			EspacioDirectory espacios) {

		this.turnos = turnos;
		this.ofertas = ofertas;
		this.disponibilidad = disponibilidad;
		this.espacios = espacios;
	}

	/**
	 * Lo que hay que saber para decidir si un intervalo se puede ocupar.
	 *
	 * @param turnoExcluidoId turno que NO cuenta como ocupacion. Es el que se esta moviendo: sigue
	 *                        vivo en su intervalo viejo mientras se lo revalida, y sin excluirlo un
	 *                        turno que se corre media hora chocaria contra si mismo
	 */
	record Pedido(
			long organizationId,
			long consultorioId,
			ConsultorioSnapshot sede,
			OfertaSnapshot oferta,
			Instant inicio,
			Instant fin,
			Long profesionalId,
			Long turnoExcluidoId) {
	}

	/** Los recursos que quedaron clavados para ese intervalo. */
	record Asignacion(Long profesionalId, Long espacioId) {
	}

	/**
	 * Revalida el pedido y devuelve los recursos asignados.
	 *
	 * @throws SlotNoDisponibleException el hueco dejo de existir
	 * @throws SlotCompletoException     no queda cupo
	 * @throws RecursoOcupadoException   el profesional o todos los espacios estan tomados
	 */
	Asignacion revalidar(Pedido pedido) {
		Long profesionalId = resolverProfesional(pedido);

		// Cupo primero: es una sola consulta y descarta el caso mas frecuente —el slot grupal
		// lleno— sin recorrer habilitaciones ni espacios.
		long ocupados = contarOcupacion(pedido);
		if (ocupados >= pedido.oferta().capacidad()) {
			throw new SlotCompletoException(pedido.oferta().capacidad());
		}

		if (profesionalId != null && hayConflictoRealDeProfesional(pedido, profesionalId)) {
			throw new RecursoOcupadoException("profesional");
		}

		Long espacioId = pedido.oferta().requiereEspacio() ? elegirEspacio(pedido) : null;
		return new Asignacion(profesionalId, espacioId);
	}

	/**
	 * Resuelve y revalida el profesional del turno.
	 *
	 * <p>Tres controles, y ninguno lo puede hacer el cliente: que este habilitado para la oferta y
	 * vigente ese dia (02.07), y que el intervalo pedido caiga DENTRO de una franja de su
	 * disponibilidad efectiva. El tercero es el que atrapa el caso de RN-M12-004: la pantalla
	 * mostro el slot hace cinco minutos y desde entonces alguien cerro el dia.
	 */
	private Long resolverProfesional(Pedido pedido) {
		if (!pedido.oferta().requiereProfesional()) {
			return null;
		}
		if (pedido.profesionalId() == null) {
			throw new SlotNoDisponibleException("la oferta exige profesional y no se indico ninguno");
		}

		boolean habilitado = ofertas
				.profesionalesHabilitados(
						pedido.organizationId(), pedido.consultorioId(), pedido.oferta().id())
				.stream()
				.filter(habilitacion -> habilitacion.recursoId() == pedido.profesionalId())
				.anyMatch(habilitacion -> habilitacion.vigenteEn(pedido.inicio()));
		if (!habilitado) {
			throw new SlotNoDisponibleException(
					"el profesional ya no esta habilitado para esta oferta en esa fecha");
		}

		LocalDate fecha = pedido.inicio()
				.atZone(ZoneId.of(pedido.sede().timezone())).toLocalDate();
		boolean dentroDeFranja = disponibilidad
				.efectiva(pedido.organizationId(), pedido.sede(), pedido.profesionalId(),
						fecha, fecha.plusDays(1))
				.stream()
				.flatMap(dia -> dia.franjas().stream())
				// Contiene, no se cruza: media consulta fuera del horario no es un turno valido.
				.anyMatch(franja -> !franja.desde().isAfter(pedido.inicio())
						&& !franja.hasta().isBefore(pedido.fin()));
		if (!dentroDeFranja) {
			throw new SlotNoDisponibleException("el profesional ya no atiende en ese horario");
		}

		return pedido.profesionalId();
	}

	/** Cuantos turnos vivos ocupan el slot, sin contar el que se esta moviendo. */
	private long contarOcupacion(Pedido pedido) {
		long ocupados = turnos.contarVivosEnSlot(
				pedido.organizationId(), pedido.oferta().id(), pedido.inicio());
		if (pedido.turnoExcluidoId() == null) {
			return ocupados;
		}
		// El excluido cuenta como ocupacion solo si esta EN este slot: un turno que se mueve desde
		// otro horario nunca estuvo aca, y restarlo de todas formas dejaria entrar uno de mas.
		boolean estabaEnEsteSlot = turnos
				.findByIdInScope(
						pedido.organizationId(), pedido.consultorioId(), pedido.turnoExcluidoId())
				.filter(Turno::estaVivo)
				.filter(turno -> turno.getOfertaId().equals(pedido.oferta().id()))
				.filter(turno -> turno.getInicio().equals(pedido.inicio()))
				.isPresent();
		return estabaEnEsteSlot ? ocupados - 1 : ocupados;
	}

	/**
	 * {@code true} si el profesional tiene otro turno que se cruza y que NO es del mismo grupo.
	 *
	 * <p>Sin esta distincion, la segunda inscripcion a una clase grupal fallaria por "profesional
	 * ocupado" contra la primera: los turnos de un mismo slot grupal comparten profesional, oferta
	 * y hora a proposito. Lo que es conflicto es cualquier OTRO turno que se cruce.
	 */
	private boolean hayConflictoRealDeProfesional(Pedido pedido, long profesionalId) {
		return turnos
				.findVivosDeProfesionalQueCruzan(
						pedido.organizationId(), profesionalId, pedido.inicio(), pedido.fin())
				.stream()
				.filter(otro -> !esElExcluido(otro, pedido))
				.anyMatch(otro -> !(otro.getOfertaId().equals(pedido.oferta().id())
						&& otro.getInicio().equals(pedido.inicio())));
	}

	/**
	 * Elige el primer espacio habilitado, en servicio y libre en ese intervalo.
	 *
	 * <p><b>El primero y no el mejor.</b> Cualquier criterio de reparto —el menos usado, el mas
	 * chico que alcance— exige leer mas estado dentro del lock que serializa toda la sede, y no
	 * hay ninguna regla de negocio que lo pida. Un criterio se agrega despues sin cambiar la
	 * estructura; la contencion no se saca.
	 *
	 * <p>El orden es el que devuelve el directorio, que es estable: la asignacion es determinista
	 * para el mismo estado de la base.
	 */
	private Long elegirEspacio(Pedido pedido) {
		List<HabilitacionSnapshot> habilitados = ofertas.espaciosHabilitados(
				pedido.organizationId(), pedido.consultorioId(), pedido.oferta().id());

		for (HabilitacionSnapshot habilitacion : habilitados) {
			if (!habilitacion.vigenteEn(pedido.inicio())) {
				continue;
			}
			Optional<EspacioSnapshot> espacio = espacios.find(
					pedido.organizationId(), habilitacion.recursoId(), pedido.inicio());
			boolean utilizable = espacio
					.filter(EspacioSnapshot::active)
					.filter(EspacioSnapshot::enServicio)
					.filter(candidato -> candidato.consultorioId() == pedido.consultorioId())
					.isPresent();
			if (!utilizable) {
				continue;
			}
			boolean libre = turnos
					.findVivosDeEspacioQueCruzan(pedido.organizationId(), habilitacion.recursoId(),
							pedido.inicio(), pedido.fin())
					.stream()
					.allMatch(otro -> esElExcluido(otro, pedido));
			if (libre) {
				return habilitacion.recursoId();
			}
		}
		// Se distingue de SlotNoDisponible: el hueco existe y el profesional atiende; lo que falta
		// es un box. La pantalla puede ofrecer otro horario en vez de mandar a recargar la agenda.
		throw new RecursoOcupadoException("espacio");
	}

	private static boolean esElExcluido(Turno turno, Pedido pedido) {
		return pedido.turnoExcluidoId() != null && turno.getId().equals(pedido.turnoExcluidoId());
	}
}
