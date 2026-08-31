package com.akine.clinical.application;

import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.spi.EventoClinico;
import com.akine.person.spi.PacienteSnapshot;

import java.time.Instant;
import java.util.List;

/**
 * La Historia Clinica tal como sale del servicio.
 *
 * <h2>Los datos del paciente viajan, pero no estan guardados aca</h2>
 *
 * <p>{@link #personaNombreCompleto} y {@link #personaDocumento} se leen de {@code person.spi} en
 * cada consulta y no se copian en {@code historia_clinica}: RN-M09-003 prohibe duplicar los datos
 * administrativos del paciente, y una copia divergiria en cuanto alguien corrija un apellido mal
 * tipeado. Que la vista los traiga no es duplicarlos — es que sin nombre en pantalla la historia
 * no se puede usar.
 *
 * <h2>{@link #eventos} esta siempre vacia hoy</h2>
 *
 * <p>Es la costura hacia el timeline (04.02, cortada por DP-10): la lista se arma preguntandole a
 * los {@code EventoClinicoContributor} registrados y hoy no hay ninguno. La lista existe igual
 * para que el consumidor la lea desde ahora y no cambie de forma cuando 04.02 llegue.
 *
 * @param conRelacionAsistencial con que se autorizo este acceso concreto. Viaja en la vista para
 *                               que quien la muestre pueda advertir que se entro por justificacion
 *                               y no por atencion, que es la mitad util de DP-03
 */
public record HistoriaClinicaView(
		long id,
		long organizationId,
		long personaId,
		String personaNombreCompleto,
		String personaDocumento,
		Instant abiertaEn,
		long abiertaPor,
		String resumen,
		Instant resumenActualizadoEn,
		Long resumenActualizadoPor,
		List<AntecedenteView> antecedentes,
		List<EventoClinico> eventos,
		boolean conRelacionAsistencial,
		long version) {

	public HistoriaClinicaView {
		antecedentes = antecedentes == null ? List.of() : List.copyOf(antecedentes);
		eventos = eventos == null ? List.of() : List.copyOf(eventos);
	}

	static HistoriaClinicaView de(
			HistoriaClinica historia,
			PacienteSnapshot paciente,
			List<AntecedenteView> antecedentes,
			List<EventoClinico> eventos,
			boolean conRelacionAsistencial) {

		return new HistoriaClinicaView(
				historia.getId(),
				historia.getOrganizationId(),
				historia.getPersonaId(),
				paciente == null ? null : paciente.nombreCompleto(),
				documentoDe(paciente),
				historia.getAbiertaEn(),
				historia.getAbiertaPor(),
				historia.getResumen(),
				historia.getResumenActualizadoEn(),
				historia.getResumenActualizadoPor(),
				antecedentes,
				eventos,
				conRelacionAsistencial,
				historia.getVersion());
	}

	/**
	 * La misma historia <b>sin una linea de contenido clinico</b>: existencia, titular, fecha de
	 * apertura y version.
	 *
	 * <h2>Por que existe sin llamador</h2>
	 *
	 * <p>Es la capacidad diferida que DP-10 pide dejar representada en el modelo. La matriz de
	 * permisos seccion 4 le da al {@code ADMINISTRATIVO} un "Limitado" sobre Ver HC que dice
	 * literalmente: solo metadatos administrativos, <b>nunca</b> contenido clinico. Esta es esa
	 * proyeccion.
	 *
	 * <p>No tiene llamador porque el {@code ADMINISTRATIVO} <b>no recibe {@code hc:read} en esta
	 * etapa</b>: distinguir su lectura de la del profesional exige un codigo de permiso propio que
	 * el catalogo de la matriz no tiene, y darle el mismo {@code hc:read} confiando en que la capa
	 * de presentacion recorte seria exactamente el control del lado equivocado. Se deja el
	 * recorte escrito y probado; falta la decision de producto sobre como se otorga.
	 */
	public HistoriaClinicaView soloMetadatos() {
		return new HistoriaClinicaView(
				id,
				organizationId,
				personaId,
				personaNombreCompleto,
				personaDocumento,
				abiertaEn,
				abiertaPor,
				null,
				resumenActualizadoEn,
				resumenActualizadoPor,
				List.of(),
				List.of(),
				conRelacionAsistencial,
				version);
	}

	private static String documentoDe(PacienteSnapshot paciente) {
		if (paciente == null || paciente.numeroDocumento() == null) {
			return null;
		}
		return paciente.tipoDocumento() + " " + paciente.numeroDocumento();
	}
}
