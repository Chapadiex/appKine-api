package com.akine.encounter.domain.evolucion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * El resultado de analizar un caso: indicadores y hallazgos (RF-M31-001, RF-M31-002).
 *
 * @param desde fecha de la primera sesion analizada; {@code null} sin sesiones
 * @param hasta fecha de la ultima sesion analizada; {@code null} sin sesiones
 */
public record AnalisisDeEvolucion(
		int sesionesAnalizadas,
		int sesionesPresentes,
		LocalDate desde,
		LocalDate hasta,
		Indicadores indicadores,
		List<Hallazgo> hallazgos) {

	public AnalisisDeEvolucion {
		hallazgos = hallazgos == null ? List.of() : List.copyOf(hallazgos);
	}

	/** Los indicadores del diseno, seccion 5.2. */
	public record Indicadores(
			Serie dolor,
			Conteo evolucion,
			Conteo tolerancia,
			List<SerieDeMedicion> mediciones,
			Adherencia adherencia,
			Frecuencia frecuencia,
			List<TratamientoResumido> tratamientos) {

		public Indicadores {
			mediciones = mediciones == null ? List.of() : List.copyOf(mediciones);
			tratamientos = tratamientos == null ? List.of() : List.copyOf(tratamientos);
		}
	}

	/** Un valor de una serie, con la sesion de donde sale. */
	public record Punto(long sesionId, LocalDate fecha, BigDecimal valor, String zona) {
	}

	/** Una serie con su primer valor, el ultimo y la diferencia (ultimo menos primero). */
	public record Serie(List<Punto> puntos, BigDecimal primero, BigDecimal ultimo, BigDecimal diferencia) {

		public Serie {
			puntos = puntos == null ? List.of() : List.copyOf(puntos);
		}
	}

	/**
	 * Una serie de medicion por definicion y lado.
	 *
	 * @param unidadesDifieren la unidad cambio dentro de la serie: no se calcula diferencia ni se
	 *                         evalua ninguna alerta
	 */
	public record SerieDeMedicion(
			long definicionId,
			String codigo,
			String nombre,
			String unidad,
			String lateralidad,
			String mejoraCuando,
			boolean unidadesDifieren,
			Serie serie) {
	}

	/** Cuantas veces aparece cada valor y los ultimos en orden. */
	public record Conteo(Map<String, Integer> porValor, List<String> ultimos) {

		public Conteo {
			porValor = porValor == null ? Map.of() : Map.copyOf(porValor);
			ultimos = ultimos == null ? List.of() : List.copyOf(ultimos);
		}
	}

	/**
	 * Adherencia sobre todas las citas del caso (RN-M31-008).
	 *
	 * @param citas                 turnos ya ocurridos que no cancelo el profesional ni la
	 *                              institucion, mas las sesiones sin turno
	 * @param ausentesEnTurno       turnos {@code AUSENTE}
	 * @param canceladosPorPaciente turnos {@code CANCELADO} por el paciente
	 * @param ausentesEnSesion      sesiones cerradas {@code AUSENTE} cuyo turno no estaba ya contado
	 * @param porcentaje            inasistencias sobre citas; {@code null} sin citas
	 */
	public record Adherencia(
			int citas,
			int inasistencias,
			int ausentesEnTurno,
			int canceladosPorPaciente,
			int ausentesEnSesion,
			Integer porcentaje) {
	}

	/**
	 * Sesiones presentes por semana en la ventana, contra la frecuencia del plan.
	 *
	 * @param planificadaPorSemana {@code null} si no hay plan o no declara frecuencia
	 */
	public record Frecuencia(int semanas, BigDecimal realPorSemana, Integer planificadaPorSemana) {
	}

	/** Una practica realizada en el caso. */
	public record TratamientoResumido(Long practicaId, String practica, int sesiones, LocalDate ultima) {
	}
}
