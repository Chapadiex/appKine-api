package com.akine.person;

import com.akine.TestcontainersConfiguration;
import com.akine.contracting.ConvenioFixtures.Desenlace;
import com.akine.contracting.application.ArancelCommands.ArancelAltaCommand;
import com.akine.contracting.application.ArancelService;
import com.akine.contracting.application.ArancelView;
import com.akine.contracting.domain.exception.ArancelSolapadoException;
import com.akine.contracting.domain.exception.OfertaNoAccesibleException;
import com.akine.contracting.spi.ResolucionDeArancel;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.offering.application.OfertaPrecioParticularService;
import com.akine.offering.application.OfertaPrecioParticularView;
import com.akine.offering.domain.exception.OfertaNotAccessibleException;
import com.akine.offering.domain.exception.PrecioParticularSolapadoException;
import com.akine.person.application.CoberturaParaOferta;
import com.akine.person.application.CoberturaParaOferta.Condicion;
import com.akine.person.application.CoberturaParaOferta.Motivo;
import com.akine.person.application.CoberturaParaOfertaService;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
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

import static com.akine.contracting.ConvenioFixtures.causaEs;
import static com.akine.contracting.ConvenioFixtures.enParalelo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-3 contra MySQL real: cobertura aplicable por oferta (RF-M08-006/007), arancel por oferta dentro
 * del convenio (RF-M16-008) y precio particular por vigencia (RF-M16-009), con el devengo de F-4
 * pasando por el cierre real de la sesion.
 *
 * <p>Las escrituras entran por los servicios reales —con el evaluador de permisos real y el lock
 * real—, no por INSERT, para que el test falle si el lock o el permiso se pierden.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class CoberturaPorOfertaIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	@Autowired private CoberturaParaOfertaService coberturaParaOferta;
	@Autowired private ArancelService arancelService;
	@Autowired private OfertaPrecioParticularService precioService;
	@Autowired private SesionService sesionService;
	@Autowired private JdbcTemplate jdbc;

	// =================================================================================
	// V80
	// =================================================================================

	@Test
	@DisplayName("V80 ejecuto: convenio_arancel.oferta_id nullable y la tabla de precios particulares")
	void v80_ejecuta() {
		assertThat(jdbc.queryForObject("""
				SELECT IS_NULLABLE FROM information_schema.COLUMNS
				 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'convenio_arancel'
				   AND COLUMN_NAME = 'oferta_id'
				""", String.class)).isEqualTo("YES");
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM information_schema.COLUMNS
				 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'oferta_precio_particular'
				   AND COLUMN_NAME = 'organization_id' AND IS_NULLABLE = 'NO'
				""", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
				 WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_convenio_arancel_oferta'
				""", Integer.class)).isEqualTo(1);
	}

	// =================================================================================
	// RF-M08-006 / 007
	// =================================================================================

	@Test
	@DisplayName("oferta con dos practicas: la principal sin arancel, la otra con arancel -> la "
			+ "cobertura aplica con la segunda; la cobertura sin convenio no aplica")
	void oferta_con_varias_practicas() {
		Fixture f = crear(true);
		// El arancel del fixture es de KINE (la principal). Se da de baja y se carga uno de FONO.
		jdbc.update("UPDATE convenio_arancel SET active = 0, deleted_at = UTC_TIMESTAMP(6), "
				+ "deactivation_reason = 'IT' WHERE id = ?", f.arancel());
		arancelGeneral(f, f.fono(), "9000.00", "8000.00", "1000.00");
		long sinConvenio = coberturaSinConvenio(f);

		CoberturaParaOferta r = coberturaParaOferta.resolver(
				f.admin(), f.persona(), f.oferta(), hoy());

		assertThat(r.condicionSugerida()).isEqualTo(Condicion.COBERTURA);
		assertThat(r.aplicables()).singleElement().satisfies(a -> {
			assertThat(a.coberturaId()).isEqualTo(f.cobertura());
			assertThat(a.practicaId()).isEqualTo(f.fono());
			assertThat(a.arancel().importeTotal()).isEqualByComparingTo("9000.00");
			assertThat(a.practicas()).extracting(CoberturaParaOferta.Practica::practicaId)
					.containsExactly(f.kine(), f.fono());
			assertThat(a.practicas().getFirst().motivo()).isEqualTo(Motivo.SIN_ARANCEL_VIGENTE);
		});
		assertThat(r.noAplicables()).singleElement().satisfies(n -> {
			assertThat(n.coberturaId()).isEqualTo(sinConvenio);
			assertThat(n.motivo()).isEqualTo(Motivo.SIN_CONVENIO_VIGENTE);
		});
	}

	@Test
	@DisplayName("CA-M08-006-06 / 007-06: oferta sin obra social -> PARTICULAR con el precio, y la "
			+ "cobertura del paciente queda intacta")
	void pilates_particular_sin_tocar_la_cobertura() {
		Fixture f = crear(false);
		Map<String, Object> antes = jdbc.queryForMap(
				"SELECT active, version FROM cobertura_paciente WHERE id = ?", f.cobertura());

		CoberturaParaOferta r = coberturaParaOferta.resolver(
				f.admin(), f.persona(), f.oferta(), hoy());

		assertThat(r.condicionSugerida()).isEqualTo(Condicion.PARTICULAR);
		assertThat(r.precioParticular()).isEqualByComparingTo("8500.00");
		assertThat(r.noAplicables()).extracting(CoberturaParaOferta.NoAplicable::motivo)
				.containsOnly(Motivo.OFERTA_NO_ADMITE_OBRA_SOCIAL);
		assertThat(jdbc.queryForMap(
				"SELECT active, version FROM cobertura_paciente WHERE id = ?", f.cobertura()))
				.isEqualTo(antes);
	}

	// =================================================================================
	// RF-M16-008: arancel por oferta
	// =================================================================================

	@Test
	@DisplayName("arancel de la oferta: manda al resolver con oferta, no sin ella, y es el que "
			+ "congela el devengo de F-4")
	void arancel_de_la_oferta_manda_y_se_devenga() {
		Fixture f = crear(true);
		ArancelView deLaOferta = arancelService.crear(f.admin2(), f.sede(), f.convenio(),
				new ArancelAltaCommand(f.kine(), new BigDecimal("15000.00"),
						new BigDecimal("13000.00"), new BigDecimal("2000.00"),
						LocalDate.of(2020, 1, 1), null, f.oferta()));

		ResolucionDeArancel conOferta = arancelService.resolver(f.admin2(), f.sede(),
				f.financiador(), f.plan(), f.kine(), f.oferta(), hoy());
		ResolucionDeArancel sinOferta = arancelService.resolver(f.admin2(), f.sede(),
				f.financiador(), f.plan(), f.kine(), hoy());
		assertThat(conOferta.arancel().arancelId()).isEqualTo(deLaOferta.id());
		assertThat(sinOferta.arancel().arancelId()).isEqualTo(f.arancel());

		CoberturaParaOferta r = coberturaParaOferta.resolver(
				f.admin(), f.persona(), f.oferta(), hoy());
		assertThat(r.aplicables()).singleElement().satisfies(a ->
				assertThat(a.arancel().ofertaId()).isEqualTo(f.oferta()));

		long sesion = insertarSesion(f);
		cerrar(f, sesion);
		List<Map<String, Object>> filas = jdbc.queryForList(
				"SELECT * FROM obligacion WHERE sesion_id = ? ORDER BY id", sesion);
		assertThat(filas).hasSize(2).allSatisfy(fila ->
				assertThat(((Number) fila.get("snapshot_arancel_id")).longValue())
						.isEqualTo(deLaOferta.id()));
		assertThat((BigDecimal) filas.get(0).get("importe_original")).isEqualByComparingTo("13000.00");
		assertThat((BigDecimal) filas.get(1).get("importe_original")).isEqualByComparingTo("2000.00");
	}

	@Test
	@DisplayName("dos aranceles concurrentes de la MISMA oferta que se pisan: uno entra, el otro "
			+ "arancel-solapado; el general no compite")
	void arancel_por_oferta_concurrente() {
		Fixture f = crear(true);

		List<Desenlace<ArancelView>> desenlaces = enParalelo(List.of(
				() -> arancelService.crear(f.admin2(), f.sede(), f.convenio(),
						altaDeOferta(f, LocalDate.of(2026, 1, 1), null)),
				() -> arancelService.crear(f.admin2(), f.sede(), f.convenio(),
						altaDeOferta(f, LocalDate.of(2026, 6, 1), null))));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("exactamente uno entra. Desenlaces: %s", desenlaces).isEqualTo(1);
		assertThat(desenlaces.stream().filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), ArancelSolapadoException.class)).count())
				.as("y el otro recibe arancel-solapado. Desenlaces: %s", desenlaces).isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM convenio_arancel
				 WHERE convenio_id = ? AND practica_id = ? AND active = 1
				""", Integer.class, f.convenio(), f.kine()))
				.as("el general del fixture + uno de la oferta").isEqualTo(2);
	}

	// =================================================================================
	// RF-M16-009: precio particular por vigencia
	// =================================================================================

	@Test
	@DisplayName("precio particular: dos altas concurrentes que se pisan -> una entra; y el "
			+ "devengo particular cobra el precio vigente, no el de lista")
	void precio_particular_concurrente_y_devengado() {
		Fixture f = crear(false);

		List<Desenlace<OfertaPrecioParticularView>> desenlaces = enParalelo(List.of(
				() -> precioService.crear(f.adminOffering(), f.org(), f.sede(), f.oferta(),
						new BigDecimal("9100.00"), null, hoy().minusDays(10), null),
				() -> precioService.crear(f.adminOffering(), f.org(), f.sede(), f.oferta(),
						new BigDecimal("9200.00"), null, hoy().minusDays(5), null)));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("Desenlaces: %s", desenlaces).isEqualTo(1);
		assertThat(desenlaces.stream().filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), PrecioParticularSolapadoException.class)).count())
				.as("Desenlaces: %s", desenlaces).isEqualTo(1);
		BigDecimal vigente = desenlaces.stream().filter(d -> !d.fallo()).findFirst().orElseThrow()
				.valor().importe();

		long sesion = insertarSesion(f);
		cerrar(f, sesion);
		assertThat(jdbc.queryForList("SELECT * FROM obligacion WHERE sesion_id = ?", sesion))
				.singleElement().satisfies(fila -> {
					assertThat(fila.get("concepto")).isEqualTo("PARTICULAR");
					assertThat((BigDecimal) fila.get("importe_original")).isEqualByComparingTo(vigente);
				});

		// CA-M16-009-06: cerrar el precio y cargar otro no toca lo ya devengado.
		jdbc.update("UPDATE oferta_precio_particular SET importe = 1.00 WHERE oferta_id = ?",
				f.oferta());
		assertThat((BigDecimal) jdbc.queryForObject(
				"SELECT importe_original FROM obligacion WHERE sesion_id = ?", BigDecimal.class,
				sesion)).isEqualByComparingTo(vigente);
	}

	// =================================================================================
	// Tenant
	// =================================================================================

	@Test
	@DisplayName("tenant: otra organizacion no ve la persona, no asocia la oferta ni le pone precio")
	void aislamiento_de_tenant() {
		Fixture a = crear(true);
		Fixture b = crear(true);

		assertThatThrownBy(() -> coberturaParaOferta.resolver(b.admin(), a.persona(), b.oferta(),
				hoy())).isInstanceOf(PersonaNotAccessibleException.class);
		assertThatThrownBy(() -> arancelService.crear(b.admin2(), b.sede(), b.convenio(),
				new ArancelAltaCommand(b.kine(), new BigDecimal("1.00"), new BigDecimal("1.00"),
						BigDecimal.ZERO, LocalDate.of(2020, 1, 1), null, a.oferta())))
				.isInstanceOf(OfertaNoAccesibleException.class);
		assertThatThrownBy(() -> precioService.crear(b.adminOffering(), b.org(), b.sede(),
				a.oferta(), new BigDecimal("1.00"), "ARS", hoy(), null))
				.isInstanceOf(OfertaNotAccessibleException.class);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM oferta_precio_particular WHERE oferta_id = ?", Integer.class,
				a.oferta())).isZero();
	}

	// =================================================================================
	// Fixture — todo sintetico
	// =================================================================================

	private record Fixture(
			long org, long sede, long profesionalCuenta, long profesionalMembership,
			long adminCuenta, long persona, long historia, long oferta, long financiador,
			long plan, long cobertura, long kine, long fono, long convenio, long arancel) {

		com.akine.person.application.OperatingActor admin() {
			return new com.akine.person.application.OperatingActor(adminCuenta, false, org, sede);
		}

		com.akine.contracting.application.OperatingActor admin2() {
			return new com.akine.contracting.application.OperatingActor(
					adminCuenta, false, org, sede);
		}

		com.akine.offering.application.OperatingActor adminOffering() {
			return new com.akine.offering.application.OperatingActor(adminCuenta, false, org, sede);
		}

		com.akine.encounter.application.OperatingActor profesional() {
			return new com.akine.encounter.application.OperatingActor(
					profesionalCuenta, false, org, sede);
		}
	}

	private Fixture crear(boolean admiteObraSocial) {
		String s = UUID.randomUUID().toString().substring(0, 10);
		long org = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Centro " + s, "b3-it-" + s, ZONA);
		long sede = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, "Sede " + s, ZONA);
		long profesional = cuenta("b3-pro-" + s);
		long membership = membership(org, sede, profesional, "PROFESIONAL");
		long admin = cuenta("b3-adm-" + s);
		// CONSULTORIO_ADMIN: convenio:manage y consultorio:manage, con el evaluador real.
		membership(org, sede, admin, "CONSULTORIO_ADMIN");

		String apellido = "Paciente" + s;
		long persona = insertar("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave, nombre_clave,
				                     active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, apellido, apellido.toUpperCase());
		jdbc.update("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, persona, admin);
		long historia = insertar("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, persona, profesional);

		long servicio = insertar("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'INDIVIDUAL', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "B3-" + s.toUpperCase(), "Servicio " + s);
		long oferta = insertar("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, precio_base, moneda, admite_obra_social,
				        requiere_caso_clinico, genera_registro_clinico, requiere_profesional,
				        requiere_espacio, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'INDIVIDUAL', 60, 1, 8500.00, 'ARS', ?, 0, 0, 1, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, sede, servicio, "Oferta " + s, admiteObraSocial ? 1 : 0);

		long financiador = insertar("""
				INSERT INTO financiador (organization_id, codigo, nombre, tipo, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 'OBRA_SOCIAL', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, "OS-" + s, "Financiador " + s);
		long plan = insertar("""
				INSERT INTO plan_cobertura (organization_id, financiador_id, codigo, nombre,
				                            vigencia_desde, requiere_autorizacion,
				                            requiere_credencial, active, version,
				                            created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, financiador, "P-" + s, "Plan " + s);
		long cobertura = cobertura(org, persona, financiador, plan, "Plan " + s, s, true);

		long especialidad = insertar("""
				INSERT INTO especialidad (organization_id, codigo, name, valid_from, active,
				                          version, created_at, updated_at)
				VALUES (?, ?, ?, '2020-01-01 00:00:00.000000', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, "E-" + s, "Especialidad " + s);
		long kine = practica(org, especialidad, "K-" + s);
		long fono = practica(org, especialidad, "F-" + s);
		ofertaPractica(org, sede, oferta, kine, true);
		ofertaPractica(org, sede, oferta, fono, false);

		long convenio = insertar("""
				INSERT INTO convenio (organization_id, consultorio_id, financiador_id, plan_id,
				                      codigo, nombre, modalidad, vigencia_desde, moneda,
				                      requiere_orden, requiere_autorizacion, requiere_credencial,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, 'POR_PRESTACION', '2020-01-01', 'ARS', 0, 0, 0, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, sede, financiador, plan, "CONV-" + s, "Convenio " + s);
		long arancel = insertar("""
				INSERT INTO convenio_arancel (organization_id, consultorio_id, convenio_id,
				                              practica_id, importe_total, importe_financiador,
				                              coseguro, moneda, vigencia_desde, active, version,
				                              created_at, updated_at)
				VALUES (?, ?, ?, ?, 12000.00, 10500.00, 1500.00, 'ARS', '2020-01-01', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, sede, convenio, kine);

		return new Fixture(org, sede, profesional, membership, admin, persona, historia, oferta,
				financiador, plan, cobertura, kine, fono, convenio, arancel);
	}

	private long coberturaSinConvenio(Fixture f) {
		String s = UUID.randomUUID().toString().substring(0, 10);
		long financiador = insertar("""
				INSERT INTO financiador (organization_id, codigo, nombre, tipo, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 'OBRA_SOCIAL', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", f.org(), "OS2-" + s, "Otro financiador " + s);
		long plan = insertar("""
				INSERT INTO plan_cobertura (organization_id, financiador_id, codigo, nombre,
				                            vigencia_desde, requiere_autorizacion,
				                            requiere_credencial, active, version,
				                            created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", f.org(), financiador, "P2-" + s, "Plan " + s);
		return cobertura(f.org(), f.persona(), financiador, plan, "Plan " + s, s, false);
	}

	private long cobertura(long org, long persona, long financiador, long plan, String planNombre,
			String s, boolean principal) {
		return insertar("""
				INSERT INTO cobertura_paciente (
				        organization_id, persona_id, tipo,
				        financiador_id, financiador_codigo, financiador_nombre, financiador_tipo,
				        plan_id, plan_codigo, plan_nombre,
				        requeria_autorizacion, requeria_credencial, referencia_capturada_el,
				        numero_afiliado, vigencia_desde, principal,
				        active, version, created_at, updated_at)
				VALUES (?, ?, 'FINANCIADA', ?, ?, ?, 'OBRA_SOCIAL', ?, ?, ?, 0, 0, UTC_TIMESTAMP(6),
				        ?, '2020-01-01', ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, persona, financiador, "OS-" + s, "Financiador " + s, plan, "P-" + s,
				planNombre, "AF-" + s, principal ? 1 : 0);
	}

	private void arancelGeneral(Fixture f, long practica, String total, String financiador,
			String coseguro) {
		arancelService.crear(f.admin2(), f.sede(), f.convenio(), new ArancelAltaCommand(practica,
				new BigDecimal(total), new BigDecimal(financiador), new BigDecimal(coseguro),
				LocalDate.of(2020, 1, 1), null));
	}

	private static ArancelAltaCommand altaDeOferta(Fixture f, LocalDate desde, LocalDate hasta) {
		return new ArancelAltaCommand(f.kine(), new BigDecimal("15000.00"),
				new BigDecimal("13000.00"), new BigDecimal("2000.00"), desde, hasta, f.oferta());
	}

	private long practica(long org, long especialidad, String codigo) {
		return insertar("""
				INSERT INTO practica (organization_id, especialidad_id, codigo, name, valid_from,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01 00:00:00.000000', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, especialidad, codigo, "Practica " + codigo);
	}

	private void ofertaPractica(long org, long sede, long oferta, long practica, boolean principal) {
		jdbc.update("""
				INSERT INTO oferta_practica (organization_id, consultorio_id, oferta_id, practica_id,
				                             principal, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, sede, oferta, practica, principal ? 1 : 0);
	}

	private long cuenta(String prefijo) {
		String email = prefijo + "@ejemplo.test";
		return insertar("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
	}

	private long membership(long org, long sede, long cuenta, String rol) {
		return insertar("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, ?, 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, sede, cuenta, rol);
	}

	private long insertarSesion(Fixture f) {
		return insertar("""
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, oferta_id,
				                    profesional_membership_id, estado, iniciada_en,
				                    iniciada_por_cuenta_id, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'BORRADOR', UTC_TIMESTAMP(6), ?, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", f.org(), f.sede(), f.historia(), f.oferta(), f.profesionalMembership(),
				f.profesionalCuenta());
	}

	private void cerrar(Fixture f, long sesionId) {
		long version = sesionService.ver(f.profesional(), f.sede(), sesionId).version();
		sesionService.cerrar(f.profesional(), f.sede(), sesionId,
				new CierreDeSesion(Asistencia.PRESENTE, "Terapia manual", null, null, null, null),
				version);
	}

	private long insertar(String insert, Object... args) {
		jdbc.update(insert, args);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private static LocalDate hoy() {
		return LocalDate.now(ZoneId.of(ZONA));
	}

}
