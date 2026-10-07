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
import java.time.LocalDate;
import java.util.Optional;

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

	@Column(name = "sesion_id", nullable = false, updatable = false)
	private Long sesionId;

	@Column(name = "persona_id", nullable = false, updatable = false)
	private Long personaId;

	@Enumerated(EnumType.STRING)
	@Column(name = "responsable", nullable = false, length = 16, updatable = false)
	private Responsable responsable;

	/**
	 * Quien es el financiador cuando {@link #responsable} es {@link Responsable#FINANCIADOR}.
	 *
	 * <p>Agregada por AKINE-07.04 (V56) y <b>obligatoria en los dos sentidos por CHECK</b>: una
	 * deuda de financiador sin financiador no se puede reclamar, y una deuda de paciente con un
	 * financiador colgado aparece en una bandeja que no le corresponde.
	 *
	 * <p>Sin ella no hay por donde agrupar: RF-M21-001 pide listar las obligaciones de un
	 * financiador por periodo. Se descarto deducirla navegando
	 * {@code snapshot_convenio_id -> convenio -> financiador}, que obligaria a {@code billing} a
	 * recorrer el agregado de {@code contracting} y que ademas dejaria sin financiador a las deudas
	 * historicas de un convenio dado de baja.
	 *
	 * <p>La escribe {@code ObligacionDevengador} desde AKINE F-4, con el financiador de la
	 * cobertura que se aplico.
	 */
	@Column(name = "financiador_id", updatable = false)
	private Long financiadorId;

	/** Que parte de la prestacion es esta fila (AKINE F-4). Ver {@link ConceptoObligacion}. */
	@Enumerated(EnumType.STRING)
	@Column(name = "concepto", nullable = false, length = 16, updatable = false)
	private ConceptoObligacion concepto;

	/** La practica facturada (DP-11). {@code null} en las particulares. */
	@Column(name = "practica_id", updatable = false)
	private Long practicaId;

	/** La cobertura del paciente que se aplico. {@code null} en las particulares. */
	@Column(name = "cobertura_id", updatable = false)
	private Long coberturaId;

	/** DP-11: la practica facturada no esta entre las que la oferta declara. Alerta, no rechazo. */
	@Column(name = "alerta_practica_no_habilitada", nullable = false, updatable = false)
	private boolean alertaPracticaNoHabilitada;

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

	// =================================================================================
	// Snapshot del convenio (V36 reservo los dos ids; V77 agrega el resto y F-4 los escribe).
	// Todos NULL en PARTICULAR y todos presentes en FINANCIADOR y COSEGURO: lo sostiene
	// ck_obligacion_snapshot_convenio_coherente. Ver SnapshotDeConvenio.
	// =================================================================================

	@Column(name = "snapshot_convenio_id", updatable = false)
	private Long snapshotConvenioId;

	@Column(name = "snapshot_arancel_id", updatable = false)
	private Long snapshotArancelId;

	@Column(name = "snapshot_plan_id", updatable = false)
	private Long snapshotPlanId;

	@Column(name = "snapshot_convenio_codigo", length = 64, updatable = false)
	private String snapshotConvenioCodigo;

	@Column(name = "snapshot_convenio_nombre", length = 160, updatable = false)
	private String snapshotConvenioNombre;

	@Column(name = "snapshot_importe_total", precision = 12, scale = 2, updatable = false)
	private BigDecimal snapshotImporteTotal;

	@Column(name = "snapshot_importe_financiador", precision = 12, scale = 2, updatable = false)
	private BigDecimal snapshotImporteFinanciador;

	@Column(name = "snapshot_coseguro", precision = 12, scale = 2, updatable = false)
	private BigDecimal snapshotCoseguro;

	@Column(name = "snapshot_requeria_orden", updatable = false)
	private Boolean snapshotRequeriaOrden;

	@Column(name = "snapshot_requeria_autorizacion", updatable = false)
	private Boolean snapshotRequeriaAutorizacion;

	@Column(name = "snapshot_requeria_credencial", updatable = false)
	private Boolean snapshotRequeriaCredencial;

	@Column(name = "snapshot_credencial_vencida", updatable = false)
	private Boolean snapshotCredencialVencida;

	@Column(name = "snapshot_vigente_el", updatable = false)
	private LocalDate snapshotVigenteEl;

	@Column(name = "snapshot_capturado_en", updatable = false)
	private Instant snapshotCapturadoEn;

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
	 * La deuda del paciente sin cobertura: el precio de la oferta (07.01, {@code PARTICULAR}).
	 *
	 * <p>Solo admite {@link Responsable#PACIENTE}: la deuda de un financiador sale siempre de un
	 * convenio y se crea con {@link #porConvenio}.
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

		if (responsable != Responsable.PACIENTE) {
			// ck_obligacion_concepto_responsable (V77) lo impide igual. Aca se atrapa antes para
			// decir cual es el problema, en vez de dejar reventar una constraint que ademas
			// dejaria la transaccion del cierre marcada para rollback.
			throw new IllegalArgumentException(
					"Una obligacion a cargo del financiador sale de un convenio: usar porConvenio");
		}
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.sesionId = sesionId;
		this.personaId = personaId;
		this.responsable = Responsable.PACIENTE;
		this.concepto = ConceptoObligacion.PARTICULAR;
		this.moneda = moneda;
		this.ofertaId = ofertaId;
		this.snapshotNombre = snapshotNombre;
		this.devengadaEn = devengadaEn;
		inicializarImporte(importe);
	}

	/**
	 * Una de las partes de una prestacion cubierta por un convenio (AKINE F-4, RF-M18-002).
	 *
	 * <p>El importe <b>no se recibe</b>: es la parte del arancel congelado que le toca al concepto
	 * —{@code importe_financiador} o {@code coseguro}—. El reparto no calcula, copia, y
	 * {@code ck_obligacion_importe_segun_concepto} (V77) lo respalda del lado de la base.
	 *
	 * @param financiadorId obligatorio si el concepto es {@link ConceptoObligacion#FINANCIADOR} y
	 *                      prohibido si no (los dos CHECK de V56)
	 */
	@SuppressWarnings("checkstyle:ParameterNumber")
	public static Obligacion porConvenio(
			long organizationId,
			long consultorioId,
			long sesionId,
			long personaId,
			ConceptoObligacion concepto,
			Long financiadorId,
			String moneda,
			long ofertaId,
			String snapshotNombre,
			Instant devengadaEn,
			SnapshotDeConvenio snapshot,
			boolean alertaPracticaNoHabilitada) {

		if (concepto == ConceptoObligacion.PARTICULAR) {
			throw new IllegalArgumentException("Una obligacion particular no sale de un convenio");
		}
		if (concepto == ConceptoObligacion.FINANCIADOR && financiadorId == null) {
			throw new IllegalArgumentException(
					"Una obligacion a cargo del financiador necesita saber de que financiador es");
		}
		if (concepto != ConceptoObligacion.FINANCIADOR && financiadorId != null) {
			throw new IllegalArgumentException(
					"Una obligacion del paciente no lleva financiador: " + financiadorId);
		}

		Obligacion o = new Obligacion();
		o.organizationId = organizationId;
		o.consultorioId = consultorioId;
		o.sesionId = sesionId;
		o.personaId = personaId;
		o.responsable = concepto.responsable();
		o.financiadorId = financiadorId;
		o.concepto = concepto;
		o.moneda = moneda;
		o.ofertaId = ofertaId;
		o.snapshotNombre = snapshotNombre;
		o.devengadaEn = devengadaEn;
		o.practicaId = snapshot.practicaId();
		o.coberturaId = snapshot.coberturaId();
		o.alertaPracticaNoHabilitada = alertaPracticaNoHabilitada;
		o.snapshotConvenioId = snapshot.convenioId();
		o.snapshotArancelId = snapshot.arancelId();
		o.snapshotPlanId = snapshot.planId();
		o.snapshotConvenioCodigo = snapshot.convenioCodigo();
		o.snapshotConvenioNombre = snapshot.convenioNombre();
		o.snapshotImporteTotal = snapshot.importeTotal();
		o.snapshotImporteFinanciador = snapshot.importeFinanciador();
		o.snapshotCoseguro = snapshot.coseguro();
		o.snapshotRequeriaOrden = snapshot.requeriaOrden();
		o.snapshotRequeriaAutorizacion = snapshot.requeriaAutorizacion();
		o.snapshotRequeriaCredencial = snapshot.requeriaCredencial();
		o.snapshotCredencialVencida = snapshot.credencialVencida();
		o.snapshotVigenteEl = snapshot.vigenteEl();
		o.snapshotCapturadoEn = snapshot.capturadoEn();
		o.inicializarImporte(snapshot.parteDe(concepto));
		return o;
	}

	private void inicializarImporte(BigDecimal importe) {
		if (importe == null || importe.signum() <= 0) {
			// "No cobrar saldo cero" es regla de la etapa. Una deuda de cero solo ensucia la
			// cuenta corriente con filas que nadie va a pagar, y una negativa es un credito
			// disfrazado de deuda.
			throw new IllegalArgumentException("Una obligacion se devenga por un importe positivo: " + importe);
		}
		this.importeOriginal = importe;
		this.saldo = importe;
		this.snapshotPrecio = importe;
		this.estado = EstadoObligacion.PENDIENTE;
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

	public Long getPersonaId() {
		return personaId;
	}

	public Responsable getResponsable() {
		return responsable;
	}

	public Long getFinanciadorId() {
		return financiadorId;
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

	public ConceptoObligacion getConcepto() {
		return concepto;
	}

	public Long getPracticaId() {
		return practicaId;
	}

	public boolean isAlertaPracticaNoHabilitada() {
		return alertaPracticaNoHabilitada;
	}

	/**
	 * El convenio que se aplico, tal como quedo congelado. Vacio en las particulares.
	 *
	 * <p>Se arma de las columnas propias: no vuelve a preguntarle nada a {@code contracting}.
	 */
	public Optional<SnapshotDeConvenio> getSnapshotDeConvenio() {
		if (snapshotConvenioId == null) {
			return Optional.empty();
		}
		return Optional.of(new SnapshotDeConvenio(
				snapshotConvenioId,
				snapshotConvenioCodigo,
				snapshotConvenioNombre,
				snapshotPlanId,
				snapshotArancelId,
				practicaId,
				coberturaId,
				snapshotImporteTotal,
				snapshotImporteFinanciador,
				snapshotCoseguro,
				Boolean.TRUE.equals(snapshotRequeriaOrden),
				Boolean.TRUE.equals(snapshotRequeriaAutorizacion),
				Boolean.TRUE.equals(snapshotRequeriaCredencial),
				Boolean.TRUE.equals(snapshotCredencialVencida),
				snapshotVigenteEl,
				snapshotCapturadoEn));
	}
}
