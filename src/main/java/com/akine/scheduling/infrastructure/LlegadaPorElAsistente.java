package com.akine.scheduling.infrastructure;

import com.akine.assistant.spi.AccionDelAsistente;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.PedidoDeAccion;
import com.akine.assistant.spi.PropuestaDeAccion;
import com.akine.assistant.spi.ResultadoDeAccion;
import com.akine.assistant.spi.Tarjeta;
import com.akine.assistant.spi.Tarjeta.TarjetaDeLlegada;
import com.akine.assistant.spi.TipoDeAccion;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ZonaDeSede;
import com.akine.scheduling.application.CicloDeRecepcionService;
import com.akine.scheduling.application.FiltroDeTurnos;
import com.akine.scheduling.application.OperatingActor;
import com.akine.scheduling.application.RecepcionService;
import com.akine.scheduling.application.TurnoDeCalendarioView;
import com.akine.scheduling.application.TurnoDelDiaView;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.TransicionDeRecepcionNoPermitidaException;
import com.akine.scheduling.domain.exception.TransicionDeTurnoNoPermitidaException;
import com.akine.scheduling.domain.exception.TurnoNotAccessibleException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * La llegada del paciente a su turno de hoy, dicha al asistente (AKINE-asistente-acciones §6.4).
 *
 * <p>Es lo que obtiene quien dicta "hoy vino Juan e hizo ultrasonido" <b>sin</b>
 * {@code sesion:register}: la recepcion marca que llego y los tratamientos quedan para el
 * profesional. Llegada es un estado de la reserva y no prueba que hubo atencion (DP-05).
 *
 * <p>Lee con {@link RecepcionService} ({@code turno:read}, con el alcance del actor) y escribe con
 * {@link CicloDeRecepcionService#registrarLlegada} ({@code turno:manage}, idempotente). No abre
 * transaccion propia.
 */
@Component
class LlegadaPorElAsistente implements AccionDelAsistente {

	private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

	private final RecepcionService recepcion;
	private final CicloDeRecepcionService ciclo;
	private final ConsultorioDirectory consultorios;

	LlegadaPorElAsistente(
			RecepcionService recepcion, CicloDeRecepcionService ciclo, ConsultorioDirectory consultorios) {
		this.recepcion = recepcion;
		this.ciclo = ciclo;
		this.consultorios = consultorios;
	}

	@Override
	public TipoDeAccion tipo() {
		return TipoDeAccion.LLEGADA;
	}

	@Override
	public PropuestaDeAccion preparar(ActorDelAsistente actor, PedidoDeAccion pedido) {
		if (pedido.personaId() == null) {
			return PropuestaDeAccion.sinTarjeta("Decime de quién es la llegada.");
		}
		long personaId = pedido.personaId();
		String nombre = pedido.personaNombre();

		// delRango toma [desde, hasta) en la zona de la sede y deja afuera los cancelados.
		List<TurnoDelDiaView> deHoy = recepcion.delRango(operador(actor), actor.consultorioId(), pedido.hoy(),
						pedido.hoy().plusDays(1), null, null, false, new FiltroDeTurnos(Set.of(), personaId))
				.turnos().stream()
				.map(TurnoDeCalendarioView::turno)
				.toList();
		if (deHoy.isEmpty()) {
			return PropuestaDeAccion.sinTarjeta(nombre + " no tiene turno hoy en esta sede.");
		}

		Instant ahora = Instant.now();
		TurnoDelDiaView turno = deHoy.stream()
				.min(Comparator.comparing((TurnoDelDiaView t) -> Duration.between(t.inicio(), ahora).abs())
						.thenComparing(TurnoDelDiaView::inicio))
				.orElseThrow();
		// Con recepcion vigente la llegada ya consta, tambien si la persona se retiro despues (U-21).
		if (turno.recepcion() != null || turno.llegadaEn() != null) {
			return PropuestaDeAccion.sinTarjeta(nombre + " ya tiene la llegada registrada.");
		}

		List<String> avisos = new ArrayList<>();
		if (deHoy.size() > 1) {
			avisos.add("Tiene más de un turno hoy: tomé el de las "
					+ HORA.format(turno.inicio().atZone(zona(pedido))) + ".");
		}
		if (!pedido.practicas().isEmpty()) {
			avisos.add("Los tratamientos los registra el profesional.");
		}
		return new PropuestaDeAccion(
				"Llegada de " + nombre,
				new TarjetaDeLlegada(personaId, nombre, turno.id(), turno.inicio()),
				Map.of(PropuestaDeAccion.PERSONA, personaId, PropuestaDeAccion.TURNO, turno.id()),
				avisos,
				List.of(),
				null);
	}

	@Override
	public ResultadoDeAccion ejecutar(ActorDelAsistente actor, long accionId, Tarjeta confirmada) {
		if (!(confirmada instanceof TarjetaDeLlegada tarjeta)) {
			return ResultadoDeAccion.rechazada("Lo confirmado no es una llegada.");
		}
		OperatingActor operador = operador(actor);
		long consultorioId = actor.consultorioId();
		try {
			// No se confia en la tarjeta: el turno se relee y tiene que ser de esa persona y de hoy.
			TurnoDelDiaView turno = recepcion.ver(operador, consultorioId, tarjeta.turnoId());
			if (turno.personaId() != tarjeta.personaId()) {
				return ResultadoDeAccion.rechazada("Ese turno no es de " + tarjeta.nombre() + ".");
			}
			ZoneId zona = ZonaDeSede.de(consultorios, actor.organizationId(), consultorioId);
			if (!LocalDate.ofInstant(turno.inicio(), zona).equals(LocalDate.now(zona))) {
				return ResultadoDeAccion.rechazada("Ese turno no es de hoy.");
			}
			ciclo.registrarLlegada(operador, consultorioId, turno.id());
		} catch (TurnoNotAccessibleException | ConsultorioNoAccesibleException inexistente) {
			return ResultadoDeAccion.rechazada("El turno ya no existe en esta sede.");
		} catch (TransicionDeTurnoNoPermitidaException noAdmite) {
			return ResultadoDeAccion.rechazada("No se puede registrar la llegada: " + noAdmite.getMotivo() + ".");
		} catch (TransicionDeRecepcionNoPermitidaException noAdmite) {
			return ResultadoDeAccion.rechazada("No se puede registrar la llegada: " + noAdmite.getMotivo() + ".");
		} catch (OptimisticLockingFailureException carrera) {
			return ResultadoDeAccion.rechazada("Alguien modificó el turno recién: volvé a pedirlo.");
		}
		return ResultadoDeAccion.confirmada(
				"Registré la llegada de " + tarjeta.nombre() + ".",
				TurnosEnElAsistente.ENLACE_RECEPCION,
				Map.of("turnoId", tarjeta.turnoId()));
	}

	private static ZoneId zona(PedidoDeAccion pedido) {
		return pedido.zona() == null ? ZoneId.of("UTC") : pedido.zona();
	}

	private static OperatingActor operador(ActorDelAsistente actor) {
		return new OperatingActor(actor.accountId(), actor.platformAdmin(), actor.organizationId(),
				actor.consultorioId(), actor.roleCode());
	}
}
