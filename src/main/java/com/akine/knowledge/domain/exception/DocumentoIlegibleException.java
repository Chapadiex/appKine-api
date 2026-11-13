package com.akine.knowledge.domain.exception;

/** El PDF no se puede leer: protegido, danado o fuera de los limites. */
public class DocumentoIlegibleException extends RuntimeException {

	private final String motivoLegible;

	public DocumentoIlegibleException(String motivoLegible) {
		super(motivoLegible);
		this.motivoLegible = motivoLegible;
	}

	public String getMotivoLegible() {
		return motivoLegible;
	}
}
