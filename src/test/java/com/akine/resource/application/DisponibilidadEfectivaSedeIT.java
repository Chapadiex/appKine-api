package com.akine.resource.application;

import com.akine.TestcontainersConfiguration;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.resource.application.DisponibilidadEfectivaView.DiaEfectivo;
import com.akine.resource.application.DisponibilidadEfectivaView.FranjaResuelta;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.BloqueDisponibilidadRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.CalendarioSedeRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.DisponibilidadExcepcionRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.FeriadoRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * El filtro por SEDE de la disponibilidad efectiva, contra MySQL real (AKINE-02.04, tarea 8).
 *
 * <h2>El caso que rompe el diseno, y por que no se puede probar con mocks</h2>
 *
 * <p>{@code DisponibilidadEfectivaCalculator} <b>no recibe {@code consultorioId} y no filtra por
 * sede</b>: su javadoc lo dice y nombra al llamador como responsable. El caso concreto es una
 * membership de alcance ORGANIZACION ({@code consultorio_id} nulo, legal desde V10) con bloques en
 * dos sedes bajo el mismo {@code membership_id}. Si la consulta pierde el predicado por
 * {@code consultorio_id}, el calculador mezcla los horarios de las dos sedes en la disponibilidad
 * de un solo profesional y <b>cada franja del resultado sigue reportando un {@code reglaId}
 * perfectamente legitimo</b>. No falla nada, no hay excepcion y ninguna pantalla protesta: el
 * sistema simplemente inventa horarios que el centro nunca cargo.
 *
 * <p>Con los puertos mockeados este test comprobaria su propio stub: quien decide que filas
 * vuelven seria el propio test. Por eso los <b>cuatro puertos de persistencia son los reales</b>,
 * contra el esquema de V22/V23 en una base de verdad.
 *
 * <h2>Por que el servicio se construye a mano y no se inyecta</h2>
 *
 * <p>Los tres colaboradores de {@code organization} —resolucion de sede, de membership y el
 * evaluador de permisos— si son dobles: montar un tenant autenticado con su matriz de permisos
 * sembrada probaria la autorizacion, que ya tiene sus propios tests, y no el predicado de la
 * consulta, que es lo unico en juego aca.
 *
 * <p>Sustituirlos con {@code @MockitoBean} no es una opcion: el bean que implementa
 * {@code PermissionGuard} implementa TAMBIEN {@code PermissionEvaluator}, y reemplazarlo por un
 * doble de un solo tipo deja al resto del contexto sin poder resolver el otro. El contexto entero
 * no arranca. Instanciar el servicio con los repositorios reales y los tres dobles evita el
 * problema sin debilitar lo que el test verifica.
 *
 * <p>Consecuencia declarada: el servicio construido a mano no tiene proxy, asi que
 * {@code @Transactional} no aplica. No importa —son lecturas, y cada consulta de Spring Data abre
 * la suya—, pero conviene saberlo antes de agregarle a este test un caso que dependa de una
 * transaccion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class DisponibilidadEfectivaSedeIT {

	/** Martes, y deliberadamente NO feriado en el calendario que siembra V22. */
	private static final LocalDate MARTES = LocalDate.of(2026, 3, 3);

	private static final int DIA_MARTES = 2;

	/** UTC-03 todo el anio: las 09:00 locales son las 12:00Z. */
	private static final String ZONA = "America/Argentina/Cordoba";

	@Autowired
	private BloqueDisponibilidadRepositoryPort bloques;

	@Autowired
	private DisponibilidadExcepcionRepositoryPort excepciones;

	@Autowired
	private FeriadoRepositoryPort feriados;

	@Autowired
	private CalendarioSedeRepositoryPort calendarios;

	@Autowired
	private DataSource dataSource;

	private final ConsultorioDirectory consultorioDirectory = mock(ConsultorioDirectory.class);
	private final ConsultorioMembershipDirectory membershipDirectory =
			mock(ConsultorioMembershipDirectory.class);
	private final PermissionGuard permissionGuard = mock(PermissionGuard.class);

	private DisponibilidadEfectivaService service;
	private JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		jdbc = new JdbcTemplate(dataSource);
		service = new DisponibilidadEfectivaService(
				bloques, excepciones, feriados, calendarios,
				consultorioDirectory, membershipDirectory, permissionGuard);
	}

	@Test
	@DisplayName("Una membership de alcance organizacion con bloques en dos sedes: consultando una, los de la otra no aparecen")
	void los_bloques_de_la_otra_sede_no_entran_en_la_disponibilidad_de_esta() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);

		long organizationId = insertarOrganization(sufijo);
		long sedeA = insertarConsultorio(organizationId, "Sede A " + sufijo);
		long sedeB = insertarConsultorio(organizationId, "Sede B " + sufijo);
		long accountId = insertarCuenta(sufijo);
		// consultorio_id NULL: alcance ORGANIZACION, legal desde V10. Una sola membership para
		// las dos sedes, que es exactamente lo que hace posible la mezcla.
		long membershipId = insertarMembershipDeOrganizacion(organizationId, accountId);

		insertarBloque(organizationId, sedeA, membershipId, "09:00:00", "13:00:00");
		insertarBloque(organizationId, sedeB, membershipId, "15:00:00", "19:00:00");

		given(consultorioDirectory.find(organizationId, sedeA)).willReturn(Optional.of(
				new ConsultorioSnapshot(sedeA, organizationId, "Sede A", ZONA, true)));
		given(membershipDirectory.find(organizationId, membershipId)).willReturn(Optional.of(
				new ConsultorioMembershipSnapshot(
						membershipId, accountId, organizationId, null, "PROFESIONAL", "ACTIVA",
						Instant.parse("2020-01-01T00:00:00Z"), null, true, true)));

		OperatingActor actor = new OperatingActor(accountId, false, organizationId, sedeA);

		DisponibilidadEfectivaView efectiva =
				service.efectiva(actor, sedeA, membershipId, MARTES, MARTES.plusDays(1));

		assertThat(efectiva.dias()).hasSize(1);
		DiaEfectivo dia = efectiva.dias().getFirst();

		assertThat(dia.franjas())
				.as("solo el bloque de la sede consultada: si el predicado por consultorio_id se "
						+ "pierde, aca aparecen dos franjas y las dos con un reglaId legitimo")
				.hasSize(1);

		FranjaResuelta franja = dia.franjas().getFirst();
		assertThat(franja.desde()).isEqualTo(Instant.parse("2026-03-03T12:00:00Z"));
		assertThat(franja.hasta()).isEqualTo(Instant.parse("2026-03-03T16:00:00Z"));
		assertThat(franja.origen()).isEqualTo("BLOQUE");

		assertThat(franja.reglaId())
				.as("y es el bloque de la sede A, no el de la B")
				.isEqualTo(idDelBloque(organizationId, sedeA, membershipId));

		// La fila de la otra sede sigue existiendo: no se filtro borrandola.
		assertThat(idDelBloque(organizationId, sedeB, membershipId)).isNotNull();
	}

	// =================================================================================
	// Fixture — datos sinteticos, igual que en DisponibilidadMigrationIT
	// =================================================================================

	private long insertarOrganization(String sufijo) {
		String slug = "efectiva-" + sufijo;
		jdbc.update("""
				INSERT INTO organization (name, slug, timezone, active, version,
				                          created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Efectiva Sintetica " + sufijo, slug, ZONA);
		return jdbc.queryForObject("SELECT id FROM organization WHERE slug = ?", Long.class, slug);
	}

	private long insertarConsultorio(long organizationId, String nombre) {
		jdbc.update("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, nombre, ZONA);
		return jdbc.queryForObject(
				"SELECT id FROM consultorio WHERE organization_id = ? AND name = ?",
				Long.class, organizationId, nombre);
	}

	private long insertarCuenta(String sufijo) {
		String email = "efectiva-" + sufijo + "@ejemplo.test";
		jdbc.update("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado,
				                    active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'Efectiva', 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
		return jdbc.queryForObject(
				"SELECT id FROM cuenta WHERE email_normalizado = ?", Long.class, email);
	}

	private long insertarMembershipDeOrganizacion(long organizationId, long accountId) {
		jdbc.update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, active, version,
				                        created_at, updated_at)
				VALUES (?, NULL, ?, 'PROFESIONAL', 0,
				        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, accountId);
		return jdbc.queryForObject("""
				SELECT id FROM membership
				 WHERE organization_id = ? AND consultorio_id IS NULL AND account_id = ?
				""", Long.class, organizationId, accountId);
	}

	private void insertarBloque(
			long organizationId, long consultorioId, long membershipId,
			String horaDesde, String horaHasta) {

		jdbc.update("""
				INSERT INTO profesional_disponibilidad
				    (organization_id, consultorio_id, membership_id, dia_semana, hora_desde,
				     hora_hasta, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, '2020-01-01', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, membershipId, DIA_MARTES, horaDesde, horaHasta);
	}

	private Long idDelBloque(long organizationId, long consultorioId, long membershipId) {
		List<Long> ids = jdbc.queryForList("""
				SELECT id FROM profesional_disponibilidad
				 WHERE organization_id = ? AND consultorio_id = ? AND membership_id = ?
				""", Long.class, organizationId, consultorioId, membershipId);
		assertThat(ids).hasSize(1);
		return ids.getFirst();
	}
}
