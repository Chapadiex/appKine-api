package com.akine.billing.application;

import com.akine.billing.domain.EstadoItemPresentacion;
import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.SnapshotDeConvenio;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import com.akine.person.spi.DatosDeLiquidacionDirectory;
import com.akine.person.spi.DatosDeLiquidacionDirectory.CoberturaDeLiquidacion;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.resource.spi.CatalogoDirectory;
import com.akine.resource.spi.CatalogoSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("Renglones legibles de la liquidacion (obras sociales simples, paso 3)")
class RenglonesLegiblesTest {

	private static final long ORG = 7L;

	private final ObligacionRepositoryPort obligaciones = mock(ObligacionRepositoryPort.class);
	private final PacienteDirectory pacientes = mock(PacienteDirectory.class);
	private final DatosDeLiquidacionDirectory datos = mock(DatosDeLiquidacionDirectory.class);
	private final CatalogoDirectory catalogo = mock(CatalogoDirectory.class);
	private final RenglonesLegibles renglones =
			new RenglonesLegibles(obligaciones, pacientes, datos, catalogo);

	@Test
	@DisplayName("cada renglon sale con paciente, documento, afiliado, plan, practica y autorizacion")
	void completa_los_renglones() {
		Obligacion obligacion = mock(Obligacion.class);
		given(obligacion.getId()).willReturn(40L);
		given(obligacion.getPracticaId()).willReturn(55L);
		given(obligacion.getSnapshotDeConvenio()).willReturn(Optional.of(snapshot(310L)));
		given(obligaciones.findAllDeLaOrganizacion(eq(ORG), any())).willReturn(List.of(obligacion));
		given(pacientes.findAll(eq(ORG), any())).willReturn(Map.of(128L, new PacienteSnapshot(
				128L, ORG, "Perez", "Ana", "DNI", "30111222", null, true, true, 1L, null)));
		given(datos.coberturas(eq(ORG), any())).willReturn(Map.of(310L,
				new CoberturaDeLiquidacion(310L, "AF-1", "OSDE", "Plan 310")));
		given(datos.autorizacionesPorSesion(eq(ORG), any())).willReturn(Map.of(77L, "AUT-9"));
		given(catalogo.findPractica(eq(ORG), eq(55L), any())).willReturn(Optional.of(
				new CatalogoSnapshot(55L, null, "KIN_SESION", "Sesion de kinesiologia", null, null,
						true, true, null, null, 0L)));

		List<PresentacionItemView> legibles = renglones.completar(ORG, List.of(item(1L), item(2L)));

		assertThat(legibles).allSatisfy(renglon -> {
			assertThat(renglon.pacienteNombre()).isEqualTo("Perez, Ana");
			assertThat(renglon.pacienteDocumento()).isEqualTo("DNI 30111222");
			assertThat(renglon.numeroAfiliado()).isEqualTo("AF-1");
			assertThat(renglon.planNombre()).isEqualTo("Plan 310");
			assertThat(renglon.practicaCodigo()).isEqualTo("KIN_SESION");
			assertThat(renglon.practicaNombre()).isEqualTo("Sesion de kinesiologia");
			assertThat(renglon.autorizacionNumero()).isEqualTo("AUT-9");
		});
		// La practica repetida se busca una sola vez: 500 renglones no son 500 consultas.
		verify(catalogo, times(1)).findPractica(anyLong(), anyLong(), any());
	}

	@Test
	@DisplayName("lo que falta viaja nulo y el renglon sale igual")
	void lo_que_falta_viaja_nulo() {
		List<PresentacionItemView> legibles = renglones.completar(ORG, List.of(item(1L)));

		assertThat(legibles).singleElement().satisfies(renglon -> {
			assertThat(renglon.id()).isEqualTo(1L);
			assertThat(renglon.pacienteNombre()).isNull();
			assertThat(renglon.numeroAfiliado()).isNull();
			assertThat(renglon.practicaCodigo()).isNull();
		});
	}

	private static PresentacionItemView item(long id) {
		return new PresentacionItemView(id, 40L, EstadoItemPresentacion.INCLUIDO,
				new BigDecimal("7000.00"), BigDecimal.ZERO, null, null, 128L, 77L,
				LocalDate.of(2026, 9, 15), "Sesion", Instant.parse("2026-10-01T12:00:00Z"), 0L,
				null, null, null, null, null, null, null);
	}

	private static SnapshotDeConvenio snapshot(long coberturaId) {
		return new SnapshotDeConvenio(12L, "CONV", "Convenio", 4L, 77L, 55L, coberturaId,
				new BigDecimal("8500.00"), new BigDecimal("7000.00"), new BigDecimal("1500.00"),
				false, false, false, false, LocalDate.of(2026, 9, 15),
				Instant.parse("2026-09-15T12:00:00Z"));
	}
}
