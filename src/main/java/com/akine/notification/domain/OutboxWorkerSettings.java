package com.akine.notification.domain;

import java.time.Duration;

/**
 * Parametros operativos del worker, como valor del dominio.
 *
 * <p>Existe para que {@code application} no tenga que inyectar la clase
 * {@code @ConfigurationProperties}, que vive en {@code infrastructure} y a la que ninguna capa
 * puede acceder. La configuracion se lee en el borde y entra al negocio como este valor.
 *
 * @param tamanoLote     filas por tick. Acotado: una transaccion de claim que abarque toda la
 *                       cola mantiene bloqueadas demasiadas filas demasiado tiempo
 * @param duracionLease  cuanto puede estar una fila en PROCESANDO antes de considerarse
 *                       huerfana. Debe ser holgadamente mayor que el timeout del envio, o el
 *                       worker se roba a si mismo filas que todavia esta procesando
 * @param backoff        politica de reintentos
 */
public record OutboxWorkerSettings(
		int tamanoLote, Duration duracionLease, RetryBackoffPolicy backoff) {

	public OutboxWorkerSettings {
		if (tamanoLote < 1) {
			throw new IllegalArgumentException("El tamano del lote debe ser al menos 1");
		}
		if (duracionLease == null || duracionLease.isNegative() || duracionLease.isZero()) {
			throw new IllegalArgumentException("La duracion del lease debe ser positiva");
		}
		if (backoff == null) {
			throw new IllegalArgumentException("El worker necesita una politica de reintentos");
		}
	}

	/** Valores por defecto: 20 filas por tick, lease de 5 minutos, backoff por defecto. */
	public static OutboxWorkerSettings porDefecto() {
		return new OutboxWorkerSettings(20, Duration.ofMinutes(5), RetryBackoffPolicy.porDefecto());
	}
}
