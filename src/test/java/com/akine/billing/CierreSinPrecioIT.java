package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.encounter.spi.OfertaSinPrecioException;
import com.akine.person.application.AutorizacionAltaCommand;
import com.akine.person.application.AutorizacionService;
import com.akine.person.domain.EstadoAutorizacion;
import com.akine.scheduling.application.CicloDeRecepcionService;
import com.akine.scheduling.application.RecepcionView;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AKINE E-7b (DP-17) contra MySQL real: toda prestacion cerrada genera deuda. Una oferta SIN
 * precio, atendida como Particular, ya no cierra en silencio sin deuda: el cierre responde 409
 * {@code oferta-sin-precio} y <b>no deja nada escrito</b> —ni correlativo consumido, ni version,
 * ni consumo de autorizacion, ni obligacion—.
 *
 * <p>La fixture es la de {@code ParticularEnObligacionIT} con la oferta sin {@code precio_base}:
 * el paciente tiene cobertura, convenio con arancel y una autorizacion vigente, asi que si el
 * cierre hubiera llegado a los observadores habria consumido una unidad. El caso de control —la
 * misma fixture sin resolver Particular— cierra, porque con cobertura el coseguro sale del arancel.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class CierreSinPrecioIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	@Autowired private SesionService sesionService;
	@Autowired private CicloDeRecepcionService recepcion;
	@Autowired private AutorizacionService autorizaciones;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("Particular + oferta sin precio: 409 oferta-sin-precio y nada escrito; cargado el precio, cierra con el numero 1")
	void particular_sin_precio_no_deja_nada_escrito() {
		Fixture f = crearFixture();
		long autorizacion = autorizar(f);
		long turno = insertarTurno(f);
		RecepcionView llegada = recepcion.registrarLlegada(
				f.agendaActor(), f.consultorioId(), turno).recepcion();
		recepcion.atenderComoParticular(f.agendaActor(), f.consultorioId(), turno,
				"Prefiere no usar la obra social", llegada.version());

		long sesion = insertarSesion(f, turno);
		tratar(f, sesion);

		assertThatThrownBy(() -> cerrar(f, sesion))
				.isInstanceOfSatisfying(OfertaSinPrecioException.class, e -> {
					assertThat(e.getOfertaId()).isEqualTo(f.ofertaId());
					assertThat(e.getDia()).isEqualTo(hoy());
					assertThat(e.getMotivo())
							.isEqualTo(OfertaSinPrecioException.Motivo.PARTICULAR_POR_RECEPCION);
				});

		// Nada escrito: la sesion sigue abierta y sin numero, el numerador de la historia no se
		// movio (ni se creo), no hay version 1, no se consumio la autorizacion, no hay deuda.
		Map<String, Object> fila = jdbc.queryForMap(
				"SELECT estado, numero_sesion, cerrada_en FROM sesion WHERE id = ?", sesion);
		assertThat(fila.get("estado")).isEqualTo("BORRADOR");
		assertThat(fila.get("numero_sesion")).isNull();
		assertThat(fila.get("cerrada_en")).isNull();
		assertThat(ultimoNumero(f)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sesion_version WHERE sesion_id = ?",
				Integer.class, sesion)).isZero();
		assertThat(consumida(autorizacion)).isZero();
		assertThat(consumosDeLaSesion(sesion)).isZero();
		assertThat(obligacionesDe(sesion)).isEmpty();

		// Se carga el precio y el MISMO cierre pasa: numero 1, sin hueco, y la deuda particular.
		jdbc.update("UPDATE oferta_servicio_consultorio SET precio_base = 8500.00, moneda = 'ARS' "
				+ "WHERE id = ?", f.ofertaId());
		cerrar(f, sesion);

		assertThat(jdbc.queryForObject("SELECT numero_sesion FROM sesion WHERE id = ?",
				Integer.class, sesion)).isEqualTo(1);
		assertThat(obligacionesDe(sesion)).singleElement().satisfies(deuda -> {
			assertThat(deuda.get("concepto")).isEqualTo("PARTICULAR");
			assertThat((BigDecimal) deuda.get("importe_original")).isEqualByComparingTo("8500.00");
		});
		assertThat(consumida(autorizacion)).isZero();
	}

	@Test
	@DisplayName("control: con cobertura y oferta sin precio cierra igual: financiador + coseguro del arancel")
	void con_cobertura_no_hace_falta_precio() {
		Fixture f = crearFixture();
		long autorizacion = autorizar(f);
		long turno = insertarTurno(f);
		recepcion.registrarLlegada(f.agendaActor(), f.consultorioId(), turno);

		long sesion = insertarSesion(f, turno);
		tratar(f, sesion);
		cerrar(f, sesion);

		assertThat(obligacionesDe(sesion)).extracting(deuda -> deuda.get("concepto"))
				.containsExactly("FINANCIADOR", "COSEGURO");
		assertThat(consumida(autorizacion)).isEqualTo(1);
	}

	// =================================================================================

	private void cerrar(Fixture f, long sesionId) {
		long version = sesionService.ver(f.profesional(), f.consultorioId(), sesionId).version();
		sesionService.cerrar(f.profesional(), f.consultorioId(), sesionId,
				new CierreDeSesion(Asistencia.PRESENTE, "Terapia manual", null, null, null, null),
				version);
	}

	private long autorizar(Fixture f) {
		return autorizaciones.registrar(f.personaActor(), f.personaId(), new AutorizacionAltaCommand(
				f.cobertura(), f.kine(), null, "AUT-" + f.kine(), EstadoAutorizacion.APROBADA, 10,
				hoy().minusDays(30), hoy().plusDays(120), null)).id();
	}

	private int ultimoNumero(Fixture f) {
		return jdbc.queryForObject("""
				SELECT COALESCE(MAX(ultimo_numero), 0) FROM sesion_numerador
				 WHERE historia_clinica_id = ?
				""", Integer.class, f.historiaClinicaId());
	}

	private List<Map<String, Object>> obligacionesDe(long sesionId) {
		return jdbc.queryForList("SELECT * FROM obligacion WHERE sesion_id = ? ORDER BY id", sesionId);
	}

	private int consumida(long autorizacionId) {
		return jdbc.queryForObject("SELECT cantidad_consumida FROM autorizacion WHERE id = ?",
				Integer.class, autorizacionId);
	}

	private int consumosDeLaSesion(long sesionId) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM autorizacion_movimiento
				 WHERE tipo = 'CONSUMO' AND tipo_origen = 'SESION' AND referencia_origen = ?
				""", Integer.class, String.valueOf(sesionId));
	}

	private static LocalDate hoy() {
		return LocalDate.now(ZoneId.of(ZONA));
	}

	// =================================================================================
	// Fixture — todo sintetico. La de ParticularEnObligacionIT, con la oferta SIN precio.
	// =================================================================================

	private record Fixture(
			long organizationId,
			long consultorioId,
			long profesionalCuentaId,
			long profesionalMembershipId,
			long adminCuentaId,
			long personaId,
			long historiaClinicaId,
			long ofertaId,
			long cobertura,
			long kine) {

		com.akine.encounter.application.OperatingActor profesional() {
			return new com.akine.encounter.application.OperatingActor(
					profesionalCuentaId, false, organizationId, consultorioId);
		}

		com.akine.scheduling.application.OperatingActor agendaActor() {
			return new com.akine.scheduling.application.OperatingActor(
					adminCuentaId, false, organizationId, consultorioId);
		}

		com.akine.person.application.OperatingActor personaActor() {
			return new com.akine.person.application.OperatingActor(
					adminCuentaId, false, organizationId, consultorioId);
		}
	}

	private Fixture crearFixture() {
		String s = UUID.randomUUID().toString().substring(0, 10);

		long org = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Centro " + s, "e7b-it-" + s, ZONA);
		long sede = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, "Sede " + s, ZONA);

		long profesional = cuenta("e7b-pro-" + s);
		long membership = membership(org, sede, profesional, "PROFESIONAL");
		long admin = cuenta("e7b-adm-" + s);
		// turno:manage para la recepcion, paciente:manage para registrar la autorizacion.
		membership(org, sede, admin, "ADMINISTRATIVO");

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
				""", "E7B-" + s.toUpperCase(), "Servicio " + s);
		long oferta = insertar("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, precio_base, moneda, admite_obra_social,
				        requiere_caso_clinico, genera_registro_clinico, requiere_profesional,
				        requiere_espacio, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'INDIVIDUAL', 60, 1, NULL, NULL, 1, 0, 0, 1, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, sede, servicio, "Oferta " + s);

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
				VALUES (?, ?, ?, ?, '2020-01-01', 1, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, financiador, "P-" + s, "Plan " + s);
		long cobertura = insertar("""
				INSERT INTO cobertura_paciente (
				        organization_id, persona_id, tipo,
				        financiador_id, financiador_codigo, financiador_nombre, financiador_tipo,
				        plan_id, plan_codigo, plan_nombre,
				        requeria_autorizacion, requeria_credencial, referencia_capturada_el,
				        numero_afiliado, vigencia_desde, principal,
				        active, version, created_at, updated_at)
				VALUES (?, ?, 'FINANCIADA', ?, ?, ?, 'OBRA_SOCIAL', ?, ?, ?, 1, 0, UTC_TIMESTAMP(6),
				        ?, '2020-01-01', 1, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, persona, financiador, "OS-" + s, "Financiador " + s, plan, "P-" + s,
				"Plan " + s, "AF-" + s);

		long especialidad = insertar("""
				INSERT INTO especialidad (organization_id, codigo, name, valid_from, active,
				                          version, created_at, updated_at)
				VALUES (?, ?, ?, '2020-01-01 00:00:00.000000', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, "E-" + s, "Especialidad " + s);
		long kine = insertar("""
				INSERT INTO practica (organization_id, especialidad_id, codigo, name, valid_from,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01 00:00:00.000000', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, especialidad, "K-" + s, "Kinesiologia " + s);
		jdbc.update("""
				INSERT INTO oferta_practica (organization_id, consultorio_id, oferta_id, practica_id,
				                             principal, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 1, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, sede, oferta, kine);

		long convenio = insertar("""
				INSERT INTO convenio (organization_id, consultorio_id, financiador_id, plan_id,
				                      codigo, nombre, modalidad, vigencia_desde, moneda,
				                      requiere_orden, requiere_autorizacion, requiere_credencial,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, 'POR_PRESTACION', '2020-01-01', 'ARS', 0, 1, 0, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, sede, financiador, plan, "CONV-" + s, "Convenio " + s);
		jdbc.update("""
				INSERT INTO convenio_arancel (organization_id, consultorio_id, convenio_id,
				                              practica_id, importe_total, importe_financiador,
				                              coseguro, moneda, vigencia_desde, active, version,
				                              created_at, updated_at)
				VALUES (?, ?, ?, ?, 12000.00, 10500.00, 1500.00, 'ARS', '2020-01-01', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, sede, convenio, kine);

		return new Fixture(org, sede, profesional, membership, admin, persona, historia, oferta,
				cobertura, kine);
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

	private long insertarTurno(Fixture f) {
		return insertar("""
				INSERT INTO turno (organization_id, consultorio_id, oferta_id, persona_id,
				                   profesional_membership_id, inicio, fin, estado,
				                   reservado_por_cuenta_id, reservado_en, version,
				                   created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 HOUR),
				        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 2 HOUR), 'RESERVADO', ?,
				        UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", f.organizationId(), f.consultorioId(), f.ofertaId(), f.personaId(),
				f.profesionalMembershipId(), f.adminCuentaId());
	}

	private long insertarSesion(Fixture f, long turno) {
		return insertar("""
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, oferta_id,
				                    turno_id, profesional_membership_id, estado, iniciada_en,
				                    iniciada_por_cuenta_id, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, 'BORRADOR', UTC_TIMESTAMP(6), ?, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", f.organizationId(), f.consultorioId(), f.historiaClinicaId(), f.ofertaId(), turno,
				f.profesionalMembershipId(), f.profesionalCuentaId());
	}

	private void tratar(Fixture f, long sesionId) {
		jdbc.update("""
				INSERT INTO tratamiento_realizado (organization_id, consultorio_id, sesion_id, orden,
				        practica_id, practica_codigo, practica_nombre, profesional_membership_id,
				        registrado_en, registrado_por_cuenta_id, active, version,
				        created_at, updated_at)
				VALUES (?, ?, ?, 1, ?, 'COD', 'Practica sintetica', ?, UTC_TIMESTAMP(6), ?, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", f.organizationId(), f.consultorioId(), sesionId, f.kine(),
				f.profesionalMembershipId(), f.profesionalCuentaId());
	}

	private long insertar(String insert, Object... args) {
		jdbc.update(insert, args);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
