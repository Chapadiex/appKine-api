package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.application.ClaseService;
import com.akine.activity.application.ClaseView;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.ProgramarClaseCommand;
import com.akine.activity.application.ProgramarSerieDeClasesCommand;
import com.akine.activity.application.ResultadoDeSerieDeClases;
import com.akine.activity.application.ResultadoDeSerieDeClases.MotivoDeOmision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Clases semanales contra MySQL real: cada fecha es una clase suelta, y la que choca se informa sin
 * frenar a las demas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class ClaseSerieIT {

	private static final ZoneId ZONA = ZoneId.of(ActividadItFixture.ZONA);
	private static final LocalTime DIECIOCHO = LocalTime.of(18, 0);
	private static final LocalTime DIECINUEVE = LocalTime.of(19, 0);

	@Autowired private ClaseService claseService;
	@Autowired private InscripcionService inscripcionService;
	@Autowired private JdbcTemplate jdbc;

	private Tenant tenant;
	private long salon;
	private LocalDate primerMartes;

	@BeforeEach
	void setUp() {
		ActividadItFixture fx = new ActividadItFixture(jdbc, inscripcionService);
		tenant = fx.crearTenant(20, 0);
		salon = crearEspacio("Salon " + UUID.randomUUID().toString().substring(0, 6), 20);
		// Dos semanas adelante: lejos de "hoy" en cualquier zona.
		primerMartes = LocalDate.now(ZONA).plusWeeks(2).with(TemporalAdjusters.next(DayOfWeek.TUESDAY));
	}

	@Test
	@DisplayName("Martes y jueves de tres semanas: seis clases, y el martes con el salon ocupado se informa")
	void la_fecha_que_choca_se_omite_y_las_demas_se_programan() {
		LocalDate martesOcupado = primerMartes.plusWeeks(1);
		ClaseView ajena = claseService.programar(tenant.actor(), tenant.consultorioId(), tenant.ofertaId(),
				new ProgramarClaseCommand(instante(martesOcupado, DIECIOCHO), instante(martesOcupado, DIECINUEVE),
						null, 8, "Otra clase", null, salon)).clase();

		ResultadoDeSerieDeClases resultado = serie(primerMartes, primerMartes.plusWeeks(2).plusDays(2), null);

		assertThat(resultado.programadas()).hasSize(5);
		assertThat(resultado.omitidas()).singleElement().satisfies(omitida -> {
			assertThat(omitida.fecha()).isEqualTo(martesOcupado);
			assertThat(omitida.motivo()).isEqualTo(MotivoDeOmision.RECURSO_OCUPADO);
		});
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM clase_programada WHERE espacio_id = ? AND estado = 'PROGRAMADA' AND id <> ?
				""", Integer.class, salon, ajena.id()))
				.as("lo que quedo en la base: cinco de la serie, sin tocar la clase ajena")
				.isEqualTo(5);
	}

	@Test
	@DisplayName("Reintentar la serie con la misma clave devuelve las mismas clases sin duplicarlas")
	void el_reintento_con_la_misma_clave_no_duplica() {
		String clave = UUID.randomUUID().toString();
		ResultadoDeSerieDeClases primera = serie(primerMartes, primerMartes.plusWeeks(1).plusDays(2), clave);
		ResultadoDeSerieDeClases segunda = serie(primerMartes, primerMartes.plusWeeks(1).plusDays(2), clave);

		assertThat(primera.programadas()).hasSize(4);
		assertThat(segunda.programadas()).extracting(ClaseView::id)
				.containsExactlyElementsOf(primera.programadas().stream().map(ClaseView::id).toList());
		assertThat(segunda.omitidas()).isEmpty();
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM clase_programada WHERE espacio_id = ?", Integer.class, salon))
				.isEqualTo(4);
	}

	@Test
	@DisplayName("Las fechas ya pasadas del rango se informan como PASADO y no se intentan")
	void las_fechas_pasadas_se_omiten() {
		LocalDate hoy = LocalDate.now(ZONA);
		ResultadoDeSerieDeClases resultado = claseService.programarSerie(tenant.actor(), tenant.consultorioId(),
				tenant.ofertaId(), new ProgramarSerieDeClasesCommand(hoy.minusDays(7), hoy.minusDays(1),
						Set.of(1, 2, 3, 4, 5, 6, 7), DIECIOCHO, DIECINUEVE, null, 8, null, null, salon));

		assertThat(resultado.programadas()).isEmpty();
		assertThat(resultado.omitidas()).hasSize(7)
				.allMatch(omitida -> omitida.motivo() == MotivoDeOmision.PASADO);
	}

	// =================================================================================

	private ResultadoDeSerieDeClases serie(LocalDate desde, LocalDate hasta, String clave) {
		return claseService.programarSerie(tenant.actor(), tenant.consultorioId(), tenant.ofertaId(),
				new ProgramarSerieDeClasesCommand(desde, hasta, Set.of(2, 4), DIECIOCHO, DIECINUEVE,
						null, 8, "Yoga sintetico", clave, salon));
	}

	private static java.time.Instant instante(LocalDate fecha, LocalTime hora) {
		return fecha.atTime(hora).atZone(ZONA).toInstant();
	}

	private long crearEspacio(String nombre, int capacidad) {
		jdbc.update("""
				INSERT INTO espacio (organization_id, consultorio_id, name, tipo, capacidad, valid_from,
				                     active, version, created_at, updated_at)
				VALUES (?, ?, ?, 'BOX', ?, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", tenant.organizationId(), tenant.consultorioId(), nombre, capacidad);
		return jdbc.queryForObject("""
				SELECT id FROM espacio WHERE organization_id = ? AND consultorio_id = ? AND name = ?
				""", Long.class, tenant.organizationId(), tenant.consultorioId(), nombre);
	}
}
