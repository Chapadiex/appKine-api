package com.akine.organization.spi;

import java.util.Set;

/**
 * Alta de un centro desde la plataforma (18/11/2026, docs/diseno/AKINE-landing-y-ofertas.md §6).
 *
 * <p>A diferencia del onboarding compuesto (ADR-0008), no crea membership: el duenio todavia no
 * tiene cuenta. Lo invita {@code identity} en la misma transaccion, y la membership nace al
 * aceptar la invitacion.
 */
public interface AltaDeCentro {

	/** Dias de la prueba gratis. */
	int DIAS_DE_PRUEBA = 30;

	CentroProvisto provisionar(Comando comando);

	/** Valida plan, servicios y slug antes de tocar nada; lanza lo mismo que {@link #provisionar}. */
	void validar(Comando comando);

	/**
	 * @param prueba       con {@code true} la suscripcion nace en PRUEBA por {@link #DIAS_DE_PRUEBA} dias
	 * @param solicitudId  pedido de la landing que este alta resuelve, o {@code null}
	 */
	record Comando(
			String nombreCentro,
			String nombrePrimeraSede,
			String zonaHoraria,
			String planCode,
			Set<Servicio> servicios,
			Set<Adicional> adicionales,
			boolean prueba,
			Long solicitudId,
			long actorAccountId) {

		public Comando {
			if (nombreCentro == null || nombreCentro.isBlank()) {
				throw new IllegalArgumentException("Indicá el nombre del centro");
			}
			if (servicios == null || servicios.isEmpty()) {
				throw new IllegalArgumentException("Elegí al menos un servicio");
			}
			servicios = Set.copyOf(servicios);
			adicionales = adicionales == null ? Set.of() : Set.copyOf(adicionales);
		}
	}

	record CentroProvisto(long organizationId, long consultorioId) {
	}
}
