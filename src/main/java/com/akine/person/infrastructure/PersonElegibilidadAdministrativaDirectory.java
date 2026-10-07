package com.akine.person.infrastructure;

import com.akine.person.application.ElegibilidadAdministrativa;
import com.akine.person.application.ElegibilidadAdministrativaService;
import com.akine.person.application.RequisitoAdministrativo;
import com.akine.person.spi.ElegibilidadAdministrativaDirectory;
import com.akine.person.spi.VeredictoDeElegibilidad;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/** Adaptador de {@link ElegibilidadAdministrativaDirectory} sobre la regla de M17. */
@Component
public class PersonElegibilidadAdministrativaDirectory implements ElegibilidadAdministrativaDirectory {

	/** Motivo cuando la persona o la cobertura no son de la organizacion. */
	public static final String NO_ACCESIBLE = "COBERTURA_NO_ACCESIBLE";

	private final ElegibilidadAdministrativaService servicio;

	public PersonElegibilidadAdministrativaDirectory(ElegibilidadAdministrativaService servicio) {
		this.servicio = servicio;
	}

	@Override
	public VeredictoDeElegibilidad evaluar(
			long organizationId, long consultorioId, long personaId, long coberturaId,
			long practicaId, LocalDate fecha) {

		return servicio.evaluar(organizationId, consultorioId, personaId, coberturaId, practicaId, fecha)
				.map(PersonElegibilidadAdministrativaDirectory::aVeredicto)
				.orElseGet(() -> new VeredictoDeElegibilidad(false, NO_ACCESIBLE, null,
						List.of("COBERTURA: la cobertura no existe para esta persona en la organizacion.")));
	}

	private static VeredictoDeElegibilidad aVeredicto(ElegibilidadAdministrativa elegibilidad) {
		return new VeredictoDeElegibilidad(
				elegibilidad.elegible(),
				elegibilidad.motivo(),
				elegibilidad.convenioId(),
				elegibilidad.requisitos().stream()
						.filter(requisito -> !requisito.cumplido())
						.map(PersonElegibilidadAdministrativaDirectory::describir)
						.toList());
	}

	private static String describir(RequisitoAdministrativo requisito) {
		return requisito.tipo().name() + ": " + requisito.detalle();
	}
}
