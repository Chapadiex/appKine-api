package com.akine.scheduling.infrastructure;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ClavesDeFila;
import com.akine.assistant.spi.HerramientaDelAsistente;
import com.akine.assistant.spi.Intencion;
import com.akine.assistant.spi.PedidoDeHerramienta;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.scheduling.application.FiltroDeTurnos;
import com.akine.scheduling.application.OperatingActor;
import com.akine.scheduling.application.RecepcionService;
import com.akine.scheduling.application.TurnoDeCalendarioView;
import com.akine.scheduling.application.TurnoDelDiaView;
import com.akine.scheduling.application.TurnosDeSedeView;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Las tres herramientas de turnos del asistente. Todas leen con {@link RecepcionService#delRango},
 * que exige {@code turno:read} en la sede y aplica el alcance del actor: un profesional con alcance
 * propio ve solo sus turnos, igual que en la agenda.
 *
 * <p>El estado de cada fila es el <b>estado operativo</b> (la foto combinada de turno, recepcion y
 * atencion): {@code ATENDIDO} significa sesion cerrada y {@code EN_ATENCION} sesion abierta. El
 * asistente no deduce nada mas: un turno no prueba que hubo una prestacion (DP-05).
 *
 * <p>Una atencion sin turno (U-24) no aparece aca: no tiene turno. Lo declara la advertencia de
 * {@link SesionesDePersona}.
 */
abstract class TurnosEnElAsistente implements HerramientaDelAsistente {

	static final String ENLACE_RECEPCION = "/agenda/recepcion";

	private final RecepcionService recepcion;

	TurnosEnElAsistente(RecepcionService recepcion) {
		this.recepcion = recepcion;
	}

	@Override
	public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, PedidoDeHerramienta pedido) {
		OperatingActor operador = new OperatingActor(actor.accountId(), actor.platformAdmin(),
				actor.organizationId(), actor.consultorioId(), actor.roleCode());
		FiltroDeTurnos filtro = pedido.personaId() == null ? FiltroDeTurnos.NINGUNO
				: new FiltroDeTurnos(Set.of(), pedido.personaId());
		TurnosDeSedeView turnos;
		try {
			// delRango toma [desde, hasta): el ultimo dia del pedido (inclusive) va como hasta + 1.
			turnos = recepcion.delRango(operador, actor.consultorioId(), pedido.desde(), pedido.hasta().plusDays(1),
					null, null, true, filtro);
		}
		catch (AccessDeniedException e) {
			return ResultadoDeHerramienta.denegado();
		}
		List<Map<String, Object>> filas = turnos.turnos().stream()
				.sorted(Comparator.comparing(t -> t.turno().inicio()))
				.map(TurnosEnElAsistente::fila)
				.toList();
		return new ResultadoDeHerramienta(filas, advertencias(), enlace(), false);
	}

	List<String> advertencias() {
		return List.of();
	}

	String enlace() {
		return ENLACE_RECEPCION;
	}

	static Map<String, Object> fila(TurnoDeCalendarioView calendario) {
		TurnoDelDiaView turno = calendario.turno();
		Map<String, Object> fila = new LinkedHashMap<>();
		fila.put(ClavesDeFila.TURNO_ID, turno.id());
		fila.put(ClavesDeFila.INICIO, turno.inicio());
		fila.put(ClavesDeFila.ESTADO, turno.operativo() == null ? turno.estado() : turno.operativo().estadoOperativo());
		fila.put(ClavesDeFila.PACIENTE, turno.personaNombre());
		fila.put(ClavesDeFila.OFERTA, turno.ofertaNombre());
		fila.put(ClavesDeFila.PROFESIONAL, calendario.profesionalNombre());
		fila.put(ClavesDeFila.SESION_ID, turno.operativo() == null ? null : turno.operativo().sesionId());
		return fila;
	}

	/** "¿Juan vino esta semana?" */
	@Component
	static class AsistenciaDePersona extends TurnosEnElAsistente {

		AsistenciaDePersona(RecepcionService recepcion) {
			super(recepcion);
		}

		@Override
		public Intencion intencion() {
			return Intencion.ASISTENCIA_PERSONA;
		}
	}

	/** "¿Se cargo la sesion de Maria?" */
	@Component
	static class SesionesDePersona extends TurnosEnElAsistente {

		static final String SIN_TURNO = "Las atenciones sin turno no aparecen en esta respuesta: buscalas en la "
				+ "historia clínica de la persona.";

		SesionesDePersona(RecepcionService recepcion) {
			super(recepcion);
		}

		@Override
		public Intencion intencion() {
			return Intencion.SESION_DE_PERSONA;
		}

		@Override
		List<String> advertencias() {
			return List.of(SIN_TURNO);
		}
	}

	/** "¿Quienes vienen hoy?" */
	@Component
	static class AgendaDelDia extends TurnosEnElAsistente {

		AgendaDelDia(RecepcionService recepcion) {
			super(recepcion);
		}

		@Override
		public Intencion intencion() {
			return Intencion.AGENDA_DEL_DIA;
		}
	}
}
