package com.akine.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Registro de idempotencia del alta self-service, del lado de {@code identity} (ADR-0008).
 *
 * <p>El reintento CONCURRENTE lo resuelve {@code uk_onboarding_registro_clave}, no un chequeo
 * previo: dos hilos con la misma clave leen ambos "no existe", los dos insertan, y el segundo
 * choca contra la restriccion. Ahi se relee la fila y se devuelve lo que hizo el ganador. Un
 * SELECT previo sin la restriccion detras seria la misma carrera con otro nombre.
 *
 * <p>La fila se escribe tambien cuando el email ya tenia cuenta y no se creo nada
 * ({@link EstadoOnboarding#DUPLICADO}): la respuesta del endpoint es identica en los dos
 * casos, y sin este registro un reintento con la misma clave volveria a encolar el aviso.
 *
 * <p>Append-only: sin setters.
 */
@Entity
@Table(name = "onboarding_registro")
public class OnboardingRegistro {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "clave_idempotencia", nullable = false, length = 64, updatable = false)
	private String claveIdempotencia;

	@Column(name = "email_normalizado", nullable = false, length = 320, updatable = false)
	private String emailNormalizado;

	@Column(name = "cuenta_id", updatable = false)
	private Long cuentaId;

	@Column(name = "organization_id", updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", updatable = false)
	private Long consultorioId;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 20, updatable = false)
	private EstadoOnboarding estado;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected OnboardingRegistro() {
		// Requerido por JPA.
	}

	private OnboardingRegistro(
			String claveIdempotencia,
			String emailNormalizado,
			Long cuentaId,
			Long organizationId,
			Long consultorioId,
			EstadoOnboarding estado,
			Instant createdAt) {
		this.claveIdempotencia = claveIdempotencia;
		this.emailNormalizado = emailNormalizado;
		this.cuentaId = cuentaId;
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.estado = estado;
		this.createdAt = createdAt;
	}

	/** El alta creo la cuenta y aprovisiono el tenant. */
	public static OnboardingRegistro completado(
			String claveIdempotencia,
			String emailNormalizado,
			Long cuentaId,
			Long organizationId,
			Long consultorioId,
			Instant ahora) {
		return new OnboardingRegistro(claveIdempotencia, emailNormalizado, cuentaId,
				organizationId, consultorioId, EstadoOnboarding.COMPLETADO, ahora);
	}

	/**
	 * El email ya tenia cuenta: no se creo nada y se encolo el aviso de "ya tenes cuenta".
	 * Sin ids, porque no hay nada nuevo a que apuntar — y porque guardar el {@code cuentaId}
	 * ajeno aca convertiria esta tabla en un indice de que direcciones estan registradas.
	 */
	public static OnboardingRegistro duplicado(
			String claveIdempotencia, String emailNormalizado, Instant ahora) {
		return new OnboardingRegistro(claveIdempotencia, emailNormalizado, null, null, null,
				EstadoOnboarding.DUPLICADO, ahora);
	}

	@PrePersist
	void alInsertar() {
		if (createdAt == null) {
			createdAt = Instant.now();
		}
	}

	public Long getId() {
		return id;
	}

	public String getClaveIdempotencia() {
		return claveIdempotencia;
	}

	public String getEmailNormalizado() {
		return emailNormalizado;
	}

	public Long getCuentaId() {
		return cuentaId;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getConsultorioId() {
		return consultorioId;
	}

	public EstadoOnboarding getEstado() {
		return estado;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
