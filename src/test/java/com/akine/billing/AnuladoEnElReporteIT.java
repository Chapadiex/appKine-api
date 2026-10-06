package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.infrastructure.ObligacionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El indicador de anulados del reporte economico (M23) no cuenta filas dadas de baja (G-2).
 *
 * <p>Los otros agregados de {@link ObligacionRepository} filtran {@code deletedAt IS NULL};
 * {@code sumarAnuladoEnElReporte} no lo hacia, asi que una obligacion anulada y despues dada de
 * baja seguia sumando en "anulado" mientras ya no existia para el resto del reporte.
 *
 * <p>Es IT y no unitario porque lo que se prueba es la consulta JPQL contra MySQL: un mock del
 * repositorio devolveria lo que el test le diga.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class AnuladoEnElReporteIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	@Autowired private ObligacionRepository obligaciones;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("una obligacion anulada y dada de baja no suma en el indicador de anulados")
	void la_baja_logica_no_suma_en_anulados() {
		Fixture fixture = crearFixture();
		// Las dos sobre la misma sesion: uk_obligacion_prestacion incluye deleted_key, asi que la
		// dada de baja y la vigente conviven, que es justamente el caso que el filtro distingue.
		insertarAnulada(fixture, "1000.00", false);
		insertarAnulada(fixture, "3000.00", true);

		Instant ahora = Instant.now();
		BigDecimal anulado = obligaciones.sumarAnuladoEnElReporte(
				fixture.organizationId(), fixture.consultorioId(),
				ahora.minus(Duration.ofDays(1)), ahora.plus(Duration.ofDays(1)));

		assertThat(anulado)
				.as("solo la anulada vigente; la dada de baja ya no existe para el reporte")
				.isEqualByComparingTo("1000.00");
	}

	// =================================================================================
	// Fixture — insercion directa, mismo criterio que CobroConcurrenteIT
	// =================================================================================

	private record Fixture(long organizationId, long consultorioId, long personaId,
			long cuentaId, long sesionId, long ofertaId) {
	}

	private void insertarAnulada(Fixture fixture, String importe, boolean dadaDeBaja) {
		jdbc.update("""
				INSERT INTO obligacion (organization_id, consultorio_id, sesion_id, persona_id,
				                        responsable, importe_original, saldo, moneda, estado,
				                        oferta_id, snapshot_nombre, snapshot_precio, devengada_en,
				                        anulada_en, anulada_por_cuenta_id, motivo_anulacion,
				                        deleted_at, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'PACIENTE', ?, 0.00, 'ARS', 'ANULADA',
				        ?, 'Sesion sintetica', ?, UTC_TIMESTAMP(6),
				        UTC_TIMESTAMP(6), ?, 'Anulacion sintetica',
				        IF(?, UTC_TIMESTAMP(6), NULL), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), fixture.consultorioId(), fixture.sesionId(),
				fixture.personaId(), new BigDecimal(importe), fixture.ofertaId(),
				new BigDecimal(importe), fixture.cuentaId(), dadaDeBaja);
	}

	private Fixture crearFixture() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);

		long organizationId = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM organization WHERE slug = ?",
				new Object[]{"Centro " + sufijo, "anulado-it-" + sufijo, ZONA},
				new Object[]{"anulado-it-" + sufijo});

		long consultorioId = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM consultorio WHERE organization_id = ? AND name = ?",
				new Object[]{organizationId, "Sede " + sufijo, ZONA},
				new Object[]{organizationId, "Sede " + sufijo});

		String email = "anulado-it-" + sufijo + "@ejemplo.test";
		long cuentaId = insertar("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM cuenta WHERE email_normalizado = ?",
				new Object[]{email, email}, new Object[]{email});

		String apellido = "Paciente" + sufijo;
		long personaId = insertar("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave, nombre_clave,
				                     active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM persona WHERE organization_id = ? AND apellido = ?",
				new Object[]{organizationId, apellido, apellido.toUpperCase()},
				new Object[]{organizationId, apellido});

		long historiaId = insertar("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM historia_clinica WHERE organization_id = ? AND persona_id = ?",
				new Object[]{organizationId, personaId, cuentaId},
				new Object[]{organizationId, personaId});

		long servicioId = insertar("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'INDIVIDUAL', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM servicio WHERE codigo = ?",
				new Object[]{"ANUL-" + sufijo.toUpperCase(), "Servicio " + sufijo},
				new Object[]{"ANUL-" + sufijo.toUpperCase()});

		long ofertaId = insertar("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, precio_base, moneda, admite_obra_social,
				        requiere_caso_clinico, genera_registro_clinico, requiere_profesional,
				        requiere_espacio, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'INDIVIDUAL', 60, 1, 1000.00, 'ARS', 0, 0, 0, 0, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", """
				SELECT id FROM oferta_servicio_consultorio
				 WHERE organization_id = ? AND nombre_comercial = ?
				""", new Object[]{organizationId, consultorioId, servicioId, "Oferta " + sufijo},
				new Object[]{organizationId, "Oferta " + sufijo});

		jdbc.update("""
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, oferta_id,
				                    profesional_membership_id, estado, iniciada_en,
				                    iniciada_por_cuenta_id, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'BORRADOR', UTC_TIMESTAMP(6), ?, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, historiaId, ofertaId, 1L, cuentaId);
		long sesionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		return new Fixture(organizationId, consultorioId, personaId, cuentaId, sesionId, ofertaId);
	}

	private long insertar(String insert, String select, Object[] insertArgs, Object[] selectArgs) {
		jdbc.update(insert, insertArgs);
		return jdbc.queryForObject(select, Long.class, selectArgs);
	}
}
