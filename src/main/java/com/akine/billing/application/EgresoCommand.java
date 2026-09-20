package com.akine.billing.application;

import com.akine.billing.domain.CategoriaEgreso;
import com.akine.billing.domain.TipoBeneficiario;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;

/**
 * Lo que hay que decir para cargar o corregir un egreso (RF-M22-001).
 *
 * @param beneficiarioMembershipId obligatorio con {@code COLABORADOR}, prohibido con {@code EXTERNO}
 * @param beneficiarioNombre       se ignora con {@code COLABORADOR}: ahi el nombre lo resuelve el
 *                                 servidor y se congela, para que la liquidacion de septiembre se
 *                                 pueda leer en marzo sin depender de nada
 * @param comprobanteTipo          opcional en el borrador y <b>obligatorio para confirmar</b>: la
 *                                 liquidacion se arma antes de tener la factura en la mano
 * @param idempotencyKey           clave del cliente para que un doble click no cargue dos veces la
 *                                 misma liquidacion. {@code null} la desactiva
 */
public record EgresoCommand(
		CategoriaEgreso categoria,
		TipoBeneficiario tipoBeneficiario,
		Long beneficiarioMembershipId,
		String beneficiarioNombre,
		String beneficiarioDocumento,
		LocalDate periodoDesde,
		LocalDate periodoHasta,
		String concepto,
		BigDecimal importeTotal,
		String moneda,
		String comprobanteTipo,
		String comprobanteNumero,
		LocalDate comprobanteFecha,
		String idempotencyKey) {

	/**
	 * Huella del pedido, para distinguir un reintento de un reuso de clave con otro contenido.
	 *
	 * <p>Mismo criterio que {@code CobroCommand} y {@code MovimientoManualCommand}: la clave
	 * <b>no</b> entra en el hash —lo que se compara es el CONTENIDO— y los campos van separados por
	 * {@code |} para que dos pedidos distintos no puedan producir la misma cadena.
	 *
	 * <p>El importe se normaliza con {@code stripTrailingZeros}: {@code 850} y {@code 850.00} son el
	 * mismo importe, y sin normalizar un reintento que formatea distinto pareceria otro pedido y
	 * daria un 409 que el usuario no puede entender ni arreglar.
	 */
	public String huella(long consultorioId) {
		String canonico = consultorioId + "|" + categoria + "|" + tipoBeneficiario + "|"
				+ beneficiarioMembershipId + "|" + beneficiarioDocumento + "|"
				+ periodoDesde + "|" + periodoHasta + "|" + concepto + "|"
				+ importeTotal.stripTrailingZeros().toPlainString() + "|" + moneda + "|"
				+ comprobanteTipo + "|" + comprobanteNumero;
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonico.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException imposible) {
			throw new IllegalStateException("SHA-256 no disponible en esta JVM", imposible);
		}
	}
}
