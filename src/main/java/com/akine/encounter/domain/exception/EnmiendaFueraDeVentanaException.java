package com.akine.encounter.domain.exception;

import com.akine.platform.spi.problem.ConTipoDeProblema;
import com.akine.platform.spi.problem.ProblemType;

import java.time.Instant;

/**
 * La sesion se cerro hace mas de lo que la organizacion admite para enmendarla. <b>409</b>.
 *
 * <p>U-25 (DP-43): la ventana se cuenta desde el cierre y la fija la organizacion; sin politica
 * propia rigen 30 dias. Viaja con las tres fechas que la pantalla necesita para explicarlo.
 */
public class EnmiendaFueraDeVentanaException extends RuntimeException implements ConTipoDeProblema {

	private final long sesionId;
	private final Instant cerradaEn;
	private final int ventanaDias;
	private final Instant enmendableHasta;

	public EnmiendaFueraDeVentanaException(
			long sesionId, Instant cerradaEn, int ventanaDias, Instant enmendableHasta) {
		super("La sesion " + sesionId + " se cerro el " + cerradaEn + " y solo se podia enmendar "
				+ "hasta el " + enmendableHasta + " (" + ventanaDias + " dias desde el cierre)");
		this.sesionId = sesionId;
		this.cerradaEn = cerradaEn;
		this.ventanaDias = ventanaDias;
		this.enmendableHasta = enmendableHasta;
	}

	@Override
	public ProblemType tipoDeProblema() {
		return ProblemType.ENMIENDA_FUERA_DE_VENTANA;
	}

	public long getSesionId() {
		return sesionId;
	}

	public Instant getCerradaEn() {
		return cerradaEn;
	}

	public int getVentanaDias() {
		return ventanaDias;
	}

	public Instant getEnmendableHasta() {
		return enmendableHasta;
	}
}
