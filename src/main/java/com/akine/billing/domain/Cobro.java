package com.akine.billing.domain;

import com.akine.billing.domain.exception.CobroAnuladoException;
import com.akine.billing.domain.exception.ImputacionesNoSumanException;
import com.akine.billing.domain.exception.MediosNoSumanException;
import com.akine.billing.domain.exception.ObligacionNoCobrableException;
import com.akine.billing.domain.exception.SaldoAFavorInsuficienteException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Dinero recibido. <b>No es la deuda ni es la caja.</b>
 *
 * <p>Regla maestra de M18/M19/M20: la Obligacion dice cuanto se debe, el Cobro dice que se pago y
 * la Caja dice que el dinero entro al arqueo del dia. Confundirlas es el error del UML de 2019.
 *
 * <h2>Las dos sumas que tienen que dar</h2>
 *
 * <p>{@code suma(medios) == total} y {@code suma(imputaciones) == total}. Son invariantes de
 * RN-M19 y <b>no se pueden expresar como CHECK</b>: MySQL no admite subconsultas ahi. Las verifica
 * {@link #exigirCoherente()} dentro de la misma transaccion que crea el cobro.
 *
 * <p>Sin la primera, un cobro de 8500 con un medio de 850 —un cero de menos— entra igual, la deuda
 * queda saldada y en la caja falta plata que nadie va a poder explicar. Sin la segunda, hay dinero
 * recibido sin destino.
 *
 * <h2>Un cobro confirmado no se edita (RN-M19-003)</h2>
 *
 * <p>Es un hecho economico consumado, con comprobante emitido y numerado. Lo que registro —total,
 * medios, imputaciones originales— no cambia nunca. Desde F-3 hay tres cosas que le pueden pasar
 * despues, y ninguna reescribe lo anterior:
 *
 * <ul>
 *   <li><b>Imputar su saldo a favor</b> ({@link #imputar}): agrega una imputacion y descuenta el
 *       anticipo. No mueve caja: la plata ya entro.</li>
 *   <li><b>Reintegrar su saldo a favor</b> ({@link #reintegrar}): descuenta el anticipo; el
 *       reintegro es otra fila y otro movimiento de caja.</li>
 *   <li><b>Anularlo</b> ({@link #anular}): queda marcado, con actor y motivo. Sus efectos los
 *       revierte el servicio con filas nuevas.</li>
 * </ul>
 *
 * <h2>El anticipo</h2>
 *
 * <p>{@code saldoAFavor} es lo recibido que todavia no se aplico (DP-06/ADR-0013). Se materializa
 * por la misma razon que el saldo de la obligacion: imputarlo tiene que ser una resta bajo lock, no
 * una suma de filas. La invariante completa es
 * {@code total = suma(imputaciones) + saldoAFavor + suma(reintegros)}; los reintegros viven en su
 * propia tabla, asi que esta clase verifica la parte que tiene en la mano al nacer.
 */
@Entity
@Table(name = "cobro")
public class Cobro {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "persona_id", nullable = false, updatable = false)
	private Long personaId;

	@Column(name = "total", nullable = false, precision = 12, scale = 2, updatable = false)
	private BigDecimal total;

	@Column(name = "moneda", nullable = false, length = 3, updatable = false)
	private String moneda;

	@Column(name = "comprobante_numero", nullable = false, updatable = false)
	private Integer comprobanteNumero;

	@Column(name = "cobrado_en", nullable = false, updatable = false)
	private Instant cobradoEn;

	@Column(name = "cobrado_por_cuenta_id", nullable = false, updatable = false)
	private Long cobradoPorCuentaId;

	@Column(name = "idempotency_key", length = 80, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_hash", length = 64, updatable = false)
	private String requestHash;

	/** Lo recibido que todavia no se imputo ni se reintegro. Ver la cabecera. */
	@Column(name = "saldo_a_favor", nullable = false, precision = 12, scale = 2)
	private BigDecimal saldoAFavor;

	/** El instante de la anulacion. V37 reservo esta columna para eso. */
	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "anulado_por_cuenta_id")
	private Long anuladoPorCuentaId;

	@Column(name = "motivo_anulacion", length = 280)
	private String motivoAnulacion;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	/**
	 * Los medios y las imputaciones viven con el cobro y no por separado.
	 *
	 * <p>Son parte del mismo hecho: un cobro sin medios no dice como entro la plata y uno sin
	 * imputaciones no dice que paga. No tienen ciclo de vida propio ni se consultan sin el cobro,
	 * asi que la cascada es correcta — es el caso en que un agregado JPA se justifica.
	 */
	@OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
	@JoinColumn(name = "cobro_id", nullable = false)
	private List<CobroMedio> medios = new ArrayList<>();

	@OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
	@JoinColumn(name = "cobro_id", nullable = false)
	private List<CobroImputacion> imputaciones = new ArrayList<>();

	protected Cobro() {
		// Requerido por JPA.
	}

	public Cobro(
			long organizationId,
			long consultorioId,
			long personaId,
			BigDecimal total,
			String moneda,
			int comprobanteNumero,
			Instant cobradoEn,
			long cobradoPorCuentaId,
			String idempotencyKey,
			String requestHash,
			List<CobroMedio> medios,
			List<CobroImputacion> imputaciones) {

		this(organizationId, consultorioId, personaId, total, moneda, comprobanteNumero, cobradoEn,
				cobradoPorCuentaId, idempotencyKey, requestHash, medios, imputaciones, BigDecimal.ZERO);
	}

	/**
	 * Un cobro con anticipo: lo que no se imputa queda a favor del paciente.
	 *
	 * @param anticipo lo que se declara a favor. Explicito y no inferido del sobrante: inferirlo
	 *                 convertiria un error de tipeo en una imputacion en un saldo a favor silencioso
	 */
	@SuppressWarnings("checkstyle:ParameterNumber")
	public Cobro(
			long organizationId,
			long consultorioId,
			long personaId,
			BigDecimal total,
			String moneda,
			int comprobanteNumero,
			Instant cobradoEn,
			long cobradoPorCuentaId,
			String idempotencyKey,
			String requestHash,
			List<CobroMedio> medios,
			List<CobroImputacion> imputaciones,
			BigDecimal anticipo) {

		if (total == null || total.signum() <= 0) {
			throw new IllegalArgumentException("Un cobro es por un importe positivo: " + total);
		}
		if (anticipo == null || anticipo.signum() < 0) {
			throw new IllegalArgumentException("El anticipo no puede ser negativo: " + anticipo);
		}

		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.personaId = personaId;
		this.total = total;
		this.moneda = moneda;
		this.comprobanteNumero = comprobanteNumero;
		this.cobradoEn = cobradoEn;
		this.cobradoPorCuentaId = cobradoPorCuentaId;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
		this.medios = new ArrayList<>(medios);
		this.imputaciones = new ArrayList<>(imputaciones);
		this.saldoAFavor = anticipo;

		// Las imputaciones que nacen con el cobro ocurren cuando el cobro, y las hace quien cobra.
		this.imputaciones.forEach(imputacion -> imputacion.sellar(cobradoEn, cobradoPorCuentaId));

		exigirCoherente();
	}

	/**
	 * Las dos sumas de RN-M19. Ver la cabecera: no hay CHECK que pueda hacerlas cumplir.
	 *
	 * <p>Se comparan con {@code compareTo} y no con {@code equals}: {@code BigDecimal.equals}
	 * distingue 8500 de 8500.00 —compara la escala ademas del valor— y con eso un cobro correcto
	 * seria rechazado segun como el cliente haya escrito el numero.
	 */
	private void exigirCoherente() {
		BigDecimal sumaMedios = medios.stream()
				.map(CobroMedio::getImporte)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		if (sumaMedios.compareTo(total) != 0) {
			throw new MediosNoSumanException(sumaMedios, total);
		}

		BigDecimal sumaImputada = imputaciones.stream()
				.map(CobroImputacion::getImporte)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		if (sumaImputada.add(saldoAFavor).compareTo(total) != 0) {
			throw new ImputacionesNoSumanException(sumaImputada, saldoAFavor, total);
		}
	}

	// =================================================================================
	// Lo que le puede pasar despues de confirmado (F-3). Ver la cabecera.
	// =================================================================================

	/**
	 * Aplica parte del saldo a favor a una deuda (imputacion posterior, RF-M19-003).
	 *
	 * <p>Valida aca y no en el servicio porque son invariantes del cobro: que este vigente, que el
	 * saldo alcance, y que no impute dos veces a la misma deuda —{@code uk_cobro_imputacion}—. Lo
	 * que esta clase no puede saber, que la deuda tenga ese saldo, lo decide el UPDATE condicional
	 * de la obligacion.
	 *
	 * @throws CobroAnuladoException            el cobro esta anulado (409)
	 * @throws SaldoAFavorInsuficienteException no queda tanto a favor (409)
	 * @throws ObligacionNoCobrableException    este cobro ya imputo a esa deuda (409)
	 */
	public void imputar(CobroImputacion imputacion) {
		exigirVigente();
		exigirSaldoAFavor(imputacion.getImporte());
		boolean yaImputada = imputaciones.stream()
				.anyMatch(previa -> previa.getObligacionId().equals(imputacion.getObligacionId()));
		if (yaImputada) {
			throw new ObligacionNoCobrableException(imputacion.getObligacionId(),
					"este cobro ya esta imputado a esa deuda");
		}
		this.saldoAFavor = saldoAFavor.subtract(imputacion.getImporte());
		this.imputaciones.add(imputacion);
	}

	/**
	 * Descuenta del saldo a favor lo que se devuelve en dinero. El reintegro es otra fila.
	 *
	 * @throws CobroAnuladoException            el cobro esta anulado (409)
	 * @throws SaldoAFavorInsuficienteException no queda tanto a favor (409)
	 */
	public void reintegrar(BigDecimal importe) {
		if (importe == null || importe.signum() <= 0) {
			throw new IllegalArgumentException("Un reintegro es por un importe positivo: " + importe);
		}
		exigirVigente();
		exigirSaldoAFavor(importe);
		this.saldoAFavor = saldoAFavor.subtract(importe);
	}

	/**
	 * Marca el cobro como anulado (RF-M19-007). <b>No borra nada</b> y no revierte nada por si
	 * solo: devolver el saldo a las deudas y compensar la caja lo hace el servicio, en la misma
	 * transaccion. El saldo a favor que quedaba se anula con el cobro: la reversion de caja ya
	 * devuelve esa plata.
	 *
	 * @throws CobroAnuladoException ya estaba anulado (409): anular dos veces revertiria la caja dos veces
	 */
	public void anular(String motivo, Instant cuando, long porCuentaId) {
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException("Anular un cobro exige motivo");
		}
		exigirVigente();
		this.deletedAt = Objects.requireNonNull(cuando);
		this.anuladoPorCuentaId = porCuentaId;
		this.motivoAnulacion = motivo.trim();
		this.saldoAFavor = BigDecimal.ZERO.setScale(2);
	}

	public boolean estaAnulado() {
		return deletedAt != null;
	}

	/** La imputacion posterior que se registro con esa clave, si la hay. */
	public Optional<CobroImputacion> imputacionConClave(String idempotencyKey) {
		return imputaciones.stream()
				.filter(imputacion -> idempotencyKey.equals(imputacion.getIdempotencyKey()))
				.findFirst();
	}

	private void exigirVigente() {
		if (estaAnulado()) {
			throw new CobroAnuladoException(id);
		}
	}

	private void exigirSaldoAFavor(BigDecimal importe) {
		if (saldoAFavor.compareTo(importe) < 0) {
			throw new SaldoAFavorInsuficienteException(id, importe, saldoAFavor);
		}
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getConsultorioId() {
		return consultorioId;
	}

	public Long getPersonaId() {
		return personaId;
	}

	public BigDecimal getTotal() {
		return total;
	}

	public String getMoneda() {
		return moneda;
	}

	public Integer getComprobanteNumero() {
		return comprobanteNumero;
	}

	public Instant getCobradoEn() {
		return cobradoEn;
	}

	public Long getCobradoPorCuentaId() {
		return cobradoPorCuentaId;
	}

	public String getRequestHash() {
		return requestHash;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public List<CobroMedio> getMedios() {
		return List.copyOf(medios);
	}

	public List<CobroImputacion> getImputaciones() {
		return List.copyOf(imputaciones);
	}

	public long getVersion() {
		return version;
	}

	public BigDecimal getSaldoAFavor() {
		return saldoAFavor;
	}

	/** El instante de la anulacion, o {@code null} si esta vigente. */
	public Instant getAnuladoEn() {
		return deletedAt;
	}

	public Long getAnuladoPorCuentaId() {
		return anuladoPorCuentaId;
	}

	public String getMotivoAnulacion() {
		return motivoAnulacion;
	}
}
