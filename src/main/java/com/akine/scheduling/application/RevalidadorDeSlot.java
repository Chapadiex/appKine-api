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
import com.akine.scheduling.spi.OcupacionExternaProbe;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
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
	private final OcupacionExternaProbe ocupacionExterna;

	RevalidadorDeSlot(
			TurnoRepositoryPort turnos,
			OfertaDirectory ofertas,
			DisponibilidadDirectory disponibilidad,
			EspacioDirectory espacios,
			OcupacionExternaProbe ocupacionExterna) {

		this.turnos = turnos;
		this.ofertas = ofertas;
		this.disponibilidad = disponibilidad;
		this.espacios = espacios;
		this.ocupacionExterna = ocupacionExterna;
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

		// AKINE-08.01. Una CLASE ocupa al profesional exactamente igual que un turno, y sin este
		// control se reserva un turno encima de una clase sin que nada falle: ningun unique puede
		// expresar un solapamiento de intervalos. Corre aca, BAJO EL LOCK de agenda_sede —el mismo
		// que toma la clase al programarse— y por eso las dos escrituras se serializan entre si.
		//
		// El error es el recurso-ocupado que esta operacion ya publica, y no uno nuevo: para la
		// pantalla el desenlace es identico —ese profesional esta tomado en ese horario— y que el
		// evento conflictivo sea una clase no cambia lo que el usuario puede hacer.
		if (profesionalId != null && ocupacionExterna.profesionalOcupado(
				pedido.organizationId(), profesionalId, pedido.inicio(), pedido.fin())) {
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
			// A-8b (DP-19): una oferta sin profesional no pasa por la disponibilidad efectiva —no
			// hay de quien calcularla—, asi que el horario general de la sede se controla aca. Sin
			// esto seria la unica puerta para reservar fuera del horario de la sede.
			if (disponibilidad.fueraDelHorarioDeSede(
					pedido.organizationId(), pedido.sede(), pedido.inicio(), pedido.fin())) {
				throw new SlotNoDisponibleException("la sede no atiende en ese horario");
			}
			return null;
		}
		if (pedido.profesionalId() == null) {
			throw new SlotNoDisponibleException("la oferta exige profesional y no se indico ninguno");
		}

		// LISTA VACIA SIGNIFICA TODOS, NO NINGUNO. Es la regla de V28 y la decision con mas
		// consecuencias de 02.07: una oferta recien creada no tiene filas de habilitacion, y si
		// eso significara "nadie puede prestarla", toda reserva contra ella se rechazaria.
		//
		// Se pierde con facilidad al refactorizar —de hecho se perdio al extraer este revalidador
		// de TurnoService— porque un `anyMatch` sobre una lista vacia da false y parece correcto.
		// Lo destapo el QA manual contra el stack real, no un test.
		List<HabilitacionSnapshot> habilitaciones = ofertas.profesionalesHabilitados(
				pedido.organizationId(), pedido.consultorioId(), pedido.oferta().id());
		boolean habilitado = habilitaciones.isEmpty()
				|| habilitaciones.stream()
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
		// Paquete E-1. Una franja grupal ocupa UN box con todas sus personas. Sin esto, la segunda
		// inscripcion veia el box "ocupado por un turno que se cruza" —el de su propio grupo— y se
		// rechazaba con recurso-ocupado: una oferta grupal que exige espacio no admitia mas de una
		// persona. Es la misma distincion que hayConflictoRealDeProfesional hace para el
		// profesional. El cupo ya lo controlo contarOcupacion contra la capacidad de la oferta.
		Optional<Long> delGrupo = espacioDelGrupo(pedido);
		if (delGrupo.isPresent()) {
			return delGrupo.get();
		}

		List<HabilitacionSnapshot> habilitados = ofertas.espaciosHabilitados(
				pedido.organizationId(), pedido.consultorioId(), pedido.oferta().id());
		if (habilitados.isEmpty()) {
			// Lista vacia significa TODOS: la oferta se puede prestar en cualquier espacio en
			// servicio de la sede. Misma regla de V28 que para los profesionales.
			return espacios.enServicio(
					pedido.organizationId(), pedido.consultorioId(), pedido.inicio(), pedido.fin())
					.stream()
					.filter(EspacioSnapshot::active)
					.filter(EspacioSnapshot::enServicio)
					.map(EspacioSnapshot::id)
					.filter(espacioId -> turnos.findVivosDeEspacioQueCruzan(
							pedido.organizationId(), espacioId, pedido.inicio(), pedido.fin()).isEmpty())
					// AKINE-08.01: un box tomado por una clase no es un box libre. Ver
					// espacioLibreDeEventosExternos.
					.filter(espacioId -> espacioLibreDeEventosExternos(pedido, espacioId))
					.findFirst()
					.orElseThrow(() -> new RecursoOcupadoException("espacio"));
		}

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
			if (libre && espacioLibreDeEventosExternos(pedido, habilitacion.recursoId())) {
				return habilitacion.recursoId();
			}
		}
		// Se distingue de SlotNoDisponible: el hueco existe y el profesional atiende; lo que falta
		// es un box. La pantalla puede ofrecer otro horario en vez de mandar a recargar la agenda.
		throw new RecursoOcupadoException("espacio");
	}

	/**
	 * {@code true} si ningun evento de otro modulo —hoy, una clase de M28— ocupa ese box en el
	 * intervalo. AKINE-08.01.
	 *
	 * <p>Un box tomado por una clase <b>no es un box libre</b>, y sin este filtro la reserva
	 * elegiria el primero que ningun turno esta usando y lo venderia dos veces. El caso no lo
	 * puede atrapar ningun unique —dos intervalos que se cruzan no comparten un valor de columna—
	 * y lo que lo hace confiable es que esta consulta corre <b>bajo el lock de {@code agenda_sede}</b>,
	 * la misma fila que la clase se disputa al programarse.
	 *
	 * <p><b>No hay nada que excluir aca.</b> El {@code turnoExcluidoId} de una reprogramacion es un
	 * turno, y un turno nunca aparece en esta respuesta: la sonda contesta por lo que NO es turno.
	 */
	private boolean espacioLibreDeEventosExternos(Pedido pedido, long espacioId) {
		return !ocupacionExterna.espacioOcupado(
				pedido.organizationId(), espacioId, pedido.inicio(), pedido.fin());
	}

	/**
	 * El box de los otros turnos vivos de la misma franja —misma oferta, mismo inicio—, si los hay.
	 *
	 * <p>La ventana es de un microsegundo porque {@code inicio} se guarda en {@code DATETIME(6)} y
	 * la consulta filtra {@code [desde, hasta)}: es la forma de pedir "exactamente este inicio" sin
	 * agregar otra consulta al puerto.
	 */
	private Optional<Long> espacioDelGrupo(Pedido pedido) {
		return turnos.findVivosDeLaOfertaEnVentana(pedido.organizationId(), pedido.oferta().id(),
						pedido.inicio(), pedido.inicio().plus(1, ChronoUnit.MICROS))
				.stream()
				.filter(otro -> !esElExcluido(otro, pedido))
				.filter(otro -> otro.getInicio().equals(pedido.inicio()))
				.map(Turno::getEspacioId)
				.filter(Objects::nonNull)
				.findFirst();
	}

	private static boolean esElExcluido(Turno turno, Pedido pedido) {
		return pedido.turnoExcluidoId() != null && turno.getId().equals(pedido.turnoExcluidoId());
	}
}
