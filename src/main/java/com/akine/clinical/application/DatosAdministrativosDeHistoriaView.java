package com.akine.clinical.application;

import java.time.Instant;
import java.util.List;

/**
 * Lo que de una Historia Clinica puede ver quien NO tiene {@code hc:read}: el "Limitado" del
 * {@code ADMINISTRATIVO} en la fila "Ver Historia Clinica" de la matriz §32, que
 * {@code docs/seguridad/matriz-permisos-minima.md} §4 define como <b>solo metadatos
 * administrativos —existencia, fechas, profesional tratante—, nunca contenido clinico</b>.
 *
 * <p>Por eso este tipo no lleva resumen, antecedentes, diagnostico ni objetivo terapeutico, y
 * no reusa {@link HistoriaClinicaView} ni {@link CasoClinicoView} con campos opcionales: un tipo
 * aparte hace imposible que un campo clinico se cuele por descuido (el mismo criterio que
 * {@code TurnoDelDiaView} en 05.04). La cobertura no viaja aca: la expone la persona en el
 * Paciente 360, con el mismo {@code paciente:read}.
 *
 * @param historiaClinicaId nulo si la persona todavia no tiene historia
 * @param abiertaEn         nulo si la persona todavia no tiene historia
 * @param casos             activos primero y despues cerrados, mas recientes primero
 */
public record DatosAdministrativosDeHistoriaView(
		long personaId,
		boolean tieneHistoriaClinica,
		Long historiaClinicaId,
		Instant abiertaEn,
		List<Caso> casos) {

	/**
	 * Un caso sin su contenido clinico: cuando se abrio, si sigue activo y quien lo trata.
	 *
	 * @param equipo los profesionales VIGENTES del caso
	 */
	public record Caso(
			long id,
			int numeroCaso,
			String estado,
			long ofertaId,
			long ofertaConsultorioId,
			Instant abiertoEn,
			Instant cerradoEn,
			List<CasoProfesionalView> equipo) {
	}
}
