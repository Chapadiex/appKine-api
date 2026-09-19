package com.akine.clinical;

import com.akine.TestcontainersConfiguration;
import com.akine.clinical.application.AdjuntoClinicoAltaCommand;
import com.akine.clinical.application.AdjuntoClinicoService;
import com.akine.clinical.application.AdjuntoClinicoService.AdjuntoClinicoAlta;
import com.akine.clinical.application.EntradaClinicaService;
import com.akine.clinical.application.EntradaClinicaView;
import com.akine.clinical.application.OperatingActor;
import com.akine.clinical.application.TimelinePagina;
import com.akine.clinical.application.TimelineService;
import com.akine.clinical.domain.CategoriaAdjuntoClinico;
import com.akine.clinical.domain.TipoEntradaClinica;
import com.akine.clinical.domain.exception.CursorInvalidoException;
import com.akine.clinical.domain.exception.HistoriaClinicaNotAccessibleException;
import com.akine.clinical.spi.EventoClinico;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El timeline clinico contra MySQL real: las cinco consultas nativas y el orden total.
 *
 * <p><b>ESCRITO EL 19/09/2026 Y NUNCA EJECUTADO: Docker no estaba disponible.</b> El motor de
 * contenedores de esta maquina no arranca sin elevacion, asi que esta clase compila y no corrio.
 * Nada de lo que afirma esta verificado todavia.
 *
 * <h2>Por que este test es el que mas falta hace de los cuatro</h2>
 *
 * <p>Las cuatro fuentes del timeline leen con {@code SELECT *} <b>nativo</b> y {@code LIMIT
 * :limite}. Una consulta nativa <b>no la valida nada</b> al arrancar la aplicacion: Hibernate
 * verifica el mapeo de las consultas JPQL y de las derivadas contra el esquema, pero una nativa es
 * texto que recien se compila cuando alguien la ejecuta. Un nombre de columna mal escrito, un
 * {@code Instant} que no se enlaza como {@code DATETIME(6)}, un {@code LIMIT} parametrizado que el
 * driver no admite: hoy <b>ningun test agarra nada de eso</b>, porque los unitarios del servicio
 * mockean los puertos y los del repositorio no existen.
 *
 * <p>La quinta nativa es {@code AdjuntoClinicoRepository.listar}, con su {@code LIMIT ... OFFSET}
 * y sus tres filtros opcionales resueltos con {@code :param IS NULL OR ...} — el patron que mas
 * facil se rompe cuando el parametro es un {@code enum} traducido a {@code String}.
 *
 * <h2>Las propiedades que se verifican, y el caso que cada una evita</h2>
 *
 * <ol>
 *   <li><b>Cada contribuyente aporta lo suyo.</b> Una fuente que devuelve vacio por un error de
 *       mapeo es invisible: el timeline responde 200 con menos hechos, y nadie nota que falta la
 *       mitad de la historia de un paciente.</li>
 *   <li><b>El orden total {@code (ocurrioEn DESC, origen ASC, referencia DESC)} se respeta al
 *       mezclar.</b> Cuatro fuentes producen empates con facilidad, y un empate sin desempate se
 *       ordena como quiera la JVM: distinto entre una pagina y la siguiente, o sea el cursor
 *       saltea.</li>
 *   <li><b>La paginacion por cursor no repite ni saltea.</b> Es lo unico que hace usable la ficha
 *       de un paciente cronico, y es donde el diseño declara su unico borde abierto.</li>
 *   <li><b>Una entrada o un adjunto dados de baja SALEN del timeline</b> y siguen siendo
 *       consultables por su id: es lo que distingue "no lo muestres" de "no existio".</li>
 *   <li><b>Solo se indexan sesiones CERRADAS.</b> Una sesion en borrador es un texto que todavia
 *       esta cambiando; indexarla pondria en la linea de tiempo una fila que muta mientras
 *       alguien la mira. El Turno no aporta nada, y eso es DP-05.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class TimelineIT {

	private static final String ZONA = "America/Argentina/Cordoba";
	private static final String JUSTIFICACION = "Prueba de integracion sintetica";

	/**
	 * Ancla fija y en el pasado.
	 *
	 * <p>La primera pagina usa {@code Instant.now()} como tope superior, asi que todo hecho tiene
	 * que haber ocurrido antes. Y fija —no {@code now().minus(...)} recalculado— para que los
	 * cuatro hechos de un escenario compartan exactamente el mismo instante cuando eso es lo que
	 * se esta probando.
	 */
	private static final Instant ANCLA =
			Instant.parse("2026-03-10T12:00:00Z").truncatedTo(ChronoUnit.MICROS);

	@Autowired private TimelineService timeline;
	@Autowired private EntradaClinicaService entradas;
	@Autowired private AdjuntoClinicoService adjuntos;
	@Autowired private JdbcTemplate jdbc;

	// =================================================================================
	// (1) Cada fuente aporta lo suyo
	// =================================================================================

	@Test
	@DisplayName("las cuatro fuentes aportan: entrada, adjunto, antecedente y sesion cerrada")
	void las_cuatro_fuentes_contribuyen() {
		Fixture fixture = crearFixture();

		registrarEntrada(fixture, "Evolucion", ANCLA.minus(4, ChronoUnit.HOURS));
		subirAdjunto(fixture, "estudio", ANCLA.minus(3, ChronoUnit.HOURS));
		insertarAntecedente(fixture, ANCLA.minus(2, ChronoUnit.HOURS));
		insertarSesion(fixture, "CERRADA", 1, ANCLA.minus(1, ChronoUnit.HOURS));

		TimelinePagina pagina = ver(fixture, null, 50);

		assertThat(pagina.eventos())
				.as("una fuente que devuelve vacio por un error de mapeo nativo es invisible: "
						+ "el endpoint responde 200 con menos hechos y nadie lo nota")
				.extracting(EventoClinico::origen)
				.containsExactly("SESION", "ANTECEDENTE_CLINICO", "ADJUNTO_CLINICO",
						"ENTRADA_CLINICA");
		assertThat(pagina.proximoCursor())
				.as("cuatro eventos en una pagina de cincuenta: no hay siguiente")
				.isNull();
		assertThat(pagina.eventos())
				.as("el timeline es un INDICE: ningun evento lleva texto clinico, solo la etiqueta "
						+ "del tipo de hecho y la referencia para ir a buscarlo")
				.allSatisfy(evento -> {
					assertThat(evento.titulo()).doesNotContain("Evolucion sintetica");
					assertThat(evento.referencia()).isPositive();
				});
	}

	// =================================================================================
	// (2) El orden total
	// =================================================================================

	@Test
	@DisplayName("cuatro hechos en el MISMO instante se desempatan por origen ASC")
	void el_empate_se_desempata_por_origen() {
		// Es el caso que el orden total existe para resolver: una sesion que cierra y el adjunto
		// que se sube en el mismo microsegundo. Sin desempate, el orden lo elige la JVM y cambia
		// entre una pagina y la siguiente — que es como el cursor saltea un evento.
		Fixture fixture = crearFixture();
		Instant mismoInstante = ANCLA.minus(1, ChronoUnit.DAYS);

		registrarEntrada(fixture, "Evolucion", mismoInstante);
		subirAdjunto(fixture, "estudio", mismoInstante);
		insertarAntecedente(fixture, mismoInstante);
		insertarSesion(fixture, "CERRADA", 1, mismoInstante);

		assertThat(ver(fixture, null, 50).eventos())
				.extracting(EventoClinico::origen)
				.as("origen ASC alfabetico: ADJUNTO < ANTECEDENTE < ENTRADA < SESION")
				.containsExactly("ADJUNTO_CLINICO", "ANTECEDENTE_CLINICO", "ENTRADA_CLINICA",
						"SESION");
	}

	@Test
	@DisplayName("dos entradas del mismo instante se desempatan por referencia DESC")
	void el_empate_dentro_de_una_fuente_lo_desempata_la_referencia() {
		// El desempate por `id DESC` de las cinco nativas. No es cosmetico: sin el, dos filas del
		// mismo microsegundo se ordenan distinto entre dos consultas y la paginacion se rompe.
		Fixture fixture = crearFixture();
		Instant mismoInstante = ANCLA.minus(2, ChronoUnit.DAYS);

		EntradaClinicaView primera = registrarEntrada(fixture, "Primera", mismoInstante);
		EntradaClinicaView segunda = registrarEntrada(fixture, "Segunda", mismoInstante);

		assertThat(ver(fixture, null, 50).eventos())
				.extracting(EventoClinico::referencia)
				.as("la fila mas nueva primero")
				.containsExactly(segunda.id(), primera.id());
	}

	// =================================================================================
	// (3) La paginacion por cursor
	// =================================================================================

	@Test
	@DisplayName("la paginacion por cursor recorre las seis entradas sin repetir ni saltear")
	void el_cursor_no_repite_ni_saltea() {
		Fixture fixture = crearFixture();
		List<Long> esperadas = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			// Instantes distintos y decrecientes: el caso general. El borde de los empates lo
			// cubre el escenario de arriba, y el borde declarado como NO resuelto —mas de
			// `limite` eventos de UNA fuente en el instante exacto del cursor— no se prueba
			// porque no esta cerrado (diseño seccion 2.1).
			esperadas.add(registrarEntrada(
					fixture, "Hecho " + i, ANCLA.minus(i + 1, ChronoUnit.DAYS)).id());
		}

		List<Long> recorridas = new ArrayList<>();
		String cursor = null;
		int paginas = 0;
		do {
			TimelinePagina pagina = ver(fixture, cursor, 2);
			pagina.eventos().forEach(evento -> recorridas.add(evento.referencia()));
			cursor = pagina.proximoCursor();
			paginas++;
			assertThat(paginas).as("el recorrido no puede ser infinito").isLessThan(10);
		} while (cursor != null);

		assertThat(recorridas)
				.as("las seis, en orden, una sola vez cada una")
				.containsExactlyElementsOf(esperadas);
		assertThat(paginas)
				.as("tres paginas de dos, y la tercera no devuelve cursor porque no sobro nada")
				.isEqualTo(3);
	}

	@Test
	@DisplayName("un cursor que no decodifica es 400, nunca 'primera pagina'")
	void un_cursor_roto_se_rechaza() {
		// Degradar a la primera pagina convertiria un cliente roto en un cliente que recorre la
		// historia en loop sin enterarse.
		Fixture fixture = crearFixture();
		registrarEntrada(fixture, "Evolucion", ANCLA.minus(1, ChronoUnit.DAYS));

		assertThatThrownBy(() -> ver(fixture, "esto-no-es-un-cursor-valido!!", 10))
				.isInstanceOf(CursorInvalidoException.class);
	}

	// =================================================================================
	// (4) La baja logica saca del indice
	// =================================================================================

	@Test
	@DisplayName("una entrada y un adjunto dados de baja salen del timeline")
	void la_baja_logica_saca_del_timeline() {
		Fixture fixture = crearFixture();
		EntradaClinicaView entrada =
				registrarEntrada(fixture, "Evolucion", ANCLA.minus(2, ChronoUnit.HOURS));
		AdjuntoClinicoAlta adjunto =
				subirAdjunto(fixture, "estudio", ANCLA.minus(1, ChronoUnit.HOURS));

		assertThat(ver(fixture, null, 50).eventos()).hasSize(2);

		entradas.darDeBaja(fixture.actor(), entrada.id(), "Cargada en la historia que no era",
				entrada.version(), JUSTIFICACION);
		adjuntos.darDeBaja(fixture.actor(), fixture.historiaClinicaId(),
				adjunto.adjunto().id(), "Documento equivocado", JUSTIFICACION);

		assertThat(ver(fixture, null, 50).eventos())
				.as("las dos fuentes filtran active = 1 sin parametro que lo negocie")
				.isEmpty();

		// Pero siguen siendo consultables por su id: la baja saca del indice, no borra.
		assertThat(entradas.ver(fixture.actor(), entrada.id(), JUSTIFICACION).vigente()).isFalse();
		assertThat(adjuntos.listar(fixture.actor(), fixture.historiaClinicaId(),
				null, null, true, 0, 20, JUSTIFICACION).total())
				.as("el listado con dados de baja lo sigue mostrando")
				.isEqualTo(1);
	}

	// =================================================================================
	// (5) Solo sesiones cerradas
	// =================================================================================

	@Test
	@DisplayName("solo las sesiones CERRADAS se indexan: el borrador no esta en el timeline")
	void el_borrador_no_es_un_hecho_clinico() {
		Fixture fixture = crearFixture();
		insertarSesion(fixture, "BORRADOR", null, null);
		insertarSesion(fixture, "CERRADA", 1, ANCLA.minus(1, ChronoUnit.HOURS));

		assertThat(ver(fixture, null, 50).eventos())
				.as("una sesion en curso es un texto que todavia cambia: indexarla pondria en la "
						+ "linea de tiempo una fila que muta mientras alguien la mira")
				.hasSize(1)
				.allSatisfy(evento -> assertThat(evento.tipo()).isEqualTo("SESION_CERRADA"));
	}

	// =================================================================================
	// La quinta nativa: el listado de adjuntos con sus filtros opcionales
	// =================================================================================

	@Test
	@DisplayName("el listado nativo de adjuntos filtra por categoria, por entrada y por vigencia")
	void el_listado_nativo_resuelve_sus_tres_filtros() {
		// `:param IS NULL OR columna = :param` sobre una nativa es el patron que mas facil se
		// rompe: el enum viaja como String y un NULL sin tipo en el driver puede resolver el OR
		// al reves. Ninguna de las tres ramas la valida nada al arrancar.
		Fixture fixture = crearFixture();
		subirAdjunto(fixture, "un estudio", ANCLA.minus(3, ChronoUnit.HOURS));
		AdjuntoClinicoAlta informe = adjuntos.subir(fixture.actor(), fixture.historiaClinicaId(),
				new AdjuntoClinicoAltaCommand(CategoriaAdjuntoClinico.INFORME, null, null,
						"informe.pdf", "application/pdf", pdf("un informe")),
				JUSTIFICACION);

		assertThat(adjuntos.listar(fixture.actor(), fixture.historiaClinicaId(),
				null, null, false, 0, 20, JUSTIFICACION).total())
				.as("sin filtros, los dos")
				.isEqualTo(2);
		assertThat(adjuntos.listar(fixture.actor(), fixture.historiaClinicaId(),
				CategoriaAdjuntoClinico.INFORME, null, false, 0, 20, JUSTIFICACION).contenido())
				.as("filtrado por categoria, uno")
				.extracting(vista -> vista.id())
				.containsExactly(informe.adjunto().id());

		adjuntos.darDeBaja(fixture.actor(), fixture.historiaClinicaId(),
				informe.adjunto().id(), "Ya no corresponde", JUSTIFICACION);

		assertThat(adjuntos.listar(fixture.actor(), fixture.historiaClinicaId(),
				null, null, false, 0, 20, JUSTIFICACION).total())
				.as("por defecto, solo vigentes")
				.isEqualTo(1);
		assertThat(adjuntos.listar(fixture.actor(), fixture.historiaClinicaId(),
				null, null, true, 0, 20, JUSTIFICACION).total())
				.as("y con los dados de baja, los dos")
				.isEqualTo(2);
	}

	// =================================================================================
	// Aislamiento de tenant — 404, nunca 403
	// =================================================================================

	@Test
	@DisplayName("un actor del tenant B no ve el timeline del tenant A: 404, nunca 403")
	void un_tenant_no_ve_el_timeline_del_otro() {
		Fixture tenantA = crearFixture();
		Fixture tenantB = crearFixture();
		registrarEntrada(tenantA, "Evolucion del tenant A", ANCLA.minus(1, ChronoUnit.HOURS));

		assertThatThrownBy(() -> timeline.ver(
				tenantB.actor(), tenantA.historiaClinicaId(), null, 50, null, JUSTIFICACION))
				.as("no accesible, no prohibido: un 403 confirmaria que esa historia existe")
				.isInstanceOf(HistoriaClinicaNotAccessibleException.class);

		assertThat(ver(tenantB, null, 50).eventos())
				.as("y su propio timeline sigue vacio: nada del otro tenant se filtro")
				.isEmpty();
	}

	// =================================================================================
	// Operaciones
	// =================================================================================

	/**
	 * El timeline sin filtrar por Caso.
	 *
	 * <p>El {@code null} del penultimo argumento es el filtro por Caso que AKINE-04.03 agrego. Esta
	 * clase se escribio para 04.02, cuando el Caso no existia, y sus escenarios siguen siendo los
	 * de un timeline completo: filtrar por caso es un escenario propio de 04.03 y no de este.
	 */
	private TimelinePagina ver(Fixture fixture, String cursor, Integer limite) {
		return timeline.ver(
				fixture.actor(), fixture.historiaClinicaId(), cursor, limite, null, JUSTIFICACION);
	}

	private EntradaClinicaView registrarEntrada(Fixture fixture, String cuerpo, Instant ocurrioEn) {
		return entradas.registrar(fixture.actor(), fixture.historiaClinicaId(),
				TipoEntradaClinica.EVOLUCION, cuerpo, ocurrioEn, JUSTIFICACION);
	}

	/**
	 * Un adjunto con {@code subido_en} puesto a mano.
	 *
	 * <p>El servicio data el alta con {@code Instant.now()} y no admite otro instante —es el hecho
	 * "ese dia entro un documento", no una fecha declarada—, asi que para armar un escenario de
	 * orden hay que correrlo despues por SQL.
	 */
	private AdjuntoClinicoAlta subirAdjunto(Fixture fixture, String marca, Instant subidoEn) {
		AdjuntoClinicoAlta alta = adjuntos.subir(fixture.actor(), fixture.historiaClinicaId(),
				new AdjuntoClinicoAltaCommand(CategoriaAdjuntoClinico.ESTUDIO, null, null,
						marca + ".pdf", "application/pdf", pdf(marca)),
				JUSTIFICACION);
		jdbc.update("UPDATE adjunto_clinico SET subido_en = ? WHERE id = ?",
				Timestamp.from(subidoEn), alta.adjunto().id());
		return alta;
	}

	private static byte[] pdf(String marca) {
		return ("%PDF-1.7\n% " + marca + "\n").getBytes(StandardCharsets.UTF_8);
	}

	private void insertarAntecedente(Fixture fixture, Instant registradoEn) {
		jdbc.update("""
				INSERT INTO historia_clinica_antecedente (organization_id, historia_clinica_id, tipo,
				                                          descripcion, registrado_en, registrado_por,
				                                          active, version, created_at, updated_at)
				VALUES (?, ?, 'ALERGIA', 'penicilina', ?, ?, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), fixture.historiaClinicaId(),
				Timestamp.from(registradoEn), fixture.cuentaId());
	}

	/**
	 * Una sesion insertada directo.
	 *
	 * <p>No se abre por el servicio a proposito: eso exigiria un turno, un slot y la cadena entera
	 * de M12, que ya tiene su propio test. Lo que este test mide es que el timeline la indexe.
	 * {@code ck_sesion_cierre_completo} ata numero, instante y autor del cierre, asi que los tres
	 * viajan juntos o ninguno.
	 */
	private void insertarSesion(
			Fixture fixture, String estado, Integer numeroSesion, Instant cerradaEn) {

		jdbc.update("""
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, oferta_id,
				                    profesional_membership_id, estado, numero_sesion, iniciada_en,
				                    iniciada_por_cuenta_id, asistencia, cerrada_en,
				                    cerrada_por_cuenta_id, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""",
				fixture.organizationId(), fixture.consultorioId(), fixture.historiaClinicaId(),
				fixture.ofertaId(), fixture.membershipId(), estado, numeroSesion,
				Timestamp.from(cerradaEn == null ? ANCLA.minus(5, ChronoUnit.HOURS) : cerradaEn),
				fixture.cuentaId(),
				cerradaEn == null ? null : "PRESENTE",
				cerradaEn == null ? null : Timestamp.from(cerradaEn),
				cerradaEn == null ? null : fixture.cuentaId());
	}

	// =================================================================================
	// Fixture — todo sintetico (AGENT.md seccion 10)
	// =================================================================================

	private record Fixture(
			long organizationId, long consultorioId, long cuentaId, long membershipId,
			long ofertaId, long historiaClinicaId, OperatingActor actor) {
	}

	private Fixture crearFixture() {
		String sufijo = UUID.randomUUID().toString().substring(0, 12);

		long organizationId = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version,
				                          created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{"Centro Sintetico " + sufijo, "timeline-it-" + sufijo, ZONA});

		long consultorioId = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, "Sede Sintetica " + sufijo, ZONA});

		String email = "timeline-it-" + sufijo + "@ejemplo.test";
		long cuentaId = insertar("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{email, email});

		long membershipId = insertar("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, 'PROFESIONAL', 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, consultorioId, cuentaId});

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
						"TL-" + sufijo.toUpperCase(), "Servicio Sintetico " + sufijo});

		long ofertaId = insertar("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, precio_base, moneda, admite_obra_social,
				        requiere_caso_clinico, genera_registro_clinico, requiere_profesional,
				        requiere_espacio, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'INDIVIDUAL', 60, 1, 8500.00, 'ARS', 0, 0, 0, 1, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{
						organizationId, consultorioId, servicioId, "Oferta " + sufijo});

		return new Fixture(organizationId, consultorioId, cuentaId, membershipId, ofertaId,
				historiaClinicaId,
				new OperatingActor(cuentaId, false, organizationId, consultorioId));
	}

	private long insertar(String sql, Object[] args) {
		jdbc.update(sql, args);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
