package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.AutorizacionVinculadaACaso;
import com.akine.clinical.domain.CasoClinico;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoClinicoRepositoryPort;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanItemRepositoryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Que autorizaciones NO puede consumir una sesion de este caso (C-4).
 *
 * <p>Una autorizacion atada al plan de OTRO caso de la misma historia pertenece a ese tratamiento:
 * gastarla en este le come unidades a un problema distinto. La excepcion es la autorizacion atada a
 * los dos casos a la vez —el limite que C-4 dejo declarado—: si este caso tambien la usa, no se la
 * excluye, porque excluirla dejaria a este caso sin una autorizacion que es suya.
 */
@DisplayName("ClinicalAutorizacionesDelCaso")
class ClinicalAutorizacionesDelCasoTest {

	private static final long ORG = 10L;
	private static final long HC = 700L;
	private static final long CASO = 900L;
	private static final long OTRO_CASO = 901L;

	private final CasoClinicoRepositoryPort casos = mock(CasoClinicoRepositoryPort.class);
	private final PlanItemRepositoryPort items = mock(PlanItemRepositoryPort.class);
	private final ClinicalAutorizacionesDelCaso sonda = new ClinicalAutorizacionesDelCaso(casos, items);

	@Test
	@DisplayName("excluye las de otros casos, salvo las que este caso tambien tiene atadas")
	void excluye_las_de_otros_casos() {
		given(casos.findByIdAndOrganizationId(CASO, ORG)).willReturn(Optional.of(caso()));
		given(items.autorizacionesConCasoDeLaHistoria(ORG, HC)).willReturn(List.of(
				new AutorizacionVinculadaACaso(1L, CASO),
				new AutorizacionVinculadaACaso(2L, OTRO_CASO),
				new AutorizacionVinculadaACaso(3L, OTRO_CASO),
				new AutorizacionVinculadaACaso(3L, CASO)));

		assertThat(sonda.deOtrosCasos(ORG, CASO))
				.as("la 1 es de este caso, la 3 de los dos: solo la 2 es ajena")
				.containsExactly(2L);
	}

	@Test
	@DisplayName("un caso de otro tenant o inexistente no excluye nada y no consulta los planes")
	void caso_ajeno() {
		given(casos.findByIdAndOrganizationId(CASO, ORG)).willReturn(Optional.empty());

		assertThat(sonda.deOtrosCasos(ORG, CASO)).isEmpty();
		verify(items, never()).autorizacionesConCasoDeLaHistoria(anyLong(), anyLong());
	}

	private static CasoClinico caso() {
		CasoClinico caso = new CasoClinico(ORG, HC, 1, 42L, 20L, "Gonalgia", null, Instant.EPOCH, 40L);
		ReflectionTestUtils.setField(caso, "id", CASO);
		return caso;
	}
}
