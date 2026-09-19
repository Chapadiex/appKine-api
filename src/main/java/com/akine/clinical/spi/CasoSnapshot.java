package com.akine.clinical.spi;

/**
 * Lo que otro modulo necesita saber de un Caso Clinico, sin poder tocar su entity.
 *
 * <p><b>No viaja el diagnostico presuntivo, ni el objetivo terapeutico, ni el equipo.</b> Eso es
 * contenido clinico y se lee con {@code hc:read}, acceso justificado y auditoria, por
 * {@code CasoClinicoService}. Un record que lo trajera de arrastre convertiria a cualquier
 * consumidor del spi en una via de lectura clinica sin auditar — es exactamente el mismo
 * argumento, y la misma linea, que {@link HistoriaClinicaSnapshot}.
 *
 * <p>Lo que viaja es lo que un modulo necesita para <b>decidir a que colgarse</b>: que el caso
 * existe, de que historia es, si sigue activo y como se lo llama —{@link #numeroCaso}, que es lo
 * que un profesional dice en voz alta y lo que una pantalla muestra al lado de la sesion—.
 *
 * @param activo {@code false} significa cerrado, no borrado: un caso cerrado se sigue leyendo
 *               entero. Lo que no admite son sesiones nuevas
 */
public record CasoSnapshot(
		long id,
		long organizationId,
		long historiaClinicaId,
		int numeroCaso,
		boolean activo) {

	/** {@code true} si el caso cuelga de esa historia. Lo usa quien ya resolvio la historia. */
	public boolean perteneceAHistoria(long historiaClinicaId) {
		return this.historiaClinicaId == historiaClinicaId;
	}
}
