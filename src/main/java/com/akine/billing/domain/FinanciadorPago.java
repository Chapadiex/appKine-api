package com.akine.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Plata que el financiador pago contra un lote (RF-M21-007).
 *
 * <h2>Esto NO es un {@link Cobro}</h2>
 *
 * <p>No se reuso el agregado de 07.02, y es deliberado: un {@code Cobro} tiene {@code persona_id},
 * imputaciones contra obligaciones de un paciente y un <b>comprobante correlativo fiscal</b> que el
 * centro le emite a quien pago. Nada de eso aplica a una transferencia mensual de una obra social
 * contra un lote. Forzarlo obligaria a inventar una persona, imputaciones que no existen y un
 * comprobante que no corresponde.
 *
 * <h2>Este es el unico punto donde M21 toca la caja</h2>
 *
 * <p>RN-M21-002: <b>el pago del financiador genera caja solo cuando se recibe.</b> El movimiento se
 * asienta en la <b>misma transaccion</b>, y el vinculo lo guarda el movimiento —{@code tipo_origen}
 * {@code PAGO_FINANCIADOR} y {@code referencia_origen} igual a este id—, exactamente como
 * {@code cobro} y {@code MovimientoCaja} se relacionan desde 07.03. Una columna aca seria la
 * segunda copia del mismo vinculo, y ademas dejaria sin efecto el unique que hace idempotente el
 * reintento.
 *
 * <p>Si el asiento se dejara para despues, nada obligaria a que alguien lo hiciera, nada
 * verificaria el importe, y la caja y la cuenta corriente divergirian <b>sin que falle nada</b>.
 * Mismo razonamiento, y misma contrapartida asumida, que {@code CajaDeCobro}.
 *
 * <h2>Append-only</h2>
 *
 * <p>Sin {@code @Version}, sin {@code updated_at}, sin baja logica, y el puerto no declara
 * {@code update} ni {@code delete}. Un historial de pagos que se puede editar no es un historial. Un
 * pago mal registrado se compensa revirtiendo su movimiento de caja y registrando el correcto.
 */
@Entity
@Table(name = "financiador_pago")
public class FinanciadorPago {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "financiador_id", nullable = false, updatable = false)
	private Long financiadorId;

	@Column(name = "presentacion_id", nullable = false, updatable = false)
	private Long presentacionId;

	@Column(name = "importe", nullable = false, precision = 12, scale = 2, updatable = false)
	private BigDecimal importe;

	@Column(name = "moneda", nullable = false, length = 3, updatable = false)
	private String moneda;

	@Enumerated(EnumType.STRING)
	@Column(name = "medio", nullable = false, length = 24, updatable = false)
	private MedioDePago medio;

	/** Cuando pago el financiador, que puede no ser cuando se cargo. */
	@Column(name = "fecha_pago", nullable = false, updatable = false)
	private LocalDate fechaPago;

	@Column(name = "referencia", length = 80, updatable = false)
	private String referencia;

	@Column(name = "registrado_en", nullable = false, updatable = false)
	private Instant registradoEn;

	@Column(name = "registrado_por_cuenta_id", nullable = false, updatable = false)
	private Long registradoPorCuentaId;

	@Column(name = "idempotency_key", length = 80, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_hash", length = 64, updatable = false)
	private String requestHash;

	protected FinanciadorPago() {
		// Requerido por JPA.
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	public FinanciadorPago(
			long organizationId,
			long consultorioId,
			long financiadorId,
			long presentacionId,
			BigDecimal importe,
			String moneda,
			MedioDePago medio,
			LocalDate fechaPago,
			String referencia,
			Instant registradoEn,
			long registradoPorCuentaId,
			String idempotencyKey,
			String requestHash) {

		if (importe == null || importe.signum() <= 0) {
			throw new IllegalArgumentException("Un pago se registra por un importe positivo: " + importe);
		}

		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.financiadorId = financiadorId;
		this.presentacionId = presentacionId;
		this.importe = importe;
		this.moneda = moneda;
		this.medio = medio;
		this.fechaPago = fechaPago;
		this.referencia = referencia;
		this.registradoEn = registradoEn;
		this.registradoPorCuentaId = registradoPorCuentaId;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
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

	public Long getFinanciadorId() {
		return financiadorId;
	}

	public Long getPresentacionId() {
		return presentacionId;
	}

	public BigDecimal getImporte() {
		return importe;
	}

	public String getMoneda() {
		return moneda;
	}

	public MedioDePago getMedio() {
		return medio;
	}

	public LocalDate getFechaPago() {
		return fechaPago;
	}

	public String getReferencia() {
		return referencia;
	}

	public Instant getRegistradoEn() {
		return registradoEn;
	}

	public String getRequestHash() {
		return requestHash;
	}
}
