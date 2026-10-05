package com.akine.person.application;

import com.akine.contracting.spi.ArancelDirectory;
import com.akine.contracting.spi.ArancelVigente;
import com.akine.contracting.spi.MotivoSinArancel;
import com.akine.contracting.spi.ReferenciaDeCobertura;
import com.akine.contracting.spi.ResolucionDeArancel;
import com.akine.person.domain.CoberturaPaciente;
import com.akine.person.domain.port.PersonRepositoryPorts.CoberturaPacienteRepositoryPort;
import com.akine.person.spi.CoberturaAplicable;
import com.akine.person.spi.CoberturaNoAplicable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * B-2 contra el contrato de {@code CoberturasAplicablesDirectory}: que coberturas de una persona
 * aplican a una practica en una fecha. Escrito desde el contrato, con un doble de
 * {@code ArancelDirectory}; la base y el tenant los cubre {@code CoberturasAplicablesDirectoryIT}.
 */
@DisplayName("Coberturas aplicables (B-2)")
class CoberturasAplicablesServiceTest {

	private static final long ORG = 7L;
	private static final long SEDE = 20L;
	private static final long PERSONA = 1204L;
	private static final long PRACTICA = 33L;
	private static final LocalDate HOY = LocalDate.of(2027, 6, 15);

	private CoberturaPacienteRepositoryPort coberturas;
	private ArancelDirectory aranceles;
	private CoberturasAplicablesService servicio;

	@BeforeEach
	void setUp() {
		coberturas = mock(CoberturaPacienteRepositoryPort.class);
		aranceles = mock(ArancelDirectory.class);
		servicio = new CoberturasAplicablesService(coberturas, aranceles);
	}

	@Test
	@DisplayName("B2-E1 una cobertura financiada vigente con arancel resuelto aplica y trae referencia y resolucion")
	void b2_e1_aplica_con_referencia_congelada_y_resolucion() {
		darCoberturas(financiada(1L, 88L, 99L, true, HOY.minusMonths(1), null, null));
		resolverConConvenio(88L, 99L);

		List<CoberturaAplicable> aplicables = aplicables();

		assertThat(aplicables).singleElement().satisfies(a -> {
			assertThat(a.coberturaId()).isEqualTo(1L);
			assertThat(a.referencia().financiadorId()).isEqualTo(88L);
			assertThat(a.referencia().financiadorNombre()).isEqualTo("Financiador 88");
			assertThat(a.referencia().planId()).isEqualTo(99L);
			assertThat(a.referencia().planNombre()).isEqualTo("Plan 99");
			assertThat(a.resolucion().estaResuelta()).isTrue();
		});
		assertThat(noAplicables()).isEmpty();
	}

	@Test
	@DisplayName("B2-E2 la principal va primero y marcada; las demas por id ascendente")
	void b2_e2_principal_primero_y_el_resto_por_id() {
		// Se entregan desordenadas a proposito: el orden es del contrato, no del repositorio.
		darCoberturas(
				financiada(30L, 88L, 930L, false, HOY.minusMonths(1), null, null),
				financiada(10L, 88L, 910L, false, HOY.minusMonths(1), null, null),
				financiada(50L, 88L, 950L, true, HOY.minusMonths(1), null, null),
				financiada(20L, 88L, 920L, false, HOY.minusMonths(1), null, null));
		given(aranceles.resolver(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any()))
				.willReturn(resuelta());

		List<CoberturaAplicable> aplicables = aplicables();

		assertThat(aplicables).extracting(CoberturaAplicable::coberturaId)
				.containsExactly(50L, 10L, 20L, 30L);
		assertThat(aplicables.get(0).principal()).isTrue();
		assertThat(aplicables.subList(1, 4)).allMatch(a -> !a.principal());
	}

	@Test
	@DisplayName("B2-E3 la vigencia es inclusiva en los dos extremos y 'hasta' NULL es abierta")
	void b2_e3_vigencia_inclusiva() {
		LocalDate desde = LocalDate.of(2027, 3, 1);
		LocalDate hasta = LocalDate.of(2027, 3, 31);
		darCoberturas(financiada(1L, 88L, 99L, true, desde, hasta, null));
		resolverConConvenio(88L, 99L);

		assertThat(aplicablesEl(desde)).as("el dia 'desde'").hasSize(1);
		assertThat(aplicablesEl(hasta)).as("el dia 'hasta'").hasSize(1);
		assertThat(aplicablesEl(desde.minusDays(1))).as("el dia anterior al desde").isEmpty();
		assertThat(aplicablesEl(hasta.plusDays(1))).as("el dia posterior al hasta").isEmpty();
		// Fuera de vigencia no es "sin convenio": la cobertura ni se evalua.
		assertThat(noAplicablesEl(desde.minusDays(1))).isEmpty();
		assertThat(noAplicablesEl(hasta.plusDays(1))).isEmpty();
	}

	@Test
	@DisplayName("B2-E3 con 'hasta' NULL la cobertura aplica en cualquier fecha desde el inicio")
	void b2_e3_hasta_null_es_abierta() {
		darCoberturas(financiada(1L, 88L, 99L, true, LocalDate.of(2027, 1, 1), null, null));
		resolverConConvenio(88L, 99L);

		assertThat(aplicablesEl(LocalDate.of(2027, 1, 1))).hasSize(1);
		assertThat(aplicablesEl(LocalDate.of(2040, 12, 31))).hasSize(1);
		assertThat(aplicablesEl(LocalDate.of(2026, 12, 31))).isEmpty();
	}

	@Test
	@DisplayName("B2-E5 una cobertura PARTICULAR nunca aparece; solo particular = lista vacia")
	void b2_e5_particular_nunca_aparece() {
		CoberturaPaciente particular = CoberturaPaciente.particular(
				ORG, PERSONA, HOY.minusMonths(1), null, true, null);
		ReflectionTestUtils.setField(particular, "id", 5L);
		darCoberturas(particular);
		given(aranceles.resolver(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any()))
				.willReturn(resuelta());

		assertThat(aplicables()).isEmpty();
		assertThat(noAplicables()).isEmpty();
	}

	@Test
	@DisplayName("B2-E6 sin convenio vigente o sin arancel vigente no aplica y el motivo se lee en noAplicables")
	void b2_e6_sin_convenio_o_sin_arancel_no_aplica_con_motivo() {
		darCoberturas(
				financiada(1L, 88L, 901L, true, HOY.minusMonths(1), null, null),
				financiada(2L, 89L, 902L, false, HOY.minusMonths(1), null, null));
		given(aranceles.resolver(ORG, SEDE, 88L, 901L, PRACTICA, HOY))
				.willReturn(ResolucionDeArancel.sinArancel(MotivoSinArancel.SIN_CONVENIO_VIGENTE));
		given(aranceles.resolver(ORG, SEDE, 89L, 902L, PRACTICA, HOY))
				.willReturn(ResolucionDeArancel.sinArancel(MotivoSinArancel.SIN_ARANCEL_VIGENTE));

		assertThat(aplicables()).isEmpty();
		assertThat(noAplicables())
				.extracting(CoberturaNoAplicable::coberturaId, CoberturaNoAplicable::motivo)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple(1L, MotivoSinArancel.SIN_CONVENIO_VIGENTE),
						org.assertj.core.groups.Tuple.tuple(2L, MotivoSinArancel.SIN_ARANCEL_VIGENTE));
	}

	@Test
	@DisplayName("B2-E7 una credencial vencida no excluye la cobertura y viaja credencialVencida=true")
	void b2_e7_credencial_vencida_no_excluye() {
		LocalDate vencio = HOY.minusDays(1);
		darCoberturas(
				financiada(1L, 88L, 901L, true, HOY.minusMonths(6), null, vencio),
				financiada(2L, 88L, 902L, false, HOY.minusMonths(6), null, HOY));
		given(aranceles.resolver(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any()))
				.willReturn(resuelta());

		List<CoberturaAplicable> aplicables = aplicables();

		assertThat(aplicables).hasSize(2);
		assertThat(aplicables.get(0).credencialVencida()).isTrue();
		assertThat(aplicables.get(0).credencialVigenciaHasta()).isEqualTo(vencio);
		// El ultimo dia de la credencial todavia es valido: vence al dia siguiente.
		assertThat(aplicables.get(1).credencialVencida()).isFalse();
	}

	@Test
	@DisplayName("B2-E8 dos vigentes, una con convenio y otra sin: solo la primera aplica, la otra va a noAplicables con motivo")
	void b2_e8_una_con_convenio_y_otra_sin() {
		darCoberturas(
				financiada(1L, 88L, 901L, true, HOY.minusMonths(1), null, null),
				financiada(2L, 89L, 902L, false, HOY.minusMonths(1), null, null));
		resolverConConvenio(88L, 901L);
		given(aranceles.resolver(ORG, SEDE, 89L, 902L, PRACTICA, HOY))
				.willReturn(ResolucionDeArancel.sinArancel(MotivoSinArancel.SIN_CONVENIO_VIGENTE));

		assertThat(aplicables()).extracting(CoberturaAplicable::coberturaId).containsExactly(1L);
		assertThat(noAplicables()).singleElement().satisfies(n -> {
			assertThat(n.coberturaId()).isEqualTo(2L);
			assertThat(n.motivo()).isEqualTo(MotivoSinArancel.SIN_CONVENIO_VIGENTE);
			assertThat(n.referencia().planId()).isEqualTo(902L);
		});
	}

	@Test
	@DisplayName("B2-E9 una persona sin coberturas en la organizacion da listas vacias y no consulta convenios")
	void b2_e9_persona_sin_coberturas_en_la_organizacion() {
		given(coberturas.activasDe(ORG, PERSONA)).willReturn(List.of());

		assertThat(aplicables()).isEmpty();
		assertThat(noAplicables()).isEmpty();
		verify(coberturas, org.mockito.Mockito.atLeastOnce()).activasDe(ORG, PERSONA);
		org.mockito.Mockito.verifyNoInteractions(aranceles);
	}

	@Test
	@DisplayName("B2-E10 es solo lectura: unicamente consulta coberturas activas y no escribe nada")
	void b2_e10_solo_lectura() {
		darCoberturas(financiada(1L, 88L, 99L, true, HOY.minusMonths(1), null, null));
		resolverConConvenio(88L, 99L);

		aplicables();
		noAplicables();

		verify(coberturas, org.mockito.Mockito.times(2)).activasDe(ORG, PERSONA);
		verifyNoMoreInteractions(coberturas);
	}

	// =================================================================================
	// Apoyo — datos sinteticos
	// =================================================================================

	private List<CoberturaAplicable> aplicables() {
		return aplicablesEl(HOY);
	}

	private List<CoberturaAplicable> aplicablesEl(LocalDate fecha) {
		return servicio.aplicables(ORG, SEDE, PERSONA, PRACTICA, fecha);
	}

	private List<CoberturaNoAplicable> noAplicables() {
		return noAplicablesEl(HOY);
	}

	private List<CoberturaNoAplicable> noAplicablesEl(LocalDate fecha) {
		return servicio.noAplicables(ORG, SEDE, PERSONA, PRACTICA, fecha);
	}

	private void darCoberturas(CoberturaPaciente... lista) {
		given(coberturas.activasDe(ORG, PERSONA)).willReturn(List.of(lista));
	}

	private void resolverConConvenio(long financiadorId, long planId) {
		given(aranceles.resolver(anyLong(), anyLong(), eq(financiadorId),
				eq(planId), anyLong(), any()))
				.willReturn(resuelta());
	}

	private static ResolucionDeArancel resuelta() {
		return ResolucionDeArancel.resuelta(new ArancelVigente(
				12L, "CONV-1", "Convenio Sintetico", "PRESTACION", 88L, 99L, PRACTICA, 5L,
				new BigDecimal("12000.00"), new BigDecimal("10000.00"),
				new BigDecimal("2000.00"), "ARS",
				false, false, false, null,
				LocalDate.of(2027, 1, 1), null, LocalDate.of(2027, 1, 1), null, HOY));
	}

	private static CoberturaPaciente financiada(
			long id, long financiadorId, long planId, boolean principal,
			LocalDate desde, LocalDate hasta, LocalDate credencialHasta) {

		CoberturaPaciente cobertura = CoberturaPaciente.financiada(
				ORG, PERSONA,
				new ReferenciaDeCobertura(
						financiadorId, "OS-" + financiadorId, "Financiador " + financiadorId,
						"PREPAGA", planId, "P-" + planId, "Plan " + planId, false, false,
						new BigDecimal("500.00"), "ARS", LocalDate.of(2027, 1, 1), Instant.now()),
				"AF-" + id, credencialHasta, desde, hasta, principal, null);
		ReflectionTestUtils.setField(cobertura, "id", id);
		return cobertura;
	}
}
