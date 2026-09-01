package com.akine.billing.application;

import com.akine.billing.domain.Cobro;
import com.akine.billing.domain.CobroImputacion;
import com.akine.billing.domain.CobroMedio;
import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.PermissionCodes;
import com.akine.billing.domain.exception.CobroNotAccessibleException;
import com.akine.billing.domain.exception.ConsultorioNoAccesibleException;
import com.akine.billing.domain.exception.ObligacionNoCobrableException;
import com.akine.billing.domain.exception.SaldoInsuficienteException;
import com.akine.billing.domain.port.CobroRepositoryPort;
import com.akine.billing.domain.port.ComprobanteNumeradorPort;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Registro de cobros (M19, RF-M19-001..007).
 *
 * <h2>El descuento del saldo es un UPDATE condicional, no un lock</h2>
 *
 * <p>{@code UPDATE obligacion SET saldo = saldo - :importe WHERE id = ? AND saldo >= :importe}. Es
 * atomico, <b>no lee antes</b> —que es donde se cuela la ventana entre lectura y escritura— y no
 * puede dejar el saldo en negativo aunque dos cobros lleguen juntos. Cuando afecta cero filas,
 * otro cobro se llevo la plata primero, y eso es un 409 y no un error tecnico.
 *
 * <p>Es distinto del lock pesimista que 05.02 necesito para los turnos, y la diferencia importa:
 * alli habia que impedir el SOLAPAMIENTO de intervalos, que ningun predicado de igualdad puede
 * expresar; aca lo que hay que garantizar es una resta que no baje de cero, y eso el motor lo hace
 * en una sentencia.
 *
 * <h2>El orden de la operacion</h2>
 *
 * <pre>
 *   1. idempotencia               &lt;- ANTES de tocar el numerador
 *   2. validar las obligaciones
 *   3. descontar cada saldo       &lt;- atomico; si una falla, revierte todo
 *   4. asegurar el numerador      &lt;- en su PROPIA transaccion
 *   5. incrementar y leer
 *   6. guardar el cobro
 * </pre>
 *
 * <p>El paso 1 va primero para que un reintento no consuma un numero de comprobante que despues
 * nadie usa: la numeracion fiscal con huecos es peor que un cobro repetido, porque nadie puede
 * explicarla despues.
 *
 * <h2>Lo que esta etapa NO hace</h2>
 *
 * <p>No registra anticipos ni anula cobros. DP-06 dice que un anticipo se registra "mediante un
 * ledger trazable y MOVIMIENTO REAL DE CAJA", y la Caja es AKINE-07.03, fuera del Paquete B: un
 * anticipo sin caja es plata que entro y que ningun arqueo puede encontrar. Un reintegro, del otro
 * lado, saca dinero de una caja que no existe. Los dos son medias funcionalidades sin M20 y se
 * difieren enteros en vez de a medias.
 */
@Service
public class CobroService {

	private static final Logger log = LoggerFactory.getLogger(CobroService.class);

	private final CobroRepositoryPort cobros;
	private final ObligacionRepositoryPort obligaciones;
	private final ComprobanteNumeradorPort numerador;
	private final ComprobanteIniciador iniciador;
	private final ConsultorioDirectory consultorios;
	private final PermissionGuard permissionGuard;

	public CobroService(
			CobroRepositoryPort cobros,
			ObligacionRepositoryPort obligaciones,
			ComprobanteNumeradorPort numerador,
			ComprobanteIniciador iniciador,
			ConsultorioDirectory consultorios,
			PermissionGuard permissionGuard) {

		this.cobros = cobros;
		this.obligaciones = obligaciones;
		this.numerador = numerador;
		this.iniciador = iniciador;
		this.consultorios = consultorios;
		this.permissionGuard = permissionGuard;
	}

	/**
	 * Registra un cobro y lo imputa a las deudas indicadas.
	 *
	 * @throws ConsultorioNoAccesibleException  sede inexistente o de otro tenant (404)
	 * @throws ObligacionNoCobrableException    deuda anulada, pagada o de otra persona (409)
	 * @throws SaldoInsuficienteException       otro cobro se llevo la plata primero (409)
	 * @throws IdempotencyKeyConflictException  misma clave, pedido distinto (409)
	 */
	@Transactional
	public CobroView registrar(
			OperatingActor actor, long consultorioId, CobroCommand command) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		// PASO 1. Antes del numerador: un reintento no puede consumir un numero de comprobante.
		Optional<Cobro> yaCobrado = command.idempotencyKey() == null
				? Optional.empty()
				: cobros.findByIdempotencyKey(organizationId, command.idempotencyKey());
		if (yaCobrado.isPresent()) {
			Cobro existente = yaCobrado.get();
			if (!command.huella(consultorioId).equals(existente.getRequestHash())) {
				throw new IdempotencyKeyConflictException(command.idempotencyKey());
			}
			return CobroView.de(existente);
		}

		// PASO 2 y 3. Validar y descontar. Si una imputacion falla, la transaccion revierte las
		// anteriores: no puede quedar plata descontada de una deuda por un cobro que no existe.
		List<CobroImputacion> imputaciones = new ArrayList<>();
		String moneda = null;
		for (CobroCommand.ImputacionPedida pedido : command.imputaciones()) {
			Obligacion obligacion = obligaciones
					.findByIdInScope(organizationId, consultorioId, pedido.obligacionId())
					.orElseThrow(() -> new ObligacionNoCobrableException(
							pedido.obligacionId(), "no existe en esta sede"));

			exigirCobrable(obligacion, command.personaId());
			moneda = exigirMonedaUnica(moneda, obligacion);

			if (cobros.descontarSaldo(obligacion.getId(), pedido.importe()) == 0) {
				throw new SaldoInsuficienteException(obligacion.getId(), pedido.importe());
			}
			cobros.actualizarEstadoPorSaldo(obligacion.getId());

			imputaciones.add(new CobroImputacion(organizationId, obligacion.getId(), pedido.importe()));
		}

		// PASO 4 y 5. La fila se asegura afuera; el incremento toma el lock y serializa.
		iniciador.asegurar(organizationId, consultorioId);
		numerador.incrementar(organizationId, consultorioId);
		int comprobante = numerador.leerUltimo(organizationId, consultorioId);

		List<CobroMedio> medios = command.medios().stream()
				.map(medio -> new CobroMedio(organizationId, medio.medio(), medio.importe(), medio.referencia()))
				.toList();

		// El constructor verifica las dos sumas de RN-M19. Se hace ahi y no aca porque son
		// invariantes del cobro y no del caso de uso: un Cobro que no las cumple no puede existir.
		Cobro cobro = cobros.save(new Cobro(
				organizationId, consultorioId, command.personaId(),
				command.total(), moneda, comprobante,
				Instant.now(), actor.accountId(),
				command.idempotencyKey(),
				command.idempotencyKey() == null ? null : command.huella(consultorioId),
				medios, imputaciones));

		log.info("Cobro registrado: cobroId={} comprobante={} personaId={} total={} {} imputaciones={}",
				cobro.getId(), comprobante, command.personaId(), command.total(), moneda,
				imputaciones.size());

		return CobroView.de(cobro);
	}

	/**
	 * El comprobante, recuperable sin volver a cobrar.
	 *
	 * <p>Es un requisito literal de la etapa —"comprobante recuperable sin crear nuevo cobro"— y no
	 * un detalle: sin esta lectura, un operador que necesita reimprimir tendria como unica salida
	 * volver a registrar el cobro, que es exactamente el error que la idempotencia trata de evitar.
	 */
	@Transactional(readOnly = true)
	public CobroView ver(OperatingActor actor, long consultorioId, long cobroId) {
		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		return cobros.findByIdInScope(organizationId, consultorioId, cobroId)
				.map(CobroView::de)
				.orElseThrow(() -> new CobroNotAccessibleException(cobroId));
	}

	/** Los cobros de un paciente en la organizacion, del mas reciente al mas viejo. */
	@Transactional(readOnly = true)
	public List<CobroView> deLaPersona(OperatingActor actor, long consultorioId, long personaId) {
		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		return cobros.findDeLaPersona(organizationId, personaId).stream()
				.map(CobroView::de)
				.toList();
	}

	// =================================================================================
	// Precondiciones
	// =================================================================================

	/**
	 * Que la deuda admita cobro y sea de quien paga.
	 *
	 * <p><b>El control de la persona es el que importa vigilar.</b> Sin el, un cobro podria saldar
	 * la deuda de otro paciente —basta con un id equivocado en el cuerpo— y las dos cuentas
	 * corrientes quedarian mal sin que nada falle: una con plata que no pago y la otra con deuda
	 * que si pago.
	 */
	private static void exigirCobrable(Obligacion obligacion, long personaId) {
		if (obligacion.getPersonaId() != personaId) {
			throw new ObligacionNoCobrableException(obligacion.getId(), "es de otra persona");
		}
		if (!obligacion.admiteCobro()) {
			throw new ObligacionNoCobrableException(obligacion.getId(),
					"esta " + obligacion.getEstado().name().toLowerCase());
		}
	}

	/**
	 * Todas las deudas de un cobro tienen que estar en la misma moneda.
	 *
	 * <p>Un cobro con un solo total no puede pagar deudas en monedas distintas sin una cotizacion,
	 * y una cotizacion es una decision de negocio que nadie tomo. Rechazarlo es preferible a
	 * sumar pesos con dolares.
	 */
	private static String exigirMonedaUnica(String monedaHastaAhora, Obligacion obligacion) {
		if (monedaHastaAhora == null) {
			return obligacion.getMoneda();
		}
		if (!monedaHastaAhora.equals(obligacion.getMoneda())) {
			throw new ObligacionNoCobrableException(obligacion.getId(),
					"esta en " + obligacion.getMoneda() + " y el cobro es en " + monedaHastaAhora);
		}
		return monedaHastaAhora;
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	/** Falta de contexto es <b>403 y nunca 401</b>: un 401 deja al frontend en bucle de login. */
	private static long exigirContexto(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("El cobro requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	private void exigirSedeDelTenant(long organizationId, long consultorioId) {
		consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	private void exigirGestion(OperatingActor actor, long organizationId, long consultorioId) {
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.COBRO_REGISTER,
				organizationId,
				consultorioId,
				null,
				Instant.now()));
	}

}
