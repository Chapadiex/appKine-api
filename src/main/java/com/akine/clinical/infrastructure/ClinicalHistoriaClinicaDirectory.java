package com.akine.clinical.infrastructure;

import com.akine.clinical.application.HistoriaClinicaService;
import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import com.akine.clinical.spi.HistoriaClinicaDirectory;
import com.akine.clinical.spi.HistoriaClinicaSnapshot;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Implementacion de {@link HistoriaClinicaDirectory}: el borde por donde {@code clinical} responde
 * a los otros modulos.
 *
 * <p><b>Nunca devuelve entities</b>, solo el record del {@code spi}. Devolver la
 * {@link HistoriaClinica} le daria al consumidor una entity gestionada con la que podria escribir
 * en tablas que no son suyas.
 *
 * <p>{@link #asegurar} delega en el servicio de aplicacion y no reimplementa el get-or-create: la
 * idempotencia depende de un {@code catch} sobre el unique, y dos copias de ese {@code catch}
 * divergen. La lectura si va directo al puerto, porque es una consulta pura sin regla asociada.
 */
@Component
public class ClinicalHistoriaClinicaDirectory implements HistoriaClinicaDirectory {

	private final HistoriaClinicaRepositoryPort historias;
	private final HistoriaClinicaService historiaClinicaService;

	public ClinicalHistoriaClinicaDirectory(
			HistoriaClinicaRepositoryPort historias, HistoriaClinicaService historiaClinicaService) {
		this.historias = historias;
		this.historiaClinicaService = historiaClinicaService;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<HistoriaClinicaSnapshot> find(long organizationId, long personaId) {
		return historias.buscarVigentePorPersona(organizationId, personaId)
				.map(ClinicalHistoriaClinicaDirectory::instantanea);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<HistoriaClinicaSnapshot> findPorId(long organizationId, long historiaClinicaId) {
		return historias.findByIdAndOrganizationId(historiaClinicaId, organizationId)
				.map(ClinicalHistoriaClinicaDirectory::instantanea);
	}

	@Override
	@Transactional
	public HistoriaClinicaSnapshot asegurar(
			long organizationId, long personaId, long actorAccountId) {
		return instantanea(historiaClinicaService.asegurar(organizationId, personaId, actorAccountId));
	}

	private static HistoriaClinicaSnapshot instantanea(HistoriaClinica historia) {
		return new HistoriaClinicaSnapshot(
				historia.getId(),
				historia.getOrganizationId(),
				historia.getPersonaId(),
				historia.getAbiertaEn(),
				historia.isVigente(),
				historia.getVersion());
	}
}
