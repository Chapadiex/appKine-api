package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.PresentacionItFixture.Desenlace;
import com.akine.billing.PresentacionItFixture.Tenant;
import com.akine.billing.application.PresentacionService;
import com.akine.billing.application.PresentacionView;
import com.akine.billing.domain.exception.ObligacionYaPresentadaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escenario 37 de {@code docs/tests-diferidos.md}: RN-M21-003 bajo concurrencia real.
 *
 * <p>"Una prestacion no debe duplicarse en presentaciones incompatibles". La consulta previa
 * {@code findVivoDeLaObligacion} tiene una ventana entre leer y escribir: dos administrativos que
 * agregan la misma obligacion a dos lotes distintos a la vez leen los dos "no esta en ningun lote"
 * y los dos insertan. Lo que decide es el unique {@code uk_presentacion_item_ocupa} sobre la columna
 * GENERADA {@code ocupa_marca} de {@code V56} — y eso solo existe en el motor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PresentacionItemConcurrenteIT {

	@Autowired private PresentacionService presentacionService;
	@Autowired private JdbcTemplate jdbc;

	private PresentacionItFixture fixture;

	@BeforeEach
	void armar() {
		fixture = new PresentacionItFixture(jdbc, presentacionService);
	}

	@Test
	@DisplayName("la misma obligacion a dos lotes a la vez: entra en uno y el otro recibe obligacion-ya-presentada")
	void la_misma_obligacion_entra_en_un_solo_lote() {
		Tenant tenant = fixture.crearTenant();
		long obligacionId = fixture.obligacionDeFinanciador(tenant, tenant.financiadorId(), "8500.00");
		PresentacionView loteA = fixture.borrador(tenant, tenant.financiadorId());
		PresentacionView loteB = fixture.borrador(tenant, tenant.financiadorId());

		List<Desenlace> desenlaces = PresentacionItFixture.enParalelo(List.<Callable<?>>of(
				() -> agregar(tenant, loteA.id(), obligacionId),
				() -> agregar(tenant, loteB.id(), obligacionId)));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("exactamente uno entra. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(desenlaces.stream().filter(d -> d.falloPor(ObligacionYaPresentadaException.class)).count())
				.as("el otro recibe el 409 obligacion-ya-presentada, no un choque de constraint "
						+ "generico ni un 500. Desenlaces: %s", desenlaces)
				.isEqualTo(1);

		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM presentacion_item
				 WHERE organization_id = ? AND obligacion_id = ? AND ocupa_marca = 1
				""", Integer.class, tenant.organizationId(), obligacionId))
				.as("una sola fila viva ocupa la obligacion")
				.isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM presentacion_item WHERE obligacion_id = ?
				""", Integer.class, obligacionId))
				.as("y el perdedor no dejo una fila huerfana: su transaccion se revirtio entera")
				.isEqualTo(1);

		BigDecimal totalA = totalPresentado(loteA.id());
		BigDecimal totalB = totalPresentado(loteB.id());
		assertThat(totalA.add(totalB))
				.as("el importe se reclama una sola vez entre los dos lotes")
				.isEqualByComparingTo("8500.00");
	}

	@Test
	@DisplayName("dos obligaciones distintas al mismo lote a la vez entran las dos")
	void dos_obligaciones_distintas_al_mismo_lote_entran_las_dos() {
		// El control negativo que 02.07 dejo como leccion: el mecanismo que impide el duplicado no
		// puede impedir tambien lo que es legitimo. Dos administrativos armando el mismo lote a la
		// vez con prestaciones distintas no compiten por nada.
		Tenant tenant = fixture.crearTenant();
		long primera = fixture.obligacionDeFinanciador(tenant, tenant.financiadorId(), "8500.00");
		long segunda = fixture.obligacionDeFinanciador(tenant, tenant.financiadorId(), "6000.00");
		PresentacionView lote = fixture.borrador(tenant, tenant.financiadorId());

		List<Desenlace> desenlaces = PresentacionItFixture.enParalelo(List.<Callable<?>>of(
				() -> agregar(tenant, lote.id(), primera),
				() -> agregar(tenant, lote.id(), segunda)));

		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("las dos entran. Desenlaces: %s", desenlaces)
				.isEmpty();
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM presentacion_item WHERE presentacion_id = ? AND ocupa_marca = 1",
				Integer.class, lote.id()))
				.isEqualTo(2);

		// Antes del arreglo, el segundo en commitear moria en ObjectOptimisticLockingFailureException:
		// las dos transacciones cargaban la presentacion con la misma @Version para recalcular el
		// total. Ahora se serializan por el lock de fila, y la segunda suma viendo el item de la
		// primera: el total del BORRADOR tiene que cerrar, no solo el del confirmado.
		assertThat(totalPresentado(lote.id())).isEqualByComparingTo("14500.00");

		PresentacionView confirmado =
				presentacionService.confirmar(tenant.actor(), tenant.consultorioId(), lote.id());
		assertThat(confirmado.totalPresentado()).isEqualByComparingTo("14500.00");
		assertThat(confirmado.saldo()).isEqualByComparingTo("14500.00");
	}

	// =================================================================================

	private Object agregar(Tenant tenant, long presentacionId, long obligacionId) {
		return presentacionService.agregarItem(
				tenant.actor(), tenant.consultorioId(), presentacionId, obligacionId);
	}

	private BigDecimal totalPresentado(long presentacionId) {
		return jdbc.queryForObject(
				"SELECT total_presentado FROM presentacion WHERE id = ?", BigDecimal.class, presentacionId);
	}
}
