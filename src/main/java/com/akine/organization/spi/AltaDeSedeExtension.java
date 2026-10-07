package com.akine.organization.spi;

import java.time.LocalTime;
import java.util.List;

/**
 * Un modulo que completa el alta de una sede en el mismo acto (RF-M03-002, CA-M03-002).
 *
 * <h2>Por que un puerto invertido</h2>
 *
 * <p>RF-M03-002 pide crear "consultorio, primer box, horario general e intervalo inicial" juntos.
 * El consultorio y el intervalo son de {@code organization}; el box ({@code espacio}) y el
 * horario general ({@code consultorio_horario}, parte del calendario de la sede) son de
 * {@code resource}. Que {@code organization} los escribiera violaria la propiedad de tablas, y
 * llamar a {@code resource} dibujaria la flecha {@code organization -> resource}, que cierra un
 * ciclo con la {@code resource -> organization.spi} que ya existe.
 *
 * <p>Invertido, la flecha va {@code resource -> organization.spi}, igual que
 * {@link ConsultorioDeactivationProbe}. Lo implementa
 * {@code resource.infrastructure.PrimerBoxYHorarioDeSede}.
 *
 * <h2>Contrato de la invocacion</h2>
 *
 * <ul>
 *   <li>Se llama <b>dentro de la transaccion del alta</b>, despues de insertar la sede y de
 *       exigir {@code consultorio:manage} con alcance organizacion. Quien implementa no vuelve a
 *       evaluar permisos: el alcance organizacion ya es mas fuerte que cualquiera de sede.</li>
 *   <li>Cualquier excepcion revierte el alta entera, sede y cupo de plan incluidos
 *       (CA-M03-002-04: una validacion fallida no deja datos parciales).</li>
 *   <li>Solo se llama cuando {@link Complemento#vacio()} es {@code false}. Un replay idempotente
 *       no la vuelve a llamar: devuelve la sede que ya existe.</li>
 * </ul>
 */
public interface AltaDeSedeExtension {

	/**
	 * Escribe lo que al modulo le toca del alta.
	 *
	 * @param accountId cuenta que da de alta la sede, para la auditoria
	 */
	void completarAlta(long organizationId, long consultorioId, long accountId, Complemento complemento);

	/**
	 * Lo que el alta trae ademas de los datos de la sede. Los dos son opcionales.
	 *
	 * @param primerBox      {@code null} = no crear box
	 * @param horarioGeneral {@code null} o vacia = no declarar horario general
	 */
	record Complemento(PrimerBox primerBox, List<FranjaHoraria> horarioGeneral) {

		public Complemento {
			horarioGeneral = horarioGeneral == null ? List.of() : List.copyOf(horarioGeneral);
		}

		public static Complemento ninguno() {
			return new Complemento(null, List.of());
		}

		public boolean vacio() {
			return primerBox == null && horarioGeneral.isEmpty();
		}
	}

	/**
	 * El primer box de la sede.
	 *
	 * @param capacidad {@code null} = la capacidad por defecto de un espacio (1)
	 */
	record PrimerBox(String nombre, Integer capacidad) {
	}

	/**
	 * Una franja semanal del horario general: dia ISO-8601 (1 = lunes) y horas locales de la
	 * sede, con {@code horaHasta} exclusiva. La medianoche como fin es {@link LocalTime#MAX}.
	 */
	record FranjaHoraria(int diaSemana, LocalTime horaDesde, LocalTime horaHasta) {
	}
}
