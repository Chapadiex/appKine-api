package com.akine.clinical;

import com.akine.TestcontainersConfiguration;
import com.akine.clinical.application.CasoClinicoAltaCommand;
import com.akine.clinical.application.CasoClinicoService;
import com.akine.clinical.application.CasoClinicoView;
import com.akine.clinical.application.CasoEventoView;
import com.akine.clinical.application.EntradaClinicaService;
import com.akine.clinical.application.IntegranteDelEquipo;
import com.akine.clinical.application.OperatingActor;
import com.akine.clinical.application.TimelinePagina;
import com.akine.clinical.application.TimelineService;
import com.akine.clinical.domain.RolEnCaso;
import com.akine.clinical.domain.TipoEntradaClinica;
import com.akine.clinical.domain.exception.CasoClinicoCerradoException;
import com.akine.clinical.domain.exception.CasoClinicoNotAccessibleException;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.application.SesionView;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.encounter.domain.exception.CasoNoAsignableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El ciclo de vida del Caso Clinico contra una base real: abrir, editar, cerrar, reabrir, cerrar.
 *
 * <p><b>ESCRITO EL 19/09/2026 Y NUNCA EJECUTADO: Docker no estaba disponible.</b> El motor de
 * contenedores de esta maquina no arranca sin elevacion, asi que esta clase compila y no corrio.
 * Nada de lo que afirma esta verificado todavia.
 *
 * <h2>Que escenario prueba</h2>
 *
 * <p><b>Un tratamiento que se da de alta, se reabre porque el paciente recae, y sigue.</b> Es el
 * recorrido completo de RF-M10-001..006 en un solo caso, y lo que lo hace un test de integracion y
 * no un unitario es que cada paso deja rastro en <b>cuatro tablas a la vez</b> —el caso, su
 * historial, su equipo y su numerador de sesiones— dentro de la misma transaccion. Con dobles se
 * puede verificar que se llamo a cada repositorio; no que lo escrito quedo coherente entre si
 * despues del commit.
 *
 * <h2>Las cuatro afirmaciones que solo aca se pueden hacer</h2>
 *
 * <ol>
 *   <li><b>Reabrir NO reinicia {@code caso_sesion_numerador}.</b> La sesion siguiente a una
 *       reapertura es la 9, no la 1. Es la quinta condicion del challenge —la que "no rompe hoy
 *       pero rompe en 04.04"— y renumerar seria reescribir historia clinica (ADR-0011). Un
 *       unitario no lo puede ver porque el contador vive en una fila que solo existe en la base.
 *       </li>
 *   <li><b>Un caso cerrado responde 409 y no 403</b>: quien opera tiene el permiso, y lo que no
 *       admite cambios es el estado. Un 403 lo mandaria a pedirle a su administrador un permiso
 *       que ya tiene. Y tampoco admite <b>sesiones nuevas</b>, que se verifica por el camino real
 *       —{@code SesionService#iniciar} a traves del {@code spi}— y no simulando el guardia.</li>
 *   <li><b>El profesional desvinculado sigue figurando en el equipo.</b> Lo trato, y sacarlo de la
 *       lista reescribiria historia. Lo que cambia es que su fila deja de estar vigente, y eso lo
 *       decide {@code hasta_key} en la base.</li>
 *   <li><b>El filtro por caso del timeline</b>, incluido que un caso de <b>otra historia</b>
 *       responda 404 y no una pagina vacia: una pagina vacia se lee como "este caso no tuvo nada",
 *       que es una respuesta afirmativa sobre datos ajenos.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class CasoClinicoCicloIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	/** Sin relacion asistencial no hay acceso clinico: hoy el probe siempre dice "sin evidencia". */
	private static final String JUSTIFICACION = "Prueba de integracion sintetica";

	@Autowired private CasoClinicoService casos;
	@Autowired private EntradaClinicaService entradas;
	@Autowired private TimelineService timeline;
	@Autowired private SesionService sesiones;
	@Autowired private JdbcTemplate jdbc;

	// =================================================================================
	// El recorrido completo
	// =================================================================================

	@Test
	@DisplayName("abrir, editar, cerrar, reabrir y cerrar deja los cinco eventos en orden")
	void el_ciclo_completo_queda_en_el_historial() {
		// El historial es lo unico que hace revisable la reapertura: RF-M10-006 pide reabrir, no
		// un tercer estado, asi que si los eventos no quedaran, un caso reabierto seria
		// indistinguible de uno que nunca se cerro.
		Fixture fixture = crearFixture();
		CasoClinicoView caso = abrirCaso(fixture);

		CasoClinicoView editado = casos.editar(fixture.actorClinico(), caso.id(),
				"Gonalgia derecha con inestabilidad", "Volver a correr 5 km",
				caso.version(), JUSTIFICACION);
		assertThat(editado.objetivoTerapeutico()).isEqualTo("Volver a correr 5 km");

		CasoClinicoView cerrado = casos.cerrar(fixture.actorClinico(), caso.id(),
				"Alta por objetivos cumplidos", editado.version(), JUSTIFICACION);
		assertThat(cerrado.estado()).isEqualTo("CERRADO");
		assertThat(cerrado.motivoCierre()).isEqualTo("Alta por objetivos cumplidos");
		assertThat(cerrado.cerradoEn()).isNotNull();

		CasoClinicoView reabierto = casos.reabrir(fixture.actorClinico(), caso.id(),
				"El paciente recae a los dos meses", cerrado.version(), JUSTIFICACION);
		assertThat(reabierto.estado()).isEqualTo("ACTIVO");
		assertThat(reabierto.cerradoEn())
				.as("reabrir LIMPIA los tres datos del cierre: si quedaran puestos sobre un caso "
						+ "activo, una consulta por cerrado_en IS NOT NULL devolveria casos abiertos")
				.isNull();
		assertThat(reabierto.motivoCierre()).isNull();

		CasoClinicoView cerradoOtraVez = casos.cerrar(fixture.actorClinico(), caso.id(),
				"Alta definitiva", reabierto.version(), JUSTIFICACION);
		assertThat(cerradoOtraVez.motivoCierre())
				.as("el motivo del segundo cierre es el suyo, no el del primero")
				.isEqualTo("Alta definitiva");

		List<CasoEventoView> historial =
				casos.eventos(fixture.actorClinico(), caso.id(), JUSTIFICACION);
		assertThat(historial.stream().map(CasoEventoView::tipo).toList())
				.as("del mas viejo al mas nuevo, sin perder ninguno")
				.containsExactly("APERTURA", "EDICION", "CIERRE", "REAPERTURA", "CIERRE");
		assertThat(historial.stream().map(CasoEventoView::motivo).toList())
				.as("el motivo aparece solo donde el CHECK lo exige, y conserva el de cada cierre")
				.containsExactly(null, null, "Alta por objetivos cumplidos",
						"El paciente recae a los dos meses", "Alta definitiva");
	}

	@Test
	@DisplayName("editar con una version vieja da 409 y no pisa el contenido")
	void el_control_optimista_protege_el_contenido() {
		// Dos profesionales del equipo editando el objetivo del mismo caso son el caso NORMAL, no
		// el raro (RF-M10-004): sin la version, el segundo pisa al primero en silencio y nadie se
		// entera de que se perdio texto clinico.
		Fixture fixture = crearFixture();
		CasoClinicoView caso = abrirCaso(fixture);
		long versionVieja = caso.version();

		casos.editar(fixture.actorClinico(), caso.id(), "Primera correccion", null,
				versionVieja, JUSTIFICACION);

		assertThatThrownBy(() -> casos.editar(fixture.actorClinico(), caso.id(),
				"Segunda correccion que llega tarde", null, versionVieja, JUSTIFICACION))
				.isInstanceOf(OptimisticLockingFailureException.class);

		assertThat(casos.ver(fixture.actorClinico(), caso.id(), JUSTIFICACION)
				.diagnosticoPresuntivo())
				.isEqualTo("Primera correccion");
	}

	@Test
	@DisplayName("cerrar dos veces no es conflicto y conserva el motivo original")
	void el_cierre_del_caso_es_idempotente() {
		// Es el mismo pedido, no un conflicto: se responde con el caso tal como quedo. Pisarlo con
		// el nuevo motivo perderia el que explica el cierre. Y por eso la idempotencia se evalua
		// ANTES de exigir la version: reintentar un cierre que ya ocurrio no puede fallar por una
		// version que avanzo justamente por ese cierre.
		Fixture fixture = crearFixture();
		CasoClinicoView caso = abrirCaso(fixture);
		CasoClinicoView cerrado = casos.cerrar(fixture.actorClinico(), caso.id(),
				"Alta por objetivos cumplidos", caso.version(), JUSTIFICACION);

		CasoClinicoView reintento = casos.cerrar(fixture.actorClinico(), caso.id(),
				"Otro motivo cualquiera", 9999L, JUSTIFICACION);

		assertThat(reintento.motivoCierre()).isEqualTo("Alta por objetivos cumplidos");
		assertThat(reintento.version()).isEqualTo(cerrado.version());
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM caso_evento WHERE caso_id = ? AND tipo = 'CIERRE'
				""", Long.class, caso.id()))
				.as("y no asienta un segundo evento de cierre")
				.isEqualTo(1L);
	}

	// =================================================================================
	// Lo que un caso cerrado no admite
	// =================================================================================

	@Test
	@DisplayName("un caso cerrado no admite editar contenido ni cambiar el equipo: 409, no 403")
	void un_caso_cerrado_no_admite_cambios() {
		Fixture fixture = crearFixture();
		CasoClinicoView caso = abrirCaso(fixture);
		CasoClinicoView cerrado = casos.cerrar(fixture.actorClinico(), caso.id(),
				"Alta por objetivos cumplidos", caso.version(), JUSTIFICACION);

		assertThatThrownBy(() -> casos.editar(fixture.actorClinico(), caso.id(),
				"Cambio tardio", null, cerrado.version(), JUSTIFICACION))
				.as("lo que corresponde es reabrirlo con motivo, que queda en el historial")
				.isInstanceOf(CasoClinicoCerradoException.class);

		assertThatThrownBy(() -> casos.cambiarEquipo(fixture.actorClinico(), caso.id(),
				List.of(new IntegranteDelEquipo(fixture.membershipId(), RolEnCaso.TRATANTE)),
				cerrado.version(), JUSTIFICACION))
				.isInstanceOf(CasoClinicoCerradoException.class);

		assertThat(casos.ver(fixture.actorClinico(), caso.id(), JUSTIFICACION).estado())
				.as("y el caso se sigue LEYENDO entero: cerrar no es borrar")
				.isEqualTo("CERRADO");
	}

	@Test
	@DisplayName("un caso cerrado no admite sesiones nuevas")
	void un_caso_cerrado_no_admite_sesiones() {
		// Por el camino real —`SesionService#iniciar` consultando `clinical.spi.CasoDirectory`— y
		// no simulando el guardia: lo que se quiere verificar es que el estado del caso viaja por
		// el spi y llega a `encounter`, que es la unica arista nueva que esta etapa agrega entre
		// los dos modulos.
		Fixture fixture = crearFixture();
		CasoClinicoView caso = abrirCaso(fixture);
		casos.cerrar(fixture.actorClinico(), caso.id(), "Alta por objetivos cumplidos",
				caso.version(), JUSTIFICACION);
		long turnoId = insertarTurno(fixture);

		assertThatThrownBy(() -> sesiones.iniciar(
				fixture.actorEncounter(), fixture.consultorioId(), turnoId, caso.id()))
				.isInstanceOfSatisfying(CasoNoAsignableException.class, no ->
						assertThat(no.getMotivo()).isEqualTo(CasoNoAsignableException.Motivo.CERRADO));
	}

	@Test
	@DisplayName("un caso de otra historia no se le puede colgar a una sesion")
	void un_caso_de_otro_paciente_no_es_asignable() {
		// 404 disfrazado: "de otra historia" es indistinguible de "no existe" para quien pregunta,
		// para que nadie pueda censar los casos de otros pacientes probando ids.
		Fixture fixture = crearFixture();
		CasoClinicoView ajeno = abrirCasoEnOtraHistoria(fixture);
		long turnoId = insertarTurno(fixture);

		assertThatThrownBy(() -> sesiones.iniciar(
				fixture.actorEncounter(), fixture.consultorioId(), turnoId, ajeno.id()))
				.isInstanceOfSatisfying(CasoNoAsignableException.class, no ->
						assertThat(no.getMotivo())
								.isEqualTo(CasoNoAsignableException.Motivo.DE_OTRA_HISTORIA));
	}

	// =================================================================================
	// Reabrir no reinicia el contador
	// =================================================================================

	@Test
	@DisplayName("reabrir NO reinicia la numeracion de sesiones del caso: la siguiente es la 4")
	void reabrir_no_renumera_las_sesiones() {
		// La quinta condicion del challenge. Renumerar seria reescribir historia clinica: la
		// sesion 1 del "segundo periodo" y la sesion 1 del primero serian dos hechos distintos con
		// el mismo nombre, y el informe del paciente dejaria de tener un orden.
		//
		// Por eso el numerador cuelga del CASO y no del "periodo de actividad" del caso, que no
		// existe como entidad y no deberia.
		Fixture fixture = crearFixture();
		CasoClinicoView caso = abrirCaso(fixture);

		for (int i = 0; i < 3; i++) {
			cerrarSesionDelCaso(fixture, caso.id());
		}
		assertThat(numerosEnCaso(caso.id())).containsExactly(1, 2, 3);

		CasoClinicoView cerrado = casos.cerrar(fixture.actorClinico(), caso.id(),
				"Alta por objetivos cumplidos", caso.version(), JUSTIFICACION);
		casos.reabrir(fixture.actorClinico(), caso.id(), "El paciente recae",
				cerrado.version(), JUSTIFICACION);

		SesionView despues = cerrarSesionDelCaso(fixture, caso.id());

		assertThat(despues.numeroEnCaso())
				.as("la siguiente a una reapertura es la 4, no la 1")
				.isEqualTo(4);
		assertThat(numerosEnCaso(caso.id())).containsExactly(1, 2, 3, 4);
		assertThat(jdbc.queryForObject("""
				SELECT ultimo_numero FROM caso_sesion_numerador WHERE caso_id = ?
				""", Integer.class, caso.id()))
				.as("el numerador nunca vuelve atras: no es un cache de COUNT(*)")
				.isEqualTo(4);
	}

	// =================================================================================
	// El equipo con vigencia
	// =================================================================================

	@Test
	@DisplayName("el profesional desvinculado sale del equipo vigente y NO desaparece de la tabla")
	void quien_trato_al_paciente_sigue_figurando() {
		// "Lo trato, y por eso sigue figurando." Lo que cambia es que deja de poder escribir, y
		// eso lo decide la membership vigente, no esta tabla. Borrar la fila reescribiria historia
		// (regla maestra 10).
		Fixture fixture = crearFixture();
		CasoClinicoView caso = abrirCaso(fixture);
		long suplente = crearMembership(fixture);

		CasoClinicoView conSuplente = casos.cambiarEquipo(fixture.actorClinico(), caso.id(),
				List.of(new IntegranteDelEquipo(fixture.membershipId(), RolEnCaso.RESPONSABLE),
						new IntegranteDelEquipo(suplente, RolEnCaso.TRATANTE)),
				caso.version(), JUSTIFICACION);
		assertThat(conSuplente.equipo()).hasSize(2);

		// SE REPIDE EL CASO ANTES DEL SEGUNDO CAMBIO, y no es ceremonia del test: la vista que
		// devuelve `cambiarEquipo` trae la version que se LEYO, porque el force-increment la hace
		// avanzar al commitear, despues de armarla. Encadenar dos cambios con la version de la
		// respuesta anterior da 409. Es la misma conducta que 02.07 declaro para las habilitaciones
		// de una oferta, y esta bien que el test la ejerza como la ejerce una pantalla.
		long versionVigente = casos.ver(fixture.actorClinico(), caso.id(), JUSTIFICACION).version();

		CasoClinicoView sinSuplente = casos.cambiarEquipo(fixture.actorClinico(), caso.id(),
				List.of(new IntegranteDelEquipo(fixture.membershipId(), RolEnCaso.RESPONSABLE)),
				versionVigente, JUSTIFICACION);

		assertThat(sinSuplente.equipo())
				.as("el equipo VIGENTE queda con uno")
				.hasSize(1);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM caso_profesional WHERE caso_id = ?
				""", Long.class, caso.id()))
				.as("y las dos filas siguen en la tabla: la que trata y la que trato")
				.isEqualTo(2L);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM caso_profesional
				 WHERE caso_id = ? AND profesional_membership_id = ? AND hasta IS NOT NULL
				""", Long.class, caso.id(), suplente))
				.as("al que salio se le puso `hasta`, no se lo borro")
				.isEqualTo(1L);
	}

	@Test
	@DisplayName("el cambio de equipo hace avanzar la version del caso aunque no toque sus columnas")
	void el_cambio_de_equipo_fuerza_el_incremento() {
		// La leccion de 02.07, pagada con un 409 que no aparecia nunca: un @Version sobre el padre
		// no protege una escritura que solo toca tablas hijas. El caso se lee con
		// OPTIMISTIC_FORCE_INCREMENT para que dos cambios de equipo concurrentes no puedan
		// commitear los dos — y que eso EFECTIVAMENTE ocurra lo decide Hibernate contra una base
		// real, no un mock.
		Fixture fixture = crearFixture();
		CasoClinicoView caso = abrirCaso(fixture);

		CasoClinicoView despues = casos.cambiarEquipo(fixture.actorClinico(), caso.id(),
				List.of(new IntegranteDelEquipo(fixture.membershipId(), RolEnCaso.TRATANTE)),
				caso.version(), JUSTIFICACION);

		// LA RESPUESTA DEVUELVE LA VERSION LEIDA, NO LA NUEVA, y es la consecuencia asumida del
		// force-increment: Hibernate hace avanzar la version al COMMITEAR, despues de que el
		// servicio ya armo la vista, asi que dentro de la misma transaccion no hay forma de
		// conocerla. Es lo mismo que 02.07 dejo escrito para las habilitaciones de una oferta —"la
		// pantalla tiene que repedir la oferta despues de guardar"— y la alternativa es peor:
		// ensuciar tambien una columna del padre haria avanzar la version DOS veces y el cliente
		// quedaria en un 409 del que no puede salir.
		assertThat(despues.version())
				.as("la vista se arma antes del commit, asi que trae la version que se leyo")
				.isEqualTo(caso.version());
		assertThat(jdbc.queryForObject(
				"SELECT version FROM caso_clinico WHERE id = ?", Long.class, caso.id()))
				.as("y la fila SI avanzo: es el force-increment de la lectura, que es lo que impide "
						+ "que dos cambios de equipo concurrentes commiteen los dos")
				.isGreaterThan(caso.version());
		assertThatThrownBy(() -> casos.cambiarEquipo(fixture.actorClinico(), caso.id(),
				List.of(new IntegranteDelEquipo(fixture.membershipId(), RolEnCaso.RESPONSABLE)),
				caso.version(), JUSTIFICACION))
				.as("quien leyo antes del cambio ya no puede guardar")
				.isInstanceOf(OptimisticLockingFailureException.class);
	}

	// =================================================================================
	// El filtro por caso del timeline
	// =================================================================================

	@Test
	@DisplayName("el timeline filtrado por caso trae solo las sesiones de ese caso")
	void el_timeline_filtra_por_caso() {
		// `sesion` es la UNICA tabla del sistema que guarda caso_id, asi que es la unica fuente
		// que puede atribuir un hecho a un caso. La entrada clinica cuelga de la historia: con
		// filtro por caso devuelve vacio en vez de devolver de mas, porque rotular "caso 2" hechos
		// que no son de ese caso es peor que no mostrarlos.
		Fixture fixture = crearFixture();
		CasoClinicoView uno = abrirCaso(fixture);
		CasoClinicoView dos = abrirCaso(fixture);
		entradas.registrar(fixture.actorClinico(), fixture.historiaClinicaId(),
				TipoEntradaClinica.EVOLUCION, "Evolucion de la historia",
				Instant.now().minus(1, ChronoUnit.HOURS), JUSTIFICACION);
		cerrarSesionDelCaso(fixture, uno.id());
		cerrarSesionDelCaso(fixture, uno.id());
		cerrarSesionDelCaso(fixture, dos.id());

		TimelinePagina sinFiltro = ver(fixture, null);
		assertThat(sinFiltro.eventos())
				.as("sin filtro entran las tres sesiones y la entrada clinica")
				.hasSize(4);

		TimelinePagina delUno = ver(fixture, uno.id());
		assertThat(delUno.eventos())
				.as("con filtro solo las dos sesiones de ese caso: la entrada NO se atribuye")
				.hasSize(2);
		assertThat(delUno.eventos())
				.allSatisfy(evento -> assertThat(evento.tipo())
						.isEqualTo("SESION_CERRADA"));

		assertThat(ver(fixture, dos.id()).eventos()).hasSize(1);
	}

	@Test
	@DisplayName("filtrar el timeline por un caso de otra historia da 404 y no una pagina vacia")
	void el_caso_ajeno_no_devuelve_pagina_vacia() {
		// Una pagina vacia se lee como "este caso no tuvo nada", que es una respuesta AFIRMATIVA
		// sobre datos ajenos: confirma que el caso existe y que el llamador puede preguntar por
		// el. El 404 no confirma nada.
		Fixture fixture = crearFixture();
		CasoClinicoView ajeno = abrirCasoEnOtraHistoria(fixture);

		assertThatThrownBy(() -> ver(fixture, ajeno.id()))
				.isInstanceOf(CasoClinicoNotAccessibleException.class);
	}

	@Test
	@DisplayName("un actor del tenant B no filtra el timeline de A por un caso de A: 404, nunca 403")
	void un_tenant_no_filtra_por_el_caso_del_otro() {
		// AGENT.md seccion 6, que lo exige en CADA test de integracion.
		Fixture tenantA = crearFixture();
		Fixture tenantB = crearFixture();
		CasoClinicoView deA = abrirCaso(tenantA);

		assertThatThrownBy(() -> timeline.ver(tenantB.actorClinico(),
				tenantB.historiaClinicaId(), null, 50, deA.id(), JUSTIFICACION))
				.as("el caso de A no existe para B, aunque la historia consultada sea la suya")
				.isInstanceOf(CasoClinicoNotAccessibleException.class);
	}

	// =================================================================================
	// Operaciones y consultas
	// =================================================================================

	private CasoClinicoView abrirCaso(Fixture fixture) {
		return casos.abrir(fixture.actorClinico(), new CasoClinicoAltaCommand(
						fixture.historiaClinicaId(),
						fixture.ofertaId(),
						"Gonalgia derecha",
						"Recuperar rango de movimiento",
						List.of(new IntegranteDelEquipo(
								fixture.membershipId(), RolEnCaso.RESPONSABLE)),
						true),
				JUSTIFICACION);
	}

	/** Un caso de OTRO paciente del mismo centro, para los dos escenarios de atribucion cruzada. */
	private CasoClinicoView abrirCasoEnOtraHistoria(Fixture fixture) {
		String sufijo = UUID.randomUUID().toString().substring(0, 12);
		long personaId = crearPaciente(fixture, "Otro" + sufijo);
		long historiaId = abrirHistoria(fixture, personaId);

		return casos.abrir(fixture.actorClinico(), new CasoClinicoAltaCommand(
						historiaId, fixture.ofertaId(), "Caso de otro paciente", null,
						List.of(), true),
				JUSTIFICACION);
	}

	private TimelinePagina ver(Fixture fixture, Long casoId) {
		return timeline.ver(fixture.actorClinico(), fixture.historiaClinicaId(), null, 50,
				casoId, JUSTIFICACION);
	}

	/**
	 * Una sesion del caso, abierta por SQL y cerrada por el servicio.
	 *
	 * <p>Se inserta directo porque abrirla por {@code iniciar} exigiria un turno por cada una, y lo
	 * que estos escenarios miden es el numerador del caso, no la cadena de M12 — que ya tiene su
	 * propio test. El cierre SI va por el servicio: ahi es donde se pide el numero.
	 */
	private SesionView cerrarSesionDelCaso(Fixture fixture, long casoId) {
		long sesionId = insertar("""
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, caso_id,
				                    oferta_id, profesional_membership_id, estado, iniciada_en,
				                    iniciada_por_cuenta_id, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, 'BORRADOR', UTC_TIMESTAMP(6), ?, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{
						fixture.organizationId(), fixture.consultorioId(),
						fixture.historiaClinicaId(), casoId, fixture.ofertaId(),
						fixture.membershipId(), fixture.cuentaId()});

		SesionView actual = sesiones.ver(
				fixture.actorEncounter(), fixture.consultorioId(), sesionId);
		return sesiones.cerrar(fixture.actorEncounter(), fixture.consultorioId(), sesionId,
				new CierreDeSesion(Asistencia.PRESENTE, "Terapia manual", null, null, null, null),
				actual.version());
	}

	/** Un turno reservado de la semana pasada: {@code iniciar} no exige que sea futuro. */
	private long insertarTurno(Fixture fixture) {
		return insertar("""
				INSERT INTO turno (organization_id, consultorio_id, oferta_id, persona_id,
				                   profesional_membership_id, inicio, fin, estado,
				                   reservado_por_cuenta_id, reservado_en, version,
				                   created_at, updated_at)
				VALUES (?, ?, ?, ?, ?,
				        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 7 DAY),
				        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 7 DAY) + INTERVAL 60 MINUTE,
				        'RESERVADO', ?, UTC_TIMESTAMP(6), 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{
						fixture.organizationId(), fixture.consultorioId(), fixture.ofertaId(),
						fixture.personaId(), fixture.membershipId(), fixture.cuentaId()});
	}

	private List<Integer> numerosEnCaso(long casoId) {
		return jdbc.queryForList("""
				SELECT numero_en_caso FROM sesion
				 WHERE caso_id = ? AND numero_en_caso IS NOT NULL
				 ORDER BY numero_en_caso
				""", Integer.class, casoId);
	}

	// =================================================================================
	// Fixture — todo sintetico (AGENT.md seccion 10)
	// =================================================================================

	private record Fixture(
			long organizationId, long consultorioId, long cuentaId, long membershipId,
			long ofertaId, long personaId, long historiaClinicaId,
			OperatingActor actorClinico,
			com.akine.encounter.application.OperatingActor actorEncounter) {
	}

	/**
	 * Un centro con una sede, un profesional, una oferta vigente y un paciente con historia.
	 *
	 * <p>Hacen falta <b>dos</b> {@code OperatingActor} porque son dos clases distintas, una por
	 * modulo: {@code clinical} y {@code encounter} no comparten tipos de aplicacion, que es
	 * justamente lo que ArchUnit hace cumplir. Los dos envuelven la misma cuenta y el mismo
	 * contexto.
	 */
	private Fixture crearFixture() {
		String sufijo = UUID.randomUUID().toString().substring(0, 12);

		long organizationId = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version,
				                          created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{"Centro Sintetico " + sufijo, "ciclo-it-" + sufijo, ZONA});

		long consultorioId = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, "Sede Sintetica " + sufijo, ZONA});

		String email = "ciclo-it-" + sufijo + "@ejemplo.test";
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

		long servicioId = insertar("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default,
				                      genera_registro_clinico_default, active, version,
				                      created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'INDIVIDUAL', 0, 0, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{"CICLO-" + sufijo.toUpperCase(), "Servicio " + sufijo});

		long ofertaId = insertar("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, precio_base, moneda, admite_obra_social,
				        requiere_caso_clinico, genera_registro_clinico, requiere_profesional,
				        requiere_espacio, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'INDIVIDUAL', 60, 1, 8500.00, 'ARS', 0, 1, 1, 1, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, consultorioId, servicioId, "Oferta " + sufijo});

		Fixture parcial = new Fixture(organizationId, consultorioId, cuentaId, membershipId,
				ofertaId, 0L, 0L,
				new OperatingActor(cuentaId, false, organizationId, consultorioId),
				new com.akine.encounter.application.OperatingActor(
						cuentaId, false, organizationId, consultorioId));

		long personaId = crearPaciente(parcial, "Paciente" + sufijo);
		long historiaClinicaId = abrirHistoria(parcial, personaId);

		return new Fixture(organizationId, consultorioId, cuentaId, membershipId, ofertaId,
				personaId, historiaClinicaId, parcial.actorClinico(), parcial.actorEncounter());
	}

	/** Otro profesional del mismo centro, para el escenario del equipo. */
	private long crearMembership(Fixture fixture) {
		String sufijo = UUID.randomUUID().toString().substring(0, 12);
		String email = "suplente-" + sufijo + "@ejemplo.test";
		long cuentaId = insertar("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Suplente', 'DePrueba', 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{email, email});

		return insertar("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, 'PROFESIONAL', 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{fixture.organizationId(), fixture.consultorioId(), cuentaId});
	}

	private long crearPaciente(Fixture fixture, String apellido) {
		long personaId = insertar("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave,
				                     nombre_clave, active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{
						fixture.organizationId(), apellido, apellido.toUpperCase()});

		insertar("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{fixture.organizationId(), personaId, fixture.cuentaId()});
		return personaId;
	}

	private long abrirHistoria(Fixture fixture, long personaId) {
		return insertar("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{fixture.organizationId(), personaId, fixture.cuentaId()});
	}

	private long insertar(String sql, Object[] args) {
		jdbc.update(sql, args);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
