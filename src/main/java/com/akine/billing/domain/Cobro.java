package com.akine.billing.domain;

import com.akine.billing.domain.exception.ImputacionesNoSumanException;
import com.akine.billing.domain.exception.MediosNoSumanException;
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
 * <h2>Un cobro confirmado no se edita</h2>
 *
 * <p>Es un hecho economico consumado, con comprobante emitido y numerado. Corregirlo es anular y
 * volver a cobrar, y anular exige el reintegro que mueve la caja — AKINE-07.03, fuera del Paquete
 * B. Por eso esta clase no tiene ni un metodo de modificacion: nace completo o no nace.
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

	@Column(name = "deleted_at")
	private Instant deletedAt;

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

		if (total == null || total.signum() <= 0) {
			throw new IllegalArgumentException("Un cobro es por un importe positivo: " + total);
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
		if (sumaImputada.compareTo(total) != 0) {
			throw new ImputacionesNoSumanException(sumaImputada, total);
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
}
