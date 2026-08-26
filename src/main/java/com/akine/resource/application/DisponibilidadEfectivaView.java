package com.akine.resource.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * La disponibilidad efectiva de un profesional en una sede, resuelta dia por dia y ya proyectada
 * a instantes UTC (diseno §4).
 *
 * <h2>Que la distingue del listado de bloques</h2>
 *
 * <p>{@link BloqueView} responde "que horario tiene cargado". Esto responde "que dias y horas
 * concretas atiende", que es otra pregunta: aplica la vigencia de cada bloque, las excepciones de
 * la sede y las suyas, y la politica de feriados. Las dos lecturas conviven a proposito.
 *
 * <p><b>Nunca omite un dia de la ventana.</b> El dia sin atencion viaja igual, con su razon: es
 * la mitad del criterio de aceptacion —"la disponibilidad efectiva explica que regla la
 * afecta"— que mas falta hace, porque un dia en blanco es justamente donde el admin no puede
 * adivinar por que.
 *
 * @param timezone zona de la sede con la que se hizo la conversion, en texto. Viaja para que la
 *                 pantalla pueda rotularlo y para que un bug de huso se vea en la respuesta en
 *                 vez de tener que deducirse de instantes corridos una hora
 * @param dias     una entrada por cada fecha local de {@code [desde, hasta)}, en orden ascendente
 */
public record DisponibilidadEfectivaView(
		long membershipId,
		long consultorioId,
		String timezone,
		List<DiaEfectivo> dias) {

	public DisponibilidadEfectivaView {
		dias = dias == null ? List.of() : List.copyOf(dias);
	}

	/**
	 * Un dia de la ventana ya resuelto.
	 *
	 * <p><b>{@code esFeriado} y {@code franjas} vacias son independientes.</b> Un feriado con
	 * {@code cierraPorFeriado = false} tiene franjas normales, y un dia sin feriado puede quedar
	 * vacio por un cierre. La pantalla necesita los dos datos por separado.
	 *
	 * @param esFeriado     {@code true} si la fecha es feriado del pais de la sede, cierre o no
	 * @param feriadoNombre nombre del feriado, o {@code null} si no lo hay. <b>Solo el servicio
	 *                      puede rellenarlo:</b> el calculador recibe las fechas de feriado como
	 *                      un {@code Set<LocalDate>} sin ids ni nombres, asi que sin este campo
	 *                      la pantalla dice "cerrado" y no puede decir por que
	 * @param razonVacio    por que el dia no tiene franjas: {@code "FERIADO"} o {@code "CIERRE"}.
	 *                      {@code null} si el dia TIENE franjas y tambien —tercer estado, no un
	 *                      descuido— si quedo vacio porque NINGUNA regla lo abrio: eso no es una
	 *                      regla que lo afecte, es la ausencia de reglas
	 * @param reglaVacio    id de la fila que lo vacio, para que la pantalla pueda linkearla.
	 *                      {@code null} cuando la regla no tiene id que ofrecer: un feriado se
	 *                      resuelve por fecha, no por id de excepcion
	 */
	public record DiaEfectivo(
			LocalDate fecha,
			boolean esFeriado,
			String feriadoNombre,
			String razonVacio,
			Long reglaVacio,
			List<FranjaResuelta> franjas) {

		public DiaEfectivo {
			franjas = franjas == null ? List.of() : List.copyOf(franjas);
		}
	}

	/**
	 * Una franja de atencion con sus instantes ya convertidos, mas la trazabilidad de la regla.
	 *
	 * <p>{@code origen} y {@code recortadoPor} son la otra mitad del criterio de aceptacion: sin
	 * ellos la pantalla puede dibujar la franja pero no explicar de donde salio ni por que quedo
	 * cortada a las 11 en vez de a las 13.
	 *
	 * <p><b>Viaja en instantes y no en horas de pared, a proposito.</b> La hora local ya esta en
	 * {@link BloqueView} y en {@link ExcepcionView}, que son las lecturas de las REGLAS; esta es
	 * la lectura del RESULTADO, y lo que consume un resultado de disponibilidad es una agenda,
	 * que compara instantes. {@link DisponibilidadEfectivaView#timezone()} viaja igual para que
	 * la pantalla pueda rotular en que huso esta mostrando.
	 *
	 * @param desde        instante UTC de inicio
	 * @param hasta        instante UTC de fin, EXCLUSIVO. Una franja que llega al fin del dia
	 *                     trae aca el <b>inicio del dia siguiente</b>, no las 23:59:59.999999999:
	 *                     ver {@code DisponibilidadEfectivaService}
	 * @param origen       {@code "BLOQUE"} o {@code "APERTURA"}: que regla la produjo
	 * @param recortadoPor {@code "CIERRE"} si una excepcion la corto, {@code null} si nada la
	 *                     recorto
	 * @param reglaId      id de la fila que la produjo —el bloque o la excepcion de apertura—
	 *                     para que la pantalla pueda linkearla
	 */
	public record FranjaResuelta(
			Instant desde,
			Instant hasta,
			String origen,
			String recortadoPor,
			Long reglaId) {
	}
}
