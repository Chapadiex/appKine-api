package com.akine.organization;

import com.akine.TestcontainersConfiguration;
import com.akine.identity.application.AltaDeCentroPlataformaService;
import com.akine.identity.application.CuentasDePlataformaService;
import com.akine.organization.application.PanelDePlataformaService;
import com.akine.organization.application.SolicitudDePruebaService;
import com.akine.organization.domain.PanelDePlataforma;
import com.akine.organization.domain.SolicitudDePrueba;
import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.AltaDeCentro;
import com.akine.organization.spi.Servicio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Panel de la consola de plataforma contra la base real (19/11/2026): los conteos se mueven con
 * lo que pasa, y las pruebas vencidas aparecen primero. La base es compartida con otros tests de la
 * clase, asi que se mide la diferencia y no el valor absoluto.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PanelDePlataformaIT {

	/** El PLATFORM_ADMIN sembrado por las migraciones. */
	private static final long PLATAFORMA = 1L;

	@Autowired private PanelDePlataformaService panel;
	@Autowired private CuentasDePlataformaService cuentas;
	@Autowired private AltaDeCentroPlataformaService alta;
	@Autowired private SolicitudDePruebaService solicitudes;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("un centro en prueba suma, se lee por vencer y vencido, y es el ultimo dado de alta")
	void centros_en_prueba() {
		PanelDePlataforma antes = panel.leer();

		long porVencer = altaEnPrueba("Centro Por Vencer");
		long vencido = altaEnPrueba("Centro Vencido");
		jdbc.update("UPDATE subscription SET prueba_hasta = now() + interval '3 days' WHERE organization_id = ?",
				porVencer);
		jdbc.update("UPDATE subscription SET prueba_hasta = now() - interval '1 day' WHERE organization_id = ?",
				vencido);

		PanelDePlataforma despues = panel.leer();

		assertThat(despues.centros().total()).isEqualTo(antes.centros().total() + 2);
		assertThat(despues.centros().enPrueba()).as("el vencido ya no esta en prueba vigente")
				.isEqualTo(antes.centros().enPrueba() + 1);
		assertThat(despues.centros().pruebaPorVencer()).isEqualTo(antes.centros().pruebaPorVencer() + 1);
		assertThat(despues.centros().pruebaVencida()).isEqualTo(antes.centros().pruebaVencida() + 1);
		assertThat(despues.pruebasQueTerminan())
				.extracting(PanelDePlataforma.PruebaQueTermina::organizationId)
				.contains(vencido);
		assertThat(despues.ultimosCentros().getFirst().organizationId()).isEqualTo(vencido);
		assertThat(despues.ultimosCentros().getFirst().estado()).isEqualTo("PRUEBA");
		assertThat(despues.ultimosCambios()).isNotNull();
	}

	@Test
	@DisplayName("un pedido de la landing suma a pendientes y encabeza los ultimos pedidos")
	void pedido_pendiente() {
		long antes = panel.leer().pedidosPendientes();

		solicitudes.recibir(new SolicitudDePruebaService.Pedido("Ana", "Panel", "panel-"
				+ UUID.randomUUID().toString().substring(0, 8) + "@centro.test", null, "Estudio Del Panel", null,
				true, false, SolicitudDePrueba.Sedes.UNA, false, null, null), UUID.randomUUID().toString());

		PanelDePlataforma despues = panel.leer();
		assertThat(despues.pedidosPendientes()).isEqualTo(antes + 1);
		assertThat(despues.ultimosPedidos().getFirst().nombreCentro()).isEqualTo("Estudio Del Panel");
		assertThat(despues.ultimosPedidos().getFirst().persona()).isEqualTo("Ana Panel");
	}

	@Test
	@DisplayName("las cuentas se cuentan por estado y una dada de baja no cuenta")
	void cuentas_por_estado() {
		CuentasDePlataformaService.Resumen resumen = cuentas.resumen();

		assertThat(resumen.activas()).isEqualTo(jdbc.queryForObject(
				"SELECT COUNT(*) FROM cuenta WHERE estado = 'ACTIVA' AND deleted_at IS NULL", Long.class));
		assertThat(resumen.pendientesDeActivar()).isEqualTo(jdbc.queryForObject(
				"SELECT COUNT(*) FROM cuenta WHERE estado = 'PENDIENTE_ACTIVACION' AND deleted_at IS NULL",
				Long.class));
		assertThat(resumen.bloqueadas()).isEqualTo(jdbc.queryForObject(
				"SELECT COUNT(*) FROM cuenta WHERE estado = 'BLOQUEADA' AND deleted_at IS NULL", Long.class));
	}

	private long altaEnPrueba(String nombre) {
		String email = "panel-" + UUID.randomUUID().toString().substring(0, 8) + "@centro.test";
		return alta.darDeAlta(new AltaDeCentro.Comando(nombre, null, null, "BASE",
				EnumSet.of(Servicio.KINESIOLOGIA), EnumSet.noneOf(Adicional.class), true, null, PLATAFORMA),
				email).organizationId();
	}
}
