package com.akine.billing.application;

import com.akine.billing.domain.Cobro;
import com.akine.billing.domain.CobroImputacion;
import com.akine.billing.domain.CobroMedio;
import com.akine.billing.domain.EstadoObligacion;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.Responsable;
import com.akine.billing.domain.exception.CajaNoAbiertaException;
import com.akine.billing.domain.exception.CobroInvalidoException;
import com.akine.billing.domain.exception.CobroNotAccessibleException;
import com.akine.billing.domain.exception.ConsultorioNoAccesibleException;
import com.akine.billing.domain.exception.ImputacionesNoSumanException;
import com.akine.billing.domain.exception.ObligacionNoCobrableException;
import com.akine.billing.domain.exception.PersonaNoAccesibleException;
import com.akine.billing.domain.exception.SaldoInsuficienteException;
import com.akine.billing.domain.port.CobroRepositoryPort;
import com.akine.billing.domain.port.ComprobanteNumeradorPort;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Registro de cobros (M19, {@link CobroService}).
 *
 * <h2>Que decide la correctitud aca</h2>
 *
 * <ul>
 *   <li><b>El orden de la operacion es la mitad del diseño.</b> La idempotencia se evalua ANTES de
 *       tocar el numerador y la validacion de las deudas va antes que el comprobante: un reintento
 *       o un pedido invalido no pueden consumir un numero fiscal. Un comprobante con huecos es peor
 *       que un cobro repetido, porque nadie puede explicarlo despues. Se verifica con
 *       {@code never()} sobre el numerador en los caminos de rechazo, y con {@link InOrder} en el
 *       feliz.
 *   <li><b>El saldo lo descuenta un UPDATE condicional, no un {@code if}.</b> El servicio no lee el
 *       saldo para decidir: pide la resta y mira cuantas filas afecto. <b>Cero filas es un 409
 *       legitimo</b> —otro cobro se llevo la plata primero— y no un error tecnico. Aca eso se
 *       prueba con un doble que devuelve cero; la carrera real necesita dos transacciones.
 *   <li><b>El {@code organizationId} viaja en el WHERE del descuento.</b> Es la correccion de
 *       AKINE-07.07: sin el, un id que venga de un body salda la deuda de otro centro del SaaS sin
 *       que nada falle.
 *   <li><b>La deuda tiene que ser de quien paga.</b> Sin ese control, un id equivocado en el cuerpo
 *       salda la deuda de otro paciente y las dos cuentas corrientes quedan mal sin que nada falle:
 *       una con plata que no pago y la otra con deuda que si pago.
 *   <li><b>El asiento de caja va dentro de la misma transaccion y puede voltear el cobro.</b> Un
 *       cobro con efectivo sin jornada abierta se rechaza entero: la plata entra al cajon igual, y
 *       si el sistema no sabe a que jornada pertenece, el arqueo del dia no cuadra contra nada.
 * </ul>
 *
 * <h2>Lo que estos tests NO pueden decir</h2>
 *
 * <ul>
 *   <li><b>Nada sobre concurrencia real.</b> El "otro cobro se llevo la plata" se simula con un
 *       mock que devuelve cero filas; que el {@code UPDATE ... WHERE saldo >= :importe} sea
 *       efectivamente atomico y que el {@code CHECK} de V36 impida el negativo solo lo prueba un IT
 *       contra MySQL. Lo mismo el lock de fila del numerador: aca el comprobante lo decide un
 *       {@code willReturn}.
 *   <li><b>Nada sobre la transaccionalidad.</b> Que una imputacion fallida revierta las anteriores
 *       lo hace Spring, no el servicio: en un unitario el rollback no existe y lo unico verificable
 *       es que la excepcion salga antes de guardar el cobro.
 *   <li><b>Nada sobre la jornada de caja.</b> {@link CajaDeCobro} esta doblado, asi que la regla
 *       "el efectivo exige caja abierta y la tarjeta no" se verifica en {@code ReglasDeCajaTest};
 *       aca solo se verifica que el cobro delegue y que la falla lo voltee.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Cobros (M19, CobroService)")
class CobroServiceTest {

	private static final long ORG = 7L;
	private static final long SEDE = 20L;
	private static final long OTRA_SEDE = 21L;
	private static final long PERSONA = 4100L;
	private static final long OTRA_PERSONA = 4101L;
	private static final long OBLIGACION = 8800L;
	private static final long OTRA_OBLIGACION = 8801L;
	private static final long COBRO = 5501L;
	private static final long CUENTA = 31L;
	private static final long SESION = 6600L;
	private static final long OFERTA = 55L;
	private static final int COMPROBANTE = 101;
	private static final String MONEDA = "ARS";
	private static final String CLAVE = "idem-2027-04-08-0001";

	@Mock private CobroRepositoryPort cobros;
	@Mock private ObligacionRepositoryPort obligaciones;
	@Mock private ComprobanteNumeradorPort numerador;
	@Mock private ComprobanteIniciador iniciador;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private PermissionGuard permissionGuard;
	@Mock private CajaDeCobro caja;
	@Mock private PacienteDirectory pacientes;

	private CobroService service;

	private final ConsultorioSnapshot sede =
			new ConsultorioSnapshot(SEDE, ORG, "Sede Centro", "America/Argentina/Cordoba", true);
	private final OperatingActor administrativo = new OperatingActor(CUENTA, false, ORG, SEDE);

	@BeforeEach
	void setUp() {
		service = new CobroService(
				cobros, obligaciones, numerador, iniciador, consultorios, permissionGuard, caja, pacientes);

		given(consultorios.find(ORG, SEDE)).willReturn(Optional.of(sede));
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("CONSULTORIO", false));
		given(numerador.leerUltimo(ORG, SEDE)).willReturn(COMPROBANTE);
		// Por defecto el descuento alcanza. El camino de cero filas se stubea por test, porque es
		// un desenlace y no el caso normal.
		given(cobros.descontarSaldo(anyLong(), anyLong(), any())).willReturn(1);
		// JPA asigna el id al persistir, y aca no es un detalle cosmetico: el asiento de caja usa
		// el id del cobro como referencia de origen —es lo que hace idempotente el reintento—, asi
		// que un doble que no lo asignara volteria el flujo con un NPE que produccion no tiene.
		given(cobros.save(any())).willAnswer(invocacion -> {
			Cobro guardado = invocacion.getArgument(0);
			if (guardado.getId() == null) {
				ReflectionTestUtils.setField(guardado, "id", COBRO);
			}
			return guardado;
		});
	}

	// =================================================================================
	// El camino feliz, y el orden de los pasos
	// =================================================================================

	@Nested
	@DisplayName("Registrar un cobro")
	class Registrar {

		@Test
		@DisplayName("descuenta cada saldo con el tenant en el WHERE, deriva el estado y numera el comprobante")
		void camino_feliz() {
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION))
					.willReturn(Optional.of(deuda(OBLIGACION, PERSONA, "6000.00", MONEDA)));
			given(obligaciones.findByIdInScope(ORG, SEDE, OTRA_OBLIGACION))
					.willReturn(Optional.of(deuda(OTRA_OBLIGACION, PERSONA, "4000.00", MONEDA)));

			CobroView vista = service.registrar(administrativo, SEDE, new CobroCommand(
					PERSONA, new BigDecimal("10000.00"),
					List.of(new CobroCommand.MedioPedido(MedioDePago.EFECTIVO, new BigDecimal("10000.00"), null)),
					List.of(
							new CobroCommand.ImputacionPedida(OBLIGACION, new BigDecimal("6000.00")),
							new CobroCommand.ImputacionPedida(OTRA_OBLIGACION, new BigDecimal("4000.00"))),
					CLAVE));

			// El tenant va en el WHERE del descuento (AKINE-07.07): una firma sin el no le da al
			// proximo llamador ninguna forma de equivocarse a favor, y un id que venga de un body
			// saldaria la deuda de otro centro del SaaS sin que nada falle.
			verify(cobros).descontarSaldo(ORG, OBLIGACION, new BigDecimal("6000.00"));
			verify(cobros).descontarSaldo(ORG, OTRA_OBLIGACION, new BigDecimal("4000.00"));
			// El estado se DERIVA del saldo, no se escribe a mano: si quedara en PENDIENTE con
			// saldo cero, la deuda seguiria apareciendo como cobrable y alguien cobraria dos veces.
			verify(cobros).actualizarEstadoPorSaldo(ORG, OBLIGACION);
			verify(cobros).actualizarEstadoPorSaldo(ORG, OTRA_OBLIGACION);

			assertThat(vista.comprobanteNumero()).isEqualTo(COMPROBANTE);
			assertThat(vista.total()).isEqualByComparingTo("10000.00");
			assertThat(vista.moneda()).isEqualTo(MONEDA);
			assertThat(vista.imputaciones())
					.extracting(CobroView.ImputacionView::obligacionId)
					.containsExactly(OBLIGACION, OTRA_OBLIGACION);
		}

		@Test
		@DisplayName("la fila del numerador se asegura ANTES de incrementarla, y en ese orden")
		void el_numerador_se_asegura_antes() {
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION))
					.willReturn(Optional.of(deuda(OBLIGACION, PERSONA, "8500.00", MONEDA)));

			service.registrar(administrativo, SEDE, cobroSimple("8500.00", CLAVE));

			// Crear la fila dentro de la transaccion que la bloquea produce deadlock entre los
			// primeros cobros concurrentes de una sede, y el try/catch no salva: atrapar una
			// excepcion de persistencia no des-marca la transaccion. Es la tercera vez que este
			// patron aparece en el proyecto y siempre por lo mismo.
			InOrder orden = inOrder(iniciador, numerador, cobros);
			orden.verify(iniciador).asegurar(ORG, SEDE);
			orden.verify(numerador).incrementar(ORG, SEDE);
			orden.verify(numerador).leerUltimo(ORG, SEDE);
			orden.verify(cobros).save(any());
		}

		@Test
		@DisplayName("el asiento de caja va despues del save y recibe el id del cobro como origen")
		void la_caja_recibe_el_id_del_cobro() {
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION))
					.willReturn(Optional.of(deuda(OBLIGACION, PERSONA, "8500.00", MONEDA)));

			service.registrar(administrativo, SEDE, cobroSimple("8500.00", CLAVE));

			// El id es lo que hace idempotente el reintento del asiento: sin el, dos ejecuciones
			// del mismo cobro produciria dos movimientos de caja que inflan el arqueo del dia.
			verify(caja).registrarIngresos(
					eq(ORG), eq(sede), eq(COBRO), eq(MONEDA), any(), any(), eq(CUENTA));
		}

		@Test
		@DisplayName("si el asiento de caja falla, el cobro entero se rechaza")
		void la_caja_puede_voltear_el_cobro() {
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION))
					.willReturn(Optional.of(deuda(OBLIGACION, PERSONA, "8500.00", MONEDA)));
			willThrow(new CajaNoAbiertaException(SEDE)).given(caja).registrarIngresos(
					anyLong(), any(), anyLong(), anyString(), any(), any(), anyLong());

			// Es la contrapartida asumida del asiento automatico: un cobro en efectivo sin jornada
			// abierta no se confirma. La alternativa —asentar despues— deja caja y cobros
			// divergiendo sin que falle nada, y el unico mecanismo que los reconciliaria es un
			// reporte que nadie corre.
			assertThatThrownBy(() -> service.registrar(administrativo, SEDE, cobroSimple("8500.00", CLAVE)))
					.isInstanceOf(CajaNoAbiertaException.class);
		}

		@Test
		@DisplayName("un cobro con dos medios conserva los dos y su referencia")
		void los_medios_viajan_completos() {
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION))
					.willReturn(Optional.of(deuda(OBLIGACION, PERSONA, "10000.00", MONEDA)));

			CobroView vista = service.registrar(administrativo, SEDE, new CobroCommand(
					PERSONA, new BigDecimal("10000.00"),
					List.of(
							new CobroCommand.MedioPedido(MedioDePago.EFECTIVO, new BigDecimal("4000.00"), null),
							new CobroCommand.MedioPedido(
									MedioDePago.TARJETA_CREDITO, new BigDecimal("6000.00"), "lote 0042")),
					List.of(new CobroCommand.ImputacionPedida(OBLIGACION, new BigDecimal("10000.00"))),
					CLAVE));

			// La referencia del medio es lo unico que permite conciliar la tarjeta contra el
			// resumen del adquirente: perderla deja 6.000 sin forma de buscarlos.
			assertThat(vista.medios())
					.extracting(CobroView.MedioView::medio, CobroView.MedioView::referencia)
					.containsExactly(
							org.assertj.core.groups.Tuple.tuple("EFECTIVO", null),
							org.assertj.core.groups.Tuple.tuple("TARJETA_CREDITO", "lote 0042"));
		}
	}

	// =================================================================================
	// Idempotencia: antes del numerador
	// =================================================================================

	@Nested
	@DisplayName("Idempotencia")
	class Idempotencia {

		@Test
		@DisplayName("un reintento con la misma clave y el mismo pedido devuelve el cobro original sin consumir comprobante")
		void el_reintento_no_consume_comprobante() {
			CobroCommand pedido = cobroSimple("8500.00", CLAVE);
			given(cobros.findByIdempotencyKey(ORG, CLAVE))
					.willReturn(Optional.of(cobroYaRegistrado(pedido)));

			CobroView vista = service.registrar(administrativo, SEDE, pedido);

			assertThat(vista.id()).isEqualTo(COBRO);
			assertThat(vista.comprobanteNumero()).isEqualTo(COMPROBANTE);
			// Lo que se protege no es el cobro duplicado sino la NUMERACION: un numero de
			// comprobante consumido por un reintento que despues nadie usa es un hueco fiscal, y
			// un hueco no se puede explicar seis meses despues.
			verify(numerador, never()).incrementar(anyLong(), anyLong());
			verify(iniciador, never()).asegurar(anyLong(), anyLong());
			// Y tampoco se vuelve a descontar: eso dejaria la deuda saldada dos veces.
			verify(cobros, never()).descontarSaldo(anyLong(), anyLong(), any());
			verify(cobros, never()).save(any());
			verifyNoInteractions(caja);
		}

		@Test
		@DisplayName("la misma clave con otro pedido es 409: una clave reusada no puede devolver un cobro ajeno")
		void misma_clave_otro_pedido_es_conflicto() {
			// El cobro guardado fue por 8.500; el pedido que llega ahora dice 9.000 con la misma
			// clave. Devolver el original le confirmaria al cliente un cobro que no pidio.
			given(cobros.findByIdempotencyKey(ORG, CLAVE))
					.willReturn(Optional.of(cobroYaRegistrado(cobroSimple("8500.00", CLAVE))));

			assertThatThrownBy(() ->
					service.registrar(administrativo, SEDE, cobroSimple("9000.00", CLAVE)))
					.isInstanceOf(IdempotencyKeyConflictException.class);

			verify(numerador, never()).incrementar(anyLong(), anyLong());
		}

		@Test
		@DisplayName("sin clave de idempotencia no se consulta el indice y el cobro no guarda huella")
		void sin_clave_no_hay_consulta() {
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION))
					.willReturn(Optional.of(deuda(OBLIGACION, PERSONA, "8500.00", MONEDA)));

			service.registrar(administrativo, SEDE, cobroSimple("8500.00", null));

			// Desactivar la idempotencia es legitimo en una carga manual. Lo que no puede pasar es
			// que se guarde una huella sin clave: el unique de la clave es lo que las vincula, y
			// una huella huerfana no la puede usar nadie.
			verify(cobros, never()).findByIdempotencyKey(anyLong(), anyString());
			ArgumentCaptor<Cobro> guardado = ArgumentCaptor.forClass(Cobro.class);
			verify(cobros).save(guardado.capture());
			assertThat(guardado.getValue().getRequestHash()).isNull();
		}
	}

	// =================================================================================
	// Lo que hace que una deuda no sea cobrable
	// =================================================================================

	@Nested
	@DisplayName("Precondiciones de la deuda")
	class Precondiciones {

		@Test
		@DisplayName("cero filas afectadas en el descuento es 409 de saldo, no un error tecnico, y no numera")
		void saldo_insuficiente_es_409() {
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION))
					.willReturn(Optional.of(deuda(OBLIGACION, PERSONA, "8500.00", MONEDA)));
			given(cobros.descontarSaldo(ORG, OBLIGACION, new BigDecimal("8500.00"))).willReturn(0);

			// Cero filas significa que otro cobro se llevo la plata primero: es un desenlace
			// legitimo de una carrera que el UPDATE condicional gano, no una falla del sistema. Un
			// 500 aca haria que el mostrador reintente a ciegas.
			assertThatThrownBy(() -> service.registrar(administrativo, SEDE, cobroSimple("8500.00", CLAVE)))
					.isInstanceOf(SaldoInsuficienteException.class)
					.extracting(error -> ((SaldoInsuficienteException) error).getObligacionId())
					.isEqualTo(OBLIGACION);

			// Y el rechazo ocurre antes del numerador: un pedido que no cobra no gasta un numero.
			verify(iniciador, never()).asegurar(anyLong(), anyLong());
			verify(numerador, never()).incrementar(anyLong(), anyLong());
			verify(cobros, never()).save(any());
		}

		@Test
		@DisplayName("no se puede imputar a la deuda de otra persona")
		void la_deuda_tiene_que_ser_de_quien_paga() {
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION))
					.willReturn(Optional.of(deuda(OBLIGACION, OTRA_PERSONA, "8500.00", MONEDA)));

			// Es el control que mas importa vigilar: sin el basta un id equivocado en el cuerpo
			// para que las dos cuentas corrientes queden mal sin que nada falle — una con plata que
			// no pago y la otra con deuda que si pago.
			assertThatThrownBy(() -> service.registrar(administrativo, SEDE, cobroSimple("8500.00", CLAVE)))
					.isInstanceOf(ObligacionNoCobrableException.class)
					.hasMessageContaining("es de otra persona");

			verify(cobros, never()).descontarSaldo(anyLong(), anyLong(), any());
		}

		@Test
		@DisplayName("una deuda anulada no admite cobro, y el mensaje dice en que estado esta")
		void la_deuda_anulada_no_admite_cobro() {
			Obligacion anulada = deuda(OBLIGACION, PERSONA, "8500.00", MONEDA);
			ReflectionTestUtils.setField(anulada, "estado", EstadoObligacion.ANULADA);
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION)).willReturn(Optional.of(anulada));

			// Cobrar contra una deuda anulada deja plata en la caja sin obligacion que la
			// justifique, que es el espejo exacto de anular una deuda ya cobrada.
			assertThatThrownBy(() -> service.registrar(administrativo, SEDE, cobroSimple("8500.00", CLAVE)))
					.isInstanceOf(ObligacionNoCobrableException.class)
					.hasMessageContaining("anulada");
		}

		@Test
		@DisplayName("una deuda ya pagada no admite otro cobro")
		void la_deuda_pagada_no_admite_cobro() {
			Obligacion pagada = deuda(OBLIGACION, PERSONA, "8500.00", MONEDA);
			ReflectionTestUtils.setField(pagada, "estado", EstadoObligacion.PAGADA);
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION)).willReturn(Optional.of(pagada));

			assertThatThrownBy(() -> service.registrar(administrativo, SEDE, cobroSimple("8500.00", CLAVE)))
					.isInstanceOf(ObligacionNoCobrableException.class)
					.hasMessageContaining("pagada");
		}

		@Test
		@DisplayName("un cobro no puede imputarse a deudas en monedas distintas")
		void una_sola_moneda_por_cobro() {
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION))
					.willReturn(Optional.of(deuda(OBLIGACION, PERSONA, "6000.00", "ARS")));
			given(obligaciones.findByIdInScope(ORG, SEDE, OTRA_OBLIGACION))
					.willReturn(Optional.of(deuda(OTRA_OBLIGACION, PERSONA, "4000.00", "USD")));

			// Un cobro tiene UN total: pagar deudas en dos monedas exigiria una cotizacion, y una
			// cotizacion es una decision de negocio que nadie tomo. Rechazar es preferible a sumar
			// pesos con dolares y dejar una cuenta corriente que no significa nada.
			assertThatThrownBy(() -> service.registrar(administrativo, SEDE, new CobroCommand(
					PERSONA, new BigDecimal("10000.00"),
					List.of(new CobroCommand.MedioPedido(
							MedioDePago.EFECTIVO, new BigDecimal("10000.00"), null)),
					List.of(
							new CobroCommand.ImputacionPedida(OBLIGACION, new BigDecimal("6000.00")),
							new CobroCommand.ImputacionPedida(OTRA_OBLIGACION, new BigDecimal("4000.00"))),
					CLAVE)))
					.isInstanceOf(ObligacionNoCobrableException.class)
					.hasMessageContaining("USD");

			verify(cobros, never()).save(any());
		}

		@Test
		@DisplayName("una deuda inexistente o de otra sede es no cobrable, no un 404 de deuda")
		void deuda_fuera_de_la_sede() {
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION)).willReturn(Optional.empty());

			// Aca el 409 es correcto y el 404 no: el recurso del pedido es el COBRO que se esta
			// creando, y la deuda es un dato del cuerpo. Un 404 diria que el endpoint no existe.
			assertThatThrownBy(() -> service.registrar(administrativo, SEDE, cobroSimple("8500.00", CLAVE)))
					.isInstanceOf(ObligacionNoCobrableException.class)
					.hasMessageContaining("no existe en esta sede");
		}
	}

	// =================================================================================
	// Autorizacion y lecturas
	// =================================================================================

	@Nested
	@DisplayName("Autorizacion y lecturas")
	class AutorizacionYLecturas {

		@Test
		@DisplayName("sin contexto de trabajo activo es 403, nunca 401, y nada se toca")
		void sin_contexto_es_403() {
			OperatingActor sinContexto = new OperatingActor(CUENTA, false, null, null);

			// Un 401 haria que el interceptor del frontend borre el token: bucle de login sobre un
			// usuario que esta autenticado y solo le falta elegir organizacion.
			assertThatThrownBy(() -> service.registrar(sinContexto, SEDE, cobroSimple("8500.00", CLAVE)))
					.isInstanceOf(AccessDeniedException.class);

			verifyNoInteractions(cobros, obligaciones, numerador, iniciador, caja);
		}

		@Test
		@DisplayName("cobrar en una sede de otro tenant es 404 y no llega al numerador ni al permiso")
		void sede_ajena_es_404() {
			given(consultorios.find(ORG, OTRA_SEDE)).willReturn(Optional.empty());

			assertThatThrownBy(() ->
					service.registrar(administrativo, OTRA_SEDE, cobroSimple("8500.00", CLAVE)))
					.isInstanceOf(ConsultorioNoAccesibleException.class);

			// Cross-tenant es 404 y nunca 403: un 403 confirma que la sede existe.
			verify(permissionGuard, never()).requirePermission(any());
			verify(numerador, never()).incrementar(anyLong(), anyLong());
		}

		@Test
		@DisplayName("registrar exige cobro:register con la sede del pedido como alcance")
		void exige_el_permiso_con_la_sede_como_alcance() {
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION))
					.willReturn(Optional.of(deuda(OBLIGACION, PERSONA, "8500.00", MONEDA)));

			service.registrar(administrativo, SEDE, cobroSimple("8500.00", CLAVE));

			// El cobro NO exige caja:operate, y eso es una decision: si lo hiciera, poder cobrar
			// dependeria del medio de pago elegido —efectivo si, tarjeta no—, que es absurdo desde
			// el mostrador y que la matriz §32 no dice en ninguna parte.
			ArgumentCaptor<PermissionQuery> consulta = ArgumentCaptor.forClass(PermissionQuery.class);
			verify(permissionGuard).requirePermission(consulta.capture());
			assertThat(consulta.getValue().permissionCode()).isEqualTo("cobro:register");
			assertThat(consulta.getValue().consultorioId()).isEqualTo(SEDE);
		}

		@Test
		@DisplayName("el comprobante se recupera sin volver a cobrar")
		void ver_recupera_el_comprobante() {
			given(cobros.findByIdInScope(ORG, SEDE, COBRO))
					.willReturn(Optional.of(cobroYaRegistrado(cobroSimple("8500.00", CLAVE))));

			// Sin esta lectura, un operador que necesita reimprimir tendria como unica salida
			// volver a registrar el cobro, que es justo lo que la idempotencia trata de evitar.
			assertThat(service.ver(administrativo, SEDE, COBRO).comprobanteNumero())
					.isEqualTo(COMPROBANTE);
			verify(numerador, never()).incrementar(anyLong(), anyLong());
		}

		@Test
		@DisplayName("un cobro de otra sede o de otro tenant no se puede ver: 404")
		void ver_fuera_de_alcance_es_404() {
			given(cobros.findByIdInScope(ORG, SEDE, COBRO)).willReturn(Optional.empty());

			assertThatThrownBy(() -> service.ver(administrativo, SEDE, COBRO))
					.isInstanceOf(CobroNotAccessibleException.class);
		}

		@Test
		@DisplayName("los cobros de un paciente se piden por organizacion, nunca por sede")
		void los_cobros_son_de_la_organizacion() {
			given(cobros.findDeLaPersona(ORG, PERSONA))
					.willReturn(List.of(cobroYaRegistrado(cobroSimple("8500.00", CLAVE))));

			// Mismo criterio que la cuenta corriente de M18: el historial de pagos de un paciente
			// es uno solo aunque haya pagado en dos sedes del mismo centro.
			assertThat(service.deLaPersona(administrativo, SEDE, PERSONA)).hasSize(1);
			verify(cobros).findDeLaPersona(ORG, PERSONA);
		}
	}

	// =================================================================================
	// Anticipos (F-3)
	// =================================================================================

	@Nested
	@DisplayName("Anticipos (F-3)")
	class Anticipos {

		@Test
		@DisplayName("un anticipo puro: valida la persona en el padron, no toca deudas y deja todo a favor")
		void anticipo_puro() {
			given(pacientes.find(ORG, PERSONA)).willReturn(Optional.of(new PacienteSnapshot(
					PERSONA, ORG, "Perez", "Ana", "DNI", "30111222", null, true, true, 1L, null)));

			CobroView vista = service.registrar(administrativo, SEDE, anticipoPuro("5000.00", MONEDA));

			assertThat(vista.saldoAFavor()).isEqualByComparingTo("5000.00");
			assertThat(vista.imputaciones()).isEmpty();
			assertThat(vista.moneda()).isEqualTo(MONEDA);
			assertThat(vista.estado()).isEqualTo(CobroView.VIGENTE);
			verify(cobros, never()).descontarSaldo(anyLong(), anyLong(), any());
			// La plata entra a la caja AHORA, una vez: es lo que hace honesto al anticipo (DP-06).
			verify(caja).registrarIngresos(
					eq(ORG), eq(sede), eq(COBRO), eq(MONEDA), any(), any(), eq(CUENTA));
		}

		@Test
		@DisplayName("un anticipo puro de una persona de otro tenant es 404 y no numera comprobante")
		void anticipo_de_persona_ajena_es_404() {
			given(pacientes.find(ORG, PERSONA)).willReturn(Optional.empty());

			assertThatThrownBy(() -> service.registrar(administrativo, SEDE, anticipoPuro("5000.00", MONEDA)))
					.isInstanceOf(PersonaNoAccesibleException.class);
			verify(numerador, never()).incrementar(anyLong(), anyLong());
		}

		@Test
		@DisplayName("un anticipo puro sin moneda es 400: no hay deuda de donde tomarla")
		void anticipo_sin_moneda_es_400() {
			assertThatThrownBy(() -> service.registrar(administrativo, SEDE, anticipoPuro("5000.00", null)))
					.isInstanceOf(CobroInvalidoException.class);
			verifyNoInteractions(pacientes);
		}

		@Test
		@DisplayName("un sobrante declarado: imputa la deuda y deja el resto a favor")
		void sobrante_declarado() {
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION))
					.willReturn(Optional.of(deuda(OBLIGACION, PERSONA, "6000.00", MONEDA)));

			CobroView vista = service.registrar(administrativo, SEDE, new CobroCommand(
					PERSONA, new BigDecimal("10000.00"),
					List.of(new CobroCommand.MedioPedido(MedioDePago.EFECTIVO, new BigDecimal("10000.00"), null)),
					List.of(new CobroCommand.ImputacionPedida(OBLIGACION, new BigDecimal("6000.00"))),
					CLAVE, new BigDecimal("4000.00"), null));

			assertThat(vista.saldoAFavor()).isEqualByComparingTo("4000.00");
			verify(cobros).descontarSaldo(ORG, OBLIGACION, new BigDecimal("6000.00"));
			// Con deudas, la persona la validan ellas: no hace falta preguntarle al padron.
			verifyNoInteractions(pacientes);
		}

		@Test
		@DisplayName("un sobrante SIN declarar sigue siendo 400: el anticipo no se infiere")
		void sobrante_sin_declarar_es_400() {
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION))
					.willReturn(Optional.of(deuda(OBLIGACION, PERSONA, "6000.00", MONEDA)));

			// Un cero de menos en la imputacion no puede convertirse en un saldo a favor que nadie
			// pidio: es justo lo que cobro-no-cuadra existe para atajar.
			assertThatThrownBy(() -> service.registrar(administrativo, SEDE, new CobroCommand(
					PERSONA, new BigDecimal("10000.00"),
					List.of(new CobroCommand.MedioPedido(MedioDePago.EFECTIVO, new BigDecimal("10000.00"), null)),
					List.of(new CobroCommand.ImputacionPedida(OBLIGACION, new BigDecimal("6000.00"))),
					CLAVE)))
					.isInstanceOf(ImputacionesNoSumanException.class);
		}

		@Test
		@DisplayName("la moneda declarada tiene que coincidir con la de las deudas")
		void moneda_declarada_distinta_es_409() {
			given(obligaciones.findByIdInScope(ORG, SEDE, OBLIGACION))
					.willReturn(Optional.of(deuda(OBLIGACION, PERSONA, "6000.00", MONEDA)));

			assertThatThrownBy(() -> service.registrar(administrativo, SEDE, new CobroCommand(
					PERSONA, new BigDecimal("6000.00"),
					List.of(new CobroCommand.MedioPedido(MedioDePago.EFECTIVO, new BigDecimal("6000.00"), null)),
					List.of(new CobroCommand.ImputacionPedida(OBLIGACION, new BigDecimal("6000.00"))),
					CLAVE, BigDecimal.ZERO, "USD")))
					.isInstanceOf(ObligacionNoCobrableException.class);
		}

		@Test
		@DisplayName("la huella de un cobro sin anticipo es la misma que antes de F-3")
		void la_huella_vieja_no_cambia() {
			// Un reintento de un cobro registrado antes de F-3 tiene que seguir siendo un reintento,
			// no un 409 que el usuario no puede entender.
			CobroCommand viejo = cobroSimple("8500.00", CLAVE);
			CobroCommand nuevo = new CobroCommand(viejo.personaId(), viejo.total(), viejo.medios(),
					viejo.imputaciones(), CLAVE, new BigDecimal("0.00"), null);

			assertThat(nuevo.huella(SEDE)).isEqualTo(viejo.huella(SEDE));
			assertThat(anticipoPuro("8500.00", MONEDA).huella(SEDE)).isNotEqualTo(viejo.huella(SEDE));
		}
	}

	private static CobroCommand anticipoPuro(String total, String moneda) {
		return new CobroCommand(
				PERSONA, new BigDecimal(total),
				List.of(new CobroCommand.MedioPedido(MedioDePago.EFECTIVO, new BigDecimal(total), null)),
				List.of(), CLAVE, new BigDecimal(total), moneda);
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	/** Un cobro de un solo medio contra una sola deuda, que es la forma mas comun del mostrador. */
	private static CobroCommand cobroSimple(String total, String idempotencyKey) {
		return new CobroCommand(
				PERSONA, new BigDecimal(total),
				List.of(new CobroCommand.MedioPedido(MedioDePago.EFECTIVO, new BigDecimal(total), null)),
				List.of(new CobroCommand.ImputacionPedida(OBLIGACION, new BigDecimal(total))),
				idempotencyKey);
	}

	/**
	 * El cobro que la base ya tiene para esa clave, con la huella del pedido que lo creo.
	 *
	 * <p>La huella se calcula con el MISMO metodo que usa el servicio: si el fixture la escribiera
	 * a mano, el test de conflicto pasaria por una huella inventada y no por la regla.
	 */
	private static Cobro cobroYaRegistrado(CobroCommand pedido) {
		Cobro cobro = new Cobro(
				ORG, SEDE, pedido.personaId(), pedido.total(), MONEDA, COMPROBANTE,
				Instant.parse("2027-04-08T13:00:00Z"), CUENTA,
				pedido.idempotencyKey(),
				pedido.idempotencyKey() == null ? null : pedido.huella(SEDE),
				pedido.medios().stream()
						.map(medio -> new CobroMedio(ORG, medio.medio(), medio.importe(), medio.referencia()))
						.toList(),
				pedido.imputaciones().stream()
						.map(imputacion -> new CobroImputacion(
								ORG, imputacion.obligacionId(), imputacion.importe()))
						.toList());
		ReflectionTestUtils.setField(cobro, "id", COBRO);
		return cobro;
	}

	/** Una deuda pendiente como la devuelve la base: con id y con saldo igual al importe. */
	private static Obligacion deuda(long id, long personaId, String importe, String moneda) {
		Obligacion obligacion = new Obligacion(
				ORG, SEDE, SESION, personaId, Responsable.PACIENTE,
				new BigDecimal(importe), moneda, OFERTA, "Sesion de kinesiologia",
				Instant.parse("2027-04-08T13:00:00Z"));
		ReflectionTestUtils.setField(obligacion, "id", id);
		return obligacion;
	}
}
