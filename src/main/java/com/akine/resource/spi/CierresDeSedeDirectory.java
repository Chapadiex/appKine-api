package com.akine.resource.spi;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Los cierres de SEDE ENTERA (excepcion de tipo {@code CIERRE} sin profesional) que tocan la agenda de
 * una persona, para su historial de agenda (AKINE-agenda-historial, "Cierres de sede").
 *
 * <p>Un cierre de sede no nombra a nadie: a quien afecta se resuelve al leer, no se copia a cada
 * profesional. Este contrato contesta la mitad que es de {@code resource} —quien tenia horario
 * habitual ahi—; la otra mitad —quien tenia turnos o clases en el periodo— la mira {@code scheduling},
 * que es dueño de los turnos.
 *
 * <p><b>No autoriza.</b> El llamador ya recorto las sedes a las que el lector alcanza.
 */
public interface CierresDeSedeDirectory {

	/**
	 * Todos los cierres de sede entera de esas sedes, activos o dados de baja, cada uno con las
	 * memberships de {@code membershipsPorSede} que tenian horario habitual en el periodo.
	 *
	 * <p>"Tenia horario" = un bloque de esa membership, en esa sede, cuya vigencia cruza el periodo del
	 * cierre en un dia de la semana que el bloque atiende (y en horas que se cruzan, si el cierre es
	 * parcial), y que no estaba dado de baja antes de que el cierre empezara. Un bloque dado de baja
	 * despues sigue contando: la asignacion historica manda.
	 *
	 * @param membershipsPorSede sede &rarr; memberships de la persona en esa sede (vigentes o no)
	 */
	List<CierreDeSede> cierresDeSede(long organizationId, Map<Long, Set<Long>> membershipsPorSede);

	/**
	 * @param fechaHasta              EXCLUSIVA, igual que en la tabla
	 * @param horaDesde               {@code null} junto con {@code horaHasta} = dia completo
	 * @param membershipsConHorario   las pedidas que tenian horario habitual en el periodo
	 */
	record CierreDeSede(
			long id,
			long consultorioId,
			String motivo,
			LocalDate fechaDesde,
			LocalDate fechaHasta,
			LocalTime horaDesde,
			LocalTime horaHasta,
			boolean activo,
			Set<Long> membershipsConHorario) {

		public CierreDeSede {
			membershipsConHorario = membershipsConHorario == null ? Set.of() : Set.copyOf(membershipsConHorario);
		}

		/** El ultimo dia afectado: {@code fechaHasta} es exclusiva. */
		public LocalDate ultimoDia() {
			return fechaHasta.minusDays(1);
		}

		public boolean parcial() {
			return horaDesde != null;
		}
	}
}
