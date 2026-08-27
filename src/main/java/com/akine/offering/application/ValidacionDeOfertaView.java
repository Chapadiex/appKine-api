package com.akine.offering.application;

import java.util.List;

/**
 * Si una combinacion de oferta, profesional y espacio puede prestarse, y <b>por que no</b>.
 *
 * <p>Responde la mitad calculable de RF-M05-008 y RF-M04-009. La otra mitad —reservar de verdad—
 * es de la etapa de agenda, que va a consumir exactamente esto.
 *
 * <h2>Por que devuelve los motivos y no un booleano</h2>
 *
 * <p>Un {@code false} pelado deja a la pantalla adivinando: puede ser que la oferta este dada de
 * baja, que este fuera de vigencia, que el profesional no este habilitado, que el espacio no este
 * en servicio o que no entre la gente. Son cinco arreglos distintos y el usuario necesita saber
 * cual. El endpoint se llama "validacion explicable" en el plan por esta razon.
 *
 * <p>Se devuelven <b>todos</b> los motivos que fallan, no el primero: si al profesional le falta
 * habilitacion Y el espacio esta fuera de servicio, arreglar uno solo no alcanza y decirlo de a
 * uno obliga a dos vueltas.
 */
public record ValidacionDeOfertaView(
		long ofertaId,
		Long membershipId,
		Long espacioId,
		boolean puedePrestarse,
		int capacidadEfectiva,
		List<MotivoDeRechazo> motivos) {

	/**
	 * Una condicion que no se cumple.
	 *
	 * @param codigo    estable, para que la pantalla ramifique sin leer prosa
	 * @param detalle   texto para mostrar, que nombra el recurso concreto
	 */
	public record MotivoDeRechazo(String codigo, String detalle) {

		/** La oferta esta dada de baja: no admite reservas nuevas (RN-M27-007). */
		public static final String OFERTA_INACTIVA = "oferta-inactiva";

		/** La oferta existe y hoy cae fuera de su ventana de vigencia. */
		public static final String OFERTA_FUERA_DE_VIGENCIA = "oferta-fuera-de-vigencia";

		/** La oferta esta restringida y este profesional no esta entre los habilitados. */
		public static final String PROFESIONAL_NO_HABILITADO = "profesional-no-habilitado";

		/** Esta habilitado, pero su habilitacion no rige hoy. */
		public static final String HABILITACION_FUERA_DE_VIGENCIA =
				"habilitacion-fuera-de-vigencia";

		/** La membership dejo de habilitarlo a atender en esa sede: se desvinculo o vencio. */
		public static final String VINCULO_NO_VIGENTE = "vinculo-no-vigente";

		/** La oferta esta restringida y este espacio no esta entre los habilitados. */
		public static final String ESPACIO_NO_HABILITADO = "espacio-no-habilitado";

		/** El espacio se dio de baja o esta fuera de su ventana en {@code resource}. */
		public static final String ESPACIO_FUERA_DE_SERVICIO = "espacio-fuera-de-servicio";

		/** El espacio no admite la cantidad de gente que la oferta declara (RF-M04-009). */
		public static final String CAPACIDAD_INSUFICIENTE = "capacidad-insuficiente";
	}
}
