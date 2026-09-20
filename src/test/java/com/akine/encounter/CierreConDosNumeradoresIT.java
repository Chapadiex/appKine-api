package com.akine.encounter;

import com.akine.TestcontainersConfiguration;
import com.akine.encounter.application.OperatingActor;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.application.SesionView;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.encounter.domain.exception.SesionNotAccessibleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>El cierre de sesion toma DOS numeradores en la misma transaccion, y el orden es fijo.</b>
 *
 * <p><b>ESCRITO EL 19/09/2026 Y NUNCA EJECUTADO: Docker no estaba disponible.</b> El motor de
 * contenedores de esta maquina no arranca sin elevacion, asi que esta clase compila y no corrio.
 * Nada de lo que afirma esta verificado todavia.
 *
 * <h2>Que escenario prueba y por que importa mas que el resto de la etapa</h2>
 *
 * <p>Hasta 04.03, ninguna transaccion de este sistema tomaba mas de un numerador. Desde 04.03,
 * {@code SesionService#cerrar} toma <b>dos</b>: el de la Historia Clinica ({@code sesion_numerador},
 * {@code V35}) y el del Caso ({@code caso_sesion_numerador}, {@code V47}), pedido a traves de
 * {@code clinical.spi.CasoDirectory}. Cada uno es un lock exclusivo de fila.
 *
 * <p>Si dos cierres concurrentes los tomaran en <b>orden distinto</b> —uno historia&rarr;caso y el
 * otro caso&rarr;historia— cada uno esperaria el lock que el otro ya tiene y se bloquearian
 * mutuamente. La regla es <b>historia primero, caso despues, siempre</b>, y hasta hoy estaba
 * <b>razonada y no probada</b>: vive en el javadoc de {@code SesionService#cerrar} y en el
 * challenge §8.4, que la llama "la mas facil de olvidar y la mas cara".
 *
 * <h2>Lo que esta clase puede probar y lo que no</h2>
 *
 * <p>Hay que decirlo de frente: <b>con datos validos, el ciclo de espera no se puede construir</b>.
 * Un caso pertenece a exactamente una historia, asi que dos sesiones que comparten caso comparten
 * historia y ya quedan serializadas en el primer numerador; y dos sesiones de historias distintas
 * no comparten ninguno de los dos. <b>Eso no es una debilidad del test: es la consecuencia del
 * orden fijo</b>, y es precisamente lo que deja de ser cierto si alguien invierte los pasos 5 y 5b
 * o mete un tercer numerador en el medio.
 *
 * <p>Entonces lo que estos escenarios verifican es lo observable: que las combinaciones que
 * comparten uno o los dos numeradores <b>terminen las dos</b>, sin deadlock, sin
 * {@code UnexpectedRollbackException} y sin lock wait timeout, y con los correlativos coherentes en
 * las <b>dos dimensiones a la vez</b>. Un deadlock real aparece aca como una tarea fallida con
 * {@code CannotAcquireLockException} en la cadena de causas.
 *
 * <p><b>Si esta clase destapa un deadlock, no se tapa con un reintento.</b> El reintento convierte
 * un error de orden de recursos en latencia y en 409 esporadicos que nadie sabe explicar. Se
 * reporta y se corrige el orden.
 *
 * <h2>Y la segunda mitad: las dos numeraciones son independientes</h2>
 *
 * <p>{@code numero_sesion} cuenta por historia y {@code numero_en_caso} cuenta por caso, y no hay
 * ninguna relacion entre los dos. Una historia con sesiones con caso y sin caso mezcladas lo
 * muestra: el correlativo de la historia avanza en todas, el del caso solo en las que tienen caso.
 * Es la desviacion declarada de la regla maestra 3 (challenge §4) hecha verificable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class CierreConDosNumeradoresIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	@Autowired private SesionService sesionService;
	@Autowired private JdbcTemplate jdbc;

	// =================================================================================
	// (a) Dos cierres del MISMO caso: comparten los dos numeradores
	// =================================================================================

	@Test
	@DisplayName("dos cierres concurrentes de sesiones del mismo caso entran los dos, 1 y 2 en ambas dimensiones")
	void dos_cierres_del_mismo_caso_no_se_bloquean() {
		// Es la combinacion con MAS solapamiento posible: las dos transacciones pelean por el
		// numerador de la historia Y por el del caso. Con el orden fijo se serializan y las dos
		// terminan; con el orden invertido en una de ellas, esta es la que se clava.
		Fixture fixture = crearFixture();
		long sesionUna = insertarSesion(fixture, fixture.casoUno());
		long sesionOtra = insertarSesion(fixture, fixture.casoUno());

		List<Desenlace> desenlaces = enParalelo(List.of(
				() -> cerrar(fixture, sesionUna),
				() -> cerrar(fixture, sesionOtra)));

		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("ningun cierre puede caerse: no hay conflicto de negocio, solo de numero. "
						+ "Desenlaces: %s", desenlaces)
				.isEmpty();
		assertThat(numerosDeSesion(fixture.historiaClinicaId()))
				.as("el correlativo de la historia: 1 y 2")
				.containsExactly(1, 2);
		assertThat(numerosEnCaso(fixture.casoUno()))
				.as("y el del caso tambien: 1 y 2, en las mismas dos sesiones")
				.containsExactly(1, 2);
	}

	// =================================================================================
	// (b) El cruzado a proposito: misma historia, casos distintos
	// =================================================================================

	@Test
	@DisplayName("el cruzado: sesion del caso 1 y sesion del caso 2 cerradas a la vez entran las dos")
	void el_cruce_de_casos_no_produce_deadlock() {
		// Este es el escenario que el challenge §8.4 nombra: "dos cierres concurrentes de sesiones
		// de casos cruzados". Comparten el numerador de la historia y NO comparten el del caso, o
		// sea que cada transaccion toma un segundo lock distinto mientras la otra espera el
		// primero. Con el orden fijo eso es una fila; con el orden invertido seria un ciclo.
		Fixture fixture = crearFixture();
		long sesionDelUno = insertarSesion(fixture, fixture.casoUno());
		long sesionDelDos = insertarSesion(fixture, fixture.casoDos());

		List<Desenlace> desenlaces = enParalelo(List.of(
				() -> cerrar(fixture, sesionDelUno),
				() -> cerrar(fixture, sesionDelDos)));

		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("ningun deadlock y ningun lock wait timeout. Desenlaces: %s", desenlaces)
				.isEmpty();
		assertThat(numerosDeSesion(fixture.historiaClinicaId()))
				.as("la historia numero las dos, sin repetir")
				.containsExactly(1, 2);
		assertThat(numerosEnCaso(fixture.casoUno()))
				.as("cada caso arranca su propio correlativo en 1")
				.containsExactly(1);
		assertThat(numerosEnCaso(fixture.casoDos()))
				.containsExactly(1);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM caso_sesion_numerador WHERE caso_id IN (?, ?)
				""", Long.class, fixture.casoUno(), fixture.casoDos()))
				.as("y quedaron DOS filas de numerador, una por caso: el ON DUPLICATE KEY UPDATE "
						+ "no duplica ni fusiona")
				.isEqualTo(2L);
	}

	@Test
	@DisplayName("cinco cierres simultaneos del mismo caso sin numerador previo entran los cinco")
	void la_creacion_del_numerador_del_caso_no_produce_deadlock() {
		// La fila de `caso_sesion_numerador` no existe todavia: los cinco cierres intentan crearla
		// a la vez. Si la creacion ocurriera DENTRO de la transaccion que despues la bloquea, las
		// primeras N se bloquearian mutuamente y el try/catch no salvaria —atrapar la excepcion no
		// des-marca la transaccion—. `CasoNumeradorIniciador.asegurarSesionesDelCaso` la crea en
		// REQUIRES_NEW justamente para esto. Es el deadlock que este repositorio ya pago CUATRO
		// veces y que la etapa se comprometio a no pagar una quinta.
		Fixture fixture = crearFixture();
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM caso_sesion_numerador WHERE caso_id = ?
				""", Long.class, fixture.casoUno()))
				.as("el escenario exige que la fila NO exista antes de la rafaga")
				.isZero();

		List<Callable<SesionView>> cierres = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			long sesionId = insertarSesion(fixture, fixture.casoUno());
			cierres.add(() -> cerrar(fixture, sesionId));
		}

		List<Desenlace> desenlaces = enParalelo(cierres);

		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("ningun cierre puede caerse por la creacion del numerador. Desenlaces: %s",
						desenlaces)
				.isEmpty();
		assertThat(numerosEnCaso(fixture.casoUno()))
				.as("1..5 dentro del caso, sin huecos y sin repetidos")
				.containsExactly(1, 2, 3, 4, 5);
		assertThat(numerosDeSesion(fixture.historiaClinicaId()))
				.as("y 1..5 en la historia: las dos numeraciones avanzan a la par cuando todas las "
						+ "sesiones son del mismo caso")
				.containsExactly(1, 2, 3, 4, 5);
	}

	// =================================================================================
	// (c) Las dos numeraciones son independientes
	// =================================================================================

	@Test
	@DisplayName("una rafaga secuencial mezclada: la historia numera 1..6 y el caso solo sus tres")
	void las_dos_numeraciones_avanzan_independientes() {
		// La prueba de que `numero_sesion` y `numero_en_caso` no son el mismo dato con dos
		// nombres. Se cierran seis sesiones alternando con caso y sin caso: la historia numera las
		// seis —porque toda sesion cerrada tiene correlativo de historia (RF-M14-002 admite
		// atencion sin caso)— y el caso numera tres, sin huecos.
		//
		// Un hueco en cualquiera de las dos se lee como "aca hubo una sesion que alguien borro",
		// que es lo que ADR-0011 prohibe que parezca. El riesgo concreto es reservar numero ANTES
		// de resolver la idempotencia: la leccion de 06.05, que el paso 1 del cierre conserva.
		Fixture fixture = crearFixture();
		List<Long> conCaso = new ArrayList<>();

		for (int i = 0; i < 6; i++) {
			boolean llevaCaso = i % 2 == 0;
			long sesionId = insertarSesion(fixture, llevaCaso ? fixture.casoUno() : null);
			if (llevaCaso) {
				conCaso.add(sesionId);
			}
			cerrar(fixture, sesionId);
		}

		assertThat(numerosDeSesion(fixture.historiaClinicaId()))
				.as("la historia numera las seis")
				.containsExactly(1, 2, 3, 4, 5, 6);
		assertThat(numerosEnCaso(fixture.casoUno()))
				.as("el caso numera sus tres, desde 1 y sin huecos: no hereda el numero de historia")
				.containsExactly(1, 2, 3);
		assertThat(jdbc.queryForList("""
				SELECT numero_sesion FROM sesion WHERE id IN (?, ?, ?) ORDER BY numero_sesion
				""", Integer.class, conCaso.get(0), conCaso.get(1), conCaso.get(2)))
				.as("y las MISMAS tres sesiones llevan 1, 3 y 5 en la historia: los dos numeros "
						+ "conviven en la fila y no se pisan")
				.containsExactly(1, 3, 5);
	}

	// =================================================================================
	// (d) La sesion sin caso
	// =================================================================================

	@Test
	@DisplayName("una sesion sin caso cierra con numero_sesion y con numero_en_caso en NULL")
	void la_sesion_sin_caso_cierra_sin_numero_de_caso() {
		// RF-M14-002 admite atencion sin caso, y es lo que son TODAS las sesiones anteriores a
		// esta etapa. El cierre no puede inventarles un caso ni un numero: `ck_sesion_numero_en_caso`
		// lo rechazaria en la base, pero antes de eso seria un correlativo que no numera dentro de
		// nada. Tampoco puede tocar `caso_sesion_numerador` de ningun caso.
		Fixture fixture = crearFixture();
		long sesionId = insertarSesion(fixture, null);

		SesionView cerrada = cerrar(fixture, sesionId);

		assertThat(cerrada.numeroSesion()).isEqualTo(1);
		assertThat(cerrada.numeroEnCaso())
				.as("sin caso no hay numero de caso, y NULL es exactamente lo que fue")
				.isNull();
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM sesion WHERE id = ? AND caso_id IS NULL
				                                AND numero_en_caso IS NULL
				""", Long.class, sesionId))
				.isEqualTo(1L);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM caso_sesion_numerador WHERE caso_id IN (?, ?)
				""", Long.class, fixture.casoUno(), fixture.casoDos()))
				.as("y no se creo ni se toco ningun numerador de caso")
				.isZero();
	}

	// =================================================================================
	// Idempotencia y estado del caso
	// =================================================================================

	@Test
	@DisplayName("cerrar dos veces no consume un segundo numero de caso")
	void el_cierre_idempotente_no_renumera_el_caso() {
		// RN-M14-005 pide resultado estable ante retry, y apretar dos veces "cerrar" es el caso
		// normal. El paso 1 va antes del 4 y del 5b: si la idempotencia se evaluara despues, cada
		// reintento consumiria DOS correlativos —uno por dimension— y las dos numeraciones
		// quedarian con huecos a la vez.
		Fixture fixture = crearFixture();
		long sesionId = insertarSesion(fixture, fixture.casoUno());

		SesionView primera = cerrar(fixture, sesionId);
		SesionView segunda = cerrar(fixture, sesionId);

		assertThat(segunda.numeroSesion()).isEqualTo(primera.numeroSesion());
		assertThat(segunda.numeroEnCaso()).isEqualTo(primera.numeroEnCaso());
		assertThat(jdbc.queryForObject("""
				SELECT ultimo_numero FROM caso_sesion_numerador WHERE caso_id = ?
				""", Integer.class, fixture.casoUno()))
				.as("el numerador del caso avanzo UNA vez, no dos")
				.isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT ultimo_numero FROM sesion_numerador WHERE historia_clinica_id = ?
				""", Integer.class, fixture.historiaClinicaId()))
				.as("y el de la historia tampoco se movio de mas")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("un caso cerrado no impide cerrar la sesion que ya estaba abierta")
	void el_caso_cerrado_no_bloquea_el_cierre_clinico() {
		// Decision declarada en el javadoc de `cerrar`: el caso se valida al INICIAR y `caso_id`
		// no se puede cambiar despues, asi que un caso que se cerro mientras la atencion
		// transcurria no impide registrarla. La atencion ocurrio; negarle el cierre obligaria a
		// elegir entre perder el registro clinico o reabrir el caso para poder guardarlo.
		Fixture fixture = crearFixture();
		long sesionId = insertarSesion(fixture, fixture.casoUno());
		cerrarElCaso(fixture.casoUno());

		SesionView cerrada = cerrar(fixture, sesionId);

		assertThat(cerrada.numeroEnCaso())
				.as("y le asigna su numero dentro del caso igual: el caso cerrado sigue contando")
				.isEqualTo(1);
	}

	// =================================================================================
	// Aislamiento de tenant — 404, nunca 403
	// =================================================================================

	@Test
	@DisplayName("un actor del tenant B no cierra la sesion del tenant A: 404, nunca 403")
	void un_tenant_no_cierra_la_sesion_del_otro() {
		// AGENT.md seccion 6, que lo exige en CADA test de integracion. Un 403 confirmaria que esa
		// sesion existe en algun lado, que es media fuga.
		Fixture tenantA = crearFixture();
		Fixture tenantB = crearFixture();
		long deA = insertarSesion(tenantA, tenantA.casoUno());

		assertThatThrownBy(() -> sesionService.ver(tenantB.actor(), tenantB.consultorioId(), deA))
				.isInstanceOf(SesionNotAccessibleException.class);
		assertThatThrownBy(() -> sesionService.cerrar(tenantB.actor(), tenantB.consultorioId(),
				deA, new CierreDeSesion(Asistencia.PRESENTE, "Intruso", null, null, null, null), 0L))
				.isInstanceOf(SesionNotAccessibleException.class);

		assertThat(numerosDeSesion(tenantA.historiaClinicaId()))
				.as("y nada se numero en la historia de A")
				.isEmpty();
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM caso_sesion_numerador WHERE caso_id = ?
				""", Long.class, tenantA.casoUno()))
				.as("ni se creo el numerador del caso de A")
				.isZero();
	}

	// =================================================================================
	// Ejecucion concurrente — calcado de CierreConcurrenteIT
	// =================================================================================

	/** La barrera es lo que hace real la carrera: sin ella la primera suele terminar antes. */
	private static List<Desenlace> enParalelo(List<Callable<SesionView>> tareas) {
		CyclicBarrier salida = new CyclicBarrier(tareas.size());
		try (ExecutorService pool = Executors.newFixedThreadPool(tareas.size())) {
			List<Future<Desenlace>> futuros = new ArrayList<>();
			for (Callable<SesionView> tarea : tareas) {
				futuros.add(pool.submit(() -> {
					salida.await(10, TimeUnit.SECONDS);
					try {
						return new Desenlace(tarea.call(), null);
					} catch (Exception error) {
						return new Desenlace(null, error);
					}
				}));
			}
			List<Desenlace> desenlaces = new ArrayList<>();
			for (Future<Desenlace> futuro : futuros) {
				desenlaces.add(futuro.get(30, TimeUnit.SECONDS));
			}
			return desenlaces;
		} catch (Exception fallo) {
			throw new IllegalStateException("La ejecucion concurrente no pudo completarse", fallo);
		}
	}

	/**
	 * El desenlace de una tarea.
	 *
	 * <p>El {@code toString} nombra la excepcion completa a proposito: un deadlock llega como
	 * {@code CannotAcquireLockException} envuelta, y sin el mensaje el fallo se lee como "una tarea
	 * fallo" sin decir por que — que es justamente la informacion por la que se escribio la clase.
	 */
	private record Desenlace(SesionView sesion, Exception error) {

		boolean fallo() {
			return error != null;
		}

		@Override
		public String toString() {
			return fallo()
					? "FALLO(" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")"
					: "OK(numeroSesion=" + sesion.numeroSesion()
							+ ", numeroEnCaso=" + sesion.numeroEnCaso() + ")";
		}
	}

	// =================================================================================
	// Operaciones y consultas
	// =================================================================================

	private SesionView cerrar(Fixture fixture, long sesionId) {
		SesionView actual = sesionService.ver(fixture.actor(), fixture.consultorioId(), sesionId);
		return sesionService.cerrar(fixture.actor(), fixture.consultorioId(), sesionId,
				new CierreDeSesion(Asistencia.PRESENTE, "Terapia manual", null, null, null, null),
				actual.version());
	}

	private List<Integer> numerosDeSesion(long historiaClinicaId) {
		return jdbc.queryForList("""
				SELECT numero_sesion FROM sesion
				 WHERE historia_clinica_id = ? AND numero_sesion IS NOT NULL
				 ORDER BY numero_sesion
				""", Integer.class, historiaClinicaId);
	}

	private List<Integer> numerosEnCaso(long casoId) {
		return jdbc.queryForList("""
				SELECT numero_en_caso FROM sesion
				 WHERE caso_id = ? AND numero_en_caso IS NOT NULL
				 ORDER BY numero_en_caso
				""", Integer.class, casoId);
	}

	/** Cierra el caso por SQL: lo que este test mide es el cierre de la SESION, no el del caso. */
	private void cerrarElCaso(long casoId) {
		jdbc.update("""
				UPDATE caso_clinico
				   SET estado = 'CERRADO', cerrado_en = UTC_TIMESTAMP(6), cerrado_por = 1,
				       motivo_cierre = 'Alta por objetivos cumplidos'
				 WHERE id = ?
				""", casoId);
	}

	// =================================================================================
	// Fixture — todo sintetico (AGENT.md seccion 10)
	// =================================================================================

	private record Fixture(
			long organizationId, long consultorioId, long membershipId, long ofertaId,
			long historiaClinicaId, long casoUno, long casoDos, long cuentaId,
			OperatingActor actor) {
	}

	/**
	 * Un paciente con historia abierta y <b>dos</b> casos activos de la misma oferta.
	 *
	 * <p>Dos casos activos a la vez son legitimos (RN-M10-002) y son la condicion del escenario
	 * cruzado: sin ellos no hay dos numeradores de caso distintos que tomar.
	 *
	 * <p>Los casos y las sesiones se insertan directo y no por sus servicios: abrir una sesion
	 * exigiria un turno, un slot y toda la cadena de M12, que ya tiene su propio test. Lo que esta
	 * clase mide son los dos numeradores del cierre.
	 */
	private Fixture crearFixture() {
		String sufijo = UUID.randomUUID().toString().substring(0, 12);

		long organizationId = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version,
				                          created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{"Centro Sintetico " + sufijo, "dos-num-" + sufijo, ZONA});

		long consultorioId = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, "Sede Sintetica " + sufijo, ZONA});

		String email = "dos-num-" + sufijo + "@ejemplo.test";
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

		String apellido = "Paciente" + sufijo;
		long personaId = insertar("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave,
				                     nombre_clave, active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, apellido, apellido.toUpperCase()});

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
				                      requiere_caso_clinico_default,
				                      genera_registro_clinico_default, active, version,
				                      created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'INDIVIDUAL', 0, 0, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{"DOSNUM-" + sufijo.toUpperCase(), "Servicio " + sufijo});

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

		return new Fixture(organizationId, consultorioId, membershipId, ofertaId, historiaClinicaId,
				insertarCaso(organizationId, historiaClinicaId, consultorioId, ofertaId, 1),
				insertarCaso(organizationId, historiaClinicaId, consultorioId, ofertaId, 2),
				cuentaId,
				new OperatingActor(cuentaId, false, organizationId, consultorioId));
	}

	private long insertarCaso(
			long organizationId, long historiaClinicaId, long consultorioId,
			long ofertaId, int numero) {

		return insertar("""
				INSERT INTO caso_clinico (organization_id, historia_clinica_id, numero_caso,
				                          oferta_id, oferta_consultorio_id, diagnostico_presuntivo,
				                          estado, abierto_en, abierto_por, version,
				                          created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'Diagnostico sintetico', 'ACTIVO', UTC_TIMESTAMP(6), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{
						organizationId, historiaClinicaId, numero, ofertaId, consultorioId});
	}

	/** Una sesion en BORRADOR, con o sin caso, lista para cerrar. */
	private long insertarSesion(Fixture fixture, Long casoId) {
		return insertar("""
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, caso_id,
				                    oferta_id, profesional_membership_id, estado, iniciada_en,
				                    iniciada_por_cuenta_id, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, 'BORRADOR', UTC_TIMESTAMP(6), ?, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{
						fixture.organizationId(), fixture.consultorioId(),
						fixture.historiaClinicaId(), casoId, fixture.ofertaId(),
						fixture.membershipId(), fixture.cuentaId()});
	}

	private long insertar(String sql, Object[] args) {
		jdbc.update(sql, args);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
