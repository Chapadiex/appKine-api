package com.akine.scheduling.domain;

/**
 * Por que un dia no ofrece ni un slot.
 *
 * <p>El criterio de aceptacion de la etapa pide slots deterministas y que no se ofrezcan recursos
 * invalidos. Pero la mitad que se olvida es la contraria: <b>cuando no hay nada que ofrecer, hay
 * que decir por que</b>. Un dia en blanco sin motivo es indistinguible de un error del sistema, y
 * es exactamente donde el recepcionista llama por telefono a preguntar.
 *
 * <p>Los cuatro primeros son de la disponibilidad y llegan tal cual desde
 * {@code OrigenFranja}/{@code razonVacio} de M05: el motor no los reinterpreta, los propaga. Los
 * demas son propios de la agenda y no existen en M05, porque dependen de la oferta.
 */
public enum MotivoSinSlots {

	/** Feriado con la sede cerrando por feriado. */
	FERIADO,

	/** Una excepcion de cierre vacio el dia. */
	CIERRE,

	/** El profesional no estaba vinculado a la sede ese dia. */
	VINCULO,

	/**
	 * Nadie cargo horario para ese dia.
	 *
	 * <p>Es distinto de {@link #CIERRE}: aca no hay ninguna regla que lo afecte, hay ausencia de
	 * reglas. M05 lo representa con {@code razonVacio == null} y franjas vacias; la agenda no
	 * puede darse ese lujo porque {@code null} ya significa "el dia SI tiene slots".
	 */
	SIN_HORARIO,

	/**
	 * La oferta no estaba vigente ese dia, o esta dada de baja.
	 *
	 * <p>Se evalua dia por dia contra {@code vigenciaDesde}/{@code vigenciaHasta}, no contra la
	 * ventana: una oferta que vence el 15 no puede seguir ofreciendo turnos el 20, que es el mismo
	 * error que el ruling R13 corrigio para la vigencia del vinculo.
	 */
	OFERTA_NO_VIGENTE,

	/**
	 * La oferta requiere profesional y no tiene ninguno habilitado, o ninguno de los habilitados
	 * tiene disponibilidad ese dia.
	 */
	SIN_PROFESIONAL,

	/**
	 * La oferta requiere espacio y no tiene ninguno habilitado en servicio ese dia.
	 *
	 * <p>Un espacio fuera de servicio o con vigencia vencida no habilita nada: RN-M04 y la
	 * validacion de habilitaciones de 02.07.
	 */
	SIN_ESPACIO,

	/**
	 * Habia franjas y habia recursos, pero ninguna franja alcanzaba para un slot completo.
	 *
	 * <p>El caso real: bloques de 30 minutos con una oferta de 45. Sin este motivo el dia sale
	 * vacio "porque si", y el administrador no tiene forma de saber que lo unico que le falta es
	 * ampliar el bloque.
	 */
	FRANJA_MAS_CORTA_QUE_LA_OFERTA,

	/** Habia slots y estan todos tomados. Lo produce 05.02; hoy no puede ocurrir. */
	COMPLETO
}
