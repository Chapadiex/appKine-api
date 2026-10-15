package com.akine.activity.application;

import com.akine.activity.domain.ClaseProgramada;

import java.time.Instant;

/**
 * Una clase, tal como sale del backend.
 *
 * <p><b>No lleva participantes</b>, ni nombres ni ids de persona: la seguridad de la etapa exige
 * vista sin lista de participantes, y en 08.01 todavia no existen. La decision queda escrita aca
 * para que 08.02 no la agregue por inercia a esta proyeccion y publique PHI en la grilla.
 *
 * @param capacidadEfectiva minimo entre la capacidad propia, la de la oferta y la del espacio
 *                          (RN-M28-002). <b>Se calcula al leer</b> y no se persiste: si se
 *                          guardara, cambiar el box dejaria clases prometiendo lugares que ya no
 *                          existen
 * @param ocupados          cuantos lugares consumen inscripciones: la columna que otorga el lugar
 *                          (08.02)
 * @param ofertaNombre      nombre comercial de la oferta, leido al proyectar. {@code null} si la
 *                          oferta ya no resuelve
 * @param profesionalNombre nombre de la cuenta del profesional. {@code null} si la clase no tiene
 *                          profesional o su membership ya no resuelve. Solo el nombre: nunca el
 *                          correo
 * @param espacioNombre     nombre del box. {@code null} si la clase no tiene box o ya no resuelve
 */
public record ClaseView(
		long id,
		long consultorioId,
		long ofertaId,
		String titulo,
		Instant inicio,
		Instant fin,
		String estado,
		Long profesionalId,
		Long espacioId,
		int capacidad,
		int capacidadEfectiva,
		int ocupados,
		String motivoCancelacion,
		Instant canceladoEn,
		long version,
		String ofertaNombre,
		String profesionalNombre,
		String espacioNombre) {

	/**
	 * Arma la vista con los nombres ya resueltos. Quien proyecta es {@link ProyeccionDeClase}, que
	 * los pide agrupados para no hacer una consulta por fila.
	 */
	public static ClaseView de(
			ClaseProgramada clase,
			int capacidadEfectiva,
			int ocupados,
			String ofertaNombre,
			String profesionalNombre,
			String espacioNombre) {
		return new ClaseView(
				clase.getId(),
				clase.getConsultorioId(),
				clase.getOfertaId(),
				clase.getTitulo(),
				clase.getInicio(),
				clase.getFin(),
				clase.getEstado().name(),
				clase.getProfesionalMembershipId(),
				clase.getEspacioId(),
				clase.getCapacidad(),
				capacidadEfectiva,
				ocupados,
				clase.getMotivoCancelacion(),
				clase.getCanceladoEn(),
				clase.getVersion(),
				ofertaNombre,
				profesionalNombre,
				espacioNombre);
	}
}
