package com.akine.organization.application;

import com.akine.organization.domain.port.ConsultorioRepositoryPort;
import com.akine.organization.domain.port.MembershipRepositoryPort;
import com.akine.organization.spi.LimitCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static com.akine.organization.application.Fixtures.ORG_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * Conteo de recursos activos del tenant para cada limite del catalogo.
 *
 * <p>El meta-test {@link #todo_limite_del_catalogo_tiene_un_conteo(LimitCode)} es el que
 * importa: si alguien agrega un valor a {@link LimitCode} sin decidir como se cuenta, el
 * {@code switch} sin {@code default} rompe la compilacion, y este test rompe si el conteo
 * queda enchufado a la tabla equivocada.
 */
@ExtendWith(MockitoExtension.class)
class TenantUsageCounterTest {

	@Mock
	private ConsultorioRepositoryPort consultorioRepository;

	@Mock
	private MembershipRepositoryPort membershipRepository;

	@InjectMocks
	private TenantUsageCounter usageCounter;

	@Test
	@DisplayName("MAX_CONSULTORIOS cuenta las sedes activas del tenant")
	void max_consultorios_cuenta_sedes_activas() {
		given(consultorioRepository.countByOrganizationIdAndActiveTrue(ORG_ID)).willReturn(3L);

		assertThat(usageCounter.count(LimitCode.MAX_CONSULTORIOS, ORG_ID)).isEqualTo(3L);
	}

	@Test
	@DisplayName("MAX_MIEMBROS_ACTIVOS cuenta las memberships activas del tenant")
	void max_miembros_cuenta_memberships_activas() {
		given(membershipRepository.countByOrganizationIdAndActiveTrue(ORG_ID)).willReturn(7L);

		assertThat(usageCounter.count(LimitCode.MAX_MIEMBROS_ACTIVOS, ORG_ID)).isEqualTo(7L);
	}

	@ParameterizedTest
	@EnumSource(LimitCode.class)
	@MockitoSettings(strictness = Strictness.LENIENT)
	@DisplayName("Todo limite del catalogo tiene un conteo real: ninguno devuelve cero en silencio")
	void todo_limite_del_catalogo_tiene_un_conteo(LimitCode limite) {
		// Un cero silencioso convertiria el limite en "siempre permite", que es peor que no
		// tenerlo: nadie mira un limite que nunca rechaza.
		given(consultorioRepository.countByOrganizationIdAndActiveTrue(ORG_ID)).willReturn(4L);
		given(membershipRepository.countByOrganizationIdAndActiveTrue(ORG_ID)).willReturn(4L);

		assertThat(usageCounter.count(limite, ORG_ID)).isEqualTo(4L);
	}
}
