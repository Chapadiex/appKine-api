package com.akine.scheduling.application;

import com.akine.scheduling.domain.OrigenDeTurno;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Lo que hay que decir para reservar un turno.
 *
 * @param inicio         instante de inicio del slot elegido, tal como lo devolvio la agenda
 * @param profesionalId  membership del profesional. {@code null} solo si la oferta no lo requiere
 * @param idempotencyKey clave del cliente para que un reintento no cree un segundo turno.
 *                       {@code null} la desactiva, y eso es legitimo: una reserva hecha a mano
 *                       desde una pantalla administrativa no siempre tiene de donde sacarla
 * @param espacioId      box elegido a mano, o {@code null} para que lo asigne el sistema
 * @param casoId         caso clinico para el que se reserva, o {@code null}
 * @param planId         plan para el que se reserva, o {@code null}
 * @param origen         desde donde se inicio. Solo trazabilidad: no entra en ninguna regla
 * @param sobreturnoMotivo por que se autoriza ocupar un horario tomado. {@code null} = reserva comun
 */
public record ReservaCommand(
		long personaId,
		Instant inicio,
		Long profesionalId,
		String idempotencyKey,
		Long coberturaId,
		Long espacioId,
		Long casoId,
		Long planId,
		OrigenDeTurno origen,
		String sobreturnoMotivo) {

	public ReservaCommand(
			long personaId, Instant inicio, Long profesionalId, String idempotencyKey, Long coberturaId) {
		this(personaId, inicio, profesionalId, idempotencyKey, coberturaId, null, null, null, null, null);
	}

	public boolean esSobreturno() {
		return sobreturnoMotivo != null && !sobreturnoMotivo.isBlank();
	}

	/** Reserva sin cobertura elegida: la forma anterior a AKINE-05.02, que siguen usando las series. */
	public ReservaCommand(long personaId, Instant inicio, Long profesionalId, String idempotencyKey) {
		this(personaId, inicio, profesionalId, idempotencyKey, null);
	}

	/**
	 * Huella del pedido, para distinguir un reintento de un reuso de clave con otro contenido.
	 *
	 * <p><b>Por que hace falta ademas de la clave.</b> Con la clave sola, un cliente que reusa la
	 * misma clave para reservar OTRO turno recibe en silencio el turno anterior y cree que reservo
	 * el nuevo. Con el hash, ese caso es un 409 explicito. AKINE-01.01 dejo ese escenario diferido
	 * —el 7b— porque {@code onboarding_registro} no guarda el hash; aca se guarda desde el
	 * principio para no repetir la deuda.
	 *
	 * <p>La clave NO entra en el hash: lo que se compara es el CONTENIDO del pedido, y meter la
	 * clave adentro haria que dos pedidos identicos con claves distintas tuvieran hashes distintos,
	 * que es exactamente lo contrario de lo que se quiere medir.
	 *
	 * <p>El separador {@code |} entre campos no es decoracion: sin el, {@code personaId=1} con
	 * {@code profesionalId=23} y {@code personaId=12} con {@code profesionalId=3} producirian la
	 * misma cadena.
	 */
	public String huella(long consultorioId, long ofertaId) {
		String canonico = consultorioId + "|" + ofertaId + "|" + personaId + "|" + inicio
				+ "|" + profesionalId
				// Solo si se eligio: asi la huella de un pedido sin cobertura es la misma que antes de
				// AKINE-05.02 y el reintento de una clave vieja no se vuelve un 409 por el despliegue.
				+ (coberturaId == null ? "" : "|c" + coberturaId)
				// Mismo criterio: lo nuevo solo entra si viene, para no volver 409 un reintento viejo.
				+ (espacioId == null ? "" : "|e" + espacioId)
				+ (casoId == null ? "" : "|k" + casoId)
				+ (planId == null ? "" : "|p" + planId)
				+ (esSobreturno() ? "|s" : "");
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonico.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException imposible) {
			// SHA-256 es obligatorio en toda JVM desde Java 1.4. Si falta, el problema no es este
			// metodo y tragarse la excepcion solo lo escondera.
			throw new IllegalStateException("SHA-256 no disponible en esta JVM", imposible);
		}
	}
}
