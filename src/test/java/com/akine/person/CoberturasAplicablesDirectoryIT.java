package com.akine.person;

import com.akine.TestcontainersConfiguration;
import com.akine.contracting.spi.ArancelDirectory;
import com.akine.contracting.spi.ArancelVigente;
import com.akine.contracting.spi.MotivoSinArancel;
import com.akine.contracting.spi.ResolucionDeArancel;
import com.akine.person.spi.CoberturaAplicable;
import com.akine.person.spi.CoberturaNoAplicable;
import com.akine.person.spi.CoberturasAplicablesDirectory;
import com.akine.person.support.CoberturaFixtures;
import com.akine.person.support.CoberturaFixtures.Plan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * B-2 contra MySQL real, a traves del borde publico {@code CoberturasAplicablesDirectory}: las
 * filas se siembran por SQL y solo el catalogo de convenios ({@code ArancelDirectory}) es un
 * doble, porque lo que se prueba aca es de {@code person}: que coberturas se eligen, en que orden
 * y con que tenant. Lo que decide un convenio es de {@code contracting}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class CoberturasAplicablesDirectoryIT {

	private static final long SEDE = 20L;
	private static final long PRACTICA = 33L;
	private static final LocalDate HOY = LocalDate.of(2027, 6, 15);

	@Autowired private CoberturasAplicablesDirectory directory;
	@Autowired private JdbcTemplate jdbc;
	@MockitoBean private ArancelDirectory aranceles;

	private CoberturaFixtures datos;

	@BeforeEach
	void setUp() {
		datos = new CoberturaFixtures(jdbc);
	}

	@Test
	@DisplayName("B2-E1 una cobertura financiada vigente con arancel resuelto aplica; trae referencia congelada y resolucion")
	void b2_e1_aplica() {
		long org = datos.organizacion();
		long persona = datos.persona(org, "Alvarez", "Ana", "30000001");
		Plan plan = datos.planNuevo(org);
		long cobertura = datos.cobertura(org, persona, plan, "62000123456",
				HOY.minusMonths(1), null, true, null);
		conConvenio(plan);

		List<CoberturaAplicable> aplicables =
				directory.aplicables(org, SEDE, persona, PRACTICA, HOY);

		assertThat(aplicables).singleElement().satisfies(a -> {
			assertThat(a.coberturaId()).isEqualTo(cobertura);
			assertThat(a.referencia().financiadorId()).isEqualTo(plan.financiadorId());
			assertThat(a.referencia().planId()).isEqualTo(plan.planId());
			// El nombre sale de la copia congelada en la cobertura, no del catalogo vivo.
			assertThat(a.referencia().financiadorNombre()).isEqualTo("Financiador Sintetico");
			assertThat(a.referencia().planNombre()).isEqualTo("Plan Sintetico");
			assertThat(a.resolucion().estaResuelta()).isTrue();
		});
		assertThat(directory.noAplicables(org, SEDE, persona, PRACTICA, HOY)).isEmpty();
	}

	@Test
	@DisplayName("B2-E2 la principal va primero y marcada; las demas por id ascendente")
	void b2_e2_orden() {
		long org = datos.organizacion();
		long persona = datos.persona(org, "Alvarez", "Ana", "30000001");
		Plan p1 = datos.planNuevo(org);
		Plan p2 = datos.planNuevo(org);
		Plan p3 = datos.planNuevo(org);
		long primera = datos.cobertura(org, persona, p1, "A1", HOY.minusMonths(1), null, false, null);
		long segunda = datos.cobertura(org, persona, p2, "A2", HOY.minusMonths(1), null, false, null);
		long principal = datos.cobertura(org, persona, p3, "A3", HOY.minusMonths(1), null, true, null);
		conConvenio(p1, p2, p3);

		List<CoberturaAplicable> aplicables =
				directory.aplicables(org, SEDE, persona, PRACTICA, HOY);

		assertThat(aplicables).extracting(CoberturaAplicable::coberturaId)
				.containsExactly(principal, primera, segunda);
		assertThat(aplicables.get(0).principal()).isTrue();
		assertThat(aplicables.get(1).principal()).isFalse();
		assertThat(aplicables.get(2).principal()).isFalse();
	}

	@Test
	@DisplayName("B2-E3 la vigencia es inclusiva en desde y hasta; el dia anterior y el posterior no aplican; hasta NULL es abierta")
	void b2_e3_vigencia_inclusiva() {
		long org = datos.organizacion();
		long persona = datos.persona(org, "Alvarez", "Ana", "30000001");
		Plan acotada = datos.planNuevo(org);
		Plan abierta = datos.planNuevo(org);
		LocalDate desde = LocalDate.of(2027, 3, 1);
		LocalDate hasta = LocalDate.of(2027, 3, 31);
		long cAcotada = datos.cobertura(org, persona, acotada, "A1", desde, hasta, false, null);
		long cAbierta = datos.cobertura(org, persona, abierta, "A2", desde, null, false, null);
		conConvenio(acotada, abierta);

		assertThat(ids(org, persona, desde)).as("el dia desde").containsExactly(cAcotada, cAbierta);
		assertThat(ids(org, persona, hasta)).as("el dia hasta").containsExactly(cAcotada, cAbierta);
		assertThat(ids(org, persona, desde.minusDays(1))).as("antes del desde").isEmpty();
		assertThat(ids(org, persona, hasta.plusDays(1))).as("despues del hasta, la abierta sigue")
				.containsExactly(cAbierta);
		assertThat(ids(org, persona, LocalDate.of(2040, 1, 1))).containsExactly(cAbierta);
	}

	@Test
	@DisplayName("B2-E4 una cobertura dada de baja no aplica ni figura como no aplicable")
	void b2_e4_baja() {
		long org = datos.organizacion();
		long persona = datos.persona(org, "Alvarez", "Ana", "30000001");
		Plan plan = datos.planNuevo(org);
		long cobertura = datos.cobertura(org, persona, plan, "A1", HOY.minusMonths(1), null, true, null);
		conConvenio(plan);
		assertThat(ids(org, persona, HOY)).as("antes de la baja").containsExactly(cobertura);

		datos.darDeBaja(cobertura);

		assertThat(ids(org, persona, HOY)).as("despues de la baja").isEmpty();
		assertThat(directory.noAplicables(org, SEDE, persona, PRACTICA, HOY)).isEmpty();
	}

	@Test
	@DisplayName("B2-E5 una cobertura PARTICULAR nunca aparece; una persona solo con particular da lista vacia")
	void b2_e5_particular() {
		long org = datos.organizacion();
		long soloParticular = datos.persona(org, "Alvarez", "Ana", "30000001");
		datos.particular(org, soloParticular, HOY.minusMonths(1), null, true);
		long mixta = datos.persona(org, "Benitez", "Beto", "30000002");
		datos.particular(org, mixta, HOY.minusMonths(1), null, true);
		Plan plan = datos.planNuevo(org);
		long financiada = datos.cobertura(org, mixta, plan, "A1", HOY.minusMonths(1), null, false, null);
		conConvenio(plan);

		assertThat(directory.aplicables(org, SEDE, soloParticular, PRACTICA, HOY)).isEmpty();
		assertThat(directory.noAplicables(org, SEDE, soloParticular, PRACTICA, HOY)).isEmpty();
		assertThat(ids(org, mixta, HOY)).containsExactly(financiada);
		assertThat(directory.noAplicables(org, SEDE, mixta, PRACTICA, HOY)).isEmpty();
	}

	@Test
	@DisplayName("B2-E6 SIN_CONVENIO_VIGENTE o SIN_ARANCEL_VIGENTE: no aplica y el motivo se lee en noAplicables")
	void b2_e6_sin_convenio_o_sin_arancel() {
		long org = datos.organizacion();
		long persona = datos.persona(org, "Alvarez", "Ana", "30000001");
		Plan sinConvenio = datos.planNuevo(org);
		Plan sinArancel = datos.planNuevo(org);
		long c1 = datos.cobertura(org, persona, sinConvenio, "A1", HOY.minusMonths(1), null, true, null);
		long c2 = datos.cobertura(org, persona, sinArancel, "A2", HOY.minusMonths(1), null, false, null);
		sinResolver(sinConvenio, MotivoSinArancel.SIN_CONVENIO_VIGENTE);
		sinResolver(sinArancel, MotivoSinArancel.SIN_ARANCEL_VIGENTE);

		assertThat(ids(org, persona, HOY)).isEmpty();
		assertThat(directory.noAplicables(org, SEDE, persona, PRACTICA, HOY))
				.extracting(CoberturaNoAplicable::coberturaId, CoberturaNoAplicable::motivo)
				.containsExactly(
						tuple(c1, MotivoSinArancel.SIN_CONVENIO_VIGENTE),
						tuple(c2, MotivoSinArancel.SIN_ARANCEL_VIGENTE));
	}

	@Test
	@DisplayName("B2-E7 una credencial vencida no excluye la cobertura; viaja credencialVencida=true")
	void b2_e7_credencial_vencida() {
		long org = datos.organizacion();
		long persona = datos.persona(org, "Alvarez", "Ana", "30000001");
		Plan vencida = datos.planNuevo(org);
		Plan vigente = datos.planNuevo(org);
		long cVencida = datos.cobertura(org, persona, vencida, "A1", HOY.minusYears(1), null, true,
				HOY.minusDays(1));
		long cVigente = datos.cobertura(org, persona, vigente, "A2", HOY.minusYears(1), null, false,
				HOY);
		conConvenio(vencida, vigente);

		List<CoberturaAplicable> aplicables =
				directory.aplicables(org, SEDE, persona, PRACTICA, HOY);

		assertThat(aplicables).extracting(CoberturaAplicable::coberturaId)
				.containsExactly(cVencida, cVigente);
		assertThat(aplicables.get(0).credencialVencida()).isTrue();
		assertThat(aplicables.get(0).credencialVigenciaHasta()).isEqualTo(HOY.minusDays(1));
		assertThat(aplicables.get(1).credencialVencida()).as("vence al dia siguiente").isFalse();
	}

	@Test
	@DisplayName("B2-E8 dos vigentes, una con convenio y otra sin: solo la primera en aplicables, la otra en noAplicables con motivo")
	void b2_e8_una_con_convenio_y_otra_sin() {
		long org = datos.organizacion();
		long persona = datos.persona(org, "Alvarez", "Ana", "30000001");
		Plan conConvenio = datos.planNuevo(org);
		Plan sinConvenio = datos.planNuevo(org);
		long cCon = datos.cobertura(org, persona, conConvenio, "A1", HOY.minusMonths(1), null, true, null);
		long cSin = datos.cobertura(org, persona, sinConvenio, "A2", HOY.minusMonths(1), null, false, null);
		conConvenio(conConvenio);
		sinResolver(sinConvenio, MotivoSinArancel.SIN_CONVENIO_VIGENTE);

		assertThat(ids(org, persona, HOY)).containsExactly(cCon);
		assertThat(directory.noAplicables(org, SEDE, persona, PRACTICA, HOY)).singleElement()
				.satisfies(n -> {
					assertThat(n.coberturaId()).isEqualTo(cSin);
					assertThat(n.motivo()).isEqualTo(MotivoSinArancel.SIN_CONVENIO_VIGENTE);
				});
	}

	@Test
	@DisplayName("B2-E9 una persona de otra organizacion o inexistente da listas vacias")
	void b2_e9_tenant_e_inexistente() {
		long orgPropia = datos.organizacion();
		long orgAjena = datos.organizacion();
		long persona = datos.persona(orgAjena, "Alvarez", "Ana", "30000001");
		Plan plan = datos.planNuevo(orgAjena);
		datos.cobertura(orgAjena, persona, plan, "A1", HOY.minusMonths(1), null, true, null);
		conConvenio(plan);
		assertThat(ids(orgAjena, persona, HOY)).as("en su organizacion si aplica").hasSize(1);

		assertThat(directory.aplicables(orgPropia, SEDE, persona, PRACTICA, HOY))
				.as("persona ajena").isEmpty();
		assertThat(directory.noAplicables(orgPropia, SEDE, persona, PRACTICA, HOY)).isEmpty();
		assertThat(directory.aplicables(orgPropia, SEDE, Long.MAX_VALUE - 1, PRACTICA, HOY))
				.as("persona inexistente").isEmpty();
		assertThat(directory.noAplicables(orgPropia, SEDE, Long.MAX_VALUE - 1, PRACTICA, HOY))
				.isEmpty();
	}

	@Test
	@DisplayName("B2-E10 es solo lectura: consultar no cambia ninguna fila ni deja auditoria")
	void b2_e10_solo_lectura() {
		long org = datos.organizacion();
		long persona = datos.persona(org, "Alvarez", "Ana", "30000001");
		Plan aplica = datos.planNuevo(org);
		Plan noAplica = datos.planNuevo(org);
		datos.cobertura(org, persona, aplica, "A1", HOY.minusMonths(1), null, true, null);
		datos.cobertura(org, persona, noAplica, "A2", HOY.minusMonths(1), null, false, null);
		conConvenio(aplica);
		sinResolver(noAplica, MotivoSinArancel.SIN_ARANCEL_VIGENTE);

		List<Map<String, Object>> coberturasAntes = foto("cobertura_paciente", org);
		List<Map<String, Object>> locksAntes = foto("cobertura_persona_lock", org);
		List<Map<String, Object>> personasAntes = foto("persona", org);
		int auditoriaAntes = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class);

		directory.aplicables(org, SEDE, persona, PRACTICA, HOY);
		directory.noAplicables(org, SEDE, persona, PRACTICA, HOY);

		assertThat(foto("cobertura_paciente", org)).isEqualTo(coberturasAntes);
		assertThat(foto("cobertura_persona_lock", org)).isEqualTo(locksAntes);
		assertThat(foto("persona", org)).isEqualTo(personasAntes);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class))
				.isEqualTo(auditoriaAntes);
	}

	// =================================================================================
	// Apoyo — datos sinteticos
	// =================================================================================

	private List<Long> ids(long org, long persona, LocalDate fecha) {
		return directory.aplicables(org, SEDE, persona, PRACTICA, fecha).stream()
				.map(CoberturaAplicable::coberturaId)
				.toList();
	}

	private List<Map<String, Object>> foto(String tabla, long org) {
		return jdbc.queryForList(
				"SELECT * FROM " + tabla + " WHERE organization_id = ? ORDER BY 1", org);
	}

	private void conConvenio(Plan... planes) {
		for (Plan plan : planes) {
			given(aranceles.resolver(anyLong(), anyLong(), eq(plan.financiadorId()),
					eq(plan.planId()), anyLong(), any(), any(LocalDate.class)))
					.willReturn(resuelta(plan));
		}
	}

	private void sinResolver(Plan plan, MotivoSinArancel motivo) {
		given(aranceles.resolver(anyLong(), anyLong(), eq(plan.financiadorId()),
				eq(plan.planId()), anyLong(), any(), any(LocalDate.class)))
				.willReturn(ResolucionDeArancel.sinArancel(motivo));
	}

	private static ResolucionDeArancel resuelta(Plan plan) {
		return ResolucionDeArancel.resuelta(new ArancelVigente(
				12L, "CONV-1", "Convenio Sintetico", "PRESTACION", plan.financiadorId(),
				plan.planId(), PRACTICA, 5L,
				new BigDecimal("12000.00"), new BigDecimal("10000.00"),
				new BigDecimal("2000.00"), "ARS",
				false, false, false, null,
				LocalDate.of(2027, 1, 1), null, LocalDate.of(2027, 1, 1), null, HOY));
	}
}
