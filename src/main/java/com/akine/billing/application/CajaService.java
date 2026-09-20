package com.akine.billing.application;

import com.akine.billing.domain.JornadaCaja;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.exception.CajaCerradaException;
import com.akine.billing.domain.exception.CajaDiferenciaSinMotivoException;
import com.akine.billing.domain.exception.CajaSaldoCambioException;
import com.akine.billing.domain.exception.CajaYaAbiertaException;
import com.akine.billing.domain.exception.JornadaCajaNotAccessibleException;
import com.akine.billing.domain.port.JornadaCajaRepositoryPort;
import com.akine.billing.domain.port.MovimientoCajaRepositoryPort;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Apertura, arqueo y consulta de jornadas de caja (M20, RF-M20-001, 005, 006 y 007).
 *
 * <h2>Caja no es cobro, y esta clase es donde eso se ve</h2>
 *
 * <p>Un cobro es un hecho comercial —alguien pago una obligacion—; un movimiento de caja es un hecho
 * monetario —entro o salio plata de un cajon concreto, en una jornada concreta, con un
 * responsable—. La relacion no es uno a uno: un cobro con dos medios produce dos movimientos, uno
 * con tarjeta produce uno que no afecta el arqueo, y una jornada existe sin ningun cobro.
 *
 * <h2>El cierre es una sentencia, y sus dos condiciones son el diseno</h2>
 *
 * <pre>
 *   ... WHERE estado = 'ABIERTA'                        &lt;- cierre concurrente
 *         AND saldo_arqueo = :saldoTeoricoEsperado      &lt;- el cobro que entra mientras se cuenta
 * </pre>
 *
 * <p>La segunda es la que resuelve el caso que rompe el diseno. Si un cobro en efectivo entra entre
 * que el operador cuenta los billetes y confirma, un cierre ingenuo registraria un faltante
 * <b>que nunca existio</b> y RN-M20-004 obligaria a justificar por escrito un desvio inventado —y
 * la plata ademas esta en el cajon, asi que la jornada siguiente tambien arrancaria mal—. Con la
 * condicion: cero filas, 409 con el teorico actual, y el operador suma los billetes que estan ahi.
 *
 * <p><b>No hay {@code @Version}</b>, y no es un olvido: las escrituras que mueven el saldo son SQL
 * nativo y no la incrementarian. Ver {@link JornadaCaja}.
 */
@Service
public class CajaService {

	private static final Logger log = LoggerFactory.getLogger(CajaService.class);

	/** Tope duro del listado: una caja con miles de jornadas no puede colgar una pantalla. */
	private static final int LIMITE_MAXIMO = 200;

	private final JornadaCajaRepositoryPort jornadas;
	private final MovimientoCajaRepositoryPort movimientos;
	private final CajaAcceso acceso;
	private final AuditTrail auditTrail;

	public CajaService(
			JornadaCajaRepositoryPort jornadas,
			MovimientoCajaRepositoryPort movimientos,
			CajaAcceso acceso,
			AuditTrail auditTrail) {

		this.jornadas = jornadas;
		this.movimientos = movimientos;
		this.acceso = acceso;
		this.auditTrail = auditTrail;
	}

	/**
	 * Abre la jornada con el saldo que se conto al empezar (RF-M20-001).
	 *
	 * <p><b>La fecha de negocio no se pide, se deriva</b> de la zona de la sede: abrir una jornada
	 * "para ayer" es un ajuste contable disfrazado de operacion.
	 *
	 * <p>{@code READ_COMMITTED} por lo mismo que las reservas de 05.02: bajo {@code REPEATABLE READ}
	 * la foto se fija en la primera lectura consistente, y la comprobacion de "ya hay una abierta"
	 * podria estar mirando un estado viejo. El unique de la base es el respaldo; esto es lo que
	 * permite explicar el rechazo con el id de la jornada que ya esta abierta.
	 *
	 * @throws CajaYaAbiertaException la sede ya tiene una jornada abierta (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public JornadaCajaView abrir(
			OperatingActor actor, long consultorioId, BigDecimal saldoInicial, String moneda) {

		long organizationId = CajaAcceso.exigirContexto(actor);
		ConsultorioSnapshot sede = acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperarCaja(actor, organizationId, consultorioId);

		jornadas.findAbierta(organizationId, consultorioId).ifPresent(abierta -> {
			throw new CajaYaAbiertaException(consultorioId, abierta.getId());
		});

		Instant ahora = Instant.now();
		JornadaCaja jornada = jornadas.save(new JornadaCaja(
				organizationId,
				consultorioId,
				CajaAcceso.fechaDeNegocio(sede, ahora),
				moneda,
				saldoInicial,
				ahora,
				actor.accountId()));

		auditar(actor, AuditEvents.CAJA_ABIERTA, jornada.getId(), null, "ABIERTA",
				Map.of("saldoInicial", saldoInicial.toPlainString(),
						"fechaNegocio", jornada.getFechaNegocio().toString()),
				null, ahora);

		log.info("Caja abierta: jornadaId={} consultorioId={} fechaNegocio={} saldoInicial={} {}",
				jornada.getId(), consultorioId, jornada.getFechaNegocio(), saldoInicial, moneda);

		return JornadaCajaView.de(jornada);
	}

	/**
	 * Cierra la jornada con el saldo contado (RF-M20-006).
	 *
	 * <p><b>La diferencia se registra: no se rechaza el cierre y no se ajusta.</b> Rechazarlo
	 * dejaria al centro sin poder cerrar el dia en que realmente falta plata, que es el dia en que
	 * el registro importa; ajustarla con un movimiento que iguale borraria el hecho, porque el
	 * ledger pasaria a sumar el conteo declarado y la diferencia desapareceria de los movimientos.
	 * Lo unico que se exige es un motivo.
	 *
	 * @param saldoTeoricoEsperado el teorico que la pantalla mostraba cuando se empezo a contar
	 * @throws CajaDiferenciaSinMotivoException el arqueo no cuadra y nadie explico por que (400)
	 * @throws CajaSaldoCambioException         entraron movimientos mientras se contaba (409)
	 * @throws CajaCerradaException             ya estaba cerrada, o la cerro otro (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public JornadaCajaView cerrar(
			OperatingActor actor, long consultorioId, long jornadaId,
			BigDecimal saldoTeoricoEsperado, BigDecimal saldoDeclarado, String motivoDiferencia) {

		long organizationId = CajaAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperarCaja(actor, organizationId, consultorioId);

		JornadaCaja jornada = jornadas.findByIdInScope(organizationId, consultorioId, jornadaId)
				.orElseThrow(() -> new JornadaCajaNotAccessibleException(jornadaId));
		if (!jornada.estaAbierta()) {
			throw new CajaCerradaException(jornadaId);
		}

		// RN-M20-004. Se evalua contra el esperado que manda el cliente y no contra el saldo actual
		// a proposito: si el saldo cambio, el UPDATE de abajo lo rechaza igual y el operador tiene
		// que recontar — pedirle un motivo antes de eso seria pedirselo por una diferencia que
		// todavia no sabemos si existe.
		BigDecimal diferencia = saldoDeclarado.subtract(saldoTeoricoEsperado);
		String motivo = motivoDiferencia == null || motivoDiferencia.isBlank()
				? null : motivoDiferencia.trim();
		if (diferencia.signum() != 0 && motivo == null) {
			throw new CajaDiferenciaSinMotivoException(diferencia);
		}

		Instant ahora = Instant.now();
		int cerradas = jornadas.cerrar(
				organizationId, jornadaId, saldoTeoricoEsperado, saldoDeclarado,
				diferencia.signum() == 0 ? null : motivo, ahora, actor.accountId());

		if (cerradas == 0) {
			// Cero filas tiene dos causas y hay que distinguirlas para responder bien. Releer es
			// seguro: un UPDATE de cero filas no marca la transaccion para rollback, a diferencia
			// de un flush fallido por constraint.
			JornadaCaja actual = jornadas.findByIdInScope(organizationId, consultorioId, jornadaId)
					.orElseThrow(() -> new JornadaCajaNotAccessibleException(jornadaId));
			if (!actual.estaAbierta()) {
				throw new CajaCerradaException(jornadaId);
			}
			throw new CajaSaldoCambioException(
					jornadaId, saldoTeoricoEsperado, actual.getSaldoArqueo());
		}

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("saldoTeorico", saldoTeoricoEsperado.toPlainString());
		detalles.put("saldoDeclarado", saldoDeclarado.toPlainString());
		detalles.put("diferencia", diferencia.toPlainString());
		auditar(actor, AuditEvents.CAJA_CERRADA, jornadaId, "ABIERTA", "CERRADA",
				detalles, motivo, ahora);

		log.info("Caja cerrada: jornadaId={} teorico={} declarado={} diferencia={}",
				jornadaId, saldoTeoricoEsperado, saldoDeclarado, diferencia);

		return ver(actor, consultorioId, jornadaId);
	}

	/**
	 * Una jornada con su saldo teorico y el desglose por medio (RF-M20-005).
	 *
	 * <p>El desglose no es adorno: sin el, un arqueo de $60.000 sobre un dia en que se facturaron
	 * $120.000 parece un faltante gigante, cuando la mitad entro por tarjeta y nunca estuvo en el
	 * cajon.
	 */
	@Transactional(readOnly = true)
	public JornadaCajaView ver(OperatingActor actor, long consultorioId, long jornadaId) {
		long organizationId = CajaAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperarCaja(actor, organizationId, consultorioId);

		JornadaCaja jornada = jornadas.findByIdInScope(organizationId, consultorioId, jornadaId)
				.orElseThrow(() -> new JornadaCajaNotAccessibleException(jornadaId));

		return JornadaCajaView.de(jornada, totalesDe(organizationId, jornadaId));
	}

	/**
	 * Las jornadas de la sede, de la mas reciente a la mas vieja (RF-M20-007).
	 *
	 * <p>Los cierres historicos se consultan; <b>no se modifican por ningun camino</b>.
	 */
	@Transactional(readOnly = true)
	public List<JornadaCajaView> historico(
			OperatingActor actor, long consultorioId, String estado,
			LocalDate desde, LocalDate hasta, int limite, int desplazamiento) {

		long organizationId = CajaAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperarCaja(actor, organizationId, consultorioId);

		return jornadas.findHistorico(
						organizationId, consultorioId, estado, desde, hasta,
						Math.min(Math.max(limite, 1), LIMITE_MAXIMO), Math.max(desplazamiento, 0))
				.stream()
				.map(JornadaCajaView::de)
				.toList();
	}

	/**
	 * Confronta el saldo materializado contra la suma del ledger.
	 *
	 * <p>Es el criterio de aceptacion de la etapa —"el saldo se reconstruye desde los
	 * movimientos"— expuesto como una operacion propia en vez de como un comentario. Si las dos
	 * cifras discrepan, <b>la que miente es la columna</b>: el ledger es la fuente de verdad.
	 *
	 * @return {@code true} si coinciden
	 */
	@Transactional(readOnly = true)
	public boolean saldoReconstruible(
			OperatingActor actor, long consultorioId, long jornadaId) {

		long organizationId = CajaAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperarCaja(actor, organizationId, consultorioId);

		JornadaCaja jornada = jornadas.findByIdInScope(organizationId, consultorioId, jornadaId)
				.orElseThrow(() -> new JornadaCajaNotAccessibleException(jornadaId));

		BigDecimal reconstruido = jornada.getSaldoInicial()
				.add(Optional.ofNullable(
						movimientos.reconstruirSaldoArqueable(organizationId, jornadaId))
						.orElse(BigDecimal.ZERO));

		// compareTo y no equals: BigDecimal.equals distingue 8500 de 8500.00, y con eso dos cifras
		// identicas darian distinto segun como las devolvio el motor. Misma trampa que 07.02.
		return jornada.getSaldoArqueo().compareTo(reconstruido) == 0;
	}

	private List<JornadaCajaView.TotalPorMedio> totalesDe(long organizationId, long jornadaId) {
		return movimientos.totalesPorMedio(organizationId, jornadaId).stream()
				.map(fila -> {
					String medio = (String) fila[0];
					return new JornadaCajaView.TotalPorMedio(
							medio,
							(BigDecimal) fila[1],
							MedioDePago.EFECTIVO.name().equals(medio));
				})
				.toList();
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	private void auditar(
			OperatingActor actor, String eventType, Long jornadaId,
			String estadoAnterior, String estadoNuevo,
			Map<String, String> detalles, String motivo, Instant ahora) {

		auditTrail.record(new AuditEntry(
				actor.contextOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_JORNADA_CAJA,
				jornadaId,
				estadoAnterior,
				estadoNuevo,
				detalles,
				motivo,
				AuditEvents.correlationId(),
				ahora));
	}
}
