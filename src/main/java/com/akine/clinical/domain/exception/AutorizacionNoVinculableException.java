package com.akine.clinical.domain.exception;

/**
 * La autorizacion que se quiso atar al item del plan no sirve (409). RF-M11-007, AKINE-04.05.
 *
 * <h2>Por que esta excepcion es de {@code clinical} y no de {@code person}</h2>
 *
 * <p>{@code person} ya tiene {@code AutorizacionVencidaException} y
 * {@code AutorizacionSinSaldoException}, y <b>no se pueden reusar</b>: viven en
 * {@code person.domain}, que ArchUnit prohibe importar desde otro modulo
 * ({@code modulos_solo_se_alcanzan_por_su_spi}). Lo que si se comparte es el {@code ProblemType},
 * que vive en {@code platform.spi}, y por eso el cliente ve <b>el mismo</b>
 * {@code autorizacion-vencida} venga de donde venga. Es la regla que 01.01 dejo fijada: las
 * excepciones de un modulo se mapean en su propio advice.
 *
 * <p>{@code motivo} usa el mismo vocabulario cerrado que publica
 * {@code GET /personas/&#123;id&#125;/autorizaciones/elegibles} —{@code VENCIDA}, {@code AGOTADA},
 * {@code AUN_NO_VIGENTE}, {@code NO_APROBADA}—, y llega tal cual desde
 * {@code person.spi.AutorizacionSnapshot}. El mismo desenlace no puede llamarse distinto segun por
 * que endpoint se lo mire.
 */
public class AutorizacionNoVinculableException extends RuntimeException {

	/** Sin saldo. Se mapea a {@code autorizacion-sin-saldo}. */
	public static final String AGOTADA = "AGOTADA";

	private final long autorizacionId;
	private final String motivo;

	public AutorizacionNoVinculableException(long autorizacionId, String motivo) {
		super("La autorizacion " + autorizacionId + " no se puede vincular: " + motivo);
		this.autorizacionId = autorizacionId;
		this.motivo = motivo;
	}

	public long getAutorizacionId() {
		return autorizacionId;
	}

	public String getMotivo() {
		return motivo;
	}

	/** Sin saldo va a {@code autorizacion-sin-saldo}; el resto a {@code autorizacion-vencida}. */
	public boolean esFaltaDeSaldo() {
		return AGOTADA.equals(motivo);
	}
}
