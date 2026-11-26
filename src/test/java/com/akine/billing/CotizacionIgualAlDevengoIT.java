package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.application.Cotizacion;
import com.akine.billing.application.CotizadorDeAtencion;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.testsupport.InsertarConId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Foco 5 del agente: lo que el asistente cotiza ({@link CotizadorDeAtencion#cotizar}) es lo que el
 * cierre real devenga. Para tres casos —particular, plan con coseguro y tope mensual alcanzado— el
 * importe y el responsable de la cotizacion coinciden con las obligaciones que deja
 * {@code SesionService#cerrar}. Las sesiones cierran sin tratamientos: el devengo toma la practica
 * principal de la oferta, que es la que la cotizacion toma.
 *
 * <p>Fixture copiado de {@link ObligacionDelFinanciadorIT}: oferta de 8500, arancel que paga 7000.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class CotizacionIgualAlDevengoIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	@Autowired private SesionService sesionService;
	@Autowired private CotizadorDeAtencion cotizador;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("particular: sin cobertura vigente, la cotizacion y la deuda son el precio de la oferta")
	void particular() {
		Fixture f = crearFixture();
		jdbc.update("UPDATE cobertura_paciente SET vigencia_hasta = CAST(? AS date) WHERE id = ?",
				hoy().minusDays(1), f.cobertura());

		Cotizacion cotizacion = cotizar(f);
		long sesion = insertarSesion(f);
		cerrar(f, sesion);

		assertThat(cotizacion.condicion()).isEqualTo(Cotizacion.PARTICULAR);
		assertThat(cotizacion.motivoParticular()).isEqualTo(Cotizacion.SIN_COBERTURA);
		assertThat(obligacionesDe(sesion)).singleElement().satisfies(fila -> {
			assertThat(fila.get("concepto")).isEqualTo("PARTICULAR");
			assertThat(fila.get("responsable")).isEqualTo("PACIENTE");
			assertThat((BigDecimal) fila.get("importe_original"))
					.isEqualByComparingTo(cotizacion.aCobrarAlPaciente());
		});
	}

	@Test
	@DisplayName("plan con coseguro: lo que paga la obra social y el coseguro son las dos deudas")
	void plan_con_coseguro() {
		Fixture f = crearFixture();

		Cotizacion cotizacion = cotizar(f);
		long sesion = insertarSesion(f);
		cerrar(f, sesion);

		assertThat(cotizacion.condicion()).isEqualTo(Cotizacion.COBERTURA);
		assertThat(cotizacion.coberturaId()).isEqualTo(f.cobertura());
		assertThat(cotizacion.importeFinanciador()).isEqualByComparingTo("7000.00");
		assertThat(cotizacion.coseguro()).isEqualByComparingTo("1500.00");
		List<Map<String, Object>> filas = obligacionesDe(sesion);
		assertThat(filas).extracting(fila -> fila.get("concepto"))
				.containsExactly("FINANCIADOR", "COSEGURO");
		assertThat(filas.get(0).get("responsable")).isEqualTo("FINANCIADOR");
		assertThat((BigDecimal) filas.get(0).get("importe_original"))
				.isEqualByComparingTo(cotizacion.importeFinanciador());
		assertThat(filas.get(1).get("responsable")).isEqualTo("PACIENTE");
		assertThat((BigDecimal) filas.get(1).get("importe_original"))
				.isEqualByComparingTo(cotizacion.aCobrarAlPaciente());
	}

	@Test
	@DisplayName("tope mensual alcanzado: la cotizacion dice particular y la segunda sesion devenga particular")
	void tope_mensual_alcanzado() {
		Fixture f = crearFixture();
		jdbc.update("UPDATE convenio SET limite_sesiones_mensual = 1 WHERE id = ?", f.convenio());
		long primera = insertarSesion(f);
		cerrar(f, primera);

		Cotizacion cotizacion = cotizar(f);
		long segunda = insertarSesion(f);
		cerrar(f, segunda);

		assertThat(cotizacion.condicion()).isEqualTo(Cotizacion.PARTICULAR);
		assertThat(cotizacion.motivoParticular()).isEqualTo(Cotizacion.TOPE_MENSUAL);
		assertThat(cotizacion.topeMensual()).isEqualTo(1);
		assertThat(cotizacion.sesionesCubiertasEnElMes()).isEqualTo(1);
		assertThat(obligacionesDe(segunda)).singleElement().satisfies(fila -> {
			assertThat(fila.get("concepto")).isEqualTo("PARTICULAR");
			assertThat(fila.get("responsable")).isEqualTo("PACIENTE");
			assertThat((BigDecimal) fila.get("importe_original"))
					.isEqualByComparingTo(cotizacion.aCobrarAlPaciente());
		});
	}

	// =================================================================================
	// Operaciones
	// =================================================================================

	private Cotizacion cotizar(Fixture f) {
		return cotizador.cotizar(f.organizationId(), f.consultorioId(), f.personaId(), f.ofertaId(),
				null, hoy());
	}

	private void cerrar(Fixture f, long sesionId) {
		long version = sesionService.ver(f.profesional(), f.consultorioId(), sesionId).version();
		sesionService.cerrar(f.profesional(), f.consultorioId(), sesionId,
				new CierreDeSesion(Asistencia.PRESENTE, "Terapia manual", null, null, null, null),
				version);
	}

	private List<Map<String, Object>> obligacionesDe(long sesionId) {
		return jdbc.queryForList(
				"SELECT * FROM obligacion WHERE sesion_id = ? ORDER BY id", sesionId);
	}

	private static LocalDate hoy() {
		return LocalDate.now(ZoneId.of(ZONA));
	}

	// =================================================================================
	// Fixture — todo sintetico, el de ObligacionDelFinanciadorIT
	// =================================================================================

	private record Fixture(
			long organizationId,
			long consultorioId,
			long profesionalCuentaId,
			long profesionalMembershipId,
			long personaId,
			long historiaClinicaId,
			long ofertaId,
			long cobertura,
			long convenio) {

		com.akine.encounter.application.OperatingActor profesional() {
			return new com.akine.encounter.application.OperatingActor(
					profesionalCuentaId, false, organizationId, consultorioId);
		}
	}

	private Fixture crearFixture() {
		boolean admiteObraSocial = true;
		String s = UUID.randomUUID().toString().substring(0, 10);

		long org = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", "Centro " + s, "f4-it-" + s, ZONA);
		long sede = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", org, "Sede " + s, ZONA);

		long profesional = cuenta("f4-pro-" + s);
		long membership = membership(org, sede, profesional, "PROFESIONAL");
		long admin = cuenta("f4-adm-" + s);
		// cobro:register: leer la cuenta corriente y operar presentaciones.
		membership(org, sede, admin, "ADMINISTRATIVO");

		String apellido = "Paciente" + s;
		long persona = insertar("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave, nombre_clave,
				                     active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", org, apellido, apellido.toUpperCase());
		jdbc.update("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, CURRENT_TIMESTAMP, ?, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", org, persona, admin);
		long historia = insertar("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              active, version, created_at, updated_at)
				VALUES (?, ?, CURRENT_TIMESTAMP, ?, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", org, persona, profesional);

		long servicio = insertar("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'INDIVIDUAL', false, false, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", "F4-" + s.toUpperCase(), "Servicio " + s);
		long oferta = insertar("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, precio_base, moneda, admite_obra_social,
				        requiere_caso_clinico, genera_registro_clinico, requiere_profesional,
				        requiere_espacio, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'INDIVIDUAL', 60, 1, 8500.00, 'ARS', CAST(? AS boolean), false, false, true, false,
				        CAST(CURRENT_DATE - INTERVAL '5 years' AS date), true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", org, sede, servicio, "Oferta " + s, admiteObraSocial ? 1 : 0);

		long financiador = insertar("""
				INSERT INTO financiador (organization_id, codigo, nombre, tipo, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 'OBRA_SOCIAL', true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", org, "OS-" + s, "Financiador " + s);
		long plan = insertar("""
				INSERT INTO plan_cobertura (organization_id, financiador_id, codigo, nombre,
				                            vigencia_desde, requiere_autorizacion,
				                            requiere_credencial, active, version,
				                            created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01', false, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", org, financiador, "P-" + s, "Plan " + s);
		// La credencial vencio ayer: B-2 la informa como alerta y no excluye la cobertura.
		long cobertura = insertar("""
				INSERT INTO cobertura_paciente (
				        organization_id, persona_id, tipo,
				        financiador_id, financiador_codigo, financiador_nombre, financiador_tipo,
				        plan_id, plan_codigo, plan_nombre,
				        requeria_autorizacion, requeria_credencial, referencia_capturada_el,
				        numero_afiliado, credencial_vigencia_hasta, vigencia_desde, principal,
				        active, version, created_at, updated_at)
				VALUES (?, ?, 'FINANCIADA', ?, ?, ?, 'OBRA_SOCIAL', ?, ?, ?, false, true, CURRENT_TIMESTAMP,
				        ?, CAST(? AS date), '2020-01-01', true, true, 0,
				        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", org, persona, financiador, "OS-" + s, "Financiador " + s, plan, "P-" + s,
				"Plan " + s, "AF-" + s,
				// La credencial vence AYER en la zona de la sede, que es como la mira el backend: con
				// CURDATE() (UTC en MySQL) entre las 21 y las 24 de Argentina es otro dia y el test
				// fallaba solo a esa hora.
				hoy().minusDays(1));

		long especialidad = insertar("""
				INSERT INTO especialidad (organization_id, codigo, name, valid_from, active,
				                          version, created_at, updated_at)
				VALUES (?, ?, ?, '2020-01-01 00:00:00.000000', true, 0,
				        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", org, "E-" + s, "Especialidad " + s);
		long kine = insertar("""
				INSERT INTO practica (organization_id, especialidad_id, codigo, name, valid_from,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01 00:00:00.000000', true, 0,
				        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", org, especialidad, "K-" + s, "Kinesiologia " + s);
		jdbc.update("""
				INSERT INTO oferta_practica (organization_id, consultorio_id, oferta_id, practica_id,
				                             principal, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", org, sede, oferta, kine);

		String codigo = "CONV-" + s;
		long convenio = insertar("""
				INSERT INTO convenio (organization_id, consultorio_id, financiador_id, plan_id,
				                      codigo, nombre, modalidad, vigencia_desde, moneda,
				                      requiere_orden, requiere_autorizacion, requiere_credencial,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, 'POR_PRESTACION', '2020-01-01', 'ARS', true, false, true, true, 0,
				        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", org, sede, financiador, plan, codigo, "Convenio " + s);
		long arancel = insertar("""
				INSERT INTO convenio_arancel (organization_id, consultorio_id, convenio_id,
				                              practica_id, importe_total, importe_financiador,
				                              coseguro, moneda, vigencia_desde, active, version,
				                              created_at, updated_at)
				VALUES (?, ?, ?, ?, 8500.00, 7000.00, 1500.00, 'ARS', '2020-01-01', true, 0,
				        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", org, sede, convenio, kine);

		return new Fixture(org, sede, profesional, membership, persona, historia, oferta, cobertura,
				convenio);
	}

	private long cuenta(String prefijo) {
		String email = prefijo + "@ejemplo.test";
		return insertar("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", email, email);
	}

	private long membership(long org, long sede, long cuenta, String rol) {
		return insertar("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, ?, false, (CURRENT_TIMESTAMP - INTERVAL '5 years'),
				        'ACTIVA', true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", org, sede, cuenta, rol);
	}

	private long insertarSesion(Fixture f) {
		return insertar("""
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, oferta_id,
				                    profesional_membership_id, estado, iniciada_en,
				                    iniciada_por_cuenta_id, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'BORRADOR', CURRENT_TIMESTAMP, ?, 0,
				        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", f.organizationId(), f.consultorioId(), f.historiaClinicaId(), f.ofertaId(),
				f.profesionalMembershipId(), f.profesionalCuentaId());
	}

	private long insertar(String insert, Object... args) {
		return InsertarConId.insertar(jdbc, insert, args);
	}
}
