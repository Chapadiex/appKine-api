package com.akine.organization.domain;

import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.Servicio;
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
import java.util.EnumSet;
import java.util.Set;

/**
 * Pedido de cambio de plan que un centro hace desde Suscripcion (25/11/2026, V50).
 *
 * <p>Guarda lo pedido y la contratacion que el centro tenia al pedir, para que la plataforma vea
 * el "de → a" y quede historial. Nunca se borra: termina APROBADO, RECHAZADO (con motivo) o
 * CANCELADO por el propio centro.
 */
@Entity
@Table(name = "pedido_de_cambio_de_plan")
public class PedidoDeCambioDePlan extends TimestampedEntity {

	public enum Estado { PENDIENTE, APROBADO, RECHAZADO, CANCELADO }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private long organizationId;

	@Column(name = "plan_code", nullable = false, length = 40, updatable = false)
	private String planCode;

	@Column(name = "kinesiologia", nullable = false, updatable = false)
	private boolean kinesiologia;

	@Column(name = "actividades", nullable = false, updatable = false)
	private boolean actividades;

	@Column(name = "ia", nullable = false, updatable = false)
	private boolean ia;

	@Column(name = "plan_code_anterior", nullable = false, length = 40, updatable = false)
	private String planCodeAnterior;

	@Column(name = "kinesiologia_anterior", nullable = false, updatable = false)
	private boolean kinesiologiaAnterior;

	@Column(name = "actividades_anterior", nullable = false, updatable = false)
	private boolean actividadesAnterior;

	@Column(name = "ia_anterior", nullable = false, updatable = false)
	private boolean iaAnterior;

	@Column(name = "comentario", length = 500, updatable = false)
	private String comentario;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 16)
	private Estado estado;

	@Column(name = "motivo_rechazo", length = 500)
	private String motivoRechazo;

	@Column(name = "pedido_por", nullable = false, updatable = false)
	private long pedidoPor;

	@Column(name = "resuelto_por")
	private Long resueltoPor;

	@Column(name = "resuelto_en")
	private Instant resueltoEn;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected PedidoDeCambioDePlan() {
		// Requerido por JPA.
	}

	public PedidoDeCambioDePlan(long organizationId, String planCode, Set<Servicio> servicios,
			Set<Adicional> adicionales, String planCodeAnterior, Set<Servicio> serviciosAnteriores,
			Set<Adicional> adicionalesAnteriores, String comentario, long pedidoPor) {
		if (servicios == null || servicios.isEmpty()) {
			throw new IllegalArgumentException("Elegí al menos un servicio");
		}
		this.organizationId = organizationId;
		this.planCode = planCode;
		this.kinesiologia = servicios.contains(Servicio.KINESIOLOGIA);
		this.actividades = servicios.contains(Servicio.ACTIVIDADES);
		this.ia = adicionales.contains(Adicional.IA);
		this.planCodeAnterior = planCodeAnterior;
		this.kinesiologiaAnterior = serviciosAnteriores.contains(Servicio.KINESIOLOGIA);
		this.actividadesAnterior = serviciosAnteriores.contains(Servicio.ACTIVIDADES);
		this.iaAnterior = adicionalesAnteriores.contains(Adicional.IA);
		this.comentario = comentario;
		this.pedidoPor = pedidoPor;
		this.estado = Estado.PENDIENTE;
	}

	public void aprobar(long actorAccountId, Instant ahora) {
		resolver(Estado.APROBADO, actorAccountId, ahora);
	}

	public void rechazar(String motivo, long actorAccountId, Instant ahora) {
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException("Indicá el motivo del rechazo");
		}
		resolver(Estado.RECHAZADO, actorAccountId, ahora);
		this.motivoRechazo = motivo.strip();
	}

	public void cancelar(long actorAccountId, Instant ahora) {
		resolver(Estado.CANCELADO, actorAccountId, ahora);
	}

	private void resolver(Estado nuevo, long actorAccountId, Instant ahora) {
		if (estado != Estado.PENDIENTE) {
			throw new IllegalStateException("El pedido ya fue resuelto");
		}
		this.estado = nuevo;
		this.resueltoPor = actorAccountId;
		this.resueltoEn = ahora;
	}

	public Set<Servicio> getServicios() {
		return servicios(kinesiologia, actividades);
	}

	public Set<Adicional> getAdicionales() {
		return ia ? EnumSet.of(Adicional.IA) : EnumSet.noneOf(Adicional.class);
	}

	public Set<Servicio> getServiciosAnteriores() {
		return servicios(kinesiologiaAnterior, actividadesAnterior);
	}

	public Set<Adicional> getAdicionalesAnteriores() {
		return iaAnterior ? EnumSet.of(Adicional.IA) : EnumSet.noneOf(Adicional.class);
	}

	private static Set<Servicio> servicios(boolean kinesiologia, boolean actividades) {
		Set<Servicio> s = EnumSet.noneOf(Servicio.class);
		if (kinesiologia) {
			s.add(Servicio.KINESIOLOGIA);
		}
		if (actividades) {
			s.add(Servicio.ACTIVIDADES);
		}
		return s;
	}

	public Long getId() { return id; }
	public long getOrganizationId() { return organizationId; }
	public String getPlanCode() { return planCode; }
	public String getPlanCodeAnterior() { return planCodeAnterior; }
	public String getComentario() { return comentario; }
	public Estado getEstado() { return estado; }
	public String getMotivoRechazo() { return motivoRechazo; }
	public long getPedidoPor() { return pedidoPor; }
	public Long getResueltoPor() { return resueltoPor; }
	public Instant getResueltoEn() { return resueltoEn; }
	public long getVersion() { return version; }
}
