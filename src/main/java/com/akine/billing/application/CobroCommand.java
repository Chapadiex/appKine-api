package com.akine.billing.application;

import com.akine.billing.domain.MedioDePago;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que hay que decir para registrar un cobro.
 *
 * @param idempotencyKey clave del cliente para que un reintento no cobre dos veces. {@code null} la
 *                       desactiva, y es legitimo en una carga manual
 * @param anticipo       lo que queda a favor del paciente (F-3). Cero cuando todo se imputa
 * @param moneda         obligatoria solo sin imputaciones; con ellas sale de las deudas
 * @param turnoId        turno en cuya recepcion se toma el cobro como prepago (E-6). Exige un
 *                       anticipo puro. {@code null} en cualquier otro cobro
 */
public record CobroCommand(
		long personaId,
		BigDecimal total,
		List<MedioPedido> medios,
		List<ImputacionPedida> imputaciones,
		String idempotencyKey,
		BigDecimal anticipo,
		String moneda,
		Long turnoId) {

	public CobroCommand {
		medios = List.copyOf(medios);
		imputaciones = List.copyOf(imputaciones == null ? List.of() : imputaciones);
		anticipo = anticipo == null ? BigDecimal.ZERO : anticipo;
		moneda = moneda == null || moneda.isBlank() ? null : moneda.trim().toUpperCase();
	}

	/** Sin turno: la forma anterior a E-6. */
	public CobroCommand(
			long personaId,
			BigDecimal total,
			List<MedioPedido> medios,
			List<ImputacionPedida> imputaciones,
			String idempotencyKey,
			BigDecimal anticipo,
			String moneda) {

		this(personaId, total, medios, imputaciones, idempotencyKey, anticipo, moneda, null);
	}

	/** Un cobro que imputa todo su total: el de 07.02. */
	public CobroCommand(
			long personaId,
			BigDecimal total,
			List<MedioPedido> medios,
			List<ImputacionPedida> imputaciones,
			String idempotencyKey) {

		this(personaId, total, medios, imputaciones, idempotencyKey, BigDecimal.ZERO, null, null);
	}

	public boolean esAnticipoPuro() {
		return imputaciones.isEmpty();
	}

	/** El cobro se toma como prepago de un turno en su recepcion (E-6). */
	public boolean esPrepago() {
		return turnoId != null;
	}

	public record MedioPedido(MedioDePago medio, BigDecimal importe, String referencia) {
	}

	public record ImputacionPedida(long obligacionId, BigDecimal importe) {
	}

	/**
	 * Huella del pedido, para distinguir un reintento de un reuso de clave con otro contenido.
	 *
	 * <p>Mismo criterio que {@code scheduling.ReservaCommand}: la clave NO entra en el hash —lo que
	 * se compara es el CONTENIDO— y los campos van separados por {@code |} para que dos pedidos
	 * distintos no puedan producir la misma cadena.
	 *
	 * <p>El total se normaliza con {@code stripTrailingZeros}: {@code 8500} y {@code 8500.00} son el
	 * mismo importe, y sin normalizar un reintento que formatea distinto pareceria otro pedido y
	 * daria un 409 que el usuario no puede entender ni arreglar.
	 */
	public String huella(long consultorioId) {
		return Huella.de(textoCanonico(consultorioId));
	}

	private String textoCanonico(long consultorioId) {
		StringBuilder canonico = new StringBuilder()
				.append(consultorioId).append('|')
				.append(personaId).append('|')
				.append(total.stripTrailingZeros().toPlainString());
		for (ImputacionPedida imputacion : imputaciones) {
			canonico.append('|').append(imputacion.obligacionId())
					.append(':').append(imputacion.importe().stripTrailingZeros().toPlainString());
		}
		// F-3. Solo cuando vienen: asi la huella de un cobro sin anticipo es la misma que antes, y
		// un reintento de un cobro registrado antes de F-3 no se vuelve un 409.
		if (anticipo.signum() != 0) {
			canonico.append("|anticipo:").append(anticipo.stripTrailingZeros().toPlainString());
		}
		if (moneda != null) {
			canonico.append("|moneda:").append(moneda);
		}
		// E-6, mismo criterio: solo cuando viene.
		if (turnoId != null) {
			canonico.append("|turno:").append(turnoId);
		}
		return canonico.toString();
	}
}
