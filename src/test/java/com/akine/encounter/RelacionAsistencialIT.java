package com.akine.encounter;

import com.akine.TestcontainersConfiguration;
import com.akine.clinical.spi.RelacionAsistencialProbe;
import com.akine.encounter.infrastructure.EncounterRelacionAsistencialProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La relacion asistencial real (C-3) contra MySQL real: las dos consultas nuevas y los vinculos,
 * con datos.
 *
 * <p>El unitario ({@code EncounterRelacionAsistencialProbeTest}) fija el algoritmo con los puertos
 * mockeados; este fija que el SQL detras de ellos responda lo mismo. Un predicado que olvida la
 * sede, la organizacion o la baja logica no falla: devuelve {@code true} para quien no debia, y lo
 * paga el paciente cuyo acceso clinico deja de pedir justificacion.
 *
 * <p><b>Los profesionales se crean con membership de SEDE, que es el caso real.</b> Una version
 * anterior de este fixture los creaba de alcance organizacion para que el contrato viejo
 * ({@code AccountContextDirectory.membership}, que solo ve esa) diera verde, y el verde escondia
 * que un profesional de una sola sede nunca tendria relacion asistencial. El fixture no se adapta
 * al codigo.
 *
 * <p>Todo sintetico (AGENT.md seccion 10). Cada escenario arma su propia organizacion con sufijo
 * unico, asi que no dependen del orden ni se pisan entre si.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class RelacionAsistencialIT {

	private static final String ZONA = "America/Argentina/Cordoba";
	private static final Instant ANCLA =
			Instant.parse("2026-03-10T12:00:00Z").truncatedTo(ChronoUnit.MICROS);

	@Autowired private RelacionAsistencialProbe sonda;
	@Autowired private ApplicationContext contexto;
	@Autowired private JdbcTemplate jdbc;

	// =================================================================================
	// El bean
	// =================================================================================

	@Test
	@DisplayName("hay un solo RelacionAsistencialProbe y es el de encounter")
	void hay_un_solo_bean_y_es_el_real() {
		// AC-1
		Map<String, RelacionAsistencialProbe> beans =
				contexto.getBeansOfType(RelacionAsistencialProbe.class);

		assertThat(beans)
				.as("el consumidor inyecta un bean singular: dos implementaciones conviviendo "
						+ "romperian el arranque, o peor, ganaria la que Spring prefiera")
				.hasSize(1);
		assertThat(sonda).isInstanceOf(EncounterRelacionAsistencialProbe.class);
	}

	// =================================================================================
	// Por turno
	// =================================================================================

	@Test
	@DisplayName("un turno RESERVADO del profesional con la persona da relacion")
	void turno_reservado_da_relacion() {
		// AC-1
		Fixture f = crearFixture();
		insertarTurno(f, f.organizationId(), f.sedeId(), f.membershipId(), f.personaId(),
				f.ofertaId(), "RESERVADO");

		assertThat(consultar(f)).isTrue();
	}

	@Test
	@DisplayName("un turno CONFIRMADO del profesional con la persona da relacion")
	void turno_confirmado_da_relacion() {
		// AC-1
		Fixture f = crearFixture();
		insertarTurno(f, f.organizationId(), f.sedeId(), f.membershipId(), f.personaId(),
				f.ofertaId(), "CONFIRMADO");

		assertThat(consultar(f)).isTrue();
	}

	@Test
	@DisplayName("un turno EN_ESPERA del profesional con la persona da relacion")
	void turno_en_espera_da_relacion() {
		// AC-1
		Fixture f = crearFixture();
		insertarTurno(f, f.organizationId(), f.sedeId(), f.membershipId(), f.personaId(),
				f.ofertaId(), "EN_ESPERA");

		assertThat(consultar(f)).isTrue();
	}

	@Test
	@DisplayName("un turno CANCELADO no da relacion: la persona nunca fue atendida")
	void turno_cancelado_no_da_relacion() {
		// AC-1
		Fixture f = crearFixture();
		insertarTurno(f, f.organizationId(), f.sedeId(), f.membershipId(), f.personaId(),
				f.ofertaId(), "CANCELADO");

		assertThat(consultar(f)).isFalse();
	}

	@Test
	@DisplayName("un turno AUSENTE no da relacion: la persona no vino")
	void turno_ausente_no_da_relacion() {
		// AC-1
		Fixture f = crearFixture();
		insertarTurno(f, f.organizationId(), f.sedeId(), f.membershipId(), f.personaId(),
				f.ofertaId(), "AUSENTE");

		assertThat(consultar(f)).isFalse();
	}

	@Test
	@DisplayName("el turno de OTRO profesional con la persona no da relacion a este actor")
	void turno_de_otro_profesional_no_da_relacion() {
		// AC-1
		Fixture f = crearFixture();
		insertarTurno(f, f.organizationId(), f.sedeId(), f.otroMembershipId(),
				f.personaId(), f.ofertaId(), "CONFIRMADO");

		assertThat(consultar(f)).isFalse();
		assertThat(sonda.tieneRelacionAsistencial(f.organizationId(), f.sedeId(),
				f.otraCuentaId(), f.personaId()))
				.as("y el turno si le da relacion a su profesional: el escenario esta bien armado")
				.isTrue();
	}

	@Test
	@DisplayName("un turno vivo en OTRA sede de la misma organizacion no da relacion en esta")
	void turno_de_otra_sede_no_da_relacion() {
		// AC-1
		// Con un vinculo de alcance organizacion, que cubre las dos sedes: lo que separa una
		// sede de la otra es solo el predicado de consultorio de la consulta de turnos.
		Fixture f = crearFixture();
		insertarTurno(f, f.organizationId(), f.otraSedeId(), f.membershipOrgId(), f.personaId(),
				f.ofertaOtraSedeId(), "CONFIRMADO");

		assertThat(sonda.tieneRelacionAsistencial(
				f.organizationId(), f.sedeId(), f.cuentaOrgId(), f.personaId())).isFalse();
		assertThat(sonda.tieneRelacionAsistencial(
				f.organizationId(), f.otraSedeId(), f.cuentaOrgId(), f.personaId()))
				.as("y en la sede del turno si hay relacion: el escenario esta bien armado")
				.isTrue();
	}

	@Test
	@DisplayName("un turno vivo de OTRA organizacion no da relacion")
	void turno_de_otra_organizacion_no_da_relacion() {
		// AC-1
		Fixture f = crearFixture();
		Fixture ajena = crearFixture();
		// Una fila de otro tenant que, de olvidarse el predicado de organizacion, calzaria por
		// profesional y por persona. La base la admite (no hay FK compuesta): es justamente el
		// dato que el WHERE tiene que dejar afuera.
		insertarTurno(f, ajena.organizationId(), ajena.sedeId(), f.membershipId(),
				f.personaId(), ajena.ofertaId(), "CONFIRMADO");

		assertThat(consultar(f)).isFalse();
	}

	// =================================================================================
	// Por sesion
	// =================================================================================

	@Test
	@DisplayName("una sesion iniciada por el actor sobre la historia de la persona da relacion")
	void sesion_iniciada_por_el_actor_da_relacion() {
		// AC-1
		Fixture f = crearFixture();
		insertarSesion(f, f.sedeId(), f.otroMembershipId(), f.cuentaId(), false);

		assertThat(consultar(f)).isTrue();
	}

	@Test
	@DisplayName("una sesion con la membership del actor, iniciada por otra cuenta, da relacion")
	void sesion_con_la_membership_del_actor_da_relacion() {
		// AC-1
		Fixture f = crearFixture();
		insertarSesion(f, f.sedeId(), f.membershipId(), f.otraCuentaId(), false);

		assertThat(consultar(f)).isTrue();
	}

	@Test
	@DisplayName("una sesion de otro actor sobre la misma historia no da relacion")
	void sesion_de_otro_actor_no_da_relacion() {
		// AC-1
		Fixture f = crearFixture();
		insertarSesion(f, f.sedeId(), f.otroMembershipId(), f.otraCuentaId(), false);

		assertThat(consultar(f)).isFalse();
		assertThat(sonda.tieneRelacionAsistencial(f.organizationId(), f.sedeId(),
				f.otraCuentaId(), f.personaId()))
				.as("y esa sesion si le da relacion a su actor: el escenario esta bien armado")
				.isTrue();
	}

	@Test
	@DisplayName("una sesion del actor en OTRA sede no da relacion en esta")
	void sesion_de_otra_sede_no_da_relacion() {
		// AC-1
		// Vinculo de alcance organizacion, por la misma razon que en el turno de otra sede.
		Fixture f = crearFixture();
		insertarSesion(f, f.otraSedeId(), f.membershipOrgId(), f.cuentaOrgId(), false);

		assertThat(sonda.tieneRelacionAsistencial(
				f.organizationId(), f.sedeId(), f.cuentaOrgId(), f.personaId())).isFalse();
		assertThat(sonda.tieneRelacionAsistencial(
				f.organizationId(), f.otraSedeId(), f.cuentaOrgId(), f.personaId()))
				.as("y en la sede de la sesion si hay relacion: el escenario esta bien armado")
				.isTrue();
	}

	@Test
	@DisplayName("una sesion dada de baja no da relacion")
	void sesion_borrada_no_da_relacion() {
		// AC-1
		Fixture f = crearFixture();
		insertarSesion(f, f.sedeId(), f.membershipId(), f.cuentaId(), true);

		assertThat(consultar(f)).isFalse();
	}

	@Test
	@DisplayName("sin turnos ni sesiones no hay relacion")
	void sin_evidencia_no_hay_relacion() {
		// AC-1
		assertThat(consultar(crearFixture())).isFalse();
	}

	// =================================================================================
	// Alcance del vinculo: sede u organizacion
	// =================================================================================

	@Test
	@DisplayName("profesional de UNA sola sede con turno vivo: true en su sede, false en otra")
	void profesional_de_una_sede_con_turno() {
		// AC-1
		Fixture f = crearFixture();
		Actor solo = crearActor(f, f.sedeId(), "ACTIVA", -5 * 365, null, true);
		insertarTurno(f, f.organizationId(), f.sedeId(), solo.membershipId(), f.personaId(),
				f.ofertaId(), "CONFIRMADO");
		// Tambien tiene turno en la otra sede, con la misma membership, que no la cubre: la
		// unica razon para el false es el alcance del vinculo.
		insertarTurno(f, f.organizationId(), f.otraSedeId(), solo.membershipId(), f.personaId(),
				f.ofertaOtraSedeId(), "CONFIRMADO");

		assertThat(consultar(f, solo, f.sedeId())).isTrue();
		assertThat(consultar(f, solo, f.otraSedeId())).isFalse();
	}

	@Test
	@DisplayName("profesional de UNA sola sede con sesion: true en su sede, false en otra")
	void profesional_de_una_sede_con_sesion() {
		// AC-1
		Fixture f = crearFixture();
		Actor solo = crearActor(f, f.sedeId(), "ACTIVA", -5 * 365, null, true);
		insertarSesion(f, f.sedeId(), solo.membershipId(), solo.cuentaId(), false);
		insertarSesion(f, f.otraSedeId(), solo.membershipId(), solo.cuentaId(), false);

		assertThat(consultar(f, solo, f.sedeId())).isTrue();
		assertThat(consultar(f, solo, f.otraSedeId())).isFalse();
	}

	@Test
	@DisplayName("vinculo de alcance ORGANIZACION: true en cualquier sede donde haya evidencia")
	void vinculo_de_organizacion_cubre_cualquier_sede() {
		// AC-1
		Fixture f = crearFixture();
		Actor org = crearActor(f, null, "ACTIVA", -5 * 365, null, true);
		insertarTurno(f, f.organizationId(), f.sedeId(), org.membershipId(), f.personaId(),
				f.ofertaId(), "RESERVADO");
		insertarSesion(f, f.otraSedeId(), org.membershipId(), org.cuentaId(), false);

		assertThat(consultar(f, org, f.sedeId())).isTrue();
		assertThat(consultar(f, org, f.otraSedeId())).isTrue();
	}

	// =================================================================================
	// Vinculos que no cuentan: el estado, la ventana y la baja
	// =================================================================================

	@Test
	@DisplayName("membership SUSPENDIDA: false aunque tenga turno y sesion")
	void membership_suspendida_no_da_relacion() {
		// AC-1
		assertVinculoNoCuenta(crearFixture(), "SUSPENDIDA", -5 * 365, null, true);
	}

	@Test
	@DisplayName("membership REVOCADA: false aunque tenga turno y sesion")
	void membership_revocada_no_da_relacion() {
		// AC-1
		assertVinculoNoCuenta(crearFixture(), "REVOCADA", -5 * 365, null, true);
	}

	@Test
	@DisplayName("membership VENCIDA: false aunque tenga turno y sesion")
	void membership_vencida_no_da_relacion() {
		// AC-1
		assertVinculoNoCuenta(crearFixture(), "ACTIVA", -5 * 365, -1, true);
	}

	@Test
	@DisplayName("membership con BAJA LOGICA: false aunque tenga turno y sesion")
	void membership_dada_de_baja_no_da_relacion() {
		// AC-1
		assertVinculoNoCuenta(crearFixture(), "ACTIVA", -5 * 365, null, false);
	}

	/**
	 * Un profesional cuyo vinculo no cuenta, con un turno vivo y una sesion en su sede: el
	 * resultado tiene que ser {@code false}, y el control de que la evidencia existe es el mismo
	 * escenario con el vinculo en regla.
	 */
	private void assertVinculoNoCuenta(Fixture f, String estado, int desdeDias, Integer hastaDias,
			boolean active) {
		Actor sinValor = crearActor(f, f.sedeId(), estado, desdeDias, hastaDias, active);
		insertarTurno(f, f.organizationId(), f.sedeId(), sinValor.membershipId(), f.personaId(),
				f.ofertaId(), "CONFIRMADO");
		insertarSesion(f, f.sedeId(), sinValor.membershipId(), sinValor.cuentaId(), false);

		assertThat(consultar(f, sinValor, f.sedeId())).isFalse();

		Actor enRegla = crearActor(f, f.sedeId(), "ACTIVA", -5 * 365, null, true);
		insertarTurno(f, f.organizationId(), f.sedeId(), enRegla.membershipId(), f.personaId(),
				f.ofertaId(), "CONFIRMADO");
		assertThat(consultar(f, enRegla, f.sedeId()))
				.as("con el vinculo en regla la misma evidencia da relacion: el escenario esta "
						+ "bien armado")
				.isTrue();
	}

	// =================================================================================
	// Operaciones
	// =================================================================================

	private boolean consultar(Fixture f) {
		return sonda.tieneRelacionAsistencial(
				f.organizationId(), f.sedeId(), f.cuentaId(), f.personaId());
	}

	private boolean consultar(Fixture f, Actor actor, long sedeId) {
		return sonda.tieneRelacionAsistencial(
				f.organizationId(), sedeId, actor.cuentaId(), f.personaId());
	}

	/**
	 * Un turno insertado directo, con las columnas que exigen los CHECK de cada estado.
	 *
	 * <p>El CANCELADO lleva su baja logica ({@code deleted_at}) y su motivo, como lo deja el ciclo
	 * de M12; el AUSENTE conserva {@code deleted_at} en NULL (V38, punto 2); el EN_ESPERA necesita
	 * hora de llegada y responsable (V39).
	 */
	private void insertarTurno(Fixture f, long organizationId, long consultorioId,
			long membershipId, long personaId, long ofertaId, String estado) {

		Timestamp inicio = utc(ANCLA.plus(1, ChronoUnit.DAYS));
		Timestamp fin = utc(ANCLA.plus(1, ChronoUnit.DAYS).plus(60, ChronoUnit.MINUTES));
		Timestamp ahora = utc(ANCLA);
		boolean cancelado = estado.equals("CANCELADO");
		boolean ausente = estado.equals("AUSENTE");
		boolean enEspera = estado.equals("EN_ESPERA");

		jdbc.update("""
				INSERT INTO turno (organization_id, consultorio_id, oferta_id, persona_id,
				                   profesional_membership_id, inicio, fin, estado,
				                   motivo_cancelacion, cancelado_en, cancelado_por_cuenta_id,
				                   ausente_en, llegada_en, llegada_por_cuenta_id,
				                   reservado_por_cuenta_id, reservado_en, deleted_at, version,
				                   created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""",
				organizationId, consultorioId, ofertaId, personaId, membershipId, inicio, fin,
				estado,
				cancelado ? "Cancelado por el paciente" : null,
				cancelado ? ahora : null,
				cancelado ? f.cuentaId() : null,
				ausente ? ahora : null,
				enEspera ? ahora : null,
				enEspera ? f.cuentaId() : null,
				f.cuentaId(), ahora,
				cancelado ? ahora : null);
	}

	/**
	 * Una sesion en BORRADOR sobre la historia del fixture. {@code profesional_membership_id} e
	 * {@code iniciada_por_cuenta_id} se pasan por separado porque son las dos puertas por las que
	 * el contrato reconoce al actor.
	 */
	private void insertarSesion(Fixture f, long consultorioId, long membershipId,
			long iniciadaPorCuentaId, boolean borrada) {

		jdbc.update("""
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, oferta_id,
				                    profesional_membership_id, estado, numero_sesion, iniciada_en,
				                    iniciada_por_cuenta_id, asistencia, cerrada_en,
				                    cerrada_por_cuenta_id, ultimo_numero_version, deleted_at,
				                    version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'BORRADOR', NULL, ?, ?, NULL, NULL, NULL, 0, ?, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""",
				f.organizationId(), consultorioId, f.historiaClinicaId(), f.ofertaId(),
				membershipId, utc(ANCLA), iniciadaPorCuentaId, borrada ? utc(ANCLA) : null);
	}

	/**
	 * El instante como {@code Timestamp} en hora de pared UTC: la aplicacion configura
	 * {@code hibernate.jdbc.time_zone: UTC} y un {@code Timestamp.from} pasado por JDBC se
	 * correria tres horas (ver {@code TimelineIT}).
	 */
	private static Timestamp utc(Instant instante) {
		return Timestamp.valueOf(LocalDateTime.ofInstant(instante, ZoneOffset.UTC));
	}

	// =================================================================================
	// Fixture — todo sintetico (AGENT.md seccion 10)
	// =================================================================================

	/**
	 * Una organizacion con dos sedes, una persona con historia clinica, una oferta por sede y tres
	 * profesionales: el actor y "otro", ambos con membership de la sede A, y uno de alcance
	 * organizacion.
	 */
	private record Fixture(
			long organizationId, long sedeId, long otraSedeId,
			long cuentaId, long membershipId, long otraCuentaId, long otroMembershipId,
			long cuentaOrgId, long membershipOrgId,
			long personaId, long historiaClinicaId, long ofertaId, long ofertaOtraSedeId) {
	}

	/** Un profesional creado por un escenario: su cuenta y su membership. */
	private record Actor(long cuentaId, long membershipId) {
	}

	private Fixture crearFixture() {
		String sufijo = UUID.randomUUID().toString().substring(0, 12);

		long organizationId = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version,
				                          created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{"Centro Sintetico " + sufijo, "relacion-it-" + sufijo, ZONA});

		long sedeId = crearConsultorio(organizationId, "Sede A " + sufijo);
		long otraSedeId = crearConsultorio(organizationId, "Sede B " + sufijo);

		long cuentaId = crearCuenta("actor-" + sufijo);
		long membershipId = crearMembership(
				organizationId, sedeId, cuentaId, "ACTIVA", -5 * 365, null, true);
		long otraCuentaId = crearCuenta("otro-" + sufijo);
		long otroMembershipId = crearMembership(
				organizationId, sedeId, otraCuentaId, "ACTIVA", -5 * 365, null, true);
		long cuentaOrgId = crearCuenta("org-" + sufijo);
		long membershipOrgId = crearMembership(
				organizationId, null, cuentaOrgId, "ACTIVA", -5 * 365, null, true);

		long personaId = insertar("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave,
				                     nombre_clave, active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{
						organizationId, "Paciente" + sufijo, ("PACIENTE" + sufijo).toUpperCase()});

		insertar("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, personaId, cuentaId});

		long historiaClinicaId = insertar("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, personaId, cuentaId});

		long servicioId = insertar("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'INDIVIDUAL', 0, 0, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{
						"RA-" + sufijo.toUpperCase(), "Servicio Sintetico " + sufijo});

		long ofertaId = crearOferta(organizationId, sedeId, servicioId, "Oferta A " + sufijo);
		long ofertaOtraSedeId =
				crearOferta(organizationId, otraSedeId, servicioId, "Oferta B " + sufijo);

		return new Fixture(organizationId, sedeId, otraSedeId, cuentaId, membershipId,
				otraCuentaId, otroMembershipId, cuentaOrgId, membershipOrgId, personaId,
				historiaClinicaId, ofertaId, ofertaOtraSedeId);
	}

	/**
	 * Un profesional nuevo en la organizacion del fixture. {@code consultorioId} nulo es alcance
	 * organizacion; los dias son relativos a hoy ({@code hastaDias} nulo es sin fin).
	 */
	private Actor crearActor(Fixture f, Long consultorioId, String estado, int desdeDias,
			Integer hastaDias, boolean active) {
		long cuentaId = crearCuenta("prof-" + UUID.randomUUID().toString().substring(0, 12));
		return new Actor(cuentaId, crearMembership(
				f.organizationId(), consultorioId, cuentaId, estado, desdeDias, hastaDias, active));
	}

	private long crearConsultorio(long organizationId, String nombre) {
		return insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, nombre, ZONA});
	}

	private long crearCuenta(String alias) {
		String email = alias + "@ejemplo.test";
		return insertar("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{email, email});
	}

	/**
	 * Una membership con el estado, la ventana y la baja logica que pida el escenario. La baja
	 * logica es {@code active = 0} con {@code deleted_at}, como la deja el ciclo de M01.
	 */
	private long crearMembership(long organizationId, Long consultorioId, long cuentaId,
			String estado, int desdeDias, Integer hastaDias, boolean active) {
		return insertar("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, valid_until, estado, active,
				                        deleted_at, version, created_at, updated_at)
				VALUES (?, ?, ?, 'PROFESIONAL', 0,
				        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL ? DAY),
				        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL ? DAY),
				        ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, consultorioId, cuentaId, desdeDias,
						hastaDias, estado, active ? 1 : 0,
						active ? null : utc(ANCLA)});
	}

	private long crearOferta(long organizationId, long consultorioId, long servicioId,
			String nombre) {
		return insertar("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, precio_base, moneda, admite_obra_social,
				        requiere_caso_clinico, genera_registro_clinico, requiere_profesional,
				        requiere_espacio, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'INDIVIDUAL', 60, 1, 8500.00, 'ARS', 0, 0, 0, 1, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, consultorioId, servicioId, nombre});
	}

	private long insertar(String sql, Object[] args) {
		jdbc.update(sql, args);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
