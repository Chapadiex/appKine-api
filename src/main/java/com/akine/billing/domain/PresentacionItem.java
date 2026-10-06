package com.akine.billing.domain;

import com.akine.billing.domain.exception.ImporteDeDebitoInvalidoException;
import com.akine.billing.domain.exception.ItemNoDebitableException;
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
 * Una obligacion dentro de un lote.
 *
 * <h2>Incluirla NO le mueve el saldo</h2>
 *
 * <p>Es la mitad de RN-M21-001 que se viola con mas facilidad: reclamar no es cobrar. La obligacion
 * se salda <b>al conciliar</b> el lote entero, nunca al incluirla y nunca al recibir un pago. Ver
 * {@code PresentacionService.conciliar} para por que.
 *
 * <h2>Los snapshots se congelan al incluir</h2>
 *
 * <p>{@code snapshotConcepto} y {@code snapshotFechaPrestacion} se copian de la obligacion. Un lote
 * impreso en agosto tiene que poder reimprimirse identico en diciembre, y leer la obligacion viva no
 * lo garantiza. Mismo criterio que {@code ArancelCongelado} y que el snapshot de precio de V36.
 *
 * <p>{@code snapshotSesionId} es una <b>referencia de trazabilidad congelada, no un puntero
 * vivo</b>: lo que se presenta es la obligacion, no la sesion. RN-M21-004 se cumple trivialmente
 * porque esta etapa no escribe una sola fila en {@code encounter} ni en {@code clinical}.
 */
@Entity
@Table(name = "presentacion_item")
public class PresentacionItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "presentacion_id", nullable = false, updatable = false)
	private Long presentacionId;

	@Column(name = "obligacion_id", nullable = false, updatable = false)
	private Long obligacionId;

	/**
	 * La columna generada {@code ocupa_marca} de V56 se deriva de aqui y <b>no se mapea</b>: la
	 * autoridad es la base, que no puede desincronizarse de este valor.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 16)
	private EstadoItemPresentacion estado;

	@Column(name = "importe_presentado", nullable = false, precision = 12, scale = 2, updatable = false)
	private BigDecimal importePresentado;

	@Column(name = "importe_debitado", nullable = false, precision = 12, scale = 2)
	private BigDecimal importeDebitado;

	@Column(name = "motivo_debito", length = 280)
	private String motivoDebito;

	@Column(name = "debitado_en")
	private Instant debitadoEn;

	@Column(name = "debitado_por_cuenta_id")
	private Long debitadoPorCuentaId;

	@Column(name = "snapshot_persona_id", nullable = false, updatable = false)
	private Long snapshotPersonaId;

	@Column(name = "snapshot_sesion_id", nullable = false, updatable = false)
	private Long snapshotSesionId;

	@Column(name = "snapshot_fecha_prestacion", nullable = false, updatable = false)
	private LocalDate snapshotFechaPrestacion;

	@Column(name = "snapshot_concepto", nullable = false, length = 160, updatable = false)
	private String snapshotConcepto;

	@Column(name = "incluido_en", nullable = false, updatable = false)
	private Instant incluidoEn;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected PresentacionItem() {
		// Requerido por JPA.
	}

	public PresentacionItem(
			long organizationId,
			long presentacionId,
			Obligacion obligacion,
			LocalDate fechaPrestacion,
			Instant incluidoEn) {

		this.organizationId = organizationId;
		this.presentacionId = presentacionId;
		this.obligacionId = obligacion.getId();
		this.estado = EstadoItemPresentacion.INCLUIDO;
		this.importePresentado = obligacion.getSaldo();
		this.importeDebitado = BigDecimal.ZERO.setScale(2);
		this.snapshotPersonaId = obligacion.getPersonaId();
		this.snapshotSesionId = obligacion.getSesionId();
		this.snapshotFechaPrestacion = fechaPrestacion;
		this.snapshotConcepto = obligacion.getSnapshotNombre();
		this.incluidoEn = incluidoEn;
	}

	/**
	 * El financiador lo rechazo (RF-M21-006).
	 *
	 * <p><b>No borra la prestacion</b> (RN-M21-004) y <b>no perdona la deuda</b>: la obligacion
	 * queda pendiente y liberada para otro lote. Que se hace con ella —re-presentarla, pasarla al
	 * paciente— es una decision del centro que esta etapa no toma por el.
	 *
	 * @throws ItemNoDebitableException ya fue debitado, aceptado o anulado (409)
	 * @throws ImporteDeDebitoInvalidoException no va entre cero y lo presentado (400)
	 */
	public void debitar(
			BigDecimal importe, String motivo, Instant occurredAt, long actorCuentaId) {

		exigirDebitable(importe);
		this.estado = EstadoItemPresentacion.DEBITADO;
		this.importeDebitado = importe;
		this.motivoDebito = motivo;
		this.debitadoEn = occurredAt;
		this.debitadoPorCuentaId = actorCuentaId;
	}

	/**
	 * Las precondiciones del debito, sin cambiar nada.
	 *
	 * <p>Separadas de {@link #debitar} para que el servicio las evalue <b>antes</b> del UPDATE
	 * condicional sobre el saldo del lote: un debito invalido no tiene que mover el saldo y depender
	 * del rollback para deshacerlo.
	 */
	public void exigirDebitable(BigDecimal importe) {
		if (estado != EstadoItemPresentacion.INCLUIDO) {
			throw new ItemNoDebitableException(id, estado.name());
		}
		if (importe == null || importe.signum() <= 0
				|| importe.compareTo(importePresentado) > 0) {
			throw new ImporteDeDebitoInvalidoException(importe, importePresentado);
		}
	}

	/** El financiador lo acepto. Solo lo invoca la conciliacion del lote. */
	public void aceptar() {
		this.estado = EstadoItemPresentacion.ACEPTADO;
	}

	/** El borrador que lo contenia se descarto: libera la obligacion sin borrar la fila. */
	public void anular() {
		this.estado = EstadoItemPresentacion.ANULADO;
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getPresentacionId() {
		return presentacionId;
	}

	public Long getObligacionId() {
		return obligacionId;
	}

	public EstadoItemPresentacion getEstado() {
		return estado;
	}

	public BigDecimal getImportePresentado() {
		return importePresentado;
	}

	public BigDecimal getImporteDebitado() {
		return importeDebitado;
	}

	public String getMotivoDebito() {
		return motivoDebito;
	}

	public Instant getDebitadoEn() {
		return debitadoEn;
	}

	public Long getSnapshotPersonaId() {
		return snapshotPersonaId;
	}

	public Long getSnapshotSesionId() {
		return snapshotSesionId;
	}

	public LocalDate getSnapshotFechaPrestacion() {
		return snapshotFechaPrestacion;
	}

	public String getSnapshotConcepto() {
		return snapshotConcepto;
	}

	public Instant getIncluidoEn() {
		return incluidoEn;
	}

	public long getVersion() {
		return version;
	}
}
