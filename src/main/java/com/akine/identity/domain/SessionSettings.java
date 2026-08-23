package com.akine.identity.domain;

import java.time.Duration;

/**
 * Parametros de vigencia de una sesion de refresh (ADR-0017).
 *
 * <p>Vive en {@code domain} y no en {@code identity.infrastructure.IdentityProperties} porque
 * {@code application} no puede depender de {@code infrastructure} —ArchUnit lo verifica— y la
 * regla de negocio "una sesion dura N y la rotacion no la extiende" es del dominio, no de la
 * configuracion. {@code IdentityConfig} construye esta pieza a partir de las propiedades. Es el
 * mismo patron que {@code OutboxWorkerSettings} en {@code notification}.
 *
 * @param refreshTtl vigencia ABSOLUTA de la sesion desde el login. La rotacion hereda este
 *                   vencimiento y no lo corre hacia adelante: si lo extendiera, una sesion
 *                   robada que se refresca sola no venceria nunca. Doce horas cubren la jornada
 *                   de un consultorio, que es el caso real
 */
public record SessionSettings(Duration refreshTtl) {

	/** Vigencia por defecto: la jornada de un consultorio (ADR-0017). */
	public static final Duration REFRESH_TTL_POR_DEFECTO = Duration.ofHours(12);

	public SessionSettings {
		if (refreshTtl == null || refreshTtl.isZero() || refreshTtl.isNegative()) {
			throw new IllegalArgumentException(
					"La vigencia del refresh debe ser positiva: una sesion que nace vencida "
							+ "deja al usuario sin poder renovar nunca");
		}
	}

	public static SessionSettings porDefecto() {
		return new SessionSettings(REFRESH_TTL_POR_DEFECTO);
	}
}
