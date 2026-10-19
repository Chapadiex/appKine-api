package com.akine.organization.infrastructure.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.akine.organization.domain.Organization;
import com.akine.organization.domain.port.OrganizationRepositoryPort;
import com.akine.organization.spi.ZonaDeOrganizacion;

/**
 * "Hoy" de la organizacion (U-38, DP-43): la zona sale de {@code organization.timezone}, y una
 * fila que no resuelve cae a Cordoba sin tumbar la lectura.
 */
@DisplayName("OrganizationZonaDirectory")
class OrganizationZonaDirectoryTest {

	private static final long ORG_ID = 10L;

	/** 01:30 UTC del 15/03: las 22:30 del 14/03 en Cordoba. Es la ventana donde UTC mentia. */
	private static final Clock A_LAS_22_30_EN_CORDOBA =
			Clock.fixed(Instant.parse("2026-03-15T01:30:00Z"), ZoneOffset.UTC);

	private final OrganizationRepositoryPort repositorio = mock(OrganizationRepositoryPort.class);
	private final ZonaDeOrganizacion zonas = new OrganizationZonaDirectory(repositorio);

	@Test
	@DisplayName("Hoy es el de la zona de la organizacion, no el de UTC")
	void hoy_en_la_zona_de_la_organizacion() {
		conZona("America/Argentina/Cordoba");

		assertThat(zonas.hoy(ORG_ID, A_LAS_22_30_EN_CORDOBA)).isEqualTo(LocalDate.of(2026, 3, 14));
	}

	@Test
	@DisplayName("Una organizacion en otro huso tiene su propio dia")
	void otra_zona_otro_dia() {
		conZona("Asia/Tokyo");

		assertThat(zonas.de(ORG_ID)).isEqualTo(ZoneId.of("Asia/Tokyo"));
		assertThat(zonas.hoy(ORG_ID, A_LAS_22_30_EN_CORDOBA)).isEqualTo(LocalDate.of(2026, 3, 15));
	}

	@Test
	@DisplayName("Una zona invalida o una organizacion que no resuelve caen a Cordoba")
	void lo_que_no_resuelve_cae_al_default() {
		conZona("No/Existe");
		assertThat(zonas.de(ORG_ID)).isEqualTo(ZonaDeOrganizacion.POR_DEFECTO);

		given(repositorio.findById(ORG_ID)).willReturn(Optional.empty());
		assertThat(zonas.de(ORG_ID)).isEqualTo(ZoneId.of("America/Argentina/Cordoba"));
	}

	private void conZona(String zona) {
		given(repositorio.findById(ORG_ID))
				.willReturn(Optional.of(new Organization("Centro", "centro", zona)));
	}
}
