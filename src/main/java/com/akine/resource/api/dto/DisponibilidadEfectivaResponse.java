package com.akine.resource.api.dto;

import com.akine.resource.application.DisponibilidadEfectivaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * La disponibilidad efectiva de un profesional en una sede, resuelta dia por dia y ya proyectada
 * a instantes UTC (diseno §4).
 *
 * <h2>{@code franja.origen} NO es un extra: es el criterio de aceptacion de la etapa</h2>
 *
 * <p>Leer esto antes de decidir que campos consume la pantalla. CA-M05 pide que la
 * disponibilidad efectiva <b>explique que regla la afecta</b>, y esa explicacion viaja en tres
 * campos y en ningun otro lado:
 *
 * <ul>
 *   <li>{@code franja.origen} — de donde salio la franja: del horario base ({@code BLOQUE}) o de
 *       una apertura puntual ({@code APERTURA}).</li>
 *   <li>{@code franja.recortadoPor} — por que la franja termina a las 11 y no a las 13.</li>
 *   <li>{@code dia.razonVacio} — por que un dia no tiene ni una franja.</li>
 * </ul>
 *
 * <p>Una pantalla que dibuje las franjas y descarte estos tres campos cumple la mitad visible de
 * la etapa y deja afuera la que hace falta: un dia en blanco es exactamente donde el
 * administrador no puede adivinar por que, y sin {@code razonVacio} la unica salida que le queda
 * es abrir las tres pantallas de reglas y compararlas a mano.
 *
 * <h2>Que la distingue del listado de bloques</h2>
 *
 * <p>{@link BloqueResponse} responde "que horario tiene cargado". Esto responde "que dias y horas
 * concretas atiende", que es otra pregunta: aplica la vigencia de cada bloque, las excepciones de
 * la sede y las del profesional, la vigencia del vinculo y la politica de feriados. Las dos
 * lecturas conviven a proposito.
 *
 * <p><b>Nunca se omite un dia</b> de {@code [desde, hasta)}, ni siquiera si el profesional ya no
 * trabaja en el centro. La pantalla dibuja una grilla de fechas y un dia faltante la correria.
 */
@Schema(description = "Disponibilidad efectiva de un profesional en una sede, dia por dia")
public record DisponibilidadEfectivaResponse(

		@Schema(description = "Vinculo del profesional consultado", example = "1")
		long membershipId,

		@Schema(description = "Sede consultada", example = "1")
		long consultorioId,

		@Schema(description = "Zona horaria con la que se convirtieron las horas de pared a "
				+ "instantes. Viaja para que la pantalla pueda rotularla y para que un bug de "
				+ "huso se vea en la respuesta en vez de deducirse de instantes corridos una hora",
				example = "America/Argentina/Cordoba")
		String timezone,

		@Schema(description = "Una entrada por cada fecha local de [desde, hasta), en orden "
				+ "ascendente. Los dias sin atencion viajan igual, con su razon")
		List<DiaEfectivoResponse> dias) {

	public static DisponibilidadEfectivaResponse from(DisponibilidadEfectivaView view) {
		return new DisponibilidadEfectivaResponse(
				view.membershipId(),
				view.consultorioId(),
				view.timezone(),
				view.dias().stream().map(DiaEfectivoResponse::from).toList());
	}

	/**
	 * Un dia de la ventana ya resuelto.
	 *
	 * <p><b>{@code esFeriado} y "sin franjas" son independientes.</b> Un feriado en una sede con
	 * {@code cierraPorFeriado = false} tiene franjas normales —"25 de diciembre, y este centro
	 * atiende" es informacion, no una contradiccion—, y un dia sin feriado puede quedar vacio por
	 * un cierre. La pantalla necesita los dos datos por separado.
	 */
	@Schema(description = "Un dia de la ventana, con sus franjas y con la razon si quedo vacio")
	public record DiaEfectivoResponse(

			@Schema(description = "Fecha local del dia", example = "2026-09-07")
			LocalDate fecha,

			@Schema(description = "Si la fecha es feriado del pais de la sede. Es el HECHO del "
					+ "calendario, no la decision de la sede: vale true aunque el centro atienda "
					+ "ese dia", example = "false")
			boolean esFeriado,

			@Schema(description = "Nombre del feriado, o null si no lo hay. Sin el, la pantalla "
					+ "dice \"cerrado\" y no puede decir por que",
					example = "Dia de la Independencia")
			String feriadoNombre,

			@Schema(
					description = """
							Por que el dia no tiene ni una franja. CINCO estados, y confundir \
							dos de ellos manda al administrador a buscar algo que no existe:

							FERIADO — es feriado del pais y la sede cierra los feriados.

							CIERRE — una excepcion de tipo CIERRE tapo el dia entero. reglaVacio \
							trae su id para linkearla.

							VINCULO — el vinculo del profesional NO estaba vigente ese dia: \
							todavia no se habia incorporado, o ya se habia desvinculado. NO es \
							lo mismo que "no atiende ese dia", y mostrarle el mismo cartel a los \
							dos hace que un admin busque un cierre que no existe.

							HORARIO_SEDE (0.74.0) — el profesional tenia horario, pero el horario \
							general de la sede no abre en ninguna de esas horas. Lo que hay que \
							revisar es el horario de la sede, no el del profesional.

							null — quinto estado, y no un descuido: o el dia TIENE franjas, o \
							quedo vacio porque NINGUNA regla lo abrio. Eso no es una regla que \
							lo afecte, es la ausencia de reglas, y se distingue mirando franjas.

							NO se declara como enum en el contrato aunque la lista sea cerrada: \
							un campo nullable con enum se publica en OpenAPI 3.1 como un tipo que \
							ADMITE el nulo junto a un enum que NO lo contiene, y un cliente \
							generado con validacion estricta rechazaria nuestra propia respuesta \
							la primera vez que un dia llegue sin razon. La enumeracion vive en \
							esta prosa a proposito.""",
					example = "CIERRE",
					nullable = true)
			String razonVacio,

			@Schema(description = "Id de la fila que vacio el dia, para que la pantalla pueda "
					+ "linkearla. null cuando la regla no tiene id que ofrecer: un feriado se "
					+ "resuelve por fecha, no por id de excepcion", example = "7")
			Long reglaVacio,

			@Schema(description = "Franjas de atencion del dia, ya en instantes UTC. Vacia si el "
					+ "dia no tiene atencion")
			List<FranjaResueltaResponse> franjas) {

		static DiaEfectivoResponse from(DisponibilidadEfectivaView.DiaEfectivo dia) {
			return new DiaEfectivoResponse(
					dia.fecha(),
					dia.esFeriado(),
					dia.feriadoNombre(),
					dia.razonVacio(),
					dia.reglaVacio(),
					dia.franjas().stream().map(FranjaResueltaResponse::from).toList());
		}
	}

	/**
	 * Una franja de atencion con sus instantes ya convertidos, mas la trazabilidad de la regla.
	 *
	 * <p><b>Viaja en instantes y no en horas de pared, a proposito.</b> La hora local ya esta en
	 * {@link BloqueResponse} y en {@link ExcepcionResponse}, que son las lecturas de las REGLAS;
	 * esta es la lectura del RESULTADO, y lo que consume un resultado de disponibilidad es una
	 * agenda, que compara instantes. El {@code timezone} viaja aparte para poder rotular.
	 */
	@Schema(description = "Una franja de atencion resuelta, con la regla que la produjo")
	public record FranjaResueltaResponse(

			@Schema(description = "Instante UTC de inicio", example = "2026-09-07T12:00:00Z")
			Instant desde,

			@Schema(description = "Instante UTC de fin, EXCLUSIVO. Una franja que llega al fin "
					+ "del dia trae aca el INICIO DEL DIA SIGUIENTE, no las 23:59:59.999999999",
					example = "2026-09-07T16:00:00Z")
			Instant hasta,

			@Schema(description = "Que regla produjo la franja. BLOQUE = el horario base; "
					+ "APERTURA = una excepcion que habilito atencion donde no la habia",
					example = "BLOQUE", allowableValues = {"BLOQUE", "APERTURA"})
			String origen,

			@Schema(
					description = """
							Por que la franja quedo mas corta que la regla que la produjo. TRES \
							estados, y el ultimo es el normal:

							CIERRE — una excepcion de tipo CIERRE le recorto un pedazo. Es lo que \
							explica por que la franja termina a las 11 y no a las 13.

							HORARIO_SEDE (0.74.0) — la franja excedia el horario general de la \
							sede y quedo solo la parte comun.

							null — nada la recorto: la franja es la regla entera.

							NO se declara como enum en el contrato aunque la lista sea cerrada. \
							Ver la nota de razonVacio: un campo nullable con enum se publica en \
							OpenAPI 3.1 con un tipo que admite el nulo y un enum que no lo \
							contiene, y un cliente generado con validacion estricta rechazaria \
							nuestra propia respuesta.""",
					example = "CIERRE",
					nullable = true)
			String recortadoPor,

			@Schema(description = "Id de la fila que produjo la franja —el bloque o la excepcion "
					+ "de apertura— para que la pantalla pueda linkearla", example = "501")
			Long reglaId) {

		static FranjaResueltaResponse from(DisponibilidadEfectivaView.FranjaResuelta franja) {
			return new FranjaResueltaResponse(
					franja.desde(),
					franja.hasta(),
					franja.origen(),
					franja.recortadoPor(),
					franja.reglaId());
		}
	}
}
