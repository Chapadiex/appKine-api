package com.akine.person.application;

import com.akine.person.domain.Autorizacion;

import java.time.LocalDate;

/**
 * Una autorizacion del paciente con el veredicto de si sirve ese dia, y por que no (RF-M17-007).
 *
 * <p>Es la mitad "explicable" del selector que el enunciado pide: la pantalla necesita mostrar
 * <b>todas</b> las candidatas y no solo las que sirven, porque decirle al mostrador "no hay
 * ninguna" sin decirle que una vencio anteayer y otra se agoto lo deja sin nada que hacer.
 *
 * <p>Por eso {@code habilita} viaja junto con {@code motivoNoElegible}, y este ultimo es
 * {@code null} exactamente cuando la autorizacion sirve. Los motivos son un vocabulario cerrado
 * —no prosa— para que el frontend pueda agrupar y traducir.
 *
 * <p><b>Lo que esta lista NO hace es filtrar por practica.</b> Una sesion declara su oferta y una
 * autorizacion es por practica; no existe tabla puente entre las dos y V24 la dejo afuera a
 * proposito. Quien elige entre varias candidatas es quien conoce el caso, y por eso se devuelven
 * todas con su practica a la vista. Unificar las dos granularidades es 06.04.
 *
 * @param motivoNoElegible {@code VENCIDA}, {@code AGOTADA}, {@code AUN_NO_VIGENTE} o
 *                         {@code NO_APROBADA}. {@code null} si habilita
 */
public record AutorizacionElegibleView(
		long id,
		long personaId,
		long coberturaId,
		long practicaId,
		String numero,
		String estadoAutorizacion,
		Integer cantidadAutorizada,
		int cantidadConsumida,
		Integer saldo,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		boolean habilita,
		String motivoNoElegible,
		Long diasParaVencer,
		Long convenioId,
		String convenioNombre) {

	/** Vencio: tiene fin declarado y ya paso. */
	public static final String VENCIDA = "VENCIDA";

	/** Sin saldo. Una autorizacion sin tope declarado nunca cae aca. */
	public static final String AGOTADA = "AGOTADA";

	/** Aprobada y vigente mas adelante: todavia no empieza. */
	public static final String AUN_NO_VIGENTE = "AUN_NO_VIGENTE";

	/** PENDIENTE, OBSERVADA o RECHAZADA: el financiador todavia no la otorgo. */
	public static final String NO_APROBADA = "NO_APROBADA";

	public static AutorizacionElegibleView de(Autorizacion autorizacion, LocalDate fecha) {
		return new AutorizacionElegibleView(
				autorizacion.getId(),
				autorizacion.getPersonaId(),
				autorizacion.getCoberturaId(),
				autorizacion.getPracticaId(),
				autorizacion.getNumero(),
				autorizacion.getEstado().name(),
				autorizacion.getCantidadAutorizada(),
				autorizacion.getCantidadConsumida(),
				autorizacion.saldo(),
				autorizacion.getVigenciaDesde(),
				autorizacion.getVigenciaHasta(),
				autorizacion.habilitaEl(fecha),
				motivoNoElegible(autorizacion, fecha),
				autorizacion.diasParaVencer(fecha),
				autorizacion.getConvenioId(),
				autorizacion.getConvenioNombre());
	}

	/**
	 * El primer motivo por el que no sirve, en orden de lo que el mostrador puede hacer al
	 * respecto.
	 *
	 * <p>El orden importa y no es arbitrario: una autorizacion vencida Y agotada se informa como
	 * vencida, porque pedir la renovacion es la accion que corresponde y ampliar la cantidad de
	 * una vencida no sirve de nada.
	 */
	private static String motivoNoElegible(Autorizacion autorizacion, LocalDate fecha) {
		if (autorizacion.habilitaEl(fecha)) {
			return null;
		}
		if (autorizacion.vencidaEl(fecha)) {
			return VENCIDA;
		}
		if (!autorizacion.getEstado().habilita()) {
			return NO_APROBADA;
		}
		if (!autorizacion.vigencia().cubre(fecha)) {
			return AUN_NO_VIGENTE;
		}
		return AGOTADA;
	}
}
