package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.PresentacionItFixture.Desenlace;
import com.akine.billing.PresentacionItFixture.Tenant;
import com.akine.billing.application.PresentacionService;
import com.akine.billing.application.PresentacionView;
import com.akine.billing.application.PresentacionCommands;
import com.akine.billing.domain.exception.PresentacionConHallazgosException;
import com.akine.billing.domain.exception.PresentacionVaciaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Escenario 39 de {@code docs/tests-diferidos.md}: el correlativo del lote, sin huecos ni
 * repeticiones.
 *
 * <p>{@code PresentacionNumeradorIniciador} replica un patron que este repositorio ya pago cuatro
 * veces: la fila del numerador se crea en una transaccion aparte con
 * {@code INSERT ... ON DUPLICATE KEY UPDATE}, y el numero se toma con
 * {@code UPDATE ... ultimo_numero + 1}. "Replica un patron" no es evidencia: la evidencia es una
 * rafaga de confirmaciones concurrentes sobre una sede <b>sin fila de numerador</b>, que es donde
 * aparece el deadlock del lazy-create.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PresentacionNumeradorConcurrenteIT {

	private static final int RAFAGA = 5;

	@Autowired private PresentacionService presentacionService;
	@Autowired private JdbcTemplate jdbc;

	private PresentacionItFixture fixture;

	@BeforeEach
	void armar() {
		fixture = new PresentacionItFixture(jdbc, presentacionService);
	}

	@Test
	@DisplayName("cinco confirmaciones concurrentes sin fila de numerador: 1..5, y un lote que falla no consume numero")
	void rafaga_sin_huecos_ni_repeticiones() {
		Tenant tenant = fixture.crearTenant();
		assertThat(filasDeNumerador(tenant, tenant.financiadorId()))
				.as("precondicion: la sede todavia no tiene numerador para este financiador")
				.isZero();

		List<Long> lotes = new ArrayList<>();
		for (int i = 0; i < RAFAGA; i++) {
			lotes.add(loteListo(tenant, tenant.financiadorId()).id());
		}

		List<Callable<?>> confirmaciones = new ArrayList<>();
		for (long lote : lotes) {
			confirmaciones.add(() -> confirmar(tenant, lote));
		}
		List<Desenlace> desenlaces = PresentacionItFixture.enParalelo(confirmaciones);

		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("ninguna confirmacion muere: ni por deadlock del lazy-create ni por "
						+ "UnexpectedRollbackException. Desenlaces: %s", desenlaces)
				.isEmpty();
		assertThat(numerosDe(tenant, tenant.financiadorId()))
				.as("uno a cinco, sin huecos ni repetidos")
				.containsExactly(1, 2, 3, 4, 5);
		assertThat(filasDeNumerador(tenant, tenant.financiadorId()))
				.as("y una sola fila de numerador, aunque las cinco intentaron crearla")
				.isEqualTo(1);

		// Un lote VACIO y uno CON HALLAZGOS se rechazan antes de pedir numero: el numero de la
		// serie no se puede quemar, porque despues el hueco no se puede explicar.
		long vacio = fixture.borrador(tenant, tenant.financiadorId()).id();
		assertThatThrownBy(() -> confirmar(tenant, vacio))
				.isInstanceOf(PresentacionVaciaException.class);

		long fueraDelPeriodo = loteDelAnioPasado(tenant);
		assertThatThrownBy(() -> confirmar(tenant, fueraDelPeriodo))
				.isInstanceOf(PresentacionConHallazgosException.class);

		assertThat(ultimoNumero(tenant, tenant.financiadorId()))
				.as("los dos rechazos no tocaron el numerador")
				.isEqualTo(5);

		PresentacionView sexto = confirmar(tenant, loteListo(tenant, tenant.financiadorId()).id());
		assertThat(sexto.numero()).as("el siguiente lote valido es el 6, no el 7 ni el 8").isEqualTo(6);
		assertThat(numerosDe(tenant, tenant.financiadorId())).containsExactly(1, 2, 3, 4, 5, 6);
	}

	@Test
	@DisplayName("dos financiadores de la misma sede, confirmando a la vez, numeran cada uno su serie")
	void dos_financiadores_numeran_independientes() {
		Tenant tenant = fixture.crearTenant();
		long otroFinanciador = fixture.insertarFinanciador(
				tenant.organizationId(), UUID.randomUUID().toString().substring(0, 8));

		List<Callable<?>> confirmaciones = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			long deUno = loteListo(tenant, tenant.financiadorId()).id();
			long deOtro = loteListo(tenant, otroFinanciador).id();
			confirmaciones.add(() -> confirmar(tenant, deUno));
			confirmaciones.add(() -> confirmar(tenant, deOtro));
		}
		List<Desenlace> desenlaces = PresentacionItFixture.enParalelo(confirmaciones);

		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("Desenlaces: %s", desenlaces)
				.isEmpty();
		assertThat(numerosDe(tenant, tenant.financiadorId())).containsExactly(1, 2, 3);
		assertThat(numerosDe(tenant, otroFinanciador))
				.as("cada obra social recibe su propia serie, que arranca en uno")
				.containsExactly(1, 2, 3);
	}

	// =================================================================================

	private PresentacionView confirmar(Tenant tenant, long presentacionId) {
		return presentacionService.confirmar(tenant.actor(), tenant.consultorioId(), presentacionId);
	}

	/** Un borrador con una prestacion propia, listo para confirmar. */
	private PresentacionView loteListo(Tenant tenant, long financiadorId) {
		long obligacionId = fixture.obligacionDeFinanciador(tenant, financiadorId, "8500.00");
		return fixture.borradorCon(tenant, financiadorId, List.of(obligacionId));
	}

	/** Una prestacion de hoy en un lote del anio pasado: FUERA_DEL_PERIODO al confirmar. */
	private long loteDelAnioPasado(Tenant tenant) {
		long obligacionId = fixture.obligacionDeFinanciador(tenant, tenant.financiadorId(), "8500.00");
		LocalDate haceUnAnio = LocalDate.now().minusYears(1);
		return presentacionService.crear(tenant.actor(), tenant.consultorioId(),
				new PresentacionCommands.Alta(tenant.financiadorId(), haceUnAnio.withDayOfMonth(1),
						haceUnAnio.withDayOfMonth(1).plusDays(27), PresentacionItFixture.MONEDA,
						List.of(obligacionId)))
				.id();
	}

	private List<Integer> numerosDe(Tenant tenant, long financiadorId) {
		return jdbc.queryForList("""
				SELECT numero FROM presentacion
				 WHERE organization_id = ? AND consultorio_id = ? AND financiador_id = ?
				   AND numero IS NOT NULL
				 ORDER BY numero
				""", Integer.class, tenant.organizationId(), tenant.consultorioId(), financiadorId);
	}

	private int filasDeNumerador(Tenant tenant, long financiadorId) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM presentacion_numerador
				 WHERE organization_id = ? AND consultorio_id = ? AND financiador_id = ?
				""", Integer.class, tenant.organizationId(), tenant.consultorioId(), financiadorId);
	}

	private int ultimoNumero(Tenant tenant, long financiadorId) {
		return jdbc.queryForObject("""
				SELECT ultimo_numero FROM presentacion_numerador
				 WHERE organization_id = ? AND consultorio_id = ? AND financiador_id = ?
				""", Integer.class, tenant.organizationId(), tenant.consultorioId(), financiadorId);
	}
}
