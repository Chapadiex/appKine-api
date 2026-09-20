package com.akine.activity.api.dto;

import com.akine.activity.domain.ResultadoAsistencia;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Los tres resultados posibles, en el borde HTTP.
 *
 * <p>Existe como enum propio de {@code api} para que el contrato publique una lista cerrada y el
 * cliente generado no reciba un {@code String} libre. El mapeo al dominio es uno a uno y vive aca,
 * en un solo lugar.
 */
@Schema(
		name = "ResultadoAsistencia",
		description = "PRESENTE y PRESENTE_TARDE dejan la inscripcion en ASISTIO; AUSENTE la deja "
				+ "en AUSENTE. **Los cuatro estados consumen cupo**, asi que marcar no mueve la "
				+ "ocupacion de la clase.")
public enum ResultadoAsistenciaApi {

	PRESENTE(ResultadoAsistencia.PRESENTE),
	PRESENTE_TARDE(ResultadoAsistencia.PRESENTE_TARDE),
	AUSENTE(ResultadoAsistencia.AUSENTE);

	private final ResultadoAsistencia dominio;

	ResultadoAsistenciaApi(ResultadoAsistencia dominio) {
		this.dominio = dominio;
	}

	public ResultadoAsistencia aDominio() {
		return dominio;
	}
}
