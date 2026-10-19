package com.akine.encounter.domain.port;

import com.akine.encounter.domain.PoliticaDeEnmienda;

import java.util.Optional;

/** La ventana para enmendar de cada organizacion (U-25). Sin fila rige el valor por defecto. */
public interface PoliticaDeEnmiendaRepositoryPort {

	Optional<PoliticaDeEnmienda> findById(Long organizationId);

	PoliticaDeEnmienda saveAndFlush(PoliticaDeEnmienda politica);
}
