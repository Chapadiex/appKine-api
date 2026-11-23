package com.akine.organization;

import com.akine.TestcontainersConfiguration;
import com.akine.identity.application.AltaDeCentroPlataformaService;
import com.akine.organization.application.ContratacionService;
import com.akine.organization.application.ContratacionView;
import com.akine.organization.application.FuncionesIaService;
import com.akine.organization.application.SolicitudDePruebaService;
import com.akine.organization.domain.SolicitudDePrueba;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.AltaDeCentro;
import com.akine.organization.spi.FuncionIa;
import com.akine.organization.spi.Servicio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contratacion y prueba gratis contra la base real (18/11/2026, docs/diseno/AKINE-landing-y-ofertas.md):
 * del pedido de la landing al centro en prueba con su duenio invitado, el vencimiento leido como
 * solo lectura y el paso a contrato.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class ContratacionYPruebaIT {

	/** El PLATFORM_ADMIN sembrado por las migraciones. */
	private static final long PLATAFORMA = 1L;

	@Autowired private SolicitudDePruebaService solicitudes;
	@Autowired private AltaDeCentroPlataformaService alta;
	@Autowired private ContratacionService contratacion;
	@Autowired private FuncionesIaService funcionesIa;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private com.akine.identity.application.AvisosDePruebaGratis avisos;

	@Test
	@DisplayName("el pedido de la landing se aprueba con el alta: centro en prueba, solo lo contratado y el duenio invitado")
	void del_pedido_al_centro_en_prueba() {
		String email = "duenia-" + UUID.randomUUID().toString().substring(0, 8) + "@centro.test";
		String clave = UUID.randomUUID().toString();
		solicitudes.recibir(pedido(email, null), clave);
		long solicitudId = jdbc.queryForObject(
				"SELECT id FROM solicitud_de_prueba WHERE idempotency_key = ?", Long.class, clave);

		AltaDeCentroPlataformaService.Resultado resultado = alta.darDeAlta(new AltaDeCentro.Comando(
				"Estudio Sintetico", "Sede Norte", null, "BASE",
				EnumSet.of(Servicio.ACTIVIDADES), EnumSet.noneOf(Adicional.class), true, solicitudId, PLATAFORMA),
				email);

		long org = resultado.organizationId();
		ContratacionView vista = contratacion.ver(org);
		assertThat(vista.estado()).isEqualTo(SubscriptionStatus.PRUEBA);
		assertThat(vista.pruebaHasta()).isNotNull();
		assertThat(vista.soloLectura()).isFalse();
		assertThat(vista.servicios()).containsExactly(Servicio.ACTIVIDADES);
		assertThat(vista.adicionales()).isEmpty();
		assertThat(vista.maxSedes()).isEqualTo(1);
		assertThat(contratacion.tieneServicio(org, Servicio.KINESIOLOGIA)).isFalse();
		assertThat(funcionesIa.prendida(org, FuncionIa.ASISTENTE)).as("sin el adicional no hay IA").isFalse();

		assertThat(jdbc.queryForObject("""
				SELECT role_code FROM colaborador_invitacion WHERE organization_id = ? AND email_normalizado = ?""",
				String.class, org, email)).isEqualTo("ORG_ADMIN");
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM membership WHERE organization_id = ?", Integer.class, org))
				.as("la membership nace al aceptar la invitacion").isZero();
		assertThat(jdbc.queryForObject("SELECT estado FROM solicitud_de_prueba WHERE id = ?", String.class, solicitudId))
				.isEqualTo("APROBADA");

		assertThatThrownBy(() -> solicitudes.descartar(solicitudId, "tarde", 1, PLATAFORMA))
				.as("un pedido resuelto no se vuelve a resolver")
				.isInstanceOf(OptimisticLockingFailureException.class);
	}

	@Test
	@DisplayName("la prueba vencida se lee como solo lectura, y contratar la pasa a ACTIVA sin fin de prueba")
	void vencimiento_y_contrato() {
		long org = altaEnPrueba();
		jdbc.update("UPDATE subscription SET prueba_hasta = now() - interval '1 day' WHERE organization_id = ?", org);

		ContratacionView vencida = contratacion.ver(org);
		assertThat(vencida.estado()).isEqualTo(SubscriptionStatus.PRUEBA);
		assertThat(vencida.soloLectura()).isTrue();

		ContratacionView contratada = contratacion.cambiar(org, "CRECIMIENTO",
				EnumSet.allOf(Servicio.class), EnumSet.of(Adicional.IA), true, vencida.version(), PLATAFORMA);

		assertThat(contratada.estado()).isEqualTo(SubscriptionStatus.ACTIVA);
		assertThat(contratada.pruebaHasta()).isNull();
		assertThat(contratada.soloLectura()).isFalse();
		assertThat(contratada.maxSedes()).isEqualTo(3);
		assertThat(funcionesIa.iaContratada(org)).isTrue();
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM subscription_transition
				 WHERE organization_id = ? AND from_status = 'PRUEBA' AND to_status = 'ACTIVA'""",
				Integer.class, org)).isEqualTo(1);

		assertThatThrownBy(() -> contratacion.cambiar(org, "BASE", Set.of(Servicio.KINESIOLOGIA), Set.of(),
				false, vencida.version(), PLATAFORMA))
				.as("version vieja")
				.isInstanceOf(OptimisticLockingFailureException.class);
	}

	@Test
	@DisplayName("descartar un pedido deja el motivo, saca de pendientes y no admite version vieja ni un segundo descarte")
	void descartar_pedido() {
		String email = "descarta-" + UUID.randomUUID().toString().substring(0, 8) + "@centro.test";
		solicitudes.recibir(pedido(email, null), UUID.randomUUID().toString());
		long id = jdbc.queryForObject("SELECT id FROM solicitud_de_prueba WHERE email = ?", Long.class, email);
		long pendientesAntes = solicitudes.pendientes();
		long version = solicitudes.ver(id).version();

		assertThatThrownBy(() -> solicitudes.descartar(id, "Duplicado", version + 1, PLATAFORMA))
				.as("version vieja")
				.isInstanceOf(OptimisticLockingFailureException.class);

		var descartada = solicitudes.descartar(id, "  Duplicado  ", version, PLATAFORMA);

		assertThat(descartada.estado()).isEqualTo(SolicitudDePrueba.Estado.DESCARTADA);
		assertThat(descartada.motivoDescarte()).isEqualTo("Duplicado");
		assertThat(descartada.resueltaEn()).isNotNull();
		assertThat(solicitudes.pendientes()).isEqualTo(pendientesAntes - 1);
		assertThatThrownBy(() -> solicitudes.descartar(id, "Otra vez", descartada.version(), PLATAFORMA))
				.as("ya resuelta")
				.isInstanceOf(OptimisticLockingFailureException.class);
		assertThatThrownBy(() -> solicitudes.ver(Long.MAX_VALUE))
				.isInstanceOf(com.akine.organization.domain.exception.SolicitudDePruebaNotFoundException.class);
	}

	@Test
	@DisplayName("el pedido publico no se duplica al reintentar, y el del campo trampa no se guarda")
	void pedido_publico() {
		String clave = UUID.randomUUID().toString();
		String email = "pide-" + UUID.randomUUID().toString().substring(0, 8) + "@centro.test";

		solicitudes.recibir(pedido(email, null), clave);
		solicitudes.recibir(pedido(email, null), clave);
		solicitudes.recibir(pedido(email, "https://spam.test"), UUID.randomUUID().toString());

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM solicitud_de_prueba WHERE email = ?", Integer.class, email))
				.isEqualTo(1);
		assertThat(solicitudes.listar(SolicitudDePrueba.Estado.PENDIENTE, email,
				org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);


		String otro = "red-" + UUID.randomUUID().toString().substring(0, 8) + "@centro.test";
		solicitudes.recibir(new SolicitudDePruebaService.Pedido("Ana", "Sintetica", otro, null, "Red Sintetica",
				null, true, true, SolicitudDePrueba.Sedes.MAS_DE_TRES, true, null, null, null, null), UUID.randomUUID().toString());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM solicitud_de_prueba WHERE email = ?", Integer.class, otro))
				.isEqualTo(1);

		assertThatThrownBy(() -> solicitudes.recibir(pedido(email, null), " "))
				.as("sin clave de idempotencia")
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> solicitudes.recibir(new SolicitudDePruebaService.Pedido("Ana", "Sintetica",
				"nada-" + UUID.randomUUID().toString().substring(0, 8) + "@centro.test", null, "Sin servicio", null,
				false, false, SolicitudDePrueba.Sedes.UNA, false, null, null, null, null), UUID.randomUUID().toString()))
				.as("sin ningun servicio")
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("un centro creado por el camino de siempre conserva todo: los dos servicios y la IA")
	void lo_de_siempre_conserva_todo() {
		String slug = "legado-" + UUID.randomUUID().toString().substring(0, 8);
		long org = jdbc.queryForObject("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				RETURNING id""", Long.class, "Centro sintetico " + slug, slug);

		assertThat(contratacion.tieneServicio(org, Servicio.KINESIOLOGIA)).isTrue();
		assertThat(contratacion.tieneServicio(org, Servicio.ACTIVIDADES)).isTrue();
		assertThat(funcionesIa.iaContratada(org)).isTrue();
	}

	@Test
	@DisplayName("un pedido de la landing le avisa por correo a la plataforma")
	void el_pedido_avisa_a_la_plataforma() {
		String clave = UUID.randomUUID().toString();
		solicitudes.recibir(pedido("aviso-" + UUID.randomUUID().toString().substring(0, 8) + "@centro.test", null),
				clave);
		long solicitudId = jdbc.queryForObject(
				"SELECT id FROM solicitud_de_prueba WHERE idempotency_key = ?", Long.class, clave);

		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM notification_outbox WHERE tipo = 'PEDIDO_DE_PRUEBA_RECIBIDO' AND clave_idempotente LIKE ?",
				Integer.class, "pedido-prueba-" + solicitudId + "-%"))
				.as("uno por administrador de plataforma, y hay al menos el sembrado").isPositive();
	}

	@Test
	@DisplayName("al duenio se le avisa antes del fin y cuando termina, una sola vez cada aviso")
	void avisos_al_duenio() {
		long porVencer = altaEnPrueba();
		long vencida = altaEnPrueba();
		duenio(porVencer, PLATAFORMA);
		duenio(vencida, PLATAFORMA);
		jdbc.update("UPDATE subscription SET prueba_hasta = now() + interval '3 days' WHERE organization_id = ?", porVencer);
		jdbc.update("UPDATE subscription SET prueba_hasta = now() - interval '1 day' WHERE organization_id = ?", vencida);

		avisos.avisarVencimientos(java.time.Instant.now());
		avisos.avisarVencimientos(java.time.Instant.now());

		assertThat(avisosDe(porVencer, "PRUEBA_POR_VENCER")).isEqualTo(1);
		assertThat(avisosDe(vencida, "PRUEBA_VENCIDA")).isEqualTo(1);
		assertThat(avisosDe(porVencer, "PRUEBA_VENCIDA")).isZero();
	}

	private int avisosDe(long org, String tipo) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE organization_id = ? AND tipo = ?",
				Integer.class, org, tipo);
	}

	private void duenio(long org, long cuenta) {
		jdbc.update("""
				INSERT INTO membership (organization_id, account_id, role_code, valid_from, created_at, updated_at)
				VALUES (?, ?, 'ORG_ADMIN', now() - interval '1 day', now(), now())""", org, cuenta);
	}

	private long altaEnPrueba() {
		String email = "otro-" + UUID.randomUUID().toString().substring(0, 8) + "@centro.test";
		return alta.darDeAlta(new AltaDeCentro.Comando("Centro Sintetico", null, null, "BASE",
				EnumSet.of(Servicio.KINESIOLOGIA), EnumSet.noneOf(Adicional.class), true, null, PLATAFORMA),
				email).organizationId();
	}

	private static SolicitudDePruebaService.Pedido pedido(String email, String trampa) {
		return new SolicitudDePruebaService.Pedido("Ana", "Sintetica", email, null, "Estudio Sintetico",
				"Cordoba", false, true, SolicitudDePrueba.Sedes.UNA, false, null, trampa, null, null);
	}
}
