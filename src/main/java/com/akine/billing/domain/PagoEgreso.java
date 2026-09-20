package com.akine.billing.domain;

import com.akine.billing.domain.exception.PagoEgresoYaAnuladoException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * El acto de saldar un {@link Egreso}, total o parcialmente.
 *
 * <h2>Un medio por pago, y no una tabla hija</h2>
 *
 * <p>A diferencia del cobro —donde el paciente paga mitad efectivo y mitad tarjeta en <b>un</b>
 * acto de mostrador—, pagarle a un profesional mitad en efectivo y mitad por transferencia son
 * <b>dos hechos distintos</b>, con dos comprobantes y probablemente dos dias. Modelarlo con una
 * tabla hija daria una hija que nunca tiene mas de una fila.
 *
 * <h2>No tiene {@code movimientoCajaId}</h2>
 *
 * <p>Es el movimiento el que apunta al pago ({@code tipo_origen = 'PAGO_EGRESO'},
 * {@code referencia_origen = pago.id}), exactamente como 07.03 decidio para el cobro. El unique
 * {@code uk_movimiento_caja_origen} que ya existe garantiza gratis que <b>un pago produzca a lo
 * sumo un movimiento</b> y que el reintento no duplique la salida de plata.
 *
 * <p>Ademas evita la columna nullable que el orden de insercion obligaria a tener: el {@code id}
 * del pago no existe antes de insertarlo, asi que el movimiento no se puede asentar antes.
 *
 * <h2>Anular no es borrar</h2>
 *
 * <p>RN-M22-002. El pago anulado conserva su fila, con motivo, actor y fecha. Lo que devuelve la
 * plata al cajon es una fila propia de {@code REVERSION_DE_EGRESO} en el ledger de caja, asentada
 * por el mismo metodo que 07.03 escribio — no hay un camino de reversion nuevo.
 */
@Entity
@Table(name = "pago_egreso")
public class PagoEgreso {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * Desnormalizado por ADR-0004, que no exceptua a las tablas hijas: una consulta que se olvide
	 * del JOIN cruzaria tenants sin fallar. Misma convencion que {@code cobro_medio} (V37).
	 */
	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "egreso_id", nullable = false, updatable = false)
	private Long egresoId;

	@Column(name = "importe", nullable = false, precision = 12, scale = 2, updatable = false)
	private BigDecimal importe;

	@Column(name = "moneda", nullable = false, length = 3, updatable = false)
	private String moneda;

	@Enumerated(EnumType.STRING)
	@Column(name = "medio", nullable = false, length = 24, updatable = false)
	private MedioDePago medio;

	/** Numero de transferencia o de recibo: lo que hace conciliable un pago que no es en efectivo. */
	@Column(name = "referencia", length = 80, updatable = false)
	private String referencia;

	/** Propia, no heredada de la jornada. Mismo criterio que {@code movimiento_caja}. */
	@Column(name = "fecha_negocio", nullable = false, updatable = false)
	private LocalDate fechaNegocio;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 16)
	private EstadoPagoEgreso estado;

	@Column(name = "pagado_en", nullable = false, updatable = false)
	private Instant pagadoEn;

	@Column(name = "pagado_por_cuenta_id", nullable = false, updatable = false)
	private Long pagadoPorCuentaId;

	@Column(name = "anulado_en")
	private Instant anuladoEn;

	@Column(name = "anulado_por_cuenta_id")
	private Long anuladoPorCuentaId;

	@Column(name = "motivo_anulacion", length = 280)
	private String motivoAnulacion;

	@Column(name = "idempotency_key", length = 80, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_hash", length = 64, updatable = false)
	private String requestHash;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected PagoEgreso() {
		// Requerido por JPA.
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	public PagoEgreso(
			long organizationId,
			long consultorioId,
			long egresoId,
			BigDecimal importe,
			String moneda,
			MedioDePago medio,
			String referencia,
			LocalDate fechaNegocio,
			Instant pagadoEn,
			long pagadoPorCuentaId,
			String idempotencyKey,
			String requestHash) {

		if (importe == null || importe.signum() <= 0) {
			throw new IllegalArgumentException("Un pago mueve un importe positivo: " + importe);
		}
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.egresoId = egresoId;
		this.importe = importe;
		this.moneda = moneda;
		this.medio = medio;
		this.referencia = referencia;
		this.fechaNegocio = fechaNegocio;
		this.estado = EstadoPagoEgreso.CONFIRMADO;
		this.pagadoEn = pagadoEn;
		this.pagadoPorCuentaId = pagadoPorCuentaId;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
	}

	/**
	 * Marca el pago como anulado. <b>No borra la fila</b> (RN-M22-002).
	 *
	 * <p>Devolver la plata al cajon y el saldo al egreso son efectos del servicio, no de esta
	 * clase: los dos son escrituras sobre otros agregados y ninguna entidad puede garantizarlas.
	 *
	 * @throws PagoEgresoYaAnuladoException <b>409</b>
	 */
	public void anular(String motivo, Instant cuando, long anuladoPorCuentaId) {
		if (estado == EstadoPagoEgreso.ANULADO) {
			throw new PagoEgresoYaAnuladoException(id);
		}
		this.estado = EstadoPagoEgreso.ANULADO;
		this.anuladoEn = cuando;
		this.anuladoPorCuentaId = anuladoPorCuentaId;
		this.motivoAnulacion = motivo;
	}

	public boolean estaVigente() {
		return estado == EstadoPagoEgreso.CONFIRMADO;
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

	public Long getEgresoId() {
		return egresoId;
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

	public String getReferencia() {
		return referencia;
	}

	public LocalDate getFechaNegocio() {
		return fechaNegocio;
	}

	public EstadoPagoEgreso getEstado() {
		return estado;
	}

	public Instant getPagadoEn() {
		return pagadoEn;
	}

	public Long getPagadoPorCuentaId() {
		return pagadoPorCuentaId;
	}

	public Instant getAnuladoEn() {
		return anuladoEn;
	}

	public String getMotivoAnulacion() {
		return motivoAnulacion;
	}

	public String getRequestHash() {
		return requestHash;
	}

	public long getVersion() {
		return version;
	}
}
