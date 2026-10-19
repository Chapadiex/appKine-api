package com.akine.encounter.infrastructure;

import com.akine.encounter.domain.PoliticaDeEnmienda;
import com.akine.encounter.domain.port.PoliticaDeEnmiendaRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoliticaDeEnmiendaRepository
		extends JpaRepository<PoliticaDeEnmienda, Long>, PoliticaDeEnmiendaRepositoryPort {
}
