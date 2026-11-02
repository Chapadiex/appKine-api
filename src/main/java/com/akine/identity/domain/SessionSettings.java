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
 * @param refreshTtl          vigencia ABSOLUTA de la sesion desde el login. La rotacion hereda
 *                            este vencimiento y no lo corre hacia adelante: si lo extendiera, una
 *                            sesion robada que se refresca sola no venceria nunca. Doce horas
 *                            cubren la jornada de un consultorio, que es el caso real
 * @param refreshTtlRecordada vigencia ABSOLUTA de una sesion abierta con "mantener la sesion
 *                            iniciada" (DP-45, enmienda de ADR-0017). Misma regla: la rotacion
 *                            la hereda y no la corre. Nunca menor que {@code refreshTtl}: pedir
 *                            que la sesion se recuerde no puede darla MAS corta
 */
public record SessionSettings(Duration refreshTtl, Duration refreshTtlRecordada) {

	/** Vigencia por defecto: la jornada de un consultorio (ADR-0017). */
	public static final Duration REFRESH_TTL_POR_DEFECTO = Duration.ofHours(12);

	/** Vigencia por defecto de una sesion recordada (DP-45). */
	public static final Duration REFRESH_TTL_RECORDADA_POR_DEFECTO = Duration.ofDays(30);

	public SessionSettings {
		if (refreshTtl == null || refreshTtl.isZero() || refreshTtl.isNegative()) {
			throw new IllegalArgumentException(
					"La vigencia del refresh debe ser positiva: una sesion que nace vencida "
							+ "deja al usuario sin poder renovar nunca");
		}
		if (refreshTtlRecordada == null || refreshTtlRecordada.compareTo(refreshTtl) < 0) {
			throw new IllegalArgumentException(
					"La vigencia de una sesion recordada no puede ser menor que la de una sesion "
							+ "comun (akine.identity.session.refresh-ttl-recordada >= refresh-ttl)");
		}
	}

	/**
	 * Solo la vigencia comun; la recordada toma su default, o la comun si esta ya es mas larga.
	 */
	public SessionSettings(Duration refreshTtl) {
		this(refreshTtl, refreshTtl != null
				&& refreshTtl.compareTo(REFRESH_TTL_RECORDADA_POR_DEFECTO) > 0
				? refreshTtl
				: REFRESH_TTL_RECORDADA_POR_DEFECTO);
	}

	public static SessionSettings porDefecto() {
		return new SessionSettings(REFRESH_TTL_POR_DEFECTO, REFRESH_TTL_RECORDADA_POR_DEFECTO);
	}

	/** Vigencia absoluta de una sesion nueva, segun se haya pedido recordarla o no. */
	public Duration vigencia(boolean recordar) {
		return recordar ? refreshTtlRecordada : refreshTtl;
	}
}
