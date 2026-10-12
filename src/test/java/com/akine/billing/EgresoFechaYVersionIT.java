package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.EgresoItFixture.Tenant;
import com.akine.billing.application.CajaService;
import com.akine.billing.application.EgresoCommand;
import com.akine.billing.application.EgresoService;
import com.akine.billing.application.EgresoView;
import com.akine.billing.application.PagoEgresoService;
import com.akine.billing.domain.CategoriaEgreso;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dos defectos de la bandeja de egresos que encontro la pantalla (web #63).
 *
 * <ol>
 *   <li><b>El filtro por fecha usaba el dia UTC.</b> {@code DATE(registrado_en)} sobre una columna
 *       UTC: un egreso cargado entre las 21 y las 24 de Cordoba caia en el dia siguiente y
 *       desaparecia de {@code hasta = hoy}.</li>
 *   <li><b>{@code PUT /egresos/{id}} no tenia control optimista.</b> La respuesta traia
 *       {@code version} y el cuerpo no la pedia: dos correcciones del mismo borrador, ganaba la
 *       ultima sin 409.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class EgresoFechaYVersionIT {

	@Autowired private JdbcTemplate jdbc;
	@Autowired private CajaService cajaService;
	@Autowired private EgresoService egresoService;
	@Autowired private PagoEgresoService pagoService;

	private EgresoItFixture fixture;

	@BeforeEach
	void preparar() {
		fixture = new EgresoItFixture(jdbc, cajaService, egresoService, pagoService);
	}

	@Test
	@DisplayName("un egreso de las 22:30 de Cordoba es de ese dia: entra con hasta = ese dia")
	void filtro_por_dia_de_la_sede() {
		Tenant t = fixture.crearTenant();
		EgresoView egreso = fixture.registrar(t, "12000.00");
		LocalDate dia = LocalDate.of(2026, 10, 9);
		Instant nocheDeCordoba = dia.atTime(22, 30).atZone(ZoneId.of(EgresoItFixture.ZONA)).toInstant();
		// Como texto UTC y no como Timestamp: sin depender de la zona de la JVM ni del driver.
		jdbc.update("UPDATE egreso SET registrado_en = ? WHERE id = ?",
				nocheDeCordoba.toString().replace("T", " ").replace("Z", ""), egreso.id());
		assertThat(jdbc.queryForObject(
				"SELECT DATE_FORMAT(registrado_en, '%Y-%m-%d %H:%i') FROM egreso WHERE id = ?",
				String.class, egreso.id()))
				.as("la columna guarda UTC: el dia UTC ya es el siguiente")
				.isEqualTo("2026-10-10 01:30");

		assertThat(buscar(t, null, dia)).as("hasta = ese dia lo incluye").containsExactly(egreso.id());
		assertThat(buscar(t, dia, dia)).containsExactly(egreso.id());
		assertThat(buscar(t, dia.plusDays(1), null)).as("desde = el dia siguiente ya no").isEmpty();
		assertThat(buscar(t, null, dia.minusDays(1))).isEmpty();
	}

	@Test
	@DisplayName("corregir el borrador con una version vieja es 409: la segunda correccion no pisa a la primera")
	void editar_con_version_vieja() {
		Tenant t = fixture.crearTenant();
		EgresoView borrador = fixture.registrar(t, "15000.00");
		long leida = borrador.version();

		EgresoView primera = egresoService.editar(
				t.actor(), t.consultorioId(), borrador.id(), correccion("16000.00"), leida);
		assertThat(primera.version())
				.as("la respuesta trae la version nueva, la que la pantalla manda en la siguiente")
				.isEqualTo(leida + 1)
				.isEqualTo(versionEnLaBase(borrador.id()));

		assertThatThrownBy(() -> egresoService.editar(
				t.actor(), t.consultorioId(), borrador.id(), correccion("99999.00"), leida))
				.as("DP-21: la version vieja sale como concurrent-modification")
				.isInstanceOf(OptimisticLockingFailureException.class);
		assertThat(jdbc.queryForObject(
				"SELECT importe_total FROM egreso WHERE id = ?", BigDecimal.class, borrador.id()))
				.as("la correccion vieja no piso a la primera")
				.isEqualByComparingTo("16000.00");

		EgresoView segunda = egresoService.editar(
				t.actor(), t.consultorioId(), borrador.id(), correccion("17000.00"), primera.version());
		assertThat(segunda.importeTotal()).isEqualByComparingTo("17000.00");
		assertThat(segunda.version()).isEqualTo(versionEnLaBase(borrador.id()));
	}

	// =================================================================================

	private java.util.List<Long> buscar(Tenant t, LocalDate desde, LocalDate hasta) {
		return egresoService.buscar(t.actor(), t.consultorioId(), null, null, null, desde, hasta, 100, 0)
				.stream().map(EgresoView::id).toList();
	}

	private long versionEnLaBase(long egresoId) {
		return jdbc.queryForObject("SELECT version FROM egreso WHERE id = ?", Long.class, egresoId);
	}

	private static EgresoCommand correccion(String importe) {
		return new EgresoCommand(
				CategoriaEgreso.HONORARIOS_PROFESIONALES, null, null, null, null,
				null, null, "Liquidacion corregida", new BigDecimal(importe), null,
				null, null, null, null);
	}
}
