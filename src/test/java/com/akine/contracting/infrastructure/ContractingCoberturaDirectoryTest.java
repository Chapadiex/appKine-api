package com.akine.contracting.infrastructure;

import com.akine.contracting.domain.Financiador;
import com.akine.contracting.domain.PlanCobertura;
import com.akine.contracting.domain.TipoFinanciador;
import com.akine.contracting.spi.PlanCoberturaSnapshot;
import com.akine.contracting.spi.ReferenciaDeCobertura;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * La costura que 03.04 y 03.05 van a consumir.
 *
 * <h2>Lo que este test existe para proteger</h2>
 *
 * <p>La garantia central de la etapa: <b>una referencia congelada no cambia cuando el plan
 * cambia</b>. El test lo hace ejecutable de la unica forma posible —congelar, mutar el plan,
 * comprobar que la copia sigue diciendo lo de antes— y esa es tambien la demostracion de por que
 * {@code congelar} devuelve un record de valores y no un puntero al agregado.
 *
 * <p>Prueba ademas la otra mitad: que las lecturas VIVAS devuelven lo dado de baja y lo vencido
 * (RN-M15-003, los historicos siguen resolviendo) mientras que lo SELECCIONABLE los excluye
 * (RN-M15-002).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ContractingCoberturaDirectory")
class ContractingCoberturaDirectoryTest {

	private static final long ORG_ID = 7L;
	private static final long FINANCIADOR_ID = 31L;
	private static final long PLAN_ID = 88L;
	private static final LocalDate DESDE = LocalDate.of(2026, 1, 1);
	private static final LocalDate HOY = LocalDate.of(2026, 6, 1);

	@Mock
	private FinanciadorRepository financiadores;

	@Mock
	private PlanCoberturaRepository planes;

	private ContractingCoberturaDirectory directory;

	@BeforeEach
	void setUp() {
		directory = new ContractingCoberturaDirectory(financiadores, planes);
	}

	@Test
	@DisplayName("La referencia congelada NO cambia cuando despues cambia el plan")
	void la_referencia_congelada_no_cambia() {
		// Es CA-M15-008-06 hecho ejecutable: "un cambio de cobertura futuro no altera
		// liquidaciones historicas". Si congelar devolviera un puntero al agregado, o si el
		// consumidor guardara solo el planId y releyera, este test no podria existir.
		Financiador financiador = financiador(true);
		PlanCobertura plan = plan(null);
		darDeAlta(financiador, plan);

		ReferenciaDeCobertura congelada = directory.congelar(ORG_ID, PLAN_ID, HOY).orElseThrow();

		plan.updateDatos("Plan 210 Premium", null, null, null, null, null,
				new BigDecimal("9999.00"), "USD");
		financiador.updateDatos("Otra Prepaga SA", null, null, null, null, null);

		assertThat(congelada.planNombre()).isEqualTo("Plan 210");
		assertThat(congelada.financiadorNombre()).isEqualTo("OSDE Binario");
		assertThat(congelada.copago()).isEqualByComparingTo("1500.00");
		assertThat(congelada.moneda()).isEqualTo("ARS");
		assertThat(congelada.vigenteEl()).isEqualTo(HOY);
		assertThat(congelada.capturadaEl()).isNotNull();

		// Y la identidad estable viaja con la copia, para que el historico sea explicable.
		assertThat(congelada.planCodigo()).isEqualTo("210");
		assertThat(congelada.financiadorCodigo()).isEqualTo("OSDE");
		assertThat(congelada.planId()).isEqualTo(PLAN_ID);
		assertThat(congelada.financiadorId()).isEqualTo(FINANCIADOR_ID);
	}

	@Test
	@DisplayName("No congela lo que no se puede elegir: devuelve empty, no una excepcion")
	void no_congela_lo_no_seleccionable() {
		// Empty y no excepcion: quien firma la cobertura decide que responder (400, 409, un
		// mensaje en la pantalla) y esa decision es suya, no de este modulo.
		darDeAlta(financiador(true), plan(LocalDate.of(2026, 3, 31)));
		assertThat(directory.congelar(ORG_ID, PLAN_ID, HOY)).isEmpty();

		darDeAlta(financiador(false), plan(null));
		assertThat(directory.congelar(ORG_ID, PLAN_ID, HOY)).isEmpty();

		PlanCobertura dadoDeBaja = plan(null);
		dadoDeBaja.deactivate(Instant.now(), "motivo");
		darDeAlta(financiador(true), dadoDeBaja);
		assertThat(directory.congelar(ORG_ID, PLAN_ID, HOY)).isEmpty();
	}

	@Test
	@DisplayName("Un plan de otro tenant no resuelve por ninguna de las dos puertas")
	void cross_tenant_no_resuelve() {
		given(planes.findByIdAndOrganizationId(anyLong(), anyLong())).willReturn(Optional.empty());
		given(financiadores.findByIdAndOrganizationId(anyLong(), anyLong()))
				.willReturn(Optional.empty());

		assertThat(directory.findPlan(ORG_ID, PLAN_ID)).isEmpty();
		assertThat(directory.congelar(ORG_ID, PLAN_ID, HOY)).isEmpty();
		assertThat(directory.findFinanciador(ORG_ID, FINANCIADOR_ID)).isEmpty();
		assertThat(directory.planesSeleccionables(ORG_ID, FINANCIADOR_ID, HOY)).isEmpty();
	}

	@Test
	@DisplayName("findPlan SI devuelve lo vencido y lo dado de baja: los historicos resuelven")
	void la_lectura_viva_devuelve_los_historicos() {
		// Responder empty para un plan viejo seria borrar historia por la puerta de atras
		// (RN-M15-003). Quien pregunta si se puede ELEGIR usa seleccionableEl, que es otra
		// pregunta.
		PlanCobertura vencido = plan(LocalDate.of(2026, 3, 31));
		darDeAlta(financiador(true), vencido);

		PlanCoberturaSnapshot snapshot = directory.findPlan(ORG_ID, PLAN_ID).orElseThrow();

		assertThat(snapshot.operable()).isTrue();
		assertThat(snapshot.seleccionableEl(HOY)).isFalse();
		assertThat(snapshot.seleccionableEl(DESDE)).isTrue();
		assertThat(snapshot.financiadorNombre()).isEqualTo("OSDE Binario");
	}

	@Test
	@DisplayName("planesSeleccionables ya viene filtrado: el llamador no puede olvidarse")
	void los_seleccionables_vienen_filtrados() {
		// Dejar el filtro del lado del llamador garantiza que antes o despues alguien lo olvide y
		// ofrezca un plan de baja en un alta nueva.
		PlanCobertura vigente = plan(null);
		PlanCobertura vencido = plan(LocalDate.of(2026, 3, 31));

		given(financiadores.findByIdAndOrganizationId(FINANCIADOR_ID, ORG_ID))
				.willReturn(Optional.of(financiador(true)));
		given(planes.findAllByOrganizationIdAndFinanciadorIdOrderByNombreAsc(ORG_ID, FINANCIADOR_ID))
				.willReturn(List.of(vigente, vencido));

		assertThat(directory.planesSeleccionables(ORG_ID, FINANCIADOR_ID, HOY)).hasSize(1);
	}

	@Test
	@DisplayName("Un financiador dado de baja hace que NINGUN plan suyo sea seleccionable")
	void el_financiador_de_baja_apaga_sus_planes() {
		given(financiadores.findByIdAndOrganizationId(FINANCIADOR_ID, ORG_ID))
				.willReturn(Optional.of(financiador(false)));
		given(planes.findAllByOrganizationIdAndFinanciadorIdOrderByNombreAsc(ORG_ID, FINANCIADOR_ID))
				.willReturn(List.of(plan(null)));

		assertThat(directory.planesSeleccionables(ORG_ID, FINANCIADOR_ID, HOY)).isEmpty();
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private void darDeAlta(Financiador financiador, PlanCobertura plan) {
		given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID)).willReturn(Optional.of(plan));
		given(financiadores.findByIdAndOrganizationId(FINANCIADOR_ID, ORG_ID))
				.willReturn(Optional.of(financiador));
	}

	private static Financiador financiador(boolean operable) {
		Financiador financiador = new Financiador(
				ORG_ID, "OSDE", "OSDE Binario", TipoFinanciador.PREPAGA, null, null, null, null);
		ReflectionTestUtils.setField(financiador, "id", FINANCIADOR_ID);
		if (!operable) {
			financiador.deactivate(Instant.now(), "motivo");
		}
		return financiador;
	}

	private static PlanCobertura plan(LocalDate hasta) {
		PlanCobertura plan = new PlanCobertura(
				ORG_ID, FINANCIADOR_ID, "210", "Plan 210", null, DESDE, hasta,
				false, true, new BigDecimal("1500.00"), "ARS");
		ReflectionTestUtils.setField(plan, "id", PLAN_ID);
		return plan;
	}
}
