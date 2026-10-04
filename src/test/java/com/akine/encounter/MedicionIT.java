package com.akine.encounter;

import com.akine.TestcontainersConfiguration;
import com.akine.encounter.api.EncounterProblemHandler;
import com.akine.encounter.application.ComparacionDeMedicionesView;
import com.akine.encounter.application.MedicionService;
import com.akine.encounter.application.MedicionView;
import com.akine.encounter.application.MedicionesDeSesionView;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.encounter.domain.LateralidadMedicion;
import com.akine.encounter.domain.ValorMedido;
import com.akine.encounter.domain.exception.ConsultorioNoAccesibleException;
import com.akine.encounter.domain.exception.MedicionDefinicionInactivaException;
import com.akine.encounter.domain.exception.MedicionDefinicionNoAccesibleException;
import com.akine.encounter.domain.exception.MedicionFueraDeRangoException;
import com.akine.encounter.domain.exception.MedicionNoAccesibleException;
import com.akine.encounter.domain.exception.MedicionTipoIncompatibleException;
import com.akine.encounter.domain.exception.SesionAjenaException;
import com.akine.encounter.domain.exception.SesionCerradaException;
import com.akine.encounter.domain.exception.SesionNotAccessibleException;
import com.akine.encounter.support.EncounterFixtures;
import com.akine.encounter.support.EncounterFixtures.Mundo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mediciones de la sesion (AKINE-06.03) contra MySQL real.
 *
 * <p>Lo que no puede contestar un doble: el upsert contra {@code uk_sesion_medicion}, los CHECK de
 * {@code V52}, el snapshot de la definicion, el borrado fisico y la consulta de la sesion anterior
 * para comparar. Cada test dice en un comentario cual es la regla que cubre.
 *
 * <p>Prueban el comportamiento <b>actual</b>: no versionan mediciones en la enmienda (eso es C-6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class MedicionIT {

	@Autowired private MedicionService medicionService;
	@Autowired private SesionService sesionService;
	@Autowired private JdbcTemplate jdbc;

	private EncounterFixtures fixtures;
	private final EncounterProblemHandler handler = new EncounterProblemHandler();

	@BeforeEach
	void preparar() {
		fixtures = new EncounterFixtures(jdbc);
	}

	// =================================================================================
	// Registro, upsert y snapshot
	// =================================================================================

	@Test
	@DisplayName("registrar guarda el valor con el snapshot de la definicion")
	void registrar_congela_la_definicion() {
		// 06.03 §3: la medicion lleva codigo, nombre, unidad, tipo y version de la definicion.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);

		MedicionView vista = registrar(mundo, sesionId, LateralidadMedicion.IZQUIERDA, "95.5", "primera toma");

		assertThat(vista.codigo()).isEqualTo("ROM-" + mundo.sufijo());
		assertThat(vista.unidad()).isEqualTo("grados");
		assertThat(vista.valorNumerico()).isEqualByComparingTo("95.5");
		var fila = jdbc.queryForMap("""
				SELECT definicion_codigo, definicion_nombre, definicion_unidad, definicion_tipo,
				       definicion_version, lateralidad, nota
				  FROM sesion_medicion WHERE id = ?
				""", vista.id());
		assertThat(fila).containsEntry("definicion_codigo", "ROM-" + mundo.sufijo())
				.containsEntry("definicion_unidad", "grados")
				.containsEntry("definicion_tipo", "NUMERICO")
				.containsEntry("lateralidad", "IZQUIERDA")
				.containsEntry("nota", "primera toma");
	}

	@Test
	@DisplayName("repetir el registro actualiza la misma fila: uk_sesion_medicion")
	void repetir_el_registro_actualiza() {
		// 06.03 §4: el autosave repite el registro.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);

		MedicionView primera = registrar(mundo, sesionId, LateralidadMedicion.DERECHA, "90", null);
		MedicionView segunda = registrar(mundo, sesionId, LateralidadMedicion.DERECHA, "100", "corregida");

		assertThat(segunda.id()).isEqualTo(primera.id());
		assertThat(contarMediciones(sesionId)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT valor_numerico FROM sesion_medicion WHERE id = ?", BigDecimal.class, primera.id()))
				.isEqualByComparingTo("100");
	}

	@Test
	@DisplayName("una medicion bilateral son dos filas, y sin lado es NO_APLICA")
	void bilateral_son_dos_filas() {
		// 06.03 §4.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);

		registrar(mundo, sesionId, LateralidadMedicion.IZQUIERDA, "80", null);
		registrar(mundo, sesionId, LateralidadMedicion.DERECHA, "120", null);
		MedicionView sinLado = registrar(mundo, sesionId, null, "60", null);

		assertThat(sinLado.lateralidad()).isEqualTo(LateralidadMedicion.NO_APLICA);
		assertThat(contarMediciones(sesionId)).isEqualTo(3);
	}

	@Test
	@DisplayName("el motor rechaza un duplicado, un valor en la columna equivocada y BILATERAL")
	void checks_y_unique_de_sesion_medicion() {
		// 06.03 §4 y V52: uk_sesion_medicion, ck_sesion_medicion_valor_tipado,
		// ck_sesion_medicion_lateralidad. Se insertan directo para ejercer el motor y no el servicio.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		insertarMedicion(mundo, sesionId, "NUMERICO", "DERECHA", new BigDecimal("10"), null);

		assertThatThrownBy(() -> insertarMedicion(mundo, sesionId, "NUMERICO", "DERECHA",
				new BigDecimal("11"), null))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("uk_sesion_medicion");
		assertThatThrownBy(() -> insertarMedicion(mundo, sesionId, "NUMERICO", "IZQUIERDA",
				null, "texto"))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ck_sesion_medicion_valor_tipado");
		assertThatThrownBy(() -> insertarMedicion(mundo, sesionId, "NUMERICO", "BILATERAL",
				new BigDecimal("10"), null))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ck_sesion_medicion_lateralidad");

		assertThat(contarMediciones(sesionId)).isEqualTo(1);
	}

	@Test
	@DisplayName("renombrar la definicion no reescribe las mediciones ya tomadas")
	void el_snapshot_sobrevive_al_renombre() {
		// 06.03 §3: "las mediciones viejas siguen diciendo lo que decian".
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		MedicionView vista = registrar(mundo, sesionId, LateralidadMedicion.IZQUIERDA, "90", null);

		jdbc.update("UPDATE medicion_definicion SET name = ?, unidad = 'rad' WHERE id = ?",
				"Nombre nuevo " + mundo.sufijo(), mundo.medicionDefinicionId());

		MedicionesDeSesionView listado = medicionService.listar(
				mundo.actor(), mundo.consultorioId(), sesionId);
		assertThat(listado.mediciones()).singleElement().satisfies(medicion -> {
			assertThat(medicion.id()).isEqualTo(vista.id());
			assertThat(medicion.nombre()).isEqualTo("Rango articular " + mundo.sufijo());
			assertThat(medicion.unidad()).isEqualTo("grados");
		});
	}

	// =================================================================================
	// Validaciones de la definicion
	// =================================================================================

	@Test
	@DisplayName("un valor fuera de rango es rechazado y no deja fila")
	void fuera_de_rango() {
		// 06.03 §6: el rango valida al registrar.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);

		assertThatThrownBy(() -> registrar(mundo, sesionId, LateralidadMedicion.NO_APLICA, "181", null))
				.isInstanceOf(MedicionFueraDeRangoException.class);
		assertThatThrownBy(() -> registrar(mundo, sesionId, LateralidadMedicion.NO_APLICA, "-1", null))
				.isInstanceOf(MedicionFueraDeRangoException.class);

		assertThat(contarMediciones(sesionId)).isZero();
		// Los extremos del rango entran.
		registrar(mundo, sesionId, LateralidadMedicion.IZQUIERDA, "0", null);
		registrar(mundo, sesionId, LateralidadMedicion.DERECHA, "180", null);
		assertThat(contarMediciones(sesionId)).isEqualTo(2);
	}

	@Test
	@DisplayName("estrechar el rango despues no invalida lo ya medido ni rompe la lectura")
	void el_rango_no_se_revalida_al_leer() {
		// 06.03 §6.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		registrar(mundo, sesionId, LateralidadMedicion.DERECHA, "170", null);

		jdbc.update("UPDATE medicion_definicion SET maximo = 100 WHERE id = ?", mundo.medicionDefinicionId());

		assertThat(medicionService.listar(mundo.actor(), mundo.consultorioId(), sesionId).mediciones())
				.singleElement()
				.satisfies(medicion -> assertThat(medicion.valorNumerico()).isEqualByComparingTo("170"));
		assertThatThrownBy(() -> registrar(mundo, sesionId, LateralidadMedicion.IZQUIERDA, "170", null))
				.as("pero registrar una nueva con el rango estrecho si se rechaza")
				.isInstanceOf(MedicionFueraDeRangoException.class);
	}

	@Test
	@DisplayName("un texto sobre una medida NUMERICO es incompatible")
	void tipo_incompatible() {
		// 06.03: el valor tiene que ser el que admite el tipo de la definicion.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);

		assertThatThrownBy(() -> medicionService.registrar(mundo.actor(), mundo.consultorioId(),
				sesionId, mundo.medicionDefinicionId(), LateralidadMedicion.NO_APLICA,
				new ValorMedido(null, "mucho", null), null))
				.isInstanceOf(MedicionTipoIncompatibleException.class);
		assertThat(contarMediciones(sesionId)).isZero();
	}

	@Test
	@DisplayName("una definicion dada de baja no admite registros nuevos pero conserva los viejos")
	void definicion_inactiva() {
		// 06.03: la baja del catalogo no cascadea.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		registrar(mundo, sesionId, LateralidadMedicion.IZQUIERDA, "90", null);

		jdbc.update("""
				UPDATE medicion_definicion
				   SET active = 0, deleted_at = UTC_TIMESTAMP(6), deactivation_reason = 'obsoleta'
				 WHERE id = ?
				""", mundo.medicionDefinicionId());

		assertThatThrownBy(() -> registrar(mundo, sesionId, LateralidadMedicion.DERECHA, "90", null))
				.isInstanceOf(MedicionDefinicionInactivaException.class);
		assertThat(contarMediciones(sesionId)).isEqualTo(1);
	}

	// =================================================================================
	// Borrado
	// =================================================================================

	@Test
	@DisplayName("borrar es fisico sobre una sesion abierta, y borrar lo que no existe es 404")
	void borrar() {
		// 06.03: un borrado que finge haber borrado oculta que se miraban datos viejos.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		registrar(mundo, sesionId, LateralidadMedicion.DERECHA, "90", null);

		medicionService.borrar(mundo.actor(), mundo.consultorioId(), sesionId,
				mundo.medicionDefinicionId(), LateralidadMedicion.DERECHA);

		assertThat(contarMediciones(sesionId)).as("DELETE fisico, no baja logica").isZero();
		assertThatThrownBy(() -> medicionService.borrar(mundo.actor(), mundo.consultorioId(), sesionId,
				mundo.medicionDefinicionId(), LateralidadMedicion.DERECHA))
				.isInstanceOfSatisfying(MedicionNoAccesibleException.class, error ->
						assertThat(handler.handleMedicionNoAccesible(error).getStatus())
								.isEqualTo(HttpStatus.NOT_FOUND.value()));
	}

	// =================================================================================
	// Sesion cerrada y propiedad
	// =================================================================================

	@Test
	@DisplayName("sobre una sesion cerrada no se registra ni se borra, pero se lee")
	void sesion_cerrada() {
		// 06.03 / 06.06: corregir una atencion cerrada es una enmienda, no una escritura.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		registrar(mundo, sesionId, LateralidadMedicion.DERECHA, "90", null);
		cerrar(mundo, sesionId);

		assertThatThrownBy(() -> registrar(mundo, sesionId, LateralidadMedicion.IZQUIERDA, "80", null))
				.isInstanceOf(SesionCerradaException.class);
		assertThatThrownBy(() -> medicionService.borrar(mundo.actor(), mundo.consultorioId(), sesionId,
				mundo.medicionDefinicionId(), LateralidadMedicion.DERECHA))
				.isInstanceOf(SesionCerradaException.class);

		assertThat(contarMediciones(sesionId)).isEqualTo(1);
		assertThat(medicionService.listar(mundo.actor(), mundo.consultorioId(), sesionId).mediciones())
				.hasSize(1);
	}

	@Test
	@DisplayName("escribir en la atencion de otro profesional es 409, no 403")
	void propiedad_de_la_sesion() {
		// 06.01: la propiedad no es un permiso.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		var otro = fixtures.crearMiembro(mundo.organizationId(), mundo.consultorioId(),
				"PROFESIONAL", "otro-" + mundo.sufijo());

		assertThatThrownBy(() -> medicionService.registrar(otro.actor(), mundo.consultorioId(), sesionId,
				mundo.medicionDefinicionId(), LateralidadMedicion.NO_APLICA,
				new ValorMedido(new BigDecimal("10"), null, null), null))
				.isInstanceOfSatisfying(SesionAjenaException.class, error ->
						assertThat(handler.handleSesionAjena(error).getStatus())
								.isEqualTo(HttpStatus.CONFLICT.value()));
		assertThat(contarMediciones(sesionId)).isZero();
	}

	// =================================================================================
	// Aislamiento de tenant: 404, nunca 403
	// =================================================================================

	@Test
	@DisplayName("un actor de otro tenant no registra, lista, compara ni borra: 404")
	void aislamiento_de_tenant() {
		// AGENT.md §6.
		Mundo a = fixtures.crearMundo();
		Mundo b = fixtures.crearMundo();
		long sesionA = fixtures.crearSesion(a);
		registrar(a, sesionA, LateralidadMedicion.DERECHA, "90", null);

		assertThatThrownBy(() -> medicionService.registrar(b.actor(), b.consultorioId(), sesionA,
				b.medicionDefinicionId(), LateralidadMedicion.IZQUIERDA,
				new ValorMedido(new BigDecimal("10"), null, null), null))
				.isInstanceOf(SesionNotAccessibleException.class);
		assertThatThrownBy(() -> medicionService.listar(b.actor(), b.consultorioId(), sesionA))
				.isInstanceOf(SesionNotAccessibleException.class);
		assertThatThrownBy(() -> medicionService.comparar(b.actor(), b.consultorioId(), sesionA))
				.isInstanceOf(SesionNotAccessibleException.class);
		assertThatThrownBy(() -> medicionService.borrar(b.actor(), b.consultorioId(), sesionA,
				a.medicionDefinicionId(), LateralidadMedicion.DERECHA))
				.isInstanceOf(SesionNotAccessibleException.class);
		assertThatThrownBy(() -> medicionService.listar(b.actor(), a.consultorioId(), sesionA))
				.isInstanceOf(ConsultorioNoAccesibleException.class);

		assertThat(handler.handleSesionNoAccesible(new SesionNotAccessibleException(sesionA)).getStatus())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(contarMediciones(sesionA)).isEqualTo(1);
	}

	@Test
	@DisplayName("una definicion de otro tenant declarada en la propia sesion es 404")
	void definicion_de_otro_tenant() {
		// AGENT.md §6: indistinguible de "no existe".
		Mundo a = fixtures.crearMundo();
		Mundo b = fixtures.crearMundo();
		long sesionA = fixtures.crearSesion(a);

		assertThatThrownBy(() -> medicionService.registrar(a.actor(), a.consultorioId(), sesionA,
				b.medicionDefinicionId(), LateralidadMedicion.NO_APLICA,
				new ValorMedido(new BigDecimal("10"), null, null), null))
				.isInstanceOfSatisfying(MedicionDefinicionNoAccesibleException.class, error ->
						assertThat(handler.handleMedicionDefinicionNoAccesible(error).getStatus())
								.isEqualTo(HttpStatus.NOT_FOUND.value()));
		assertThat(contarMediciones(sesionA)).isZero();
	}

	// =================================================================================
	// Comparacion con la sesion anterior
	// =================================================================================

	@Test
	@DisplayName("sin sesion anterior comparar no es un error: anterior null")
	void comparar_sin_baseline() {
		// 06.03 §5: "re-evaluacion sin baseline" es una respuesta valida.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		registrar(mundo, sesionId, LateralidadMedicion.DERECHA, "90", null);

		ComparacionDeMedicionesView comparacion = medicionService.comparar(
				mundo.actor(), mundo.consultorioId(), sesionId);

		assertThat(comparacion.sesionAnteriorId()).isNull();
		assertThat(comparacion.medidas()).singleElement().satisfies(medida -> {
			assertThat(medida.anterior()).isNull();
			assertThat(medida.delta()).isNull();
		});
	}

	@Test
	@DisplayName("comparar trae la ultima sesion cerrada con mediciones y calcula el delta al leer")
	void comparar_con_baseline() {
		// 06.03 §5: el delta se calcula al leer, nunca se guarda.
		Mundo mundo = fixtures.crearMundo();
		long anterior = fixtures.crearSesion(mundo);
		jdbc.update("UPDATE sesion SET iniciada_en = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 7 DAY) WHERE id = ?",
				anterior);
		registrar(mundo, anterior, LateralidadMedicion.DERECHA, "90", null);
		registrar(mundo, anterior, LateralidadMedicion.IZQUIERDA, "70", null);
		cerrar(mundo, anterior);

		long actual = fixtures.crearSesion(mundo);
		registrar(mundo, actual, LateralidadMedicion.DERECHA, "110", null);

		ComparacionDeMedicionesView comparacion = medicionService.comparar(
				mundo.actor(), mundo.consultorioId(), actual);

		assertThat(comparacion.sesionAnteriorId()).isEqualTo(anterior);
		assertThat(comparacion.medidas()).hasSize(2);
		var derecha = comparacion.medidas().stream()
				.filter(medida -> medida.lateralidad() == LateralidadMedicion.DERECHA)
				.findFirst().orElseThrow();
		assertThat(derecha.delta()).isEqualByComparingTo("20");
		var izquierda = comparacion.medidas().stream()
				.filter(medida -> medida.lateralidad() == LateralidadMedicion.IZQUIERDA)
				.findFirst().orElseThrow();
		assertThat(izquierda.actual()).as("la que se tomo la vez pasada y hoy no, igual aparece").isNull();
		assertThat(izquierda.anterior()).isNotNull();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sesion_medicion WHERE sesion_id = ?",
				Long.class, actual)).as("comparar no guarda nada").isEqualTo(1);
	}

	@Test
	@DisplayName("una sesion anterior todavia abierta no es baseline")
	void baseline_exige_sesion_cerrada() {
		// 06.03 §5.
		Mundo mundo = fixtures.crearMundo();
		long anterior = fixtures.crearSesion(mundo);
		jdbc.update("UPDATE sesion SET iniciada_en = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 7 DAY) WHERE id = ?",
				anterior);
		registrar(mundo, anterior, LateralidadMedicion.DERECHA, "90", null);
		long actual = fixtures.crearSesion(mundo);

		assertThat(medicionService.comparar(mundo.actor(), mundo.consultorioId(), actual)
				.sesionAnteriorId()).isNull();
	}

	// =================================================================================
	// Helpers
	// =================================================================================

	private MedicionView registrar(
			Mundo mundo, long sesionId, LateralidadMedicion lado, String valor, String nota) {
		return medicionService.registrar(mundo.actor(), mundo.consultorioId(), sesionId,
				mundo.medicionDefinicionId(), lado,
				new ValorMedido(new BigDecimal(valor), null, null), nota);
	}

	private void cerrar(Mundo mundo, long sesionId) {
		long version = sesionService.ver(mundo.actor(), mundo.consultorioId(), sesionId).version();
		sesionService.cerrar(mundo.actor(), mundo.consultorioId(), sesionId,
				new CierreDeSesion(Asistencia.PRESENTE, "Terapia manual", null, null, null, null),
				version);
	}

	private long contarMediciones(long sesionId) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM sesion_medicion WHERE sesion_id = ?", Long.class, sesionId);
	}

	private void insertarMedicion(
			Mundo mundo, long sesionId, String tipo, String lateralidad,
			BigDecimal numerico, String texto) {

		jdbc.update("""
				INSERT INTO sesion_medicion
				       (organization_id, sesion_id, definicion_id, definicion_codigo, definicion_nombre,
				        definicion_unidad, definicion_tipo, definicion_version, lateralidad,
				        valor_numerico, valor_texto, registrada_en, registrada_por_cuenta_id,
				        version, created_at, updated_at)
				VALUES (?, ?, ?, 'ROM', 'Rango', 'grados', ?, 0, ?, ?, ?, UTC_TIMESTAMP(6), ?, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", mundo.organizationId(), sesionId, mundo.medicionDefinicionId(), tipo, lateralidad,
				numerico, texto, mundo.profesional().cuentaId());
	}
}
