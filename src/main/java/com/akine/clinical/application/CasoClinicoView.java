package com.akine.clinical.application;

import com.akine.clinical.domain.CasoClinico;

import java.time.Instant;
import java.util.List;

/**
 * Un Caso Clinico con su equipo, tal como sale de la aplicacion.
 *
 * <p>Trae el equipo <b>vigente</b> y no el historico completo: el historico se lee con el historial
 * del caso, que es otra operacion. Mostrar los dos juntos en la ficha haria que un profesional que
 * dejo el equipo hace un año aparezca al lado del que atiende hoy.
 *
 * <p>{@code version} es la que el cliente tiene que devolver al editar. Dos profesionales del
 * equipo editando el objetivo del mismo caso son el caso normal, no el raro (RF-M10-004).
 *
 * @param numeroCaso correlativo <b>dentro de la historia</b>. No es el id, y no es el numero de
 *                   sesion: "el caso 2" y "la sesion 2" cuentan cosas distintas
 */
public record CasoClinicoView(
		long id,
		long historiaClinicaId,
		int numeroCaso,
		long ofertaId,
		long ofertaConsultorioId,
		String ofertaNombre,
		String diagnosticoPresuntivo,
		String objetivoTerapeutico,
		String estado,
		Instant abiertoEn,
		long abiertoPor,
		Instant cerradoEn,
		Long cerradoPor,
		String motivoCierre,
		List<CasoProfesionalView> equipo,
		long version) {

	public static CasoClinicoView de(CasoClinico caso, List<CasoProfesionalView> equipo) {
		return de(caso, equipo, null);
	}

	/** Con el nombre comercial de la oferta resuelto al leer (0.107.0), o {@code null}. */
	public static CasoClinicoView de(
			CasoClinico caso, List<CasoProfesionalView> equipo, String ofertaNombre) {
		return new CasoClinicoView(
				caso.getId(),
				caso.getHistoriaClinicaId(),
				caso.getNumeroCaso(),
				caso.getOfertaId(),
				caso.getOfertaConsultorioId(),
				ofertaNombre,
				caso.getDiagnosticoPresuntivo(),
				caso.getObjetivoTerapeutico(),
				caso.getEstado().name(),
				caso.getAbiertoEn(),
				caso.getAbiertoPor(),
				caso.getCerradoEn(),
				caso.getCerradoPor(),
				caso.getMotivoCierre(),
				List.copyOf(equipo),
				caso.getVersion());
	}

	/**
	 * La misma vista con otra version: la usa el cambio de equipo, que avanza la version con un
	 * {@code UPDATE} condicionado y deja la entidad con la leida.
	 */
	public CasoClinicoView conVersion(long nuevaVersion) {
		return new CasoClinicoView(id, historiaClinicaId, numeroCaso, ofertaId, ofertaConsultorioId,
				ofertaNombre, diagnosticoPresuntivo, objetivoTerapeutico, estado, abiertoEn,
				abiertoPor, cerradoEn, cerradoPor, motivoCierre, equipo, nuevaVersion);
	}
}
