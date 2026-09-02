package com.akine.scheduling.infrastructure;

import com.akine.person.spi.AporteDeResumen;
import com.akine.person.spi.ConsultaDeResumen;
import com.akine.person.spi.HitoDeResumen;
import com.akine.person.spi.IndicadorDeResumen;
import com.akine.person.spi.ResumenDePersonaContributor;
import com.akine.scheduling.domain.EstadoTurno;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Lo que {@code scheduling} aporta al Paciente 360 (AKINE-03.02, RF-M07-004).
 *
 * <h2>Por que esta clase vive aca y no en {@code person}</h2>
 *
 * <p>Porque la dependencia entre los dos modulos ya tiene sentido: {@code scheduling} depende de
 * {@code person.spi} —un turno cuelga de una persona— y hacer que {@code person} preguntara por
 * turnos cerraria un ciclo que ArchUnit rechaza. La inversion esta explicada en
 * {@link ResumenDePersonaContributor}.
 *
 * <p>Vive en {@code infrastructure} y no en {@code application} por la misma razon que
 * {@link ReservaProbeSobreTurnos}: es un adaptador hacia afuera, no una regla de negocio.
 *
 * <h2>Que muestra</h2>
 *
 * <p>Dos numeros —cuantos turnos futuros vivos, cuantas ausencias— y los ultimos hechos datados.
 * <b>Nada clinico</b>: que hubo turno el martes es administrativo; que se hizo en ese turno es M14
 * y se pide con permiso propio.
 *
 * <p>Las <b>ausencias</b> estan porque son el dato operativo que cambia una decision de mostrador:
 * quien falto tres veces sin avisar es a quien se le pide confirmacion antes de darle el proximo
 * turno. Es informacion que el centro ya tiene y que hoy vive desparramada en la agenda.
 */
@Component
public class TurnosEnElResumenDePersona implements ResumenDePersonaContributor {

	private static final String SECCION = "turnos";

	private final TurnoRepositoryPort turnos;

	public TurnosEnElResumenDePersona(TurnoRepositoryPort turnos) {
		this.turnos = turnos;
	}

	@Override
	public String seccion() {
		return SECCION;
	}

	/**
	 * {@code turno:read}, que es el mismo permiso con el que se lee la agenda.
	 *
	 * <p>Lo declara este modulo y no {@code person} justamente para que no se desincronice: el dia
	 * que {@code scheduling} cambie el permiso con el que expone sus turnos, el 360 lo sigue solo.
	 */
	@Override
	public String permisoRequerido() {
		return PermissionCodes.TURNO_READ;
	}

	@Override
	public AporteDeResumen aportar(ConsultaDeResumen consulta) {
		// Se piden mas filas que hitos porque los indicadores cuentan sobre la misma lectura: con
		// el limite justo, "3 turnos futuros" seria en realidad "3 de los ultimos 5", que es un
		// numero equivocado presentado como si fuera exacto. El tope sigue existiendo —esto es una
		// ficha, no un historico— y esta escrito para que se vea que es una decision.
		List<Turno> encontrados = turnos.findDeLaPersona(
				consulta.organizationId(), consulta.personaId(), Math.max(consulta.limiteHitos(), 1) * 10);

		Instant ahora = Instant.now();
		long futuros = 0;
		long ausencias = 0;
		List<HitoDeResumen> hitos = new ArrayList<>();

		for (Turno turno : encontrados) {
			if (turno.getEstado() == EstadoTurno.AUSENTE) {
				ausencias++;
			}
			if (turno.getDeletedAt() == null
					&& turno.getEstado().admiteTransicion()
					&& turno.getInicio().isAfter(ahora)) {
				futuros++;
			}
			if (hitos.size() < consulta.limiteHitos()) {
				hitos.add(new HitoDeResumen(
						SECCION,
						"TURNO",
						turno.getInicio(),
						"Turno",
						turno.getEstado().name(),
						turno.getId()));
			}
		}

		return new AporteDeResumen(
				SECCION,
				List.of(
						IndicadorDeResumen.contando("turnos-futuros", "Turnos futuros", futuros),
						IndicadorDeResumen.contando("turnos-ausencias", "Ausencias", ausencias)),
				hitos);
	}
}
