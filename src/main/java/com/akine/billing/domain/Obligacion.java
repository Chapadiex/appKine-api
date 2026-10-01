package com.akine.billing.domain;

import com.akine.billing.domain.exception.ObligacionAnuladaException;
import com.akine.billing.domain.exception.ObligacionConCobrosException;
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

/**
 * Una deuda derivada de una prestacion. <b>No es el cobro ni es la caja.</b>
 *
 * <p>Es la regla maestra de M18/M19/M20 y el error que el UML de 2019 cometia: alli Turno, Atencion
 * y Cobro eran la misma cosa. Aca la Obligacion dice cuanto se debe; el Cobro (M19) dice que se
 * pago; la Caja (M20) dice que el dinero entro. Esta clase no tiene medio de pago, ni comprobante,
 * ni movimiento.
 *
 * <h2>El importe se congela</h2>
 *
 * <p>{@code snapshotPrecio} y {@code snapshotNombre} se copian de la Oferta al devengar. Editar el
 * precio de una oferta manana no puede cambiar lo que se le debe a alguien por una sesion de hoy:
 * eso es reescribir una cuenta corriente. Mismo criterio que el Turno con su intervalo.
 *
 * <h2>El saldo se materializa, y no es pereza</h2>
 *
 * <p>Es una derivacion —importe menos lo imputado— pero se guarda. Calcularlo al leer obligaria a
 * M19 a sumar todos los cobros DENTRO del lock que imputa, y no habria nada que impida que dos
 * imputaciones concurrentes lo dejen en negativo. Con la columna, imputar es un UPDATE condicional y
 * el CHECK de V36 impide el estado imposible.
 */
@Entity
@Table(name = "obligacion")
public class Obligacion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	/**
	 * La prestacion que origino la deuda. <b>{@code null} cuando el origen no es
	 * {@link OrigenObligacion#SESION}</b>: una compra de pack no tiene sesion (AKINE-08.06).
	 */
	@Column(name = "sesion_id", updatable = false)
	private Long sesionId;

	@Enumerated(EnumType.STRING)
	@Column(name = "origen", nullable = false, length = 16, updatable = false)
	private OrigenObligacion origen;

	/** El pase comprado que origino la deuda. {@code null} para el origen {@code SESION}. */
	@Column(name = "pase_id", updatable = false)
	private Long paseId;

	@Column(name = "persona_id", nullable = false, updatable = false)
	private Long personaId;

	@Enumerated(EnumType.STRING)
	@Column(name = "responsable", nullable = false, length = 16, updatable = false)
	private Responsable responsable;

	@Column(name = "importe_original", nullable = false, precision = 12, scale = 2, updatable = false)
	private BigDecimal importeOriginal;

	@Column(name = "saldo", nullable = false, precision = 12, scale = 2)
	private BigDecimal saldo;

	@Column(name = "moneda", nullable = false, length = 3, updatable = false)
	private String moneda;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 16)
	private EstadoObligacion estado;

	@Column(name = "oferta_id", nullable = false, updatable = false)
	private Long ofertaId;

	@Column(name = "snapshot_nombre", nullable = false, length = 160, updatable = false)
	private String snapshotNombre;

	@Column(name = "snapshot_precio", nullable = false, precision = 12, scale = 2, updatable = false)
	private BigDecimal snapshotPrecio;

	/** Reservado para AKINE-03.05. Ver V36: se declara para que agregarlo sea sumar datos. */
	@Column(name = "snapshot_convenio_id", updatable = false)
	private Long snapshotConvenioId;

	/** Reservado para AKINE-03.05. */
	@Column(name = "snapshot_arancel_id", updatable = false)
	private Long snapshotArancelId;

	@Column(name = "devengada_en", nullable = false, updatable = false)
	private Instant devengadaEn;

	@Column(name = "anulada_en")
	private Instant anuladaEn;

	@Column(name = "anulada_por_cuenta_id")
	private Long anuladaPorCuentaId;

	@Column(name = "motivo_anulacion", length = 280)
	private String motivoAnulacion;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Obligacion() {
		// Requerido por JPA.
	}

	/**
	 * La deuda que devenga una <b>prestacion concretada</b> (RF-M18-001).
	 *
	 * <p>Es el constructor que existe desde 07.01 y no cambio de forma: su origen es
	 * {@link OrigenObligacion#SESION} y no se pasa, porque un constructor que recibe una sesion no
	 * puede ser de otra cosa.
	 */
	public Obligacion(
			long organizationId,
			long consultorioId,
			long sesionId,
			long personaId,
			Responsable responsable,
			BigDecimal importe,
			String moneda,
			long ofertaId,
			String snapshotNombre,
			Instant devengadaEn) {

		this(organizationId, consultorioId, OrigenObligacion.SESION, sesionId, null, personaId,
				responsable, importe, moneda, ofertaId, snapshotNombre, devengadaEn);
	}

	/**
	 * La deuda que devenga la <b>compra de un pack de creditos</b> (RF-M18-009, RF-M29-002).
	 *
	 * <p>Sin {@code sesionId}: no hubo prestacion todavia, y ponerle una seria afirmar que la hubo.
	 * La deuda nace de la venta y el consumo de esos creditos <b>no devenga nada mas</b> — cobrar
	 * el pack y ademas cada clase es cobrar dos veces lo mismo.
	 *
	 * <p>El {@code ofertaId} es el de la oferta cuyos creditos se vendieron, congelado en la venta:
	 * sirve para que la cuenta corriente pueda explicar de que era el pack aunque el producto se
	 * haya editado despues.
	 */
	public static Obligacion porVentaDePase(
			long organizationId,
			long consultorioId,
			long paseId,
			long personaId,
			BigDecimal importe,
			String moneda,
			long ofertaId,
			String snapshotNombre,
			Instant devengadaEn) {

		return new Obligacion(
				organizationId, consultorioId, OrigenObligacion.VENTA_PASE, null, paseId, personaId,
				// El responsable de un pack es SIEMPRE el paciente: no existe financiador que
				// pague creditos anticipados, y admitir otro valor abriria un estado que ningun
				// circuito de presentacion sabe resolver.
				Responsable.PACIENTE, importe, moneda, ofertaId, snapshotNombre, devengadaEn);
	}

	private Obligacion(
			long organizationId,
			long consultorioId,
			OrigenObligacion origen,
			Long sesionId,
			Long paseId,
			long personaId,
			Responsable responsable,
			BigDecimal importe,
			String moneda,
			long ofertaId,
			String snapshotNombre,
			Instant devengadaEn) {

		if (importe == null || importe.signum() <= 0) {
			// "No cobrar saldo cero" es regla de la etapa. Una deuda de cero solo ensucia la
			// cuenta corriente con filas que nadie va a pagar, y una negativa es un credito
			// disfrazado de deuda.
			throw new IllegalArgumentException("Una obligacion se devenga por un importe positivo: " + importe);
		}

		// Exactamente un origen, y el origen concuerda con la columna que lo materializa. Es el
		// mismo invariante que ck_obligacion_origen hace cumplir en la base; las dos cosas hacen
		// falta, porque el motor garantiza que ninguna fila imposible exista y esto da el error
		// antes de llegar al motor.
		if ((sesionId == null) == (paseId == null)) {
			throw new IllegalArgumentException(
					"Una obligacion nace de exactamente un hecho: o una sesion o una venta");
		}

		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.origen = origen;
		this.sesionId = sesionId;
		this.paseId = paseId;
		this.personaId = personaId;
		this.responsable = responsable;
		this.importeOriginal = importe;
		this.saldo = importe;
		this.moneda = moneda;
		this.estado = EstadoObligacion.PENDIENTE;
		this.ofertaId = ofertaId;
		this.snapshotNombre = snapshotNombre;
		this.snapshotPrecio = importe;
		this.devengadaEn = devengadaEn;
	}

	/**
	 * Anula la deuda con motivo obligatorio.
	 *
	 * <p><b>No borra: anula.</b> Una deuda que desaparece de la base es una cuenta corriente que no
	 * cuadra y que nadie puede auditar seis meses despues.
	 *
	 * <p><b>Una obligacion con cobros imputados no se anula</b>, y por eso el control mira el saldo
	 * y no el estado. Anular algo que ya se cobro dejaria plata en la caja sin deuda que la
	 * justifique; lo que corresponde en ese caso es una devolucion, que es M19 y tiene su propio
	 * registro.
	 */
	public void anular(String motivo, Instant occurredAt, long anuladaPorCuentaId) {
		if (estado == EstadoObligacion.ANULADA) {
			throw new ObligacionAnuladaException(id);
		}
		if (saldo.compareTo(importeOriginal) != 0) {
			throw new ObligacionConCobrosException(id, importeOriginal.subtract(saldo));
		}
		this.estado = EstadoObligacion.ANULADA;
		this.saldo = BigDecimal.ZERO.setScale(importeOriginal.scale());
		this.anuladaEn = occurredAt;
		this.anuladaPorCuentaId = anuladaPorCuentaId;
		this.motivoAnulacion = motivo;
	}

	/** {@code true} si todavia se le puede imputar un cobro. Lo consume AKINE-07.02. */
	public boolean admiteCobro() {
		return estado == EstadoObligacion.PENDIENTE || estado == EstadoObligacion.PARCIAL;
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

	public Long getSesionId() {
		return sesionId;
	}

	public OrigenObligacion getOrigen() {
		return origen;
	}

	public Long getPaseId() {
		return paseId;
	}

	public Long getPersonaId() {
		return personaId;
	}

	public Responsable getResponsable() {
		return responsable;
	}

	public BigDecimal getImporteOriginal() {
		return importeOriginal;
	}

	public BigDecimal getSaldo() {
		return saldo;
	}

	public String getMoneda() {
		return moneda;
	}

	public EstadoObligacion getEstado() {
		return estado;
	}

	public Long getOfertaId() {
		return ofertaId;
	}

	public String getSnapshotNombre() {
		return snapshotNombre;
	}

	public BigDecimal getSnapshotPrecio() {
		return snapshotPrecio;
	}

	public Instant getDevengadaEn() {
		return devengadaEn;
	}

	public Instant getAnuladaEn() {
		return anuladaEn;
	}

	public String getMotivoAnulacion() {
		return motivoAnulacion;
	}

	public long getVersion() {
		return version;
	}
}
