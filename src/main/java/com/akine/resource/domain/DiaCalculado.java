package com.akine.resource.domain;

import java.util.List;

/**
 * Un dia de disponibilidad efectiva ya resuelto.
 *
 * <h2>Por que no alcanza con una lista</h2>
 *
 * <p>El calculador devolvia originalmente {@code List<FranjaEfectiva>} por fecha, y una lista
 * vacia no explica nada: el admin que ve el martes en blanco no puede distinguir "nadie cargo
 * horario ese dia" de "hay un feriado y la sede cierra por feriado" ni de "una licencia tapo el
 * dia entero". El criterio de aceptacion de la etapa pide que la disponibilidad efectiva
 * <b>explique que regla la afecta</b>, y el dia en blanco es justamente donde mas hace falta la
 * explicacion.
 *
 * <p>Las franjas con contenido ya se explican solas: cada {@link FranjaEfectiva} lleva su
 * {@code origen} y su {@code recortadoPor}. Este record cubre el hueco complementario.
 *
 * @param franjas    franjas de atencion del dia, ordenadas por hora de inicio. Nunca {@code null}
 * @param razonVacio {@code null} si el dia tiene franjas. Si no, la regla que lo vacio:
 *                   {@link OrigenFranja#FERIADO} o {@link OrigenFranja#CIERRE}. Tambien
 *                   {@code null} cuando el dia quedo vacio porque no habia ninguna regla que lo
 *                   abriera: eso no es una regla que lo afecte, es la ausencia de reglas
 * @param reglaVacio id de la fila que lo vacio, para que la UI pueda linkearla. {@code null}
 *                   cuando la regla no tiene id que ofrecer (un feriado se resuelve por fecha,
 *                   no por id de excepcion)
 */
public record DiaCalculado(List<FranjaEfectiva> franjas, OrigenFranja razonVacio, Long reglaVacio) {

	public DiaCalculado {
		franjas = franjas == null ? List.of() : List.copyOf(franjas);
	}

	/** Dia con atencion. No lleva razon de vacio porque no esta vacio. */
	public static DiaCalculado con(List<FranjaEfectiva> franjas) {
		return new DiaCalculado(franjas, null, null);
	}

	/** Dia sin atencion, con la regla que lo dejo asi. */
	public static DiaCalculado vacio(OrigenFranja razon, Long reglaId) {
		return new DiaCalculado(List.of(), razon, reglaId);
	}

	/** Dia sin atencion porque ninguna regla lo abrio. No hay nada que explicar. */
	public static DiaCalculado sinReglas() {
		return new DiaCalculado(List.of(), null, null);
	}

	public boolean estaVacio() {
		return franjas.isEmpty();
	}
}
