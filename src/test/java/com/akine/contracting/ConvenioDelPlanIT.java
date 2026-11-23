package com.akine.contracting;

import com.akine.TestcontainersConfiguration;
import com.akine.contracting.ConvenioFixtures.Desenlace;
import com.akine.contracting.ConvenioFixtures.Escenario;
import com.akine.contracting.application.ArancelCommands.ArancelAltaCommand;
import com.akine.contracting.application.ArancelService;
import com.akine.contracting.application.ArancelView;
import com.akine.contracting.application.ConvenioAltaCommand;
import com.akine.contracting.application.ConvenioService;
import com.akine.contracting.application.ConvenioView;
import com.akine.contracting.application.OperatingActor;
import com.akine.contracting.domain.ModalidadConvenio;
import com.akine.contracting.domain.exception.ArancelSolapadoException;
import com.akine.contracting.spi.ArancelDirectory;
import com.akine.contracting.spi.ResolucionDeArancel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;

import static com.akine.contracting.ConvenioFixtures.causaEs;
import static com.akine.contracting.ConvenioFixtures.enParalelo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * El convenio del plan (V43): lo que paga la obra social se carga una vez en el plan y vale en
 * todas las sedes; un convenio propio de una sede manda sobre el en esa sede.
 *
 * <p>Contra la base real porque lo que decide son la consulta con {@code consultorio_id IS NULL},
 * el orden que pone primero al de la sede, el unique parcial {@code uk_convenio_del_plan_activo} y
 * el {@code FOR UPDATE} sobre la fila del plan.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class ConvenioDelPlanIT {

	private static final LocalDate ENERO = LocalDate.of(2027, 1, 1);
	private static final LocalDate MARZO = LocalDate.of(2027, 3, 1);
	private static final LocalDate JUNIO_30 = LocalDate.of(2027, 6, 30);
	private static final LocalDate DICIEMBRE = LocalDate.of(2027, 12, 31);
	private static final LocalDate ABRIL_15 = LocalDate.of(2027, 4, 15);

	@Autowired private ConvenioService convenioService;
	@Autowired private ArancelService arancelService;
	@Autowired private ArancelDirectory arancelDirectory;
	@Autowired private JdbcTemplate jdbc;

	private ConvenioFixtures fixtures;

	@BeforeEach
	void prepararFixtures() {
		fixtures = new ConvenioFixtures(jdbc);
	}

	@Test
	@DisplayName("dos altas simultaneas del convenio del plan dejan uno solo y devuelven el mismo")
	void asegurar_es_idempotente_con_concurrencia() {
		Escenario escenario = fixtures.crear();
		OperatingActor actor = actor(escenario);

		Callable<ConvenioView> asegurar = () -> convenioService.asegurarDelPlan(
				actor, escenario.financiadorId(), escenario.planId());
		List<Desenlace<ConvenioView>> desenlaces = enParalelo(List.of(asegurar, asegurar, asegurar));

		assertThat(desenlaces).as("ninguna falla: %s", desenlaces).noneMatch(Desenlace::fallo);
		assertThat(desenlaces.stream().map(d -> d.valor().id()).distinct().count())
				.as("las tres devuelven el mismo convenio").isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM convenio
				 WHERE organization_id = ? AND plan_id = ? AND consultorio_id IS NULL AND active
				""", Long.class, escenario.organizationId(), escenario.planId()))
				.isEqualTo(1);
		assertThat(desenlaces.get(0).valor().consultorioId()).isNull();
	}

	@Test
	@DisplayName("el precio cargado en el plan resuelve en otra sede, y el convenio de una sede manda")
	void el_precio_del_plan_vale_en_todas_las_sedes_salvo_convenio_propio() {
		Escenario escenario = fixtures.crear();
		OperatingActor actor = actor(escenario);
		long otraSede = fixtures.otraSede(escenario);

		ConvenioView delPlan = convenioService.asegurarDelPlan(
				actor, escenario.financiadorId(), escenario.planId());
		ArancelView cargado = arancelService.crear(
				actor, escenario.consultorioId(), delPlan.id(), arancel(escenario, "7000.00"));
		assertThat(jdbc.queryForObject("SELECT consultorio_id FROM convenio_arancel WHERE id = ?",
				Long.class, cargado.id()))
				.as("el arancel copia la sede del convenio: ninguna").isNull();

		ResolucionDeArancel enOtraSede = resolver(escenario, otraSede);
		assertThat(enOtraSede.estaResuelta()).as("vale en una sede donde nadie cargo nada").isTrue();
		assertThat(enOtraSede.arancel().convenioId()).isEqualTo(delPlan.id());
		assertThat(enOtraSede.arancel().importeFinanciador()).isEqualByComparingTo("7000.00");

		// La sede de la ruta firma su propio convenio con otro precio: ahi manda el suyo.
		ConvenioView propio = convenioService.crear(actor, escenario.consultorioId(),
				new ConvenioAltaCommand(escenario.financiadorId(), escenario.planId(), "PROPIO",
						"Propio de la sede", ModalidadConvenio.POR_PRESTACION, ENERO, DICIEMBRE,
						"ARS", null, null, null, null, null, null));
		arancelService.crear(
				actor, escenario.consultorioId(), propio.id(), arancel(escenario, "9000.00"));

		ResolucionDeArancel enLaSede = resolver(escenario, escenario.consultorioId());
		assertThat(enLaSede.arancel().convenioId()).isEqualTo(propio.id());
		assertThat(enLaSede.arancel().importeFinanciador()).isEqualByComparingTo("9000.00");
		assertThat(resolver(escenario, otraSede).arancel().convenioId())
				.as("y la otra sede sigue con el del plan").isEqualTo(delPlan.id());
	}

	@Test
	@DisplayName("dos precios simultaneos del plan que se pisan: uno entra y el otro recibe 409")
	void dos_aranceles_solapados_del_plan_no_pasan_los_dos() {
		Escenario escenario = fixtures.crear();
		OperatingActor actor = actor(escenario);
		long convenioId = convenioService.asegurarDelPlan(
				actor, escenario.financiadorId(), escenario.planId()).id();

		List<Desenlace<ArancelView>> desenlaces = enParalelo(List.of(
				() -> arancelService.crear(actor, escenario.consultorioId(), convenioId,
						arancel(escenario, "7000.00", ENERO, JUNIO_30)),
				() -> arancelService.crear(actor, escenario.consultorioId(), convenioId,
						arancel(escenario, "8000.00", MARZO, DICIEMBRE))));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("exactamente uno entra: %s", desenlaces).isEqualTo(1);
		assertThat(desenlaces.stream().filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), ArancelSolapadoException.class)).count())
				.as("y el otro recibe arancel-solapado, no un deadlock: %s", desenlaces)
				.isEqualTo(1);
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private ResolucionDeArancel resolver(Escenario escenario, long consultorioId) {
		return arancelDirectory.resolver(escenario.organizationId(), consultorioId,
				escenario.financiadorId(), escenario.planId(), escenario.practicaId(), ABRIL_15);
	}

	private static OperatingActor actor(Escenario escenario) {
		return new OperatingActor(
				escenario.accountId(), false, escenario.organizationId(), escenario.consultorioId());
	}

	private static ArancelAltaCommand arancel(Escenario escenario, String importe) {
		return arancel(escenario, importe, ENERO, DICIEMBRE);
	}

	private static ArancelAltaCommand arancel(
			Escenario escenario, String importe, LocalDate desde, LocalDate hasta) {
		return new ArancelAltaCommand(escenario.practicaId(), new BigDecimal(importe),
				new BigDecimal(importe), BigDecimal.ZERO, desde, hasta);
	}
}
