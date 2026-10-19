package com.akine.organization.infrastructure.tenant;

import java.time.DateTimeException;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.akine.organization.domain.port.OrganizationRepositoryPort;
import com.akine.organization.spi.ZonaDeOrganizacion;

/**
 * Implementacion de {@link ZonaDeOrganizacion}: lee {@code organization.timezone}.
 *
 * <p>Devuelve la zona tambien para una organizacion dada de baja: sus lecturas historicas siguen
 * necesitando un "hoy". Cae a {@link ZonaDeOrganizacion#POR_DEFECTO} con un WARN si la
 * organizacion no existe o declara una zona que {@code java.time} no reconoce.
 */
@Component
public class OrganizationZonaDirectory implements ZonaDeOrganizacion {

	private static final Logger log = LoggerFactory.getLogger(OrganizationZonaDirectory.class);

	private final OrganizationRepositoryPort organizationRepository;

	public OrganizationZonaDirectory(OrganizationRepositoryPort organizationRepository) {
		this.organizationRepository = organizationRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public ZoneId de(long organizationId) {
		String zona = organizationRepository.findById(organizationId)
				.map(organizacion -> organizacion.getTimezone())
				.orElse(null);
		if (zona == null || zona.isBlank()) {
			log.warn("Organizacion sin zona horaria resoluble, se usa {}: organizationId={}",
					POR_DEFECTO, organizationId);
			return POR_DEFECTO;
		}
		try {
			return ZoneId.of(zona);
		} catch (DateTimeException invalida) {
			log.warn("Organizacion con zona horaria invalida, se usa {}: organizationId={} timezone={}",
					POR_DEFECTO, organizationId, zona);
			return POR_DEFECTO;
		}
	}
}
