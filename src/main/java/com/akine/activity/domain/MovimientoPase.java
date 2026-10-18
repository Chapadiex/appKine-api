package com.akine.activity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Un movimiento del ledger de creditos (RN-M29-003). <b>Inmutable</b>: todas las columnas son
 * {@code updatable = false} y el puerto no tiene {@code update} ni {@code delete}, que es la unica
 * garantia real.
 *
 * <p>{@code cantidad} lleva signo —positivo en {@code COMPRA} y {@code DEVOLUCION}, negativo en
 * {@code CONSUMO}— porque el invariante que se tiene que poder afirmar es una suma:
 * {@code creditos_disponibles == SUM(cantidad)} (la {@code COMPRA} aporta los iniciales).
 *
 * <p><b>Un credito no es plata</b> (RN-M29-006): ningun movimiento de esta tabla toca la caja.
 */
@Entity
@Table(name = "movimiento_pase")
public class MovimientoPase {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "pase_id", nullable = false, updatable = false)
	private Long paseId;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", nullable = false, length = 16, updatable = false)
	private TipoMovimientoPase tipo;

	@Column(name = "cantidad", nullable = false, updatable = false)
	private int cantidad;

	@Column(name = "saldo_resultante", nullable = false, updatable = false)
	private int saldoResultante;

	@Enumerated(EnumType.STRING)
	@Column(name = "origen_tipo", nullable = false, length = 24, updatable = false)
	private OrigenMovimientoPase origenTipo;

	@Column(name = "origen_id", nullable = false, updatable = false)
	private Long origenId;

	@Column(name = "asistencia_id", updatable = false)
	private Long asistenciaId;

	@Column(name = "actor_cuenta_id", updatable = false)
	private Long actorCuentaId;

	@Column(name = "motivo", length = 280, updatable = false)
	private String motivo;

	@Column(name = "ocurrido_en", nullable = false, updatable = false)
	private Instant ocurridoEn;

	protected MovimientoPase() {
		// Requerido por JPA.
	}

	private MovimientoPase(
			long organizationId, long paseId, TipoMovimientoPase tipo, int cantidad,
			int saldoResultante, OrigenMovimientoPase origenTipo, long origenId, Long asistenciaId,
			Long actorCuentaId, String motivo, Instant ocurridoEn) {

		if (cantidad == 0) {
			throw new IllegalArgumentException("Un movimiento de cero creditos no explica nada");
		}
		if (saldoResultante < 0) {
			// RN-M29-008. El UPDATE condicional lo impide antes; esto es el control de forma.
			throw new IllegalArgumentException("El saldo de un pase no puede quedar negativo");
		}
		this.organizationId = organizationId;
		this.paseId = paseId;
		this.tipo = tipo;
		this.cantidad = cantidad;
		this.saldoResultante = saldoResultante;
		this.origenTipo = origenTipo;
		this.origenId = origenId;
		this.asistenciaId = asistenciaId;
		this.actorCuentaId = actorCuentaId;
		this.motivo = motivo;
		this.ocurridoEn = ocurridoEn;
	}

	/** El movimiento que explica el saldo inicial (RF-M29-002 paso 7). Origen: la propia venta. */
	public static MovimientoPase compra(PaseServicio pase, long actorCuentaId, Instant ahora) {
		return new MovimientoPase(
				pase.getOrganizationId(), pase.getId(), TipoMovimientoPase.COMPRA,
				pase.getCreditosIniciales(), pase.getCreditosIniciales(),
				OrigenMovimientoPase.VENTA, pase.getId(), null, actorCuentaId, null, ahora);
	}

	/** Un credito consumido por una asistencia (RF-M29-003). */
	public static MovimientoPase consumoPorAsistencia(
			long organizationId, long paseId, int saldoResultante, long eventoId,
			long asistenciaId, long actorCuentaId, Instant ahora) {
		return new MovimientoPase(
				organizationId, paseId, TipoMovimientoPase.CONSUMO, -1, saldoResultante,
				OrigenMovimientoPase.ASISTENCIA, eventoId, asistenciaId, actorCuentaId, null, ahora);
	}

	/**
	 * La devolucion del credito de una asistencia corregida a ausente. <b>No borra el consumo</b>:
	 * lo compensa con un movimiento nuevo, que es lo que el plan de 08.07 pide ("devolucion no
	 * borra consumo").
	 */
	public static MovimientoPase devolucionPorCorreccion(
			long organizationId, long paseId, int saldoResultante, long eventoId,
			long asistenciaId, long actorCuentaId, String motivo, Instant ahora) {
		return new MovimientoPase(
				organizationId, paseId, TipoMovimientoPase.DEVOLUCION, 1, saldoResultante,
				OrigenMovimientoPase.ASISTENCIA, eventoId, asistenciaId, actorCuentaId,
				recortar(motivo), ahora);
	}

	/**
	 * La devolucion del credito que una asistencia tomo de una clase que despues se cancelo
	 * (RF-M29-004). {@code origen_id} es la clase: una persona tiene una sola asistencia por clase,
	 * asi que {@code uk_movimiento_origen} garantiza una sola devolucion por pase y por cancelacion
	 * (CA-M29-004-06). Lleva la asistencia para que su neto vuelva a cero.
	 */
	public static MovimientoPase devolucionPorCancelacion(
			long organizationId, long paseId, int saldoResultante, long claseId, long asistenciaId,
			long actorCuentaId, String motivo, Instant ahora) {
		return new MovimientoPase(
				organizationId, paseId, TipoMovimientoPase.DEVOLUCION, 1, saldoResultante,
				OrigenMovimientoPase.CANCELACION, claseId, asistenciaId, actorCuentaId,
				recortar(motivo), ahora);
	}

	/**
	 * Un ajuste manual del saldo (RF-M29-005), con motivo obligatorio (CA-M29-005-06).
	 *
	 * <p>{@code origen_id} es la <b>version del pase sobre la que se aplico</b>. Cada escritura del
	 * saldo mueve la version, asi que dos ajustes nunca comparten una, y el reintento del mismo
	 * ajuste —misma version leida— choca contra {@code uk_movimiento_origen} en vez de ajustar dos
	 * veces. Es la clave natural del comando: no hace falta una columna de idempotencia.
	 *
	 * <p>La anulacion (que descarta el saldo remanente) usa el mismo movimiento: es un acto manual
	 * con motivo, y un origen propio exigia reescribir el CHECK de V64 (ver V101).
	 */
	public static MovimientoPase ajusteManual(
			long organizationId, long paseId, int cantidad, int saldoResultante, long versionLeida,
			long actorCuentaId, String motivo, Instant ahora) {
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException("Un ajuste de saldo exige motivo (RF-M29-005)");
		}
		return new MovimientoPase(
				organizationId, paseId, TipoMovimientoPase.AJUSTE, cantidad, saldoResultante,
				OrigenMovimientoPase.AJUSTE_MANUAL, versionLeida, null, actorCuentaId,
				recortar(motivo.strip()), ahora);
	}

	/**
	 * Los creditos que el vencimiento descarta (RF-M29-006, CA-M29-006-06). Sin actor: lo produce
	 * el sistema. {@code origen_id} es la version del pase al vencer, por el mismo motivo que el
	 * ajuste: un pase que se reactiva por una devolucion tardia puede volver a vencer.
	 */
	public static MovimientoPase vencimiento(
			long organizationId, long paseId, int descartados, long versionLeida, Instant ahora) {
		return new MovimientoPase(
				organizationId, paseId, TipoMovimientoPase.VENCIMIENTO, -descartados, 0,
				OrigenMovimientoPase.VENCIMIENTO, versionLeida, null, null, null, ahora);
	}

	private static String recortar(String motivo) {
		return motivo == null || motivo.length() <= 280 ? motivo : motivo.substring(0, 280);
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getPaseId() {
		return paseId;
	}

	public TipoMovimientoPase getTipo() {
		return tipo;
	}

	public int getCantidad() {
		return cantidad;
	}

	public int getSaldoResultante() {
		return saldoResultante;
	}

	public OrigenMovimientoPase getOrigenTipo() {
		return origenTipo;
	}

	public Long getOrigenId() {
		return origenId;
	}

	public Long getAsistenciaId() {
		return asistenciaId;
	}

	public Long getActorCuentaId() {
		return actorCuentaId;
	}

	public String getMotivo() {
		return motivo;
	}

	public Instant getOcurridoEn() {
		return ocurridoEn;
	}
}
