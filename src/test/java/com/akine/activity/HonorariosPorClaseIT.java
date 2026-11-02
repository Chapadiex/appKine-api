package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.application.AsistenciaService;
import com.akine.activity.application.HonorariosDeClasesService;
import com.akine.activity.application.HonorariosViews.BorradorDeHonorariosView;
import com.akine.activity.application.HonorariosViews.ClaseExcluidaView;
import com.akine.activity.application.HonorariosViews.ConfirmarLiquidacionCommand;
import com.akine.activity.application.HonorariosViews.LineaDeHonorarioView;
import com.akine.activity.application.HonorariosViews.ResultadoDeConfirmacion;
import com.akine.activity.application.IdempotencyKeyConflictException;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.InstructoresDeClaseService;
import com.akine.activity.application.OperatingActor;
import com.akine.activity.application.RegistrarAsistenciaCommand;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.activity.domain.exception.ClaseConHonorariosLiquidadosException;
import com.akine.activity.domain.exception.LiquidacionDeHonorariosNoAccesibleException;
import com.akine.activity.domain.exception.LiquidacionDeHonorariosVaciaException;
import com.akine.billing.application.EgresoCommand;
import com.akine.billing.application.EgresoService;
import com.akine.billing.application.EgresoView;
import com.akine.billing.domain.CategoriaEgreso;
import com.akine.billing.domain.TipoBeneficiario;
import com.akine.billing.domain.exception.EgresoDeLiquidacionException;
import com.akine.offering.application.OfertaHonorarioClaseService;
import com.akine.offering.application.OfertaHonorarioClaseView;
import com.akine.testsupport.InsertarConId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DP-38 contra MySQL real: honorarios por clase de punta a punta (AKINE-08.09b).
 *
 * <p>Oferta con tarifa -> clase con dos instructores -> asistencias -> borrador de cada uno (el
 * centavo al primero) -> confirmar -> egreso BORRADOR con snapshot -> reintento sin duplicar -> la
 * clase ya no entra -> la tarifa nueva no toca lo liquidado -> el egreso no cambia su importe ->
 * los instructores no cambian -> anular el egreso libera -> se vuelve a liquidar con la tarifa
 * nueva -> otro tenant no la ve. Cada paso se mira en el motor, no en lo que dijo el servicio.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class HonorariosPorClaseIT {

	private static final BigDecimal TARIFA = new BigDecimal("333.33");
	private static final BigDecimal TARIFA_NUEVA = new BigDecimal("999.00");

	@Autowired private InscripcionService inscripcionService;
	@Autowired private AsistenciaService asistenciaService;
	@Autowired private OfertaHonorarioClaseService honorarioService;
	@Autowired private InstructoresDeClaseService instructoresService;
	@Autowired private HonorariosDeClasesService honorariosService;
	@Autowired private EgresoService egresoService;
	@Autowired private JdbcTemplate jdbc;

	private ActividadItFixture fx;

	@BeforeEach
	void setUp() {
		fx = new ActividadItFixture(jdbc, inscripcionService);
	}

	@Test
	@DisplayName("Tarifa, dos instructores, borrador, confirmar, snapshot, idempotencia, bloqueo, anular libera")
	void circuito_de_los_honorarios_por_clase() {
		Tenant tenant = fx.crearTenant(10, 4);
		long ana = profesional(tenant, "Ana");
		long beto = profesional(tenant, "Beto");
		LocalDate hoy = LocalDate.now(ZoneId.of(ActividadItFixture.ZONA));
		LocalDate desde = hoy;
		LocalDate hasta = hoy.plusDays(5);

		OfertaHonorarioClaseView tarifa = honorarioService.crear(offering(tenant), tenant.organizationId(),
				tenant.consultorioId(), tenant.ofertaId(), TARIFA, "ARS", hoy.minusDays(10), null);

		// Clase 1: Ana principal + Beto adicional, 3 presentes (uno tarde) y un ausente.
		long clase1 = claseEnCurso(tenant, ana, 1, 0, 1, 2, 3);
		instructoresService.reemplazar(tenant.actor(), tenant.consultorioId(), clase1, List.of(beto));
		registrar(tenant, clase1, 0, ResultadoAsistencia.PRESENTE);
		registrar(tenant, clase1, 1, ResultadoAsistencia.PRESENTE_TARDE);
		registrar(tenant, clase1, 2, ResultadoAsistencia.PRESENTE);
		registrar(tenant, clase1, 3, ResultadoAsistencia.AUSENTE);
		asistenciaService.cerrar(tenant.actor(), tenant.consultorioId(), clase1);
		// Clase 2: solo Ana, un presente. Clase 3: solo Ana, nadie presente.
		long clase2 = claseEnCurso(tenant, ana, 2, 0);
		registrar(tenant, clase2, 0, ResultadoAsistencia.PRESENTE);
		asistenciaService.cerrar(tenant.actor(), tenant.consultorioId(), clase2);
		long clase3 = claseEnCurso(tenant, ana, 3, 1);
		registrar(tenant, clase3, 1, ResultadoAsistencia.AUSENTE);
		asistenciaService.cerrar(tenant.actor(), tenant.consultorioId(), clase3);

		// --- Borradores: 333,33 x 3 = 999,99 entre dos -> 500,00 a Ana (primera) y 499,99 a Beto.
		BorradorDeHonorariosView deAna = honorariosService.borrador(
				tenant.actor(), tenant.consultorioId(), ana, desde, hasta, null);
		assertThat(deAna.clases()).extracting(LineaDeHonorarioView::claseId).containsExactly(clase1, clase2);
		LineaDeHonorarioView compartida = deAna.clases().get(0);
		assertThat(compartida.presentes()).isEqualTo(3);
		assertThat(compartida.importeClase()).isEqualByComparingTo("999.99");
		assertThat(compartida.instructores()).isEqualTo(2);
		assertThat(compartida.posicion()).isEqualTo(1);
		assertThat(compartida.importeColaborador()).isEqualByComparingTo("500.00");
		assertThat(deAna.clases().get(1).importeColaborador()).isEqualByComparingTo("333.33");
		assertThat(deAna.importeTotal()).isEqualByComparingTo("833.33");
		assertThat(deAna.moneda()).isEqualTo("ARS");
		assertThat(deAna.excluidas()).extracting(ClaseExcluidaView::claseId, ClaseExcluidaView::motivo)
				.containsExactly(org.assertj.core.groups.Tuple.tuple(clase3, "SIN_PRESENTES"));

		BorradorDeHonorariosView deBeto = honorariosService.borrador(
				tenant.actor(), tenant.consultorioId(), beto, desde, hasta, null);
		assertThat(deBeto.clases()).singleElement().satisfies(l -> {
			assertThat(l.posicion()).isEqualTo(2);
			assertThat(l.importeColaborador()).isEqualByComparingTo("499.99");
		});

		// --- Confirmar: egreso BORRADOR + liquidacion + snapshot.
		String clave = "honor-" + UUID.randomUUID();
		ConfirmarLiquidacionCommand pedido = confirmacion(deAna, ana, clave);
		ResultadoDeConfirmacion confirmada = honorariosService.confirmar(
				tenant.actor(), tenant.consultorioId(), pedido);
		assertThat(confirmada.creada()).isTrue();
		long liquidacionId = confirmada.liquidacion().id();
		long egresoId = confirmada.liquidacion().egresoId();
		assertThat(confirmada.liquidacion().egresoEstado()).isEqualTo("BORRADOR");
		assertThat(confirmada.liquidacion().colaboradorNombre()).isEqualTo("Ana DePrueba");
		assertThat(confirmada.liquidacion().ofertaId()).isEqualTo(tenant.ofertaId());

		// --- DP-45: los numeros de la tira, del borrador y de la liquidacion.
		assertThat(deAna.resumen()).satisfies(r -> {
			assertThat(r.cantidadClases()).isEqualTo(2);
			assertThat(r.cantidadExcluidas()).isEqualTo(1);
			assertThat(r.totalPresentes())
					.isEqualTo(deAna.clases().get(0).presentes() + deAna.clases().get(1).presentes());
			assertThat(r.importeTotal()).isEqualByComparingTo("833.33");
			assertThat(r.importePagado()).isNull();
			assertThat(r.regla()).isEqualTo("POR_PARTICIPANTE_PRESENTE");
		});
		assertThat(confirmada.liquidacion().resumen()).satisfies(r -> {
			assertThat(r.cantidadClases()).isEqualTo(2);
			assertThat(r.cantidadExcluidas()).isNull();
			assertThat(r.importePagado()).as("el egreso nace en borrador: nada pagado").isEqualByComparingTo("0");
			assertThat(r.saldoPendiente()).isEqualByComparingTo("833.33");
		});

		assertThat(jdbc.queryForMap("""
				SELECT estado, categoria, tipo_beneficiario, beneficiario_membership_id, importe_total,
				       saldo_pendiente, periodo_desde, periodo_hasta, moneda, oferta_id
				  FROM egreso WHERE id = ?
				""", egresoId))
				.containsEntry("estado", "BORRADOR")
				.containsEntry("categoria", "HONORARIOS_PROFESIONALES")
				.containsEntry("tipo_beneficiario", "COLABORADOR")
				.containsEntry("beneficiario_membership_id", ana)
				.containsEntry("periodo_desde", java.sql.Date.valueOf(desde))
				.containsEntry("periodo_hasta", java.sql.Date.valueOf(hasta))
				.containsEntry("moneda", "ARS")
				// DP-39: imputado a la oferta de sus clases, entra solo en la rentabilidad por servicio.
				.containsEntry("oferta_id", tenant.ofertaId());
		assertThat(jdbc.queryForObject("SELECT importe_total FROM egreso WHERE id = ?", BigDecimal.class, egresoId))
				.isEqualByComparingTo("833.33");
		assertThat(jdbc.queryForMap(
				"SELECT estado, egreso_id, importe_total FROM liquidacion_honorario WHERE id = ?", liquidacionId))
				.containsEntry("estado", "VIGENTE")
				.containsEntry("egreso_id", egresoId);
		List<Map<String, Object>> detalle = jdbc.queryForList("""
				SELECT clase_id, presentes, honorario_id, importe_por_participante, importe_clase,
				       instructores, posicion, importe_colaborador, oferta_id, liberada_key
				  FROM liquidacion_honorario_clase WHERE liquidacion_id = ? ORDER BY clase_inicio
				""", liquidacionId);
		assertThat(detalle).hasSize(2);
		assertThat(detalle.get(0))
				.containsEntry("clase_id", clase1)
				.containsEntry("presentes", 3)
				.containsEntry("honorario_id", tarifa.id())
				.containsEntry("instructores", 2)
				.containsEntry("posicion", 1)
				.containsEntry("oferta_id", tenant.ofertaId())
				.containsEntry("liberada_key", 0L);
		assertThat((BigDecimal) detalle.get(0).get("importe_colaborador")).isEqualByComparingTo("500.00");
		assertThat(contarAuditoria(tenant, "LIQUIDACION_HONORARIOS_CONFIRMADA")).isEqualTo(1);
		assertThat(egresoService.ver(billing(tenant), tenant.consultorioId(), egresoId).liquidacionHonorariosId())
				.isEqualTo(liquidacionId);

		// --- Reintento con la misma clave: la misma liquidacion, sin escribir.
		ResultadoDeConfirmacion reintento = honorariosService.confirmar(
				tenant.actor(), tenant.consultorioId(), pedido);
		assertThat(reintento.creada()).isFalse();
		assertThat(reintento.liquidacion().id()).isEqualTo(liquidacionId);
		assertThat(contarLiquidaciones(tenant)).isEqualTo(1);
		ConfirmarLiquidacionCommand otroPedido = new ConfirmarLiquidacionCommand(ana, desde, hasta,
				pedido.claseIds(), new BigDecimal("1.00"), null, null, null, null, clave, null);
		assertThatThrownBy(() -> honorariosService.confirmar(tenant.actor(), tenant.consultorioId(), otroPedido))
				.isInstanceOf(IdempotencyKeyConflictException.class);

		// --- La clase ya liquidada no entra en otro borrador; confirmar de nuevo es 409 vacia.
		BorradorDeHonorariosView despues = honorariosService.borrador(
				tenant.actor(), tenant.consultorioId(), ana, desde, hasta, null);
		assertThat(despues.clases()).isEmpty();
		assertThat(despues.excluidas()).filteredOn(e -> e.motivo().equals("YA_LIQUIDADA"))
				.extracting(ClaseExcluidaView::egresoId).containsOnly(egresoId).hasSize(2);
		assertThatThrownBy(() -> honorariosService.confirmar(tenant.actor(), tenant.consultorioId(),
				confirmacion(deAna, ana, null)))
				.isInstanceOf(LiquidacionDeHonorariosVaciaException.class);

		// --- Beto liquida su parte por separado: la misma clase, otro colaborador.
		ResultadoDeConfirmacion deBetoConfirmada = honorariosService.confirmar(
				tenant.actor(), tenant.consultorioId(), confirmacion(deBeto, beto, null));
		assertThat(deBetoConfirmada.liquidacion().importeTotal()).isEqualByComparingTo("499.99");

		// --- La tarifa nueva no toca lo liquidado (CA-M22-007-06).
		honorarioService.darDeBaja(offering(tenant), tenant.organizationId(), tenant.consultorioId(),
				tenant.ofertaId(), tarifa.id(), "Aumento");
		honorarioService.crear(offering(tenant), tenant.organizationId(), tenant.consultorioId(),
				tenant.ofertaId(), TARIFA_NUEVA, "ARS", hoy.minusDays(10), null);
		assertThat(jdbc.queryForObject("""
				SELECT importe_por_participante FROM liquidacion_honorario_clase
				 WHERE liquidacion_id = ? AND clase_id = ?
				""", BigDecimal.class, liquidacionId, clase1)).isEqualByComparingTo(TARIFA);

		// --- El egreso de la liquidacion no cambia su importe; el concepto si.
		EgresoView egreso = egresoService.ver(billing(tenant), tenant.consultorioId(), egresoId);
		assertThatThrownBy(() -> egresoService.editar(billing(tenant), tenant.consultorioId(), egresoId,
				edicion(egreso, new BigDecimal("900.00"), "Otro concepto"), egreso.version()))
				.isInstanceOf(EgresoDeLiquidacionException.class);
		EgresoView editado = egresoService.editar(billing(tenant), tenant.consultorioId(), egresoId,
				edicion(egreso, egreso.importeTotal(), "Honorarios octubre"), egreso.version());
		assertThat(editado.concepto()).isEqualTo("Honorarios octubre");

		// --- Los instructores de una clase liquidada no cambian.
		assertThatThrownBy(() -> instructoresService.reemplazar(
				tenant.actor(), tenant.consultorioId(), clase1, List.of()))
				.isInstanceOf(ClaseConHonorariosLiquidadosException.class);

		// --- Anular el egreso libera: las clases vuelven, con la tarifa nueva.
		egresoService.anular(billing(tenant), tenant.consultorioId(), egresoId, "Liquidacion mal armada");
		assertThat(jdbc.queryForMap("SELECT estado, liberada_en FROM liquidacion_honorario WHERE id = ?",
				liquidacionId)).containsEntry("estado", "LIBERADA").doesNotContainEntry("liberada_en", null);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM liquidacion_honorario_clase
				 WHERE liquidacion_id = ? AND liberada_key = id
				""", Integer.class, liquidacionId)).isEqualTo(2);
		assertThat(contarAuditoria(tenant, "LIQUIDACION_HONORARIOS_LIBERADA")).isEqualTo(1);

		BorradorDeHonorariosView liberado = honorariosService.borrador(
				tenant.actor(), tenant.consultorioId(), ana, desde, hasta, null);
		assertThat(liberado.clases()).extracting(LineaDeHonorarioView::claseId).containsExactly(clase1, clase2);
		// 999,00 x 3 = 2.997,00 entre dos = 1.498,50 a cada uno; mas 999,00 de la clase 2.
		assertThat(liberado.importeTotal()).isEqualByComparingTo("2497.50");
		ResultadoDeConfirmacion segunda = honorariosService.confirmar(
				tenant.actor(), tenant.consultorioId(), confirmacion(liberado, ana, null));
		assertThat(segunda.creada()).isTrue();
		assertThat(segunda.liquidacion().id()).isNotEqualTo(liquidacionId);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM liquidacion_honorario_clase WHERE clase_id = ?
				""", Integer.class, clase1)).isEqualTo(3);

		// --- Otro tenant no la ve: 404 y no 403.
		Tenant otro = fx.crearTenant(10, 0);
		assertThatThrownBy(() -> honorariosService.ver(otro.actor(), otro.consultorioId(), liquidacionId))
				.isInstanceOf(LiquidacionDeHonorariosNoAccesibleException.class);
		assertThat(honorariosService.borrador(otro.actor(), otro.consultorioId(), ana, desde, hasta, null).clases())
				.isEmpty();
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private long profesional(Tenant tenant, String nombre) {
		String email = nombre.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@ejemplo.test";
		long cuentaId = InsertarConId.insertar(jdbc, """
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, ?, 'DePrueba', 'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email, nombre);
		return InsertarConId.insertar(jdbc, """
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, 'PROFESIONAL', 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", tenant.organizationId(), tenant.consultorioId(), cuentaId);
	}

	/** Una clase en curso dentro de {@code dias}, con ese principal y esas personas inscriptas. */
	private long claseEnCurso(Tenant tenant, long principal, int dias, int... personas) {
		long clase = fx.programarClase(tenant, 10, dias);
		jdbc.update("UPDATE clase_programada SET profesional_membership_id = ? WHERE id = ?", principal, clase);
		for (int persona : personas) {
			fx.inscribir(tenant, clase, tenant.persona(persona), false);
		}
		asistenciaService.iniciar(tenant.actor(), tenant.consultorioId(), clase);
		return clase;
	}

	private void registrar(Tenant tenant, long clase, int persona, ResultadoAsistencia resultado) {
		long inscripcion = fx.inscripcionDe(clase, tenant.persona(persona));
		asistenciaService.registrar(tenant.actor(), tenant.consultorioId(), clase,
				new RegistrarAsistenciaCommand(inscripcion, resultado, OrigenAsistencia.MOSTRADOR, null, null));
	}

	private static ConfirmarLiquidacionCommand confirmacion(
			BorradorDeHonorariosView borrador, long colaborador, String clave) {
		return new ConfirmarLiquidacionCommand(colaborador, borrador.desde(), borrador.hasta(),
				borrador.clases().stream().map(LineaDeHonorarioView::claseId).toList(),
				borrador.importeTotal(), null, null, null, null, clave, null);
	}

	private static EgresoCommand edicion(EgresoView egreso, BigDecimal importe, String concepto) {
		return new EgresoCommand(CategoriaEgreso.valueOf(egreso.categoria()), TipoBeneficiario.COLABORADOR,
				egreso.beneficiarioMembershipId(), null, null, egreso.periodoDesde(), egreso.periodoHasta(),
				concepto, importe, egreso.moneda(), null, null, null, null, egreso.ofertaId());
	}

	private static com.akine.offering.application.OperatingActor offering(Tenant tenant) {
		OperatingActor actor = tenant.actor();
		return new com.akine.offering.application.OperatingActor(
				actor.accountId(), false, tenant.organizationId(), tenant.consultorioId());
	}

	private static com.akine.billing.application.OperatingActor billing(Tenant tenant) {
		return new com.akine.billing.application.OperatingActor(
				tenant.actor().accountId(), false, tenant.organizationId(), tenant.consultorioId());
	}

	private int contarLiquidaciones(Tenant tenant) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM liquidacion_honorario WHERE organization_id = ?",
				Integer.class, tenant.organizationId());
	}

	private int contarAuditoria(Tenant tenant, String evento) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event WHERE organization_id = ? AND event_type = ?
				""", Integer.class, tenant.organizationId(), evento);
	}
}
