package com.akine.person;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.akine.TestcontainersConfiguration;
import com.akine.person.application.OperatingActor;
import com.akine.person.application.PerfilFiltro;
import com.akine.person.application.PersonaBusqueda;
import com.akine.person.application.PersonaEstadoFiltro;
import com.akine.person.application.PersonaPagina;
import com.akine.person.application.PersonaService;
import com.akine.person.application.PersonaView;
import com.akine.person.support.CoberturaFixtures;
import com.akine.person.support.CoberturaFixtures.Plan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.akine.organization.application.PermissionEvaluatorService;
import com.akine.organization.spi.PermissionDecision;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-1 contra MySQL real: la busqueda del padron por numero de afiliado, escrita desde el contrato
 * ({@code GET /api/v1/personas?q=...}) y sembrando las filas por SQL.
 *
 * <p>Cada test crea su propia organizacion, asi que ninguna fila de otro test puede aparecer en un
 * resultado. Los numeros de afiliado y los documentos son sinteticos y no se parecen entre si.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PersonaBusquedaAfiliadoIT {

	private static final LocalDate HOY = LocalDate.of(2027, 6, 15);

	@Autowired private PersonaService personaService;
	@Autowired private JdbcTemplate jdbc;

	/**
	 * Este IT prueba el SQL de la busqueda, no la autorizacion: las organizaciones se siembran por
	 * SQL y no tienen memberships. Desde AKINE-DU-6 (DP-22) leer el padron exige paciente:read, y
	 * ese control lo prueba {@code PacienteReadIT} por HTTP con cuentas reales. Aca el guard se
	 * espia y concede: es el mismo bean que {@code PermissionEvaluator}, asi que no se puede
	 * reemplazar por un mock de una sola interfaz.
	 */
	@MockitoSpyBean private PermissionEvaluatorService permisos;

	private CoberturaFixtures datos;

	@BeforeEach
	void setUp() {
		datos = new CoberturaFixtures(jdbc);
		org.mockito.Mockito.doReturn(PermissionDecision.concedida("ORGANIZACION", false))
				.when(permisos).requirePermission(org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("B1-E1 el numero de afiliado exacto encuentra a la persona")
	void b1_e1_numero_exacto() {
		long org = datos.organizacion();
		long ana = personaConAfiliado(org, "Alvarez", "Ana", "30000001", "62000123456");
		personaConAfiliado(org, "Benitez", "Beto", "30000002", "77700099988");

		assertThat(ids(org, "62000123456")).containsExactly(ana);
	}

	@Test
	@DisplayName("B1-E2 un fragmento del numero, final o medio, tambien encuentra")
	void b1_e2_match_contiene() {
		long org = datos.organizacion();
		long ana = personaConAfiliado(org, "Alvarez", "Ana", "30000001", "62000123456");

		assertThat(ids(org, "3456")).as("final").containsExactly(ana);
		assertThat(ids(org, "0012")).as("medio").containsExactly(ana);
		assertThat(ids(org, "6200")).as("principio").containsExactly(ana);
	}

	@Test
	@DisplayName("B1-E3 sin separadores de ambos lados: 12-345.678/9 se halla con 123456789 y 12.345; 123456789 con 12-345")
	void b1_e3_sin_separadores_de_ambos_lados() {
		long org = datos.organizacion();
		long conSeparadores = personaConAfiliado(org, "Alvarez", "Ana", "30000001", "12-345.678/9");
		long sinSeparadores = personaConAfiliado(org, "Benitez", "Beto", "30000002", "987654321");

		assertThat(ids(org, "123456789")).containsExactly(conSeparadores);
		assertThat(ids(org, "12.345")).containsExactly(conSeparadores);
		assertThat(ids(org, "98-765")).containsExactly(sinSeparadores);
		assertThat(ids(org, "9876.54/321")).containsExactly(sinSeparadores);
	}

	@Test
	@DisplayName("B1-E3b un afiliado alfanumerico se halla en minusculas o mayusculas, con o sin guion")
	void b1_e3b_alfanumerico() {
		long org = datos.organizacion();
		long ana = personaConAfiliado(org, "Alvarez", "Ana", "30000001", "ab-1234");

		for (String tipeado : List.of("ab-1234", "AB-1234", "ab1234", "AB1234", "Ab 12")) {
			assertThat(ids(org, tipeado)).as("tipeado como '%s'", tipeado).containsExactly(ana);
		}
	}

	@Test
	@DisplayName("B1-E4 una cobertura dada de baja no matchea")
	void b1_e4_cobertura_de_baja_no_matchea() {
		long org = datos.organizacion();
		long ana = datos.persona(org, "Alvarez", "Ana", "30000001");
		long cobertura = datos.cobertura(org, ana, datos.planNuevo(org), "62000123456",
				HOY.minusYears(1), null, true, null);
		assertThat(ids(org, "62000123456")).as("antes de la baja").containsExactly(ana);

		datos.darDeBaja(cobertura);

		assertThat(ids(org, "62000123456")).as("despues de la baja").isEmpty();
	}

	@Test
	@DisplayName("B1-E5 una cobertura de vigencia futura o ya terminada SI matchea")
	void b1_e5_la_vigencia_no_filtra() {
		long org = datos.organizacion();
		long futura = datos.persona(org, "Alvarez", "Ana", "30000001");
		datos.cobertura(org, futura, datos.planNuevo(org), "41000111222",
				LocalDate.of(2099, 1, 1), null, false, null);
		long terminada = datos.persona(org, "Benitez", "Beto", "30000002");
		datos.cobertura(org, terminada, datos.planNuevo(org), "52000333444",
				LocalDate.of(2001, 1, 1), LocalDate.of(2001, 12, 31), false, null);

		assertThat(ids(org, "41000111222")).containsExactly(futura);
		assertThat(ids(org, "52000333444")).containsExactly(terminada);
	}

	@Test
	@DisplayName("B1-E6 una cobertura de otra organizacion no matchea")
	void b1_e6_tenant() {
		long orgPropia = datos.organizacion();
		long orgAjena = datos.organizacion();
		long ajena = datos.persona(orgAjena, "Alvarez", "Ana", "30000001");
		datos.cobertura(orgAjena, ajena, datos.planNuevo(orgAjena), "62000123456",
				HOY.minusYears(1), null, true, null);
		long propia = personaConAfiliado(orgPropia, "Benitez", "Beto", "30000002", "55000999888");

		assertThat(ids(orgPropia, "62000123456")).as("el afiliado ajeno no se ve").isEmpty();
		assertThat(ids(orgAjena, "62000123456")).as("y en su organizacion si").containsExactly(ajena);
		assertThat(ids(orgPropia, "55000999888")).containsExactly(propia);
	}

	@Test
	@DisplayName("B1-E7 el mismo numero en dos planes distintos devuelve las dos personas")
	void b1_e7_mismo_numero_en_dos_planes() {
		long org = datos.organizacion();
		long ana = personaConAfiliado(org, "Alvarez", "Ana", "30000001", "62000123456");
		long beto = personaConAfiliado(org, "Benitez", "Beto", "30000002", "62000123456");

		PersonaPagina pagina = buscar(org, "62000123456", null, null, 0, 20);

		assertThat(pagina.contenido()).extracting(PersonaView::id).containsExactly(ana, beto);
		assertThat(pagina.total()).isEqualTo(2);
	}

	@Test
	@DisplayName("B1-E8 una persona con dos coberturas que matchean aparece una sola vez y el total es correcto")
	void b1_e8_sin_duplicados() {
		long org = datos.organizacion();
		long ana = datos.persona(org, "Alvarez", "Ana", "30000001");
		datos.cobertura(org, ana, datos.planNuevo(org), "62000123456",
				HOY.minusYears(2), HOY.minusYears(1), false, null);
		datos.cobertura(org, ana, datos.planNuevo(org), "62-000-123-456",
				HOY.minusMonths(1), null, true, null);
		long beto = personaConAfiliado(org, "Benitez", "Beto", "30000002", "62000999000");

		PersonaPagina todo = buscar(org, "6200", null, null, 0, 20);
		assertThat(todo.contenido()).extracting(PersonaView::id).containsExactly(ana, beto);
		assertThat(todo.total()).as("total del filtro, sin contar filas de cobertura").isEqualTo(2);

		PersonaPagina primera = buscar(org, "6200", null, null, 0, 1);
		PersonaPagina segunda = buscar(org, "6200", null, null, 1, 1);
		assertThat(primera.contenido()).extracting(PersonaView::id).containsExactly(ana);
		assertThat(segunda.contenido()).extracting(PersonaView::id).containsExactly(beto);
		assertThat(primera.total()).isEqualTo(2);
		assertThat(segunda.total()).isEqualTo(2);

		assertThat(buscar(org, "62000123456", null, null, 0, 20).total()).isEqualTo(1);
	}

	@Test
	@DisplayName("B1-E9 se combina con estado y perfil: la persona inactiva no sale por defecto y si con TODOS")
	void b1_e9_combina_con_estado_y_perfil() {
		long org = datos.organizacion();
		long activa = datos.persona(org, "Alvarez", "Ana", "30000001");
		datos.cobertura(org, activa, datos.planNuevo(org), "62000111111",
				HOY.minusYears(1), null, true, null);
		datos.perfilPaciente(org, activa);
		long inactiva = datos.persona(org, "Benitez", "Beto", "30000002", null, false);
		datos.cobertura(org, inactiva, datos.planNuevo(org), "62000222222",
				HOY.minusYears(1), null, true, null);

		assertThat(ids(org, "6200", null, null)).as("por defecto: solo ACTIVAS")
				.containsExactly(activa);
		assertThat(ids(org, "6200", PersonaEstadoFiltro.TODOS, null))
				.containsExactly(activa, inactiva);
		assertThat(ids(org, "6200", PersonaEstadoFiltro.INACTIVO, null))
				.containsExactly(inactiva);
		assertThat(ids(org, "62000222222", null, null)).as("el afiliado de la inactiva, por defecto")
				.isEmpty();
		assertThat(ids(org, "62000222222", PersonaEstadoFiltro.TODOS, null))
				.containsExactly(inactiva);

		assertThat(ids(org, "6200", PersonaEstadoFiltro.TODOS, PerfilFiltro.CON_PERFIL))
				.containsExactly(activa);
		assertThat(ids(org, "6200", PersonaEstadoFiltro.TODOS, PerfilFiltro.SIN_PERFIL))
				.containsExactly(inactiva);
	}

	@Test
	@DisplayName("B1-E10 la busqueda por documento, telefono, apellido y nombre sigue igual")
	void b1_e10_regresion() {
		long org = datos.organizacion();
		long ana = datos.persona(org, "Alvarez", "Ana", "30111222", "541155550000", true);
		long beto = datos.persona(org, "Benitez", "Beto", "27888999", "541166661111", true);
		datos.cobertura(org, beto, datos.planNuevo(org), "99000777555",
				HOY.minusYears(1), null, true, null);

		assertThat(ids(org, "30111222")).as("documento").containsExactly(ana);
		assertThat(ids(org, "30.111.222")).as("documento con puntos").containsExactly(ana);
		assertThat(ids(org, "1222")).as("final de documento").containsExactly(ana);
		assertThat(ids(org, "5555-0000")).as("telefono").containsExactly(ana);
		assertThat(ids(org, "+54 11 6666")).as("telefono con prefijo").containsExactly(beto);
		assertThat(ids(org, "Alvarez")).as("apellido").containsExactly(ana);
		assertThat(ids(org, "alvarez")).as("apellido en minusculas").containsExactly(ana);
		assertThat(ids(org, "Beto")).as("nombre").containsExactly(beto);
		assertThat(ids(org, "zzz")).as("sin coincidencias").isEmpty();
		assertThat(ids(org, null)).as("sin texto: el padron paginado").containsExactly(ana, beto);
	}

	@Test
	@DisplayName("B1-E11 el numero de afiliado no aparece en la auditoria ni en los logs de la busqueda")
	void b1_e11_el_afiliado_no_se_audita_ni_se_loguea() {
		long org = datos.organizacion();
		personaConAfiliado(org, "Alvarez", "Ana", "30000001", "62000123456");
		int eventosAntes = contarEventosDeAuditoria();

		// Se abre al maximo el log del codigo propio ({@code com.akine}). No el de los frameworks: con
		// TRACE, Hibernate vuelca los parametros de bind de cualquier consulta y eso no es un
		// defecto de la busqueda sino una decision de configuracion que aca no se toma.
		Logger raiz = (Logger) LoggerFactory.getLogger("com.akine");
		Logger todo = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		Level nivelPrevio = raiz.getLevel();
		ListAppender<ILoggingEvent> capturados = new ListAppender<>();
		capturados.start();
		todo.addAppender(capturados);
		raiz.setLevel(Level.ALL);
		try {
			assertThat(ids(org, "62000123456")).hasSize(1);
			assertThat(ids(org, "62-000-123")).hasSize(1);
		} finally {
			todo.detachAppender(capturados);
			raiz.setLevel(nivelPrevio);
		}

		assertThat(capturados.list)
				.as("ningun log de la busqueda menciona el numero de afiliado")
				.noneMatch(e -> e.getFormattedMessage().contains("62000123456")
						|| e.getFormattedMessage().contains("62-000-123"));
		assertThat(contarEventosDeAuditoria())
				.as("la busqueda no deja eventos de auditoria")
				.isEqualTo(eventosAntes);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event
				 WHERE details LIKE '%62000123456%' OR reason LIKE '%62000123456%'
				""", Integer.class)).isZero();
	}

	// =================================================================================
	// Apoyo — datos sinteticos
	// =================================================================================

	private int contarEventosDeAuditoria() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class);
	}

	/** Persona activa con una cobertura vigente en un plan propio, para no chocar con el unique. */
	private long personaConAfiliado(
			long org, String apellido, String nombre, String documento, String afiliado) {

		long persona = datos.persona(org, apellido, nombre, documento);
		Plan plan = datos.planNuevo(org);
		datos.cobertura(org, persona, plan, afiliado, HOY.minusYears(1), null, true, null);
		return persona;
	}

	private List<Long> ids(long org, String texto) {
		return ids(org, texto, null, null);
	}

	private List<Long> ids(
			long org, String texto, PersonaEstadoFiltro estado, PerfilFiltro perfil) {
		return buscar(org, texto, estado, perfil, 0, 50).contenido().stream()
				.map(PersonaView::id)
				.toList();
	}

	private PersonaPagina buscar(
			long org, String texto, PersonaEstadoFiltro estado, PerfilFiltro perfil,
			int page, int size) {

		OperatingActor actor = new OperatingActor(1L, false, org, null);
		return personaService.buscar(actor, new PersonaBusqueda(texto, estado, perfil), page, size);
	}
}
