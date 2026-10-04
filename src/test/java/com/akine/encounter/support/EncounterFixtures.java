package com.akine.encounter.support;

import com.akine.encounter.application.OperatingActor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Datos sinteticos para los ITs de {@code encounter} que corren contra MySQL real.
 *
 * <p>Cada {@link Mundo} es un tenant completo y aislado —organizacion, sede, profesional con su
 * membership, historia clinica, oferta, practica, espacio y una medida del catalogo— con sufijos
 * aleatorios, asi que los tests no se pisan entre si ni dependen del orden de ejecucion.
 *
 * <p>Se inserta directo y no por los servicios, por el mismo motivo que {@code CierreConcurrenteIT}:
 * abrir una sesion por el camino normal exige turno, slot y toda la cadena de M12, que ya tiene su
 * propio test. Lo que estos ITs miden esta del lado de la sesion para adelante.
 */
public final class EncounterFixtures {

	private static final String ZONA = "America/Argentina/Cordoba";

	private final JdbcTemplate jdbc;

	public EncounterFixtures(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Un tenant completo, con un profesional que ya puede registrar atenciones. */
	public Mundo crearMundo() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);

		long organizationId = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM organization WHERE slug = ?",
				new Object[]{"Centro " + sufijo, "enc-it-" + sufijo, ZONA},
				new Object[]{"enc-it-" + sufijo});

		long consultorioId = crearConsultorio(organizationId, "Sede " + sufijo);

		Miembro profesional = crearMiembro(organizationId, consultorioId, "PROFESIONAL", "prof-" + sufijo);

		String apellido = "Paciente" + sufijo;
		long personaId = insertar("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave, nombre_clave,
				                     active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM persona WHERE organization_id = ? AND apellido = ?",
				new Object[]{organizationId, apellido, apellido.toUpperCase()},
				new Object[]{organizationId, apellido});

		jdbc.update("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, personaId, profesional.cuentaId());

		long historiaClinicaId = insertar("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM historia_clinica WHERE organization_id = ? AND persona_id = ?",
				new Object[]{organizationId, personaId, profesional.cuentaId()},
				new Object[]{organizationId, personaId});

		long servicioId = insertar("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'INDIVIDUAL', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM servicio WHERE codigo = ?",
				new Object[]{"ENC-" + sufijo.toUpperCase(), "Servicio " + sufijo},
				new Object[]{"ENC-" + sufijo.toUpperCase()});

		long ofertaId = insertar("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, precio_base, moneda, admite_obra_social,
				        requiere_caso_clinico, genera_registro_clinico, requiere_profesional,
				        requiere_espacio, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'INDIVIDUAL', 60, 1, 8500.00, 'ARS', 0, 0, 0, 1, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", """
				SELECT id FROM oferta_servicio_consultorio
				 WHERE organization_id = ? AND nombre_comercial = ?
				""", new Object[]{organizationId, consultorioId, servicioId, "Oferta " + sufijo},
				new Object[]{organizationId, "Oferta " + sufijo});

		long especialidadId = insertar("""
				INSERT INTO especialidad (organization_id, codigo, name, valid_from, active, version,
				                          created_at, updated_at)
				VALUES (?, ?, ?, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM especialidad WHERE organization_id = ? AND codigo = ?",
				new Object[]{organizationId, "ESP-" + sufijo, "Especialidad " + sufijo},
				new Object[]{organizationId, "ESP-" + sufijo});

		long practicaId = crearPractica(organizationId, especialidadId, "PR-" + sufijo, "Practica " + sufijo);

		long espacioId = crearEspacio(organizationId, consultorioId, "Box " + sufijo);

		long medicionId = insertar("""
				INSERT INTO medicion_definicion (organization_id, codigo, name, tipo, unidad, minimo,
				                                 maximo, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 'NUMERICO', 'grados', 0, 180, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM medicion_definicion WHERE organization_id = ? AND codigo = ?",
				new Object[]{organizationId, "ROM-" + sufijo, "Rango articular " + sufijo},
				new Object[]{organizationId, "ROM-" + sufijo});

		return new Mundo(organizationId, consultorioId, historiaClinicaId, ofertaId, especialidadId,
				practicaId, espacioId, medicionId, profesional, sufijo);
	}

	public long crearConsultorio(long organizationId, String nombre) {
		return insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM consultorio WHERE organization_id = ? AND name = ?",
				new Object[]{organizationId, nombre, ZONA},
				new Object[]{organizationId, nombre});
	}

	/** Una cuenta con membership vigente en la sede, con el rol indicado. */
	public Miembro crearMiembro(long organizationId, long consultorioId, String rol, String alias) {
		String email = alias + "@ejemplo.test";
		long cuentaId = insertar("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM cuenta WHERE email_normalizado = ?",
				new Object[]{email, email}, new Object[]{email});

		long membershipId = insertar("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, ?, 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM membership WHERE organization_id = ? AND account_id = ?",
				new Object[]{organizationId, consultorioId, cuentaId, rol},
				new Object[]{organizationId, cuentaId});

		return new Miembro(cuentaId, membershipId,
				new OperatingActor(cuentaId, false, organizationId, consultorioId));
	}

	public long crearPractica(long organizationId, long especialidadId, String codigo, String nombre) {
		return insertar("""
				INSERT INTO practica (organization_id, especialidad_id, codigo, name, valid_from, active,
				                      version, created_at, updated_at)
				VALUES (?, ?, ?, ?, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM practica WHERE organization_id = ? AND codigo = ?",
				new Object[]{organizationId, especialidadId, codigo, nombre},
				new Object[]{organizationId, codigo});
	}

	public long crearEspacio(long organizationId, long consultorioId, String nombre) {
		return insertar("""
				INSERT INTO espacio (organization_id, consultorio_id, name, tipo, capacidad, valid_from,
				                     active, version, created_at, updated_at)
				VALUES (?, ?, ?, 'BOX', 1, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SELECT id FROM espacio WHERE organization_id = ? AND consultorio_id = ? AND name = ?",
				new Object[]{organizationId, consultorioId, nombre},
				new Object[]{organizationId, consultorioId, nombre});
	}

	/** Una sesion BORRADOR del profesional del mundo, lista para registrar o cerrar. */
	public long crearSesion(Mundo mundo) {
		return crearSesion(mundo, mundo.profesional());
	}

	/** Una sesion BORRADOR cuyo dueño es {@code duenio}, que tiene que ser miembro de la sede. */
	public long crearSesion(Mundo mundo, Miembro duenio) {
		jdbc.update("""
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, oferta_id,
				                    profesional_membership_id, estado, iniciada_en,
				                    iniciada_por_cuenta_id, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'BORRADOR', UTC_TIMESTAMP(6), ?, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", mundo.organizationId(), mundo.consultorioId(), mundo.historiaClinicaId(),
				mundo.ofertaId(), duenio.membershipId(), duenio.cuentaId());
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertar(String insert, String select, Object[] insertArgs, Object[] selectArgs) {
		jdbc.update(insert, insertArgs);
		return jdbc.queryForObject(select, Long.class, selectArgs);
	}

	/** Un miembro de una sede: la cuenta, su membership y el actor con el que opera. */
	public record Miembro(long cuentaId, long membershipId, OperatingActor actor) {
	}

	/** Un tenant sintetico completo. */
	public record Mundo(
			long organizationId,
			long consultorioId,
			long historiaClinicaId,
			long ofertaId,
			long especialidadId,
			long practicaId,
			long espacioId,
			long medicionDefinicionId,
			Miembro profesional,
			String sufijo) {

		public OperatingActor actor() {
			return profesional.actor();
		}
	}
}
