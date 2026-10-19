package com.akine.encounter.domain.exception;

import com.akine.platform.spi.problem.ConTipoDeProblema;
import com.akine.platform.spi.problem.ProblemType;

/**
 * La oferta de la atencion exige Caso Clinico y la atencion no tiene ninguno. <b>409</b>.
 *
 * <p>RF-M10-007 y U-09 (DP-43): el Caso es obligatorio <b>solo</b> en las ofertas con
 * {@code requiereCasoClinico = true}; en las demas sigue opcional, y una oferta que no lo exige no
 * pide un Caso artificial (paso 7 del RF). Lo emiten iniciar sin {@code casoId}, quitar el Caso de
 * una atencion abierta y cerrar una atencion abierta sin Caso.
 *
 * <p>409 y no 400: el mismo pedido es valido en otra oferta. Lo que lo impide es la configuracion
 * de esta, y la accion que corresponde es elegir o abrir un Caso.
 */
public class CasoClinicoRequeridoException extends RuntimeException implements ConTipoDeProblema {

	private final long ofertaId;

	public CasoClinicoRequeridoException(long ofertaId) {
		super("La oferta " + ofertaId + " exige Caso Clinico: elegi un caso activo del paciente o "
				+ "abri uno antes de continuar");
		this.ofertaId = ofertaId;
	}

	@Override
	public ProblemType tipoDeProblema() {
		return ProblemType.CASO_CLINICO_REQUERIDO;
	}

	public long getOfertaId() {
		return ofertaId;
	}
}
