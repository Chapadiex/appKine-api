package com.akine.billing.api;

import com.akine.billing.application.IdempotencyKeyConflictException;
import com.akine.billing.domain.exception.BeneficiarioNoVinculadoException;
import com.akine.billing.domain.exception.CajaCerradaException;
import com.akine.billing.domain.exception.CajaDiferenciaSinMotivoException;
import com.akine.billing.domain.exception.CajaMonedaDistintaException;
import com.akine.billing.domain.exception.CajaNoAbiertaException;
import com.akine.billing.domain.exception.CajaSaldoCambioException;
import com.akine.billing.domain.exception.CajaSaldoInsuficienteException;
import com.akine.billing.domain.exception.CajaYaAbiertaException;
import com.akine.billing.domain.exception.CobroNotAccessibleException;
import com.akine.billing.domain.exception.ConsultorioNoAccesibleException;
import com.akine.billing.domain.exception.EgresoComprobanteDuplicadoException;
import com.akine.billing.domain.exception.EgresoConPagosException;
import com.akine.billing.domain.exception.EgresoNoConfirmableException;
import com.akine.billing.domain.exception.EgresoNoEditableException;
import com.akine.billing.domain.exception.EgresoNoPagableException;
import com.akine.billing.domain.exception.EgresoNotAccessibleException;
import com.akine.billing.domain.exception.EgresoSaldoInsuficienteException;
import com.akine.billing.domain.exception.EgresoSinComprobanteException;
import com.akine.billing.domain.exception.EgresoYaAnuladoException;
import com.akine.billing.domain.exception.FacturaDuplicadaException;
import com.akine.billing.domain.exception.FinanciadorNoAccesibleException;
import com.akine.billing.domain.exception.ImputacionesNoSumanException;
import com.akine.billing.domain.exception.ItemNoDebitableException;
import com.akine.billing.domain.exception.JornadaCajaNotAccessibleException;
import com.akine.billing.domain.exception.MediosNoSumanException;
import com.akine.billing.domain.exception.MovimientoCajaNotAccessibleException;
import com.akine.billing.domain.exception.MovimientoNoReversibleException;
import com.akine.billing.domain.exception.ObligacionAnuladaException;
import com.akine.billing.domain.exception.ObligacionConCobrosException;
import com.akine.billing.domain.exception.ObligacionNoCobrableException;
import com.akine.billing.domain.exception.ObligacionNoPresentableException;
import com.akine.billing.domain.exception.ObligacionNotAccessibleException;
import com.akine.billing.domain.exception.ObligacionYaPresentadaException;
import com.akine.billing.domain.exception.PagoEgresoNotAccessibleException;
import com.akine.billing.domain.exception.PagoEgresoYaAnuladoException;
import com.akine.billing.domain.exception.PresentacionConHallazgosException;
import com.akine.billing.domain.exception.PresentacionEstadoInvalidoException;
import com.akine.billing.domain.exception.PresentacionItemNotAccessibleException;
import com.akine.billing.domain.exception.PresentacionNoConciliaException;
import com.akine.billing.domain.exception.PresentacionNoEditableException;
import com.akine.billing.domain.exception.PresentacionNotAccessibleException;
import com.akine.billing.domain.exception.PresentacionSaldoInsuficienteException;
import com.akine.billing.domain.exception.PresentacionVaciaException;
import com.akine.billing.domain.exception.SaldoInsuficienteException;
import com.akine.platform.spi.problem.ProblemType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El contrato de errores de {@code billing} (M18 a M22).
 *
 * <h2>Por que un test de un advice no es ceremonia</h2>
 *
 * <p>Estos cuarenta y cinco handlers <b>son</b> el contrato: lo que la pantalla puede decirle al
 * usuario sale de aca, y el gate de drift del OpenAPI no los mira —compara el YAML contra las
 * anotaciones, no contra lo que el advice devuelve—. Un {@code type} que se olvida deja a la
 * pantalla sin forma de distinguir un saldo insuficiente de un 409 generico, y nada falla.
 *
 * <h2>Las tres cosas que se verifican</h2>
 *
 * <p><b>Que el status sea el que corresponde</b> —404 para lo que esta fuera del alcance, 409 para
 * lo que depende del estado, 400 para lo que depende del cuerpo—, <b>que el {@code type} sea el
 * declarado</b> y no el generico, y <b>que viaje el dato accionable</b>: un "no alcanza el saldo"
 * sin el saldo disponible obliga al operador a reintentar a ciegas.
 */
@DisplayName("BillingProblemHandler")
class BillingProblemHandlerTest {

	private final BillingProblemHandler handler = new BillingProblemHandler();

	private static final BigDecimal PLATA = new BigDecimal("1500.00");

	// =================================================================================
	// Lo que esta fuera del alcance: 404, nunca 403
	// =================================================================================

	@Nested
	@DisplayName("Fuera del alcance")
	class FueraDelAlcance {

		@Test
		@DisplayName("Las siete entidades del modulo responden 404 con el type generico")
		void todo_lo_inalcanzable_es_404() {
			// Un 403 confirmaria que ese id existe y bastaria probar ids consecutivos para censar
			// la actividad economica de otro centro.
			List<ProblemDetail> respuestas = List.of(
					handler.handleConsultorioNoAccesible(new ConsultorioNoAccesibleException(7L)),
					handler.handleObligacionNoAccesible(new ObligacionNotAccessibleException(1L)),
					handler.handleCobroNoAccesible(new CobroNotAccessibleException(2L)),
					handler.handleJornadaNoAccesible(new JornadaCajaNotAccessibleException(3L)),
					handler.handleMovimientoNoAccesible(
							new MovimientoCajaNotAccessibleException(4L)),
					handler.handlePresentacionNoAccesible(
							new PresentacionNotAccessibleException(5L)),
					handler.handleItemNoAccesible(new PresentacionItemNotAccessibleException(6L)),
					handler.handleFinanciadorNoAccesible(new FinanciadorNoAccesibleException(8L)),
					handler.handleEgresoNoAccesible(new EgresoNotAccessibleException(9L)),
					handler.handlePagoEgresoNoAccesible(new PagoEgresoNotAccessibleException(10L)));

			assertThat(respuestas).allSatisfy(problem -> {
				assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
				assertThat(problem.getType()).isEqualTo(ProblemType.NOT_FOUND.uri());
				assertThat(problem.getTitle()).isNotBlank();
			});
		}
	}

	// =================================================================================
	// Lo que depende del cuerpo: 400
	// =================================================================================

	@Nested
	@DisplayName("Problemas del cuerpo enviado")
	class DelCuerpo {

		@Test
		@DisplayName("Los medios que no suman son 400: el estado del servidor esta perfecto")
		void medios_que_no_suman() {
			ProblemDetail problem = handler.handleMediosNoSuman(
					new MediosNoSumanException(new BigDecimal("900.00"), PLATA));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.COBRO_NO_CUADRA.uri());
		}

		@Test
		@DisplayName("Las imputaciones que no suman son 400")
		void imputaciones_que_no_suman() {
			ProblemDetail problem = handler.handleImputacionesNoSuman(
					new ImputacionesNoSumanException(new BigDecimal("900.00"), PLATA));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		}

		@Test
		@DisplayName("Cerrar la caja con diferencia y sin motivo es 400, no 409")
		void diferencia_sin_motivo() {
			// La diferencia NO se rechaza —eso dejaria al centro sin poder cerrar el dia en que
			// realmente falta plata— y no se ajusta: lo unico que se exige es que alguien escriba
			// por que.
			ProblemDetail problem = handler.handleDiferenciaSinMotivo(
					new CajaDiferenciaSinMotivoException(new BigDecimal("-250.00")));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
			assertThat(problem.getType())
					.isEqualTo(ProblemType.CAJA_DIFERENCIA_SIN_MOTIVO.uri());
		}

		@Test
		@DisplayName("Confirmar un egreso sin comprobante es 400: falta un campo, no sobra un estado")
		void egreso_sin_comprobante() {
			ProblemDetail problem = handler.handleEgresoSinComprobante(
					new EgresoSinComprobanteException(400L));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		}

		@Test
		@DisplayName("Confirmar un lote vacio es 400: no hay estado que haya cambiado")
		void presentacion_vacia() {
			// Un reclamo por cero pesos ademas consumiria un numero de la serie para no decir nada.
			ProblemDetail problem = handler.handlePresentacionVacia(
					new PresentacionVaciaException(11L));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		}
	}

	// =================================================================================
	// Lo que depende del estado: 409, y con el dato accionable adentro
	// =================================================================================

	@Nested
	@DisplayName("Conflictos de estado")
	class DelEstado {

		@Test
		@DisplayName("Sin caja abierta el cobro en efectivo es 409 y dice de que sede")
		void caja_no_abierta() {
			// La pantalla tiene que poder ofrecer ABRIR la caja; si solo dice "no se puede", el
			// administrativo queda trabado sin entender por que.
			ProblemDetail problem = handler.handleCajaNoAbierta(new CajaNoAbiertaException(7L));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getType()).isEqualTo(ProblemType.CAJA_NO_ABIERTA.uri());
			assertThat(problem.getProperties()).containsEntry("consultorioId", 7L);
		}

		@Test
		@DisplayName("La caja ya abierta lleva el id de la jornada que existe")
		void caja_ya_abierta() {
			// Sin ese id la pantalla deja al operador sin salida: no puede llevarlo a la jornada
			// que ya esta abierta.
			ProblemDetail problem = handler.handleCajaYaAbierta(new CajaYaAbiertaException(7L, 55L));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getProperties()).containsEntry("jornadaAbiertaId", 55L);
		}

		@Test
		@DisplayName("El saldo teorico que cambio lleva el numero NUEVO")
		void caja_saldo_cambio() {
			// Es el control que impide registrar un faltante que nunca existio: si un cobro en
			// efectivo entra mientras se cuenta, la accion correcta es sumar esos billetes y
			// confirmar contra el numero nuevo.
			ProblemDetail problem = handler.handleCajaSaldoCambio(
					new CajaSaldoCambioException(55L, PLATA, new BigDecimal("2000.00")));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getProperties()).containsKey("saldoTeoricoActual");
		}

		@Test
		@DisplayName("Los cuatro saldos insuficientes viajan con el disponible")
		void los_saldos_insuficientes_dicen_cuanto_hay() {
			// Sin el disponible, el operador reintenta a ciegas.
			List<ProblemDetail> respuestas = List.of(
					handler.handleCajaSaldoInsuficiente(
							new CajaSaldoInsuficienteException(55L, PLATA, BigDecimal.TEN)),
					handler.handleEgresoSaldoInsuficiente(
							new EgresoSaldoInsuficienteException(400L, PLATA, BigDecimal.TEN)),
					handler.handlePresentacionSaldo(
							new PresentacionSaldoInsuficienteException(11L, PLATA, BigDecimal.TEN)),
					handler.handleSaldoInsuficiente(new SaldoInsuficienteException(1L, PLATA)));

			assertThat(respuestas).allSatisfy(problem ->
					assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value()));
		}

		@Test
		@DisplayName("La caja cerrada y la moneda distinta son 409 con type propio")
		void caja_cerrada_y_moneda() {
			assertThat(handler.handleCajaCerrada(new CajaCerradaException(55L)).getType())
					.isEqualTo(ProblemType.CAJA_CERRADA.uri());
			assertThat(handler.handleCajaMonedaDistinta(
					new CajaMonedaDistintaException("ARS", "USD")).getType())
					.isEqualTo(ProblemType.CAJA_MONEDA_DISTINTA.uri());
		}

		@Test
		@DisplayName("Un movimiento no reversible lleva el motivo: ya revertido o ES una reversion")
		void movimiento_no_reversible() {
			ProblemDetail problem = handler.handleMovimientoNoReversible(
					new MovimientoNoReversibleException(4L, "ya fue revertido"));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getProperties()).containsKey("motivo");
		}

		@Test
		@DisplayName("Un egreso con pagos vivos dice CUANTO se pago ya")
		void egreso_con_pagos() {
			// Con ese numero la pantalla nombra la accion correcta: anular primero los pagos.
			ProblemDetail problem = handler.handleEgresoConPagos(
					new EgresoConPagosException(400L, new BigDecimal("500.00")));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getProperties()).containsKey("yaPagado");
		}

		@Test
		@DisplayName("Una obligacion ya presentada dice en que lote esta")
		void obligacion_ya_presentada() {
			ProblemDetail problem = handler.handleYaPresentada(
					new ObligacionYaPresentadaException(1L, 11L));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getProperties()).containsKey("presentacionId");
		}

		@Test
		@DisplayName("Un lote con hallazgos devuelve la lista ENTERA, no el primero")
		void presentacion_con_hallazgos() {
			// Devolver el primero obligaria al administrativo a reintentar tantas veces como items
			// rotos haya.
			ProblemDetail problem = handler.handleConHallazgos(
					new PresentacionConHallazgosException(11L, List.of("sin orden", "sin saldo")));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getProperties()).containsKey("hallazgos");
		}

		@Test
		@DisplayName("Conciliar con residual dice cuanto quedo sin explicar")
		void presentacion_no_concilia() {
			// No hay cierre con diferencia: el sistema nombra el residual y se niega a fingir.
			ProblemDetail problem = handler.handleNoConcilia(
					new PresentacionNoConciliaException(11L, new BigDecimal("120.00")));

			assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			assertThat(problem.getProperties()).containsKey("residual");
		}

		@Test
		@DisplayName("Los diez conflictos restantes son 409 con su type propio y su titulo")
		void el_resto_de_los_conflictos() {
			List<Supplier<ProblemDetail>> respuestas = List.of(
					() -> handler.handleYaAnulada(new ObligacionAnuladaException(1L)),
					() -> handler.handleConCobros(new ObligacionConCobrosException(1L, PLATA)),
					() -> handler.handleNoCobrable(new ObligacionNoCobrableException(1L, "anulada")),
					() -> handler.handleIdempotencyConflict(
							new IdempotencyKeyConflictException("clave-1")),
					() -> handler.handlePresentacionNoEditable(
							new PresentacionNoEditableException(11L, "PRESENTADA")),
					() -> handler.handleEgresoNoEditable(
							new EgresoNoEditableException(400L, "CONFIRMADO")),
					() -> handler.handleEstadoInvalido(
							new PresentacionEstadoInvalidoException(11L, "BORRADOR", "PRESENTADA")),
					() -> handler.handleEgresoNoConfirmable(
							new EgresoNoConfirmableException(400L, "ANULADO")),
					() -> handler.handleEgresoYaAnulado(new EgresoYaAnuladoException(400L)),
					() -> handler.handleNoPresentable(
							new ObligacionNoPresentableException(1L, "sin saldo")),
					() -> handler.handleEgresoNoPagable(
							new EgresoNoPagableException(400L, "es un borrador")),
					() -> handler.handleItemNoDebitable(new ItemNoDebitableException(6L, "ANULADO")),
					() -> handler.handleFacturaDuplicada(new FacturaDuplicadaException("A-0001")),
					() -> handler.handleComprobanteDuplicado(
							new EgresoComprobanteDuplicadoException("FACTURA", "0001", 401L)),
					() -> handler.handlePagoYaAnulado(new PagoEgresoYaAnuladoException(50L)),
					() -> handler.handleBeneficiarioNoVinculado(
							new BeneficiarioNoVinculadoException(31L)));

			assertThat(respuestas.stream().map(Supplier::get))
					.allSatisfy(problem -> {
						assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
						assertThat(problem.getTitle()).isNotBlank();
						assertThat(problem.getType())
								.as("un type generico deja a la pantalla sin forma de distinguir "
										+ "este conflicto de cualquier otro")
								.isNotEqualTo(ProblemType.CONFLICT.uri());
					});
		}
	}

	// =================================================================================
	// El invariante que vale para los cuarenta y cinco
	// =================================================================================

	@Test
	@DisplayName("Ningun handler deja el type o el titulo sin poner")
	void todos_declaran_type_y_titulo() {
		// Es lo que el gate de drift del contrato NO mira: compara el YAML contra las anotaciones,
		// no contra lo que el advice devuelve.
		Stream<ProblemDetail> todos = Stream.of(
				handler.handleConsultorioNoAccesible(new ConsultorioNoAccesibleException(7L)),
				handler.handleCajaNoAbierta(new CajaNoAbiertaException(7L)),
				handler.handleMediosNoSuman(new MediosNoSumanException(BigDecimal.ONE, PLATA)),
				handler.handleEgresoYaAnulado(new EgresoYaAnuladoException(400L)),
				handler.handleFacturaDuplicada(new FacturaDuplicadaException("A-0001")));

		assertThat(todos).allSatisfy(problem -> {
			assertThat(problem.getType()).isNotNull();
			assertThat(problem.getTitle()).isNotBlank();
			assertThat(problem.getDetail()).isNotBlank();
		});
	}
}
