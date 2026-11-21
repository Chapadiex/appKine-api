package com.akine.organization.domain;

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
 * Pedido de prueba gratis de 30 dias que llega desde la landing (18/11/2026, V28).
 *
 * <p>Global: no tiene organizacion hasta que la plataforma lo aprueba y crea el centro. Nunca se
 * borra: termina APROBADA (con el centro que genero) o DESCARTADA (con motivo).
 */
@Entity
@Table(name = "solicitud_de_prueba")
public class SolicitudDePrueba extends TimestampedEntity {

	public enum Estado { PENDIENTE, APROBADA, DESCARTADA }

	/** Cuantas sedes dice tener quien pide la prueba. */
	public enum Sedes { UNA, HASTA_TRES, MAS_DE_TRES }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "nombre", nullable = false, length = 80)
	private String nombre;

	@Column(name = "apellido", nullable = false, length = 80)
	private String apellido;

	@Column(name = "email", nullable = false, length = 254)
	private String email;

	@Column(name = "telefono", length = 40)
	private String telefono;

	@Column(name = "nombre_centro", nullable = false, length = 120)
	private String nombreCentro;

	@Column(name = "ciudad", length = 120)
	private String ciudad;

	@Column(name = "kinesiologia", nullable = false)
	private boolean kinesiologia;

	@Column(name = "actividades", nullable = false)
	private boolean actividades;

	@Enumerated(EnumType.STRING)
	@Column(name = "sedes", nullable = false, length = 16)
	private Sedes sedes;

	@Column(name = "interesa_ia", nullable = false)
	private boolean interesaIa;

	@Column(name = "comentario", length = 1000)
	private String comentario;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 16)
	private Estado estado;

	@Column(name = "motivo_descarte", length = 500)
	private String motivoDescarte;

	@Column(name = "organization_id")
	private Long organizationId;

	@Column(name = "resuelta_por")
	private Long resueltaPor;

	@Column(name = "resuelta_en")
	private Instant resueltaEn;

	@Column(name = "version_terminos", length = 40, updatable = false)
	private String versionTerminos;

	@Column(name = "terminos_aceptados_en", updatable = false)
	private Instant terminosAceptadosEn;

	@Column(name = "idempotency_key", nullable = false, length = 80, updatable = false)
	private String idempotencyKey;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected SolicitudDePrueba() {
		// Requerido por JPA.
	}

	public SolicitudDePrueba(String nombre, String apellido, String email, String telefono,
			String nombreCentro, String ciudad, boolean kinesiologia, boolean actividades,
			Sedes sedes, boolean interesaIa, String comentario, String idempotencyKey) {
		if (!kinesiologia && !actividades) {
			throw new IllegalArgumentException("Elegí al menos un servicio");
		}
		this.nombre = nombre;
		this.apellido = apellido;
		this.email = email;
		this.telefono = telefono;
		this.nombreCentro = nombreCentro;
		this.ciudad = ciudad;
		this.kinesiologia = kinesiologia;
		this.actividades = actividades;
		this.sedes = sedes;
		this.interesaIa = interesaIa;
		this.comentario = comentario;
		this.idempotencyKey = idempotencyKey;
		this.estado = Estado.PENDIENTE;
	}

	/** Deja constancia de la version de los terminos que se acepto al pedir la prueba. */
	public void aceptarTerminos(String version, Instant en) {
		this.versionTerminos = version;
		this.terminosAceptadosEn = en;
	}

	public String getVersionTerminos() {
		return versionTerminos;
	}

	public Instant getTerminosAceptadosEn() {
		return terminosAceptadosEn;
	}

	public void aprobar(long organizationId, long actorAccountId, Instant ahora) {
		exigirPendiente();
		this.estado = Estado.APROBADA;
		this.organizationId = organizationId;
		this.resueltaPor = actorAccountId;
		this.resueltaEn = ahora;
	}

	public void descartar(String motivo, long actorAccountId, Instant ahora) {
		exigirPendiente();
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException("Indicá el motivo del descarte");
		}
		this.estado = Estado.DESCARTADA;
		this.motivoDescarte = motivo.strip();
		this.resueltaPor = actorAccountId;
		this.resueltaEn = ahora;
	}

	private void exigirPendiente() {
		if (estado != Estado.PENDIENTE) {
			throw new IllegalStateException("La solicitud ya fue resuelta");
		}
	}

	public Long getId() { return id; }
	public String getNombre() { return nombre; }
	public String getApellido() { return apellido; }
	public String getEmail() { return email; }
	public String getTelefono() { return telefono; }
	public String getNombreCentro() { return nombreCentro; }
	public String getCiudad() { return ciudad; }
	public boolean isKinesiologia() { return kinesiologia; }
	public boolean isActividades() { return actividades; }
	public Sedes getSedes() { return sedes; }
	public boolean isInteresaIa() { return interesaIa; }
	public String getComentario() { return comentario; }
	public Estado getEstado() { return estado; }
	public String getMotivoDescarte() { return motivoDescarte; }
	public Long getOrganizationId() { return organizationId; }
	public Long getResueltaPor() { return resueltaPor; }
	public Instant getResueltaEn() { return resueltaEn; }
	public long getVersion() { return version; }
}
