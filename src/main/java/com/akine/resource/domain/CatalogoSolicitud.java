package com.akine.resource.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Pedido de un tenant para que la plataforma incorpore un concepto GLOBAL al catalogo
 * (RF-M06-005).
 *
 * <h2>Que problema resuelve</h2>
 *
 * <p>Un centro necesita una especialidad que el catalogo de plataforma no tiene. No puede
 * crearla el como global —seria decidir por todos los demas tenants del SaaS— asi que la pide.
 * <b>Mientras tanto no queda bloqueado</b>: puede crear el concepto como contextual suyo y
 * seguir trabajando. La solicitud existe para que el catalogo comun crezca con criterio, no
 * para frenar a nadie.
 *
 * <h2>Por que no hereda de {@link CatalogoConcepto}</h2>
 *
 * <p>Porque no es un concepto: no tiene vigencia, no se ofrece en ningun selector y no se da
 * de baja — se resuelve. Compartir la superclase solo por parecido estructural le daria cuatro
 * campos que no significan nada y un metodo {@code estaVigente} que nadie sabria interpretar.
 *
 * <h2>Idempotencia</h2>
 *
 * <p>CA-M06-005-05 exige que un reintento no duplique efectos. Lo sostiene la base con un
 * unique sobre {@code (organization_id, tipo, nombre_pendiente)}, donde {@code nombre_pendiente}
 * vale el nombre <b>solo mientras la solicitud esta PENDIENTE</b>: dos pedidos iguales del
 * mismo centro chocan, y volver a pedir algo ya rechazado —que es legitimo— no choca. Ver la
 * migracion V20.
 */
@Entity
@Table(name = "catalogo_solicitud")
public class CatalogoSolicitud extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** Tenant que solicita. Siempre presente: no existen solicitudes de plataforma. */
	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	/** Sede desde la que se pidio. Es trazabilidad; la solicitud es de la organizacion. */
	@Column(name = "consultorio_id", updatable = false)
	private Long consultorioId;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", nullable = false, updatable = false, length = 24)
	private CatalogoTipo tipo;

	@Column(name = "nombre_propuesto", nullable = false, updatable = false, length = 160)
	private String nombrePropuesto;

	@Column(name = "codigo_propuesto", updatable = false, length = 48)
	private String codigoPropuesto;

	@Column(name = "justificacion", nullable = false, updatable = false, length = 500)
	private String justificacion;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 16)
	private SolicitudEstado estado = SolicitudEstado.PENDIENTE;

	@Column(name = "solicitada_por_account_id", nullable = false, updatable = false)
	private Long solicitadaPorAccountId;

	@Column(name = "resuelta_por_account_id")
	private Long resueltaPorAccountId;

	@Column(name = "resuelta_at")
	private Instant resueltaAt;

	@Column(name = "resolucion_nota", length = 500)
	private String resolucionNota;

	/**
	 * Concepto global creado al aprobar, si se creo.
	 *
	 * <p>Sin clave foranea a proposito: apunta a una de tres tablas segun {@link #tipo}, y una
	 * FK solo puede apuntar a una. La integridad la sostiene el servicio, que es quien crea el
	 * concepto y la solicitud en la misma transaccion.
	 */
	@Column(name = "concepto_id")
	private Long conceptoId;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected CatalogoSolicitud() {
		// Requerido por JPA.
	}

	public CatalogoSolicitud(
			Long organizationId,
			Long consultorioId,
			CatalogoTipo tipo,
			String nombrePropuesto,
			String codigoPropuesto,
			String justificacion,
			Long solicitadaPorAccountId) {

		if (organizationId == null || tipo == null || solicitadaPorAccountId == null) {
			throw new IllegalArgumentException(
					"Una solicitud de catalogo exige tenant, tipo y solicitante");
		}
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.tipo = tipo;
		this.nombrePropuesto = exigirTexto(
				nombrePropuesto, "El nombre propuesto es obligatorio");
		this.codigoPropuesto = vacioEsNulo(codigoPropuesto);
		this.justificacion = exigirTexto(
				justificacion,
				"La justificacion es obligatoria: sin ella la plataforma no puede decidir");
		this.solicitadaPorAccountId = solicitadaPorAccountId;
		this.estado = SolicitudEstado.PENDIENTE;
	}

	/**
	 * Resuelve la solicitud. Es la unica transicion, y es terminal.
	 *
	 * @param conceptoId concepto global publicado al aprobar (AKINE-A-7), o {@code null} si se
	 *                   rechaza
	 * @throws IllegalStateException    si ya estaba resuelta. Lo traduce a 409 el manejador: dos
	 *                                  administradores de plataforma decidiendo a la vez tienen
	 *                                  que enterarse de que el otro llego primero
	 * @throws IllegalArgumentException si falta el motivo. Aprobar y rechazar sin decir por que
	 *                                  deja al centro sin ninguna respuesta accionable
	 */
	public void resolver(
			SolicitudEstado nuevoEstado,
			long resueltaPorAccountId,
			String nota,
			Long conceptoId,
			Instant at) {

		if (this.estado != SolicitudEstado.PENDIENTE) {
			throw new IllegalStateException("La solicitud ya fue resuelta");
		}
		if (nuevoEstado == null || nuevoEstado == SolicitudEstado.PENDIENTE) {
			throw new IllegalArgumentException(
					"Resolver una solicitud exige aprobarla o rechazarla");
		}
		this.estado = nuevoEstado;
		this.resueltaPorAccountId = resueltaPorAccountId;
		this.resueltaAt = at;
		this.resolucionNota = exigirTexto(
				nota, "Resolver una solicitud exige un motivo declarado");
		this.conceptoId = conceptoId;
	}

	public boolean estaPendiente() {
		return estado == SolicitudEstado.PENDIENTE;
	}

	private static String exigirTexto(String valor, String mensaje) {
		if (valor == null || valor.isBlank()) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor.strip();
	}

	private static String vacioEsNulo(String valor) {
		return valor == null || valor.isBlank() ? null : valor.strip();
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

	public CatalogoTipo getTipo() {
		return tipo;
	}

	public String getNombrePropuesto() {
		return nombrePropuesto;
	}

	public String getCodigoPropuesto() {
		return codigoPropuesto;
	}

	public String getJustificacion() {
		return justificacion;
	}

	public SolicitudEstado getEstado() {
		return estado;
	}

	public Long getSolicitadaPorAccountId() {
		return solicitadaPorAccountId;
	}

	public Long getResueltaPorAccountId() {
		return resueltaPorAccountId;
	}

	public Instant getResueltaAt() {
		return resueltaAt;
	}

	public String getResolucionNota() {
		return resolucionNota;
	}

	public Long getConceptoId() {
		return conceptoId;
	}

	public long getVersion() {
		return version;
	}
}
