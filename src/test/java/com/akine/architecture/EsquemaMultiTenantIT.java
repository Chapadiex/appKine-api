package com.akine.architecture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.akine.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0004 verificado contra el ESQUEMA REAL: toda tabla de negocio lleva
 * {@code organization_id NOT NULL}, salvo las excepciones declaradas en <b>ADR-0023</b>
 * ({@code docs/adr/0023-tablas-globales-sin-organization-id.md}).
 *
 * <p><b>Por que existe este test y por que llega recien en AKINE-02.06.</b> Los cuatro ADR que
 * ADR-0023 supersede —0019, 0020, 0021 y 0022— afirman en sus consecuencias que "un test generico
 * 'toda tabla lleva organization_id' necesita una entrada mas en su lista de exclusion". Ese test
 * <b>nunca se escribio</b>: hasta esta etapa la regla la sostenia la revision humana de cada
 * {@code .sql}. Consolidar el criterio en un solo documento habria hecho la mentira peor, porque
 * ADR-0023 se lee como la declaracion autoritativa: alguien agrega una tabla sin la columna, cree
 * que un gate lo habria frenado, y no lo frena nadie. Este test hace verdadera esa frase.
 *
 * <p><b>Por que contra MySQL real y no ArchUnit sobre las entidades JPA.</b> Una regla de ArchUnit
 * no puede ver lo que importa: una tabla sin entidad. {@code feriado} vivio sin entidad hasta que
 * 02.04 la mapeo, y {@code servicio} y {@code oferta_servicio_consultorio} no tienen ninguna
 * todavia — las crea la tarea 1 de esta etapa y las mapea la tarea 2. Un gate que solo mirara
 * entidades habria dado verde sobre exactamente las tablas que esta etapa acaba de crear. Lo que
 * manda es el esquema aplicado (ADR-0003: Flyway es la unica autoridad del esquema).
 *
 * <p><b>Por que la lista es explicita y esta escrita a mano.</b> Excluir por patron —"si no tiene
 * la columna, no aplica"— pasaria por alto el error que este test existe para atrapar. La lista es
 * de tablas conocidas y justificadas, con su motivo al lado, para que una tabla NUEVA sin
 * {@code organization_id} <b>falle</b> hasta que alguien la agregue a mano. Ese momento —tener que
 * escribir el motivo— es exactamente cuando corresponde consultar ADR-0023 y demostrar sus tres
 * condiciones.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class EsquemaMultiTenantIT {

	/** Forma que toma la excepcion en el esquema. Cada entrada declara cual le corresponde. */
	private enum Forma {

		/** La columna no existe. Tabla enteramente global, o que <b>es</b> el tenant. */
		SIN_COLUMNA,

		/**
		 * La columna existe y es NULLABLE, con un significado declarado para el {@code NULL}.
		 * Nunca es "falta el dato".
		 */
		NULLABLE
	}

	private record Excepcion(Forma forma, String motivo) {
	}

	/**
	 * La lista consolidada de ADR-0023, tabla por tabla. Si esto cambia, ADR-0023 cambia con ello:
	 * <b>el array no reemplaza al ADR, lo materializa</b>. Un array dice QUE esta excluido y nunca
	 * POR QUE, y es el por que lo que hace falta al disenar la tabla numero cuarenta.
	 */
	private static final Map<String, Excepcion> EXCEPCIONES = new LinkedHashMap<>();

	static {
		// --- ADR-0019 (superseded by 0023) — identidad y tablas previas al tenant -------------
		EXCEPCIONES.put("organization", new Excepcion(Forma.SIN_COLUMNA,
				"ES el tenant: su id es el organization_id de todas las demas"));
		EXCEPCIONES.put("plan", new Excepcion(Forma.SIN_COLUMNA,
				"Catalogo comercial de plataforma: no es dato de un tenant, es la oferta que todos consumen"));
		EXCEPCIONES.put("plan_limit", new Excepcion(Forma.SIN_COLUMNA,
				"Cuelga de plan, que es global"));
		EXCEPCIONES.put("plan_feature", new Excepcion(Forma.SIN_COLUMNA,
				"Cuelga de plan, que es global"));
		EXCEPCIONES.put("cuenta", new Excepcion(Forma.SIN_COLUMNA,
				"Identidad unica cross-tenant (ADR-0009). La cuenta precede al tenant y lo atraviesa"));
		EXCEPCIONES.put("token_verificacion", new Excepcion(Forma.SIN_COLUMNA,
				"Cuelga de cuenta, que es global. El token se presenta ANTES de que haya contexto"));
		EXCEPCIONES.put("refresh_token", new Excepcion(Forma.SIN_COLUMNA,
				"Cuelga de cuenta. context_organization_id es el ALCANCE del token, no ownership de tenant"));
		EXCEPCIONES.put("platform_schema_info", new Excepcion(Forma.SIN_COLUMNA,
				"Tabla tecnica de infraestructura, no de negocio"));
		EXCEPCIONES.put("notification_outbox", new Excepcion(Forma.NULLABLE,
				"Hay notificaciones PREVIAS al tenant (activacion, recuperacion). "
						+ "NULL = evento de identidad global, operable solo por admin de plataforma"));
		EXCEPCIONES.put("audit_event", new Excepcion(Forma.NULLABLE,
				"NULL SOLO para eventos de plataforma sin tenant. Todo evento de negocio lo lleva"));
		EXCEPCIONES.put("onboarding_registro", new Excepcion(Forma.NULLABLE,
				"La fila nace ANTES que el tenant: el registro self-service existe mientras la "
						+ "organizacion todavia no. NULL = registro sin organizacion creada aun, y la "
						+ "referencia es logica, sin FK fisica (ADR-0001)"));

		// --- ADR-0020 (superseded by 0023) — rol de plataforma --------------------------------
		EXCEPCIONES.put("platform_role", new Excepcion(Forma.SIN_COLUMNA,
				"Rol DE LA PLATAFORMA. La matriz 1.3 lo define como el rol que no tiene membership en "
						+ "ninguna organizacion: acotarlo a un tenant seria el rol contrario"));

		// --- ADR-0021 (superseded by 0023) — catalogos clinicos, DOS poblaciones --------------
		EXCEPCIONES.put("especialidad", new Excepcion(Forma.NULLABLE,
				"Dos poblaciones en la misma tabla: NULL = concepto de plataforma, valor = del tenant. "
						+ "Los unique van sobre owner_key = IFNULL(organization_id, 0)"));
		EXCEPCIONES.put("practica", new Excepcion(Forma.NULLABLE,
				"Dos poblaciones en la misma tabla, igual que especialidad (ADR-0021)"));
		EXCEPCIONES.put("nomenclador", new Excepcion(Forma.NULLABLE,
				"Dos poblaciones en la misma tabla, igual que especialidad (ADR-0021)"));
		EXCEPCIONES.put("nomenclador_item", new Excepcion(Forma.NULLABLE,
				"Dos poblaciones en la misma tabla; el duenio se replica del nomenclador padre (ADR-0021)"));
		EXCEPCIONES.put("medicion_definicion", new Excepcion(Forma.NULLABLE,
				"Dos poblaciones en la misma tabla, igual que especialidad: NULL = test de "
						+ "plataforma, valor = test propio del centro. El unique va sobre owner_key y la "
						+ "decision de usarlo vive en la sesion, que lleva organization_id (ADR-0021, 06.03)"));

		// --- ADR-0022 (superseded by 0023) — feriados -----------------------------------------
		EXCEPCIONES.put("feriado", new Excepcion(Forma.SIN_COLUMNA,
				"Hecho del calendario publico nacional. La decision de la sede —si cierra ese dia— vive "
						+ "en consultorio_calendario, que si lleva organization_id NOT NULL"));

		// --- ADR-0023 — la quinta excepcion, AKINE-02.06 --------------------------------------
		EXCEPCIONES.put("servicio", new Excepcion(Forma.SIN_COLUMNA,
				"Concepto del catalogo global (RN-M27-001). Como lo presta un centro concreto vive en "
						+ "oferta_servicio_consultorio, que si lleva organization_id NOT NULL"));
	}

	/** Tabla de Flyway. No es del dominio y no la escribe ninguna migracion nuestra. */
	private static final String FLYWAY = "flyway_schema_history";

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;

	private JdbcTemplate jdbc() {
		if (jdbc == null) {
			jdbc = new JdbcTemplate(dataSource);
		}
		return jdbc;
	}

	// =================================================================================
	// La regla
	// =================================================================================

	@Test
	@DisplayName("toda tabla del esquema lleva organization_id NOT NULL, salvo las excepciones de ADR-0023")
	void toda_tabla_lleva_organization_id_not_null_salvo_las_excepciones_de_adr_0023() {
		List<String> incumplen = new ArrayList<>();

		for (String tabla : tablasDelEsquema()) {
			if (EXCEPCIONES.containsKey(tabla)) {
				continue;
			}
			String nullable = nullabilidadDeOrganizationId(tabla);
			if (nullable == null) {
				incumplen.add(tabla + " (no tiene la columna organization_id)");
			}
			else if (!"NO".equals(nullable)) {
				incumplen.add(tabla + " (organization_id existe pero es NULLABLE)");
			}
		}

		assertThat(incumplen)
				.as("ADR-0004: toda tabla de negocio lleva organization_id NOT NULL, sin excepcion. "
						+ "Las tablas de arriba no lo cumplen y no estan en la lista de excepciones "
						+ "declaradas.%n%n"
						+ "Si alguna es legitimamente global, NO la agregues a EXCEPCIONES sin antes "
						+ "demostrar las TRES condiciones de ADR-0023, seccion 'El criterio, en un solo "
						+ "lugar':%n"
						+ "  1. el hecho que guarda no pertenece a ningun tenant: preguntar de que "
						+ "organizacion es la fila NO TIENE RESPUESTA; 'de todas' no cuenta;%n"
						+ "  2. dos tenants no pueden discrepar legitimamente sobre su contenido, y podes "
						+ "NOMBRAR la tabla hermana con organization_id NOT NULL donde vive la decision "
						+ "del centro;%n"
						+ "  3. el aislamiento esta resuelto por otro mecanismo nombrado y verificable.%n"
						+ "Y agregar la fila a la tabla de ADR-0023, que es donde vive la lista. Sumar "
						+ "una tabla a este array sin eso es como la regla muere en silencio.")
				.isEmpty();
	}

	// =================================================================================
	// La lista de excepciones tampoco es un salvoconducto: se verifica su forma
	// =================================================================================

	@Test
	@DisplayName("cada excepcion declarada tiene en el esquema exactamente la forma que ADR-0023 dice")
	void cada_excepcion_declarada_tiene_la_forma_que_adr_0023_dice() {
		// Sin esto la lista seria un perdon en blanco: una tabla listada como SIN_COLUMNA podria
		// ganar un organization_id nullable en una migracion futura —o una NULLABLE perderlo— y el
		// test de arriba nunca se enteraria, porque solo mira las que NO estan listadas.
		List<String> desviadas = new ArrayList<>();

		for (Map.Entry<String, Excepcion> entrada : EXCEPCIONES.entrySet()) {
			String tabla = entrada.getKey();
			Forma esperada = entrada.getValue().forma();
			String nullable = nullabilidadDeOrganizationId(tabla);

			Forma real;
			if (nullable == null) {
				real = Forma.SIN_COLUMNA;
			}
			else if ("YES".equals(nullable)) {
				real = Forma.NULLABLE;
			}
			else {
				desviadas.add(tabla + ": declarada " + esperada + " pero el esquema la tiene con "
						+ "organization_id NOT NULL. Si dejo de ser una excepcion, sacala de esta lista "
						+ "y de la tabla de ADR-0023");
				continue;
			}

			if (real != esperada) {
				desviadas.add(tabla + ": declarada " + esperada + " y el esquema la tiene " + real
						+ ". Motivo declarado: " + entrada.getValue().motivo());
			}
		}

		assertThat(desviadas)
				.as("una excepcion que cambio de forma dejo de ser la que ADR-0023 justifico")
				.isEmpty();
	}

	@Test
	@DisplayName("la lista de excepciones no tiene entradas muertas")
	void la_lista_de_excepciones_no_tiene_entradas_muertas() {
		// Una tabla renombrada o eliminada deja su entrada como un agujero abierto con nombre viejo:
		// no protege nada y sigue pareciendo una excepcion vigente.
		assertThat(tablasDelEsquema())
				.as("toda entrada de EXCEPCIONES tiene que corresponder a una tabla que existe")
				.containsAll(EXCEPCIONES.keySet());
	}

	@Test
	@DisplayName("el esquema aplicado tiene las tablas de las migraciones, no una muestra")
	void el_esquema_aplicado_tiene_las_tablas_de_las_migraciones() {
		// El contrapeso de los tres tests de arriba: todos recorren la lista de tablas, y si esa
		// lista viniera vacia —esquema equivocado, base sin migrar, DATABASE() apuntando a otro
		// lado— los tres pasarian sin verificar absolutamente nada.
		assertThat(tablasDelEsquema())
				.as("el esquema tiene que estar migrado a V24: si esta vacio o incompleto, los otros "
						+ "tests de esta clase pasan sin mirar nada")
				.hasSizeGreaterThanOrEqualTo(30)
				.contains("organization", "membership", "consultorio", "espacio", "feriado",
						"servicio", "oferta_servicio_consultorio");
	}

	// =================================================================================
	// Helpers
	// =================================================================================

	private List<String> tablasDelEsquema() {
		return jdbc().queryForList("""
				SELECT table_name FROM information_schema.tables
				 WHERE table_schema = DATABASE()
				   AND table_type = 'BASE TABLE'
				   AND table_name <> ?
				 ORDER BY table_name
				""", String.class, FLYWAY);
	}

	/** {@code "NO"}, {@code "YES"}, o {@code null} si la columna no existe. */
	private String nullabilidadDeOrganizationId(String tabla) {
		List<String> filas = jdbc().queryForList("""
				SELECT is_nullable FROM information_schema.columns
				 WHERE table_schema = DATABASE()
				   AND table_name = ?
				   AND column_name = 'organization_id'
				""", String.class, tabla);
		return filas.isEmpty() ? null : filas.get(0);
	}
}
