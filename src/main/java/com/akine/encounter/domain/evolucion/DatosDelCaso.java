package com.akine.encounter.domain.evolucion;

import com.akine.encounter.domain.Evolucion;
import com.akine.encounter.domain.ProximaConducta;
import com.akine.encounter.domain.Tolerancia;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Todo lo que {@link ReglasDeEvolucion} necesita de un caso, ya leido y en tipos planos (M31).
 *
 * <p>Las fechas llegan como {@link LocalDate} en la zona de la sede: el dominio no conoce zonas.
 *
 * @param casoActivo   el caso esta {@code ACTIVO}
 * @param sesiones     sesiones cerradas del caso, de la mas vieja a la mas nueva
 * @param turnos       turnos del caso, cancelados incluidos, por inicio
 * @param plan         plan ACTIVO o SUSPENDIDO del caso; {@code null} si no tiene
 * @param definiciones definiciones de medicion por id, para rango y sentido de mejora
 */
public record DatosDelCaso(
		boolean casoActivo,
		List<Sesion> sesiones,
		List<Turno> turnos,
		Plan plan,
		Map<Long, Definicion> definiciones) {

	public DatosDelCaso {
		sesiones = sesiones == null ? List.of() : List.copyOf(sesiones);
		turnos = turnos == null ? List.of() : List.copyOf(turnos);
		definiciones = definiciones == null ? Map.of() : Map.copyOf(definiciones);
	}

	/** Una sesion cerrada del caso. */
	public record Sesion(
			long sesionId,
			LocalDate fecha,
			Instant cerradaEn,
			boolean presente,
			Long turnoId,
			Integer dolorEva,
			String dolorZona,
			Evolucion evolucion,
			Tolerancia tolerancia,
			ProximaConducta proximaConducta,
			List<Medicion> mediciones,
			List<Tratamiento> tratamientos) {

		public Sesion {
			mediciones = mediciones == null ? List.of() : List.copyOf(mediciones);
			tratamientos = tratamientos == null ? List.of() : List.copyOf(tratamientos);
		}
	}

	/** Una medicion numerica o de escala de una sesion. */
	public record Medicion(
			long definicionId,
			String codigo,
			String nombre,
			String unidad,
			String lateralidad,
			BigDecimal valor) {
	}

	/** Un tratamiento realizado en una sesion. */
	public record Tratamiento(Long practicaId, String practicaNombre) {
	}

	/** Un turno del caso. Estado y origen como texto: son de {@code scheduling}. */
	public record Turno(long turnoId, LocalDate fecha, String estado, String origenCancelacion) {
	}

	/** El plan vigente del caso. */
	public record Plan(
			String estado,
			LocalDate activadoEl,
			Instant versionVigenteDesde,
			Integer frecuenciaSemanal,
			Integer duracionSemanas,
			boolean completo) {

		public boolean activo() {
			return "ACTIVO".equals(estado);
		}
	}

	/**
	 * Lo que se lee vivo de la definicion: rango y sentido de mejora.
	 *
	 * @param mejoraCuando {@code SUBE}, {@code BAJA} o {@code null}
	 */
	public record Definicion(long id, BigDecimal minimo, BigDecimal maximo, String mejoraCuando) {

		/** El ancho del rango, o {@code null} si la definicion no declara los dos extremos. */
		public BigDecimal rango() {
			if (minimo == null || maximo == null || maximo.compareTo(minimo) <= 0) {
				return null;
			}
			return maximo.subtract(minimo);
		}
	}
}
