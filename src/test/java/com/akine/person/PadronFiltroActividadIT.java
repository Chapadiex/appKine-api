package com.akine.person;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.application.InscripcionService;
import com.akine.organization.application.PermissionEvaluatorService;
import com.akine.organization.spi.PermissionDecision;
import com.akine.person.application.ActividadFiltro;
import com.akine.person.application.OperatingActor;
import com.akine.person.application.PerfilFiltro;
import com.akine.person.application.PersonaBusqueda;
import com.akine.person.application.PersonaPagina;
import com.akine.person.application.PersonaService;
import com.akine.person.application.PersonaView;
import com.akine.testsupport.InsertarConId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Filtro por actividades del padron contra MySQL (10/11/2026).
 *
 * <p>Prueba el SQL: que una inscripcion viva a una clase futura y un pase vigente cuenten, y que
 * un pase vencido no. El {@code EXISTS} mira tablas de {@code activity} desde {@code person}, asi
 * que un nombre de columna equivocado solo aparece ejecutando.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PadronFiltroActividadIT {

	@Autowired private PersonaService personaService;
	@Autowired private InscripcionService inscripciones;
	@Autowired private JdbcTemplate jdbc;

	/** Prueba la consulta, no la autorizacion: mismo criterio que {@code PersonaBusquedaAfiliadoIT}. */
	@MockitoSpyBean private PermissionEvaluatorService permisos;

	private ActividadItFixture datos;

	@BeforeEach
	void setUp() {
		datos = new ActividadItFixture(jdbc, inscripciones);
		org.mockito.Mockito.doReturn(PermissionDecision.concedida("ORGANIZACION", false))
				.when(permisos).requirePermission(org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("Con actividad: inscripcion a una clase futura o pase vigente; sin: el resto")
	void separa_a_quien_hace_actividades() {
		Tenant tenant = datos.crearTenant(10, 4);
		long inscripta = tenant.persona(0);
		long conPase = tenant.persona(1);
		long conPaseVencido = tenant.persona(2);
		long sinNada = tenant.persona(3);

		long clase = datos.programarClase(tenant, 10, 2);
		datos.inscribir(tenant, clase, inscripta, false);

		long producto = producto(tenant);
		pase(tenant, producto, conPase, "CURRENT_DATE - INTERVAL '5 days'", "CURRENT_DATE + INTERVAL '25 days'");
		pase(tenant, producto, conPaseVencido, "CURRENT_DATE - INTERVAL '40 days'",
				"CURRENT_DATE - INTERVAL '10 days'");

		assertThat(ids(tenant, ActividadFiltro.CON_ACTIVIDAD))
				.containsExactlyInAnyOrder(inscripta, conPase);
		assertThat(ids(tenant, ActividadFiltro.SIN_ACTIVIDAD))
				.containsExactlyInAnyOrder(conPaseVencido, sinNada);
		assertThat(ids(tenant, null)).hasSize(4);
		assertThat(contar(tenant, ActividadFiltro.CON_ACTIVIDAD)).isEqualTo(2);
	}

	@Test
	@DisplayName("Se combina con el perfil: ningun inscripto es paciente, asi que la interseccion es vacia")
	void se_combina_con_el_perfil() {
		Tenant tenant = datos.crearTenant(10, 2);
		long clase = datos.programarClase(tenant, 10, 3);
		datos.inscribir(tenant, clase, tenant.persona(0), false);

		assertThat(buscar(tenant, PerfilFiltro.CON_PERFIL, ActividadFiltro.CON_ACTIVIDAD)
				.contenido()).isEmpty();
		assertThat(buscar(tenant, PerfilFiltro.SIN_PERFIL, ActividadFiltro.CON_ACTIVIDAD)
				.contenido()).extracting(PersonaView::id).containsExactly(tenant.persona(0));
	}

	private long producto(Tenant tenant) {
		return InsertarConId.insertar(jdbc, """
				INSERT INTO producto_servicio (organization_id, consultorio_id, oferta_id, nombre, tipo,
				                               creditos, precio, moneda, vigencia_dias, vigencia_desde)
				VALUES (?, ?, ?, 'Pack sintetico', 'PACK', 8, 1000.00, 'ARS', 30, CURRENT_DATE - INTERVAL '60 days')
				""", tenant.organizationId(), tenant.consultorioId(), tenant.ofertaId());
	}

	private void pase(Tenant tenant, long producto, long persona, String desde, String hasta) {
		jdbc.update("""
				INSERT INTO pase_servicio (organization_id, consultorio_id, persona_id, producto_id,
				                           snapshot_nombre, snapshot_precio, snapshot_moneda,
				                           snapshot_creditos, snapshot_oferta_id, creditos_iniciales,
				                           creditos_disponibles, vigencia_desde, vigencia_hasta, estado,
				                           vendido_por_cuenta_id, vendido_en)
				VALUES (?, ?, ?, ?, 'Pack sintetico', 1000.00, 'ARS', 8, ?, 8, 8, %s, %s, 'ACTIVO',
				        ?, CURRENT_TIMESTAMP)
				""".formatted(desde, hasta),
				tenant.organizationId(), tenant.consultorioId(), persona, producto,
				tenant.ofertaId(), tenant.actor().accountId());
	}

	private List<Long> ids(Tenant tenant, ActividadFiltro actividad) {
		return buscar(tenant, null, actividad).contenido().stream().map(PersonaView::id).toList();
	}

	private long contar(Tenant tenant, ActividadFiltro actividad) {
		return buscar(tenant, null, actividad).total();
	}

	private PersonaPagina buscar(Tenant tenant, PerfilFiltro perfil, ActividadFiltro actividad) {
		OperatingActor actor = new OperatingActor(1L, false, tenant.organizationId(), null);
		return personaService.buscar(
				actor, new PersonaBusqueda(null, null, perfil, "", actividad), 0, 50);
	}
}
