package com.akine.clinical;

import com.akine.TestcontainersConfiguration;
import com.akine.clinical.application.AdjuntoClinicoAltaCommand;
import com.akine.clinical.application.AdjuntoClinicoService;
import com.akine.clinical.application.AdjuntoClinicoService.AdjuntoClinicoAlta;
import com.akine.clinical.application.AdjuntoClinicoView;
import com.akine.clinical.application.ContenidoDeAdjuntoClinico;
import com.akine.clinical.application.OperatingActor;
import com.akine.clinical.domain.CategoriaAdjuntoClinico;
import com.akine.clinical.domain.exception.AdjuntoClinicoNoDisponibleException;
import com.akine.clinical.domain.exception.AdjuntoClinicoNotAccessibleException;
import com.akine.clinical.domain.exception.HistoriaClinicaNotAccessibleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El adjunto clinico contra MySQL real: idempotencia de la subida, baja logica y binario perdido.
 *
 * <p><b>ESCRITO EL 19/09/2026 Y NUNCA EJECUTADO: Docker no estaba disponible.</b> El motor de
 * contenedores de esta maquina no arranca sin elevacion, asi que esta clase compila y no corrio.
 * Nada de lo que afirma esta verificado todavia.
 *
 * <h2>Que escenario prueba y por que importa</h2>
 *
 * <p><b>Subir es la operacion mas expuesta a reintentos que tiene el producto.</b> Un profesional
 * con conexion mala reenvia el mismo estudio; el navegador reintenta un POST que si habia
 * llegado. La etapa decide que eso devuelve <b>el adjunto que ya existe, con 200</b> —ni 201 ni
 * 409— y que no deja dos filas que despues alguien desempata a ojo dentro de una historia
 * clinica. Quien hace cumplir ese invariante es el unique
 * {@code uk_adjunto_clinico_contenido_vigente}
 * {@code (organization_id, historia_clinica_id, checksum_sha256, deleted_key)}, <b>no</b> el
 * pre-chequeo por checksum: el pre-chequeo solo ahorra escribir el binario en el camino feliz.
 *
 * <p>Los unitarios del servicio no pueden probarlo. Con dobles, el repositorio devuelve lo que se
 * le dijo que devuelva: se puede verificar que el servicio <b>consulta</b> por checksum antes de
 * insertar, pero no que una segunda fila con el mismo checksum sea rechazada por el motor, y menos
 * que dos subidas simultaneas —donde el pre-chequeo de las dos da vacio— terminen en una sola
 * fila. Eso solo lo contesta InnoDB.
 *
 * <h2>Las otras tres propiedades, y el caso que cada una evita</h2>
 *
 * <ol>
 *   <li><b>La baja logica no borra el binario</b> (challenge seccion 5). Si lo borrara, dar de
 *       baja por error un estudio clinico seria irreversible, y el historico dejaria de
 *       resolver.</li>
 *   <li><b>Un adjunto dado de baja se puede volver a subir.</b> Es lo que hace el centinela
 *       {@code '1970-01-01'} de {@code deleted_key}: con un unique sobre {@code deleted_at} a
 *       secas, todos los vigentes tendrian {@code NULL}, que en MySQL no colisiona consigo mismo
 *       — el unique protegeria el historico y desprotegeria lo vigente, o sea exactamente al
 *       reves.</li>
 *   <li><b>Un binario que el almacenamiento perdio responde 409 y no 404</b>, y deja la fila
 *       marcada {@code NO_DISPONIBLE}. Un 404 diria "ese estudio no existe" sobre una historia
 *       clinica donde el listado lo sigue mostrando, y el problema solo se veria en el momento de
 *       fallar.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class AdjuntoClinicoIT {

	private static final String ZONA = "America/Argentina/Cordoba";
	private static final String JUSTIFICACION = "Prueba de integracion sintetica";

	@Autowired private AdjuntoClinicoService servicio;
	@Autowired private JdbcTemplate jdbc;

	// =================================================================================
	// Idempotencia de la subida
	// =================================================================================

	@Test
	@DisplayName("subir dos veces el mismo contenido devuelve el adjunto que ya existe, y una sola fila")
	void la_subida_es_idempotente() {
		Fixture fixture = crearFixture();
		byte[] contenido = pdf("informe de resonancia");

		AdjuntoClinicoAlta primera = subir(fixture, contenido, "rmn.pdf");
		AdjuntoClinicoAlta segunda = subir(fixture, contenido, "rmn.pdf");

		assertThat(primera.creado())
				.as("la primera crea")
				.isTrue();
		assertThat(segunda.creado())
				.as("la segunda NO crea: es el mismo pedido, y la capa REST lo traduce a 200")
				.isFalse();
		assertThat(segunda.adjunto().id())
				.as("y devuelve el mismo adjunto, no uno nuevo")
				.isEqualTo(primera.adjunto().id());
		assertThat(contarAdjuntos(fixture))
				.as("una sola fila en la historia clinica")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("el mismo nombre de archivo con contenido distinto son dos adjuntos")
	void el_nombre_no_identifica_el_contenido() {
		// La idempotencia es por CHECKSUM y no por nombre, y es deliberado: dos estudios distintos
		// llamados los dos "estudio.pdf" es el caso normal de un consultorio. Si el nombre
		// participara, el segundo se perderia en silencio dentro de una historia clinica.
		Fixture fixture = crearFixture();

		subir(fixture, pdf("resonancia de rodilla"), "estudio.pdf");
		subir(fixture, pdf("laboratorio completo"), "estudio.pdf");

		assertThat(contarAdjuntos(fixture)).isEqualTo(2);
	}

	@Test
	@DisplayName("dos subidas SIMULTANEAS del mismo contenido dejan una sola fila")
	void la_subida_concurrente_se_resuelve_como_idempotente() {
		// El escenario que el pre-chequeo no cubre: las dos consultan por checksum antes de que
		// ninguna haya insertado, las dos ven vacio y las dos intentan insertar. Quien decide es
		// el unique, y el servicio tiene que convertir ese choque en la respuesta idempotente en
		// vez de dejarlo salir como un 500.
		Fixture fixture = crearFixture();
		byte[] contenido = pdf("estudio que se reintenta");

		Callable<AdjuntoClinicoAlta> una = () -> subir(fixture, contenido, "reintento.pdf");
		Callable<AdjuntoClinicoAlta> otra = () -> subir(fixture, contenido, "reintento.pdf");

		List<Desenlace<AdjuntoClinicoAlta>> desenlaces = enParalelo(List.of(una, otra));

		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("ninguna falla: un reintento no es un error. Desenlaces: %s", desenlaces)
				.isEmpty();
		assertThat(contarAdjuntos(fixture))
				.as("y queda UNA fila, no dos. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
	}

	// =================================================================================
	// Baja logica
	// =================================================================================

	@Test
	@DisplayName("la baja logica no borra el binario: el adjunto dado de baja se sigue descargando")
	void la_baja_no_borra_el_binario() {
		Fixture fixture = crearFixture();
		byte[] contenido = pdf("consentimiento firmado");
		AdjuntoClinicoAlta alta = subir(fixture, contenido, "consentimiento.pdf");

		AdjuntoClinicoView dadoDeBaja = servicio.darDeBaja(fixture.actor(),
				fixture.historiaClinicaId(), alta.adjunto().id(), "Cargado en la historia que no era",
				JUSTIFICACION);

		assertThat(dadoDeBaja.estadoCicloDeVida()).isEqualTo("INACTIVO");

		// Una baja logica dice "esto ya no corresponde para operar", no "esto nunca existio".
		// Negar la descarga convertiria la baja en un borrado con otro nombre, que es lo que la
		// regla maestra 10 prohibe.
		ContenidoDeAdjuntoClinico bajado = servicio.contenido(fixture.actor(),
				fixture.historiaClinicaId(), alta.adjunto().id(), JUSTIFICACION);

		assertThat(bajado.contenido()).isEqualTo(contenido);
		assertThat(bajado.contentType())
				.as("y el tipo es el DETECTADO por los bytes, no el que declaro el cliente")
				.isEqualTo("application/pdf");
	}

	@Test
	@DisplayName("un adjunto dado de baja libera el lugar: el mismo contenido se puede volver a subir")
	void una_baja_libera_el_unique() {
		// Es lo que hace `deleted_key` con su centinela. Sin el, el unique tendria que ser sobre
		// `deleted_at`, donde varios NULL no colisionan en MySQL: protegeria el historico y
		// desprotegeria lo vigente. El caso real es trivial y ocurre: alguien da de baja un
		// estudio por error y lo vuelve a subir.
		Fixture fixture = crearFixture();
		byte[] contenido = pdf("estudio que se da de baja y vuelve");
		AdjuntoClinicoAlta primera = subir(fixture, contenido, "vuelve.pdf");

		servicio.darDeBaja(fixture.actor(), fixture.historiaClinicaId(),
				primera.adjunto().id(), "Se dio de baja por error", JUSTIFICACION);

		AdjuntoClinicoAlta segunda = subir(fixture, contenido, "vuelve.pdf");

		assertThat(segunda.creado())
				.as("el contenido vuelve a entrar: el unique solo cubre lo VIGENTE")
				.isTrue();
		assertThat(segunda.adjunto().id()).isNotEqualTo(primera.adjunto().id());
		assertThat(contarAdjuntos(fixture))
				.as("y las dos filas conviven: la de baja no se borro")
				.isEqualTo(2);
	}

	@Test
	@DisplayName("repetir la baja no es conflicto y conserva el motivo original")
	void la_baja_es_idempotente_y_no_pisa_el_motivo() {
		// Pisar el motivo con el del segundo pedido perderia el primero, que es el que explica por
		// que ese documento salio de la historia.
		Fixture fixture = crearFixture();
		AdjuntoClinicoAlta alta = subir(fixture, pdf("documento"), "doc.pdf");

		servicio.darDeBaja(fixture.actor(), fixture.historiaClinicaId(),
				alta.adjunto().id(), "Motivo original", JUSTIFICACION);
		AdjuntoClinicoView repetida = servicio.darDeBaja(fixture.actor(),
				fixture.historiaClinicaId(), alta.adjunto().id(), "Motivo nuevo", JUSTIFICACION);

		assertThat(repetida.deactivationReason()).isEqualTo("Motivo original");
	}

	// =================================================================================
	// El binario perdido
	// =================================================================================

	@Test
	@DisplayName("un binario que el almacenamiento no tiene da 409 y deja la fila NO_DISPONIBLE")
	void el_binario_faltante_es_conflicto_y_no_un_404() {
		Fixture fixture = crearFixture();
		AdjuntoClinicoAlta alta = subir(fixture, pdf("estudio que se va a perder"), "perdido.pdf");

		// Se apunta la fila a una clave que el almacenamiento no tiene, en vez de borrar el
		// archivo del disco: el efecto sobre el servicio es el mismo —`storage.leer` devuelve
		// vacio— y el test no depende de donde monto su raiz esta maquina.
		jdbc.update("UPDATE adjunto_clinico SET storage_key = ? WHERE id = ?",
				UUID.randomUUID().toString().replace("-", ""), alta.adjunto().id());

		assertThatThrownBy(() -> servicio.contenido(fixture.actor(), fixture.historiaClinicaId(),
				alta.adjunto().id(), JUSTIFICACION))
				.as("409 y no 404: la metadata existe y quien pregunta la esta viendo en la lista")
				.isInstanceOf(AdjuntoClinicoNoDisponibleException.class);

		// ATENCION a quien corra esto por primera vez: esta afirmacion es la que el javadoc de
		// `AdjuntoClinicoService` promete, y hay una razon concreta para sospechar que NO se
		// cumple. `contenidoDe` marca la fila y acto seguido lanza una RuntimeException dentro de
		// un metodo `@Transactional` sin `noRollbackFor`: Spring revierte la transaccion y se
		// lleva la marca. Si este assert falla, el defecto es de produccion —AdjuntoClinicoService
		// lineas 471-473— y NO del test. Esta escrito contra la conducta especificada a proposito.
		assertThat(jdbc.queryForObject(
				"SELECT estado FROM adjunto_clinico WHERE id = ?", String.class,
				alta.adjunto().id()))
				.as("y la fila queda marcada, para que el problema se vea en el listado y no solo "
						+ "en el momento de fallar")
				.isEqualTo("NO_DISPONIBLE");

		// Segunda descarga: ya no hace falta consultar el almacenamiento, la fila lo dice.
		assertThatThrownBy(() -> servicio.contenido(fixture.actor(), fixture.historiaClinicaId(),
				alta.adjunto().id(), JUSTIFICACION))
				.isInstanceOf(AdjuntoClinicoNoDisponibleException.class);
	}

	// =================================================================================
	// Aislamiento de tenant y de historia — 404, nunca 403
	// =================================================================================

	@Test
	@DisplayName("un actor del tenant B no alcanza el adjunto del tenant A: 404, nunca 403")
	void un_tenant_no_alcanza_el_adjunto_del_otro() {
		Fixture tenantA = crearFixture();
		Fixture tenantB = crearFixture();
		AdjuntoClinicoAlta deA = subir(tenantA, pdf("estudio del tenant A"), "a.pdf");

		assertThatThrownBy(() -> servicio.contenido(tenantB.actor(),
				tenantA.historiaClinicaId(), deA.adjunto().id(), JUSTIFICACION))
				.as("la historia de A no existe para B: se corta antes de mirar el adjunto")
				.isInstanceOf(HistoriaClinicaNotAccessibleException.class);
		assertThatThrownBy(() -> servicio.listar(tenantB.actor(), tenantA.historiaClinicaId(),
				null, null, false, 0, 20, JUSTIFICACION))
				.isInstanceOf(HistoriaClinicaNotAccessibleException.class);

		// Y con la historia PROPIA de B mas el id ajeno: el adjunto tampoco aparece.
		assertThatThrownBy(() -> servicio.contenido(tenantB.actor(),
				tenantB.historiaClinicaId(), deA.adjunto().id(), JUSTIFICACION))
				.isInstanceOf(AdjuntoClinicoNotAccessibleException.class);
	}

	@Test
	@DisplayName("el mismo contenido en dos historias distintas son dos adjuntos, no uno")
	void el_unique_es_por_historia() {
		// El unique lleva `historia_clinica_id`, y tiene que llevarlo: el mismo consentimiento
		// modelo firmado por dos pacientes tiene el mismo checksum, y compartir la fila pondria el
		// documento de uno en la historia del otro.
		Fixture unPaciente = crearFixture();
		long otraHistoria = abrirOtraHistoria(unPaciente);
		byte[] contenido = pdf("consentimiento modelo");

		subir(unPaciente, contenido, "consentimiento.pdf");
		AdjuntoClinicoAlta enLaOtra = servicio.subir(unPaciente.actor(), otraHistoria,
				new AdjuntoClinicoAltaCommand(CategoriaAdjuntoClinico.CONSENTIMIENTO_CLINICO, null,
						null, "consentimiento.pdf", "application/pdf", contenido),
				JUSTIFICACION);

		assertThat(enLaOtra.creado()).isTrue();
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM adjunto_clinico WHERE organization_id = ?
				""", Integer.class, unPaciente.organizationId()))
				.isEqualTo(2);
	}

	@Test
	@DisplayName("un archivo que no esta en la lista blanca se rechaza por sus BYTES")
	void el_tipo_se_decide_por_los_bytes() {
		// RN-M25-001. El reflejo —mirar la extension o el Content-Type declarado— no valida nada:
		// los dos los elige quien sube. Un .pdf que en realidad es HTML con un <script>, servido
		// despues desde el mismo origen, es un XSS almacenado.
		Fixture fixture = crearFixture();
		byte[] disfrazado = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> servicio.subir(fixture.actor(), fixture.historiaClinicaId(),
				new AdjuntoClinicoAltaCommand(CategoriaAdjuntoClinico.ESTUDIO, null, null,
						"inofensivo.pdf", "application/pdf", disfrazado),
				JUSTIFICACION))
				.isInstanceOf(com.akine.clinical.domain.exception.ArchivoClinicoNoAceptadoException.class);
		assertThat(contarAdjuntos(fixture))
				.as("y no quedo ninguna fila")
				.isZero();
	}

	@Test
	@DisplayName("un adjunto colgado de una entrada de OTRA historia se rechaza")
	void la_entrada_tiene_que_ser_de_esta_historia() {
		// La base no lo puede exigir: un CHECK no consulta otra tabla. Sin este control, un pedido
		// sobre la historia A dejaria un documento colgando de la evolucion de otro paciente del
		// mismo centro.
		Fixture fixture = crearFixture();
		long otraHistoria = abrirOtraHistoria(fixture);
		long entradaDeLaOtra = insertarEntrada(fixture.organizationId(), otraHistoria);

		assertThatThrownBy(() -> servicio.subir(fixture.actor(), fixture.historiaClinicaId(),
				new AdjuntoClinicoAltaCommand(CategoriaAdjuntoClinico.INFORME, entradaDeLaOtra,
						null, "informe.pdf", "application/pdf", pdf("informe cruzado")),
				JUSTIFICACION))
				.isInstanceOf(AdjuntoClinicoNotAccessibleException.class);
	}

	@Test
	@DisplayName("un adjunto colgado de una entrada de ESTA historia entra")
	void la_entrada_propia_se_admite() {
		// Control negativo del anterior: sin el, un rechazo demasiado amplio pasaria inadvertido.
		Fixture fixture = crearFixture();
		long entradaPropia =
				insertarEntrada(fixture.organizationId(), fixture.historiaClinicaId());

		assertThatCode(() -> servicio.subir(fixture.actor(), fixture.historiaClinicaId(),
				new AdjuntoClinicoAltaCommand(CategoriaAdjuntoClinico.INFORME, entradaPropia,
						null, "informe.pdf", "application/pdf", pdf("informe propio")),
				JUSTIFICACION))
				.doesNotThrowAnyException();
	}

	// =================================================================================
	// Ejecucion concurrente — calcado de AutorizacionConcurrenteIT
	// =================================================================================

	private static <T> List<Desenlace<T>> enParalelo(List<Callable<T>> tareas) {
		CyclicBarrier salida = new CyclicBarrier(tareas.size());
		try (ExecutorService pool = Executors.newFixedThreadPool(tareas.size())) {
			List<Future<Desenlace<T>>> futuros = new ArrayList<>();
			for (Callable<T> tarea : tareas) {
				futuros.add(pool.submit(() -> {
					salida.await(10, TimeUnit.SECONDS);
					try {
						return new Desenlace<>(tarea.call(), null);
					} catch (Exception error) {
						return new Desenlace<T>(null, error);
					}
				}));
			}
			List<Desenlace<T>> desenlaces = new ArrayList<>();
			for (Future<Desenlace<T>> futuro : futuros) {
				desenlaces.add(futuro.get(30, TimeUnit.SECONDS));
			}
			return desenlaces;
		} catch (Exception error) {
			throw new IllegalStateException("La ejecucion concurrente no pudo completarse", error);
		}
	}

	private record Desenlace<T>(T valor, Exception error) {

		boolean fallo() {
			return error != null;
		}

		@Override
		public String toString() {
			return fallo()
					? "FALLO(" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")"
					: "OK(" + valor + ")";
		}
	}

	// =================================================================================
	// Operaciones y consultas
	// =================================================================================

	private AdjuntoClinicoAlta subir(Fixture fixture, byte[] contenido, String nombre) {
		return servicio.subir(fixture.actor(), fixture.historiaClinicaId(),
				new AdjuntoClinicoAltaCommand(CategoriaAdjuntoClinico.ESTUDIO, null, null,
						nombre, "application/pdf", contenido),
				JUSTIFICACION);
	}

	/** Un PDF sintetico: lo que la lista blanca mira es la firma {@code %PDF-} del byte cero. */
	private static byte[] pdf(String marca) {
		return ("%PDF-1.7\n% " + marca + "\n").getBytes(StandardCharsets.UTF_8);
	}

	private int contarAdjuntos(Fixture fixture) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM adjunto_clinico WHERE historia_clinica_id = ?
				""", Integer.class, fixture.historiaClinicaId());
	}

	// =================================================================================
	// Fixture — todo sintetico (AGENT.md seccion 10)
	// =================================================================================

	private record Fixture(
			long organizationId, long consultorioId, long cuentaId, long personaId,
			long historiaClinicaId, OperatingActor actor) {
	}

	private Fixture crearFixture() {
		String sufijo = UUID.randomUUID().toString().substring(0, 12);

		long organizationId = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version,
				                          created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{"Centro Sintetico " + sufijo, "adjcli-it-" + sufijo, ZONA});

		long consultorioId = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, "Sede Sintetica " + sufijo, ZONA});

		String email = "adjcli-it-" + sufijo + "@ejemplo.test";
		long cuentaId = insertar("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{email, email});

		insertar("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, 'PROFESIONAL', 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, consultorioId, cuentaId});

		long personaId = insertarPersona(organizationId, "Paciente" + sufijo);
		long historiaClinicaId = insertarHistoria(organizationId, personaId, cuentaId);

		return new Fixture(organizationId, consultorioId, cuentaId, personaId, historiaClinicaId,
				new OperatingActor(cuentaId, false, organizationId, consultorioId));
	}

	/** Una segunda historia del mismo tenant: otro paciente, mismo centro. */
	private long abrirOtraHistoria(Fixture fixture) {
		long otraPersona = insertarPersona(
				fixture.organizationId(), "Otro" + UUID.randomUUID().toString().substring(0, 8));
		return insertarHistoria(fixture.organizationId(), otraPersona, fixture.cuentaId());
	}

	private long insertarPersona(long organizationId, String apellido) {
		return insertar("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave,
				                     nombre_clave, active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, apellido, apellido.toUpperCase()});
	}

	private long insertarHistoria(long organizationId, long personaId, long cuentaId) {
		insertar("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, personaId, cuentaId});

		return insertar("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, personaId, cuentaId});
	}

	/** Una entrada clinica minima, insertada directo: lo que este test mide es el adjunto. */
	private long insertarEntrada(long organizationId, long historiaClinicaId) {
		return insertar("""
				INSERT INTO entrada_clinica (organization_id, historia_clinica_id, tipo, ocurrio_en,
				                             origen, ultimo_numero_version, registrada_en,
				                             registrada_por, active, version, created_at, updated_at)
				VALUES (?, ?, 'EVOLUCION', UTC_TIMESTAMP(6), 'MANUAL', 1, UTC_TIMESTAMP(6), 1, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, historiaClinicaId});
	}

	private long insertar(String sql, Object[] args) {
		jdbc.update(sql, args);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
