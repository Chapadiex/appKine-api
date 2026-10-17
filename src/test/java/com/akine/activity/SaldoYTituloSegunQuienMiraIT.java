package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.api.dto.ResultadoDeAsistenciaResponse;
import com.akine.activity.application.AsistenciaService;
import com.akine.activity.application.ClaseService;
import com.akine.activity.application.ConsumoDeCredito;
import com.akine.activity.application.ConsumoDeCredito.Efecto;
import com.akine.activity.application.IngresoSinInscripcionCommand;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.ItemDeLote;
import com.akine.activity.application.OperatingActor;
import com.akine.activity.application.ProductoService;
import com.akine.activity.application.ProductoView;
import com.akine.activity.application.RegistrarAsistenciaCommand;
import com.akine.activity.application.ResultadoDeAsistencia;
import com.akine.activity.application.ResultadoDeLote;
import com.akine.activity.application.VenderPaseCommand;
import com.akine.activity.application.VentaDePaseService;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.notification.domain.EmailMessage;
import com.akine.notification.domain.EmailTemplates;
import com.akine.notification.domain.SanitizedPayload;
import com.akine.notification.spi.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U-46 (c) y (d), decididas por el usuario el 17/10/2026, contra MySQL real.
 *
 * <ul>
 *   <li><b>(c)</b> Quien toma lista sin {@code pase:read} —el {@code PROFESIONAL}— recibe el
 *       {@code efecto} del consumo pero no el pase ni el saldo. El {@code ADMINISTRATIVO}, que tiene
 *       el permiso, los recibe. En los tres caminos: individual, lote e ingreso sin inscripcion.</li>
 *   <li><b>(d)</b> El aviso de cancelacion de una clase no lleva el titulo: ni en la fila del
 *       outbox ni en el correo que se redacta desde ella.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class SaldoYTituloSegunQuienMiraIT {

	private static final String TITULO_CLINICO = "Rehabilitacion post-ACV";

	@Autowired private ProductoService productoService;
	@Autowired private VentaDePaseService ventaService;
	@Autowired private InscripcionService inscripcionService;
	@Autowired private AsistenciaService asistenciaService;
	@Autowired private ClaseService claseService;
	@Autowired private JdbcTemplate jdbc;

	private ActividadItFixture fx;

	@BeforeEach
	void setUp() {
		fx = new ActividadItFixture(jdbc, inscripcionService);
	}

	@Test
	@DisplayName("U-46 (c): el PROFESIONAL recibe el efecto sin pase ni saldo; el ADMINISTRATIVO, todo")
	void el_saldo_solo_con_pase_read() {
		Tenant tenant = fx.crearTenant(10, 4);
		PaseVentaYConsumoIT.declararPorPack(jdbc, tenant.ofertaId());
		ProductoView producto = productoService.crear(tenant.actor(), tenant.consultorioId(),
				PaseVentaYConsumoIT.comando(tenant.ofertaId(), "Pack Pilates", 8));
		for (Long persona : tenant.personas()) {
			ventaService.vender(tenant.actor(), tenant.consultorioId(),
					new VenderPaseCommand(producto.id(), persona, null));
		}
		OperatingActor profesional = miembro(tenant, "PROFESIONAL");
		OperatingActor administrativo = miembro(tenant, "ADMINISTRATIVO");

		long clase = fx.programarClase(tenant, 10, 1);
		for (Long persona : tenant.personas().subList(0, 3)) {
			fx.inscribir(tenant, clase, persona, false);
		}
		asistenciaService.iniciar(tenant.actor(), tenant.consultorioId(), clase);

		// Individual, PROFESIONAL: consumio, pero no sabe que pase ni cuanto queda.
		ResultadoDeAsistencia delProfesional = asistenciaService.registrar(profesional,
				tenant.consultorioId(), clase,
				presente(fx.inscripcionDe(clase, tenant.persona(0))));
		assertSinPase(delProfesional.consumo());
		assertThat(ResultadoDeAsistenciaResponse.de(delProfesional).consumoDeCredito().saldo()).isNull();
		// El consumo ocurrio igual: el motor lo dice, aunque la respuesta lo calle.
		assertThat(saldoDe(tenant.persona(0))).isEqualTo(7);

		// Individual, ADMINISTRATIVO: con pase:read ve el pase y el saldo.
		ResultadoDeAsistencia delAdministrativo = asistenciaService.registrar(administrativo,
				tenant.consultorioId(), clase,
				presente(fx.inscripcionDe(clase, tenant.persona(1))));
		assertThat(delAdministrativo.consumo().efecto()).isEqualTo(Efecto.CONSUMIDO);
		assertThat(delAdministrativo.consumo().paseId()).isEqualTo(paseDe(tenant.persona(1)));
		assertThat(delAdministrativo.consumo().saldo()).isEqualTo(7);

		// Lote, PROFESIONAL.
		ResultadoDeLote lote = asistenciaService.registrarLote(profesional, tenant.consultorioId(),
				clase, List.of(presente(fx.inscripcionDe(clase, tenant.persona(2)))));
		assertThat(lote.items()).singleElement().satisfies(item -> {
			assertThat(item.exitoso()).isTrue();
			assertSinPase(item.resultado().consumo());
		});
		assertThat(lote.items()).extracting(ItemDeLote::inscripcionId)
				.containsExactly(fx.inscripcionDe(clase, tenant.persona(2)));

		// Ingreso sin inscripcion: el PROFESIONAL tiene inscripcion:manage y asistencia:manage.
		ResultadoDeAsistencia ingreso = asistenciaService.registrarIngresoSinInscripcion(
				profesional, tenant.consultorioId(), clase,
				new IngresoSinInscripcionCommand(tenant.persona(3), ResultadoAsistencia.PRESENTE, null));
		assertSinPase(ingreso.consumo());
		assertThat(saldoDe(tenant.persona(3))).isEqualTo(7);
	}

	@Test
	@DisplayName("U-46 (d): el aviso de cancelacion de una clase no lleva su titulo")
	void el_aviso_de_cancelacion_no_lleva_el_titulo() {
		Tenant tenant = fx.crearTenant(10, 1);
		String email = "inscripta-" + tenant.organizationId() + "@ejemplo.test";
		jdbc.update("UPDATE persona SET email = ? WHERE id = ?", email, tenant.persona(0));
		long clase = fx.programarClase(tenant, 10, 2);
		jdbc.update("UPDATE clase_programada SET titulo = ? WHERE id = ?", TITULO_CLINICO, clase);
		fx.inscribir(tenant, clase, tenant.persona(0), false);

		claseService.cancelar(tenant.actor(), tenant.consultorioId(), clase, "Sin instructor");

		String payload = jdbc.queryForObject("""
				SELECT CAST(payload_sanitizado AS CHAR) FROM notification_outbox
				 WHERE organization_id = ? AND tipo = 'CLASE_MODIFICADA' AND destinatario = ?
				""", String.class, tenant.organizationId(), email);
		assertThat(payload)
				.doesNotContain("claseTitulo")
				.doesNotContain("Rehabilitacion")
				.contains("claseInicio");

		// El correo es lo que lee la casilla: se redacta desde lo que quedo en la fila.
		EmailMessage correo = EmailTemplates.render(NotificationType.CLASE_MODIFICADA, email,
				SanitizedPayload.fromJson(payload), null);
		assertThat(correo.asunto() + "\n" + correo.cuerpo()).doesNotContain("Rehabilitacion");
		assertThat(correo.cuerpo()).contains("Hubo un cambio en tu clase del ").contains(" en Sede ");
	}

	// =================================================================================

	private static void assertSinPase(ConsumoDeCredito consumo) {
		assertThat(consumo.efecto()).isEqualTo(Efecto.CONSUMIDO);
		assertThat(consumo.paseId()).isNull();
		assertThat(consumo.saldo()).isNull();
	}

	private static RegistrarAsistenciaCommand presente(long inscripcionId) {
		return new RegistrarAsistenciaCommand(
				inscripcionId, ResultadoAsistencia.PRESENTE, OrigenAsistencia.MOSTRADOR, null, null);
	}

	private long paseDe(long personaId) {
		return jdbc.queryForObject("SELECT id FROM pase_servicio WHERE persona_id = ?", Long.class, personaId);
	}

	private int saldoDe(long personaId) {
		return jdbc.queryForObject(
				"SELECT creditos_disponibles FROM pase_servicio WHERE persona_id = ?", Integer.class, personaId);
	}

	/** Una cuenta con una membership vigente del rol pedido, en la sede del tenant. */
	private OperatingActor miembro(Tenant tenant, String rol) {
		String email = rol.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@ejemplo.test";
		jdbc.update("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
		long cuentaId = jdbc.queryForObject(
				"SELECT id FROM cuenta WHERE email_normalizado = ?", Long.class, email);
		jdbc.update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, ?, 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", tenant.organizationId(), tenant.consultorioId(), cuentaId, rol);
		return new OperatingActor(cuentaId, false, tenant.organizationId(), tenant.consultorioId());
	}
}
