package com.akine.person.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Perfil clinico OPCIONAL de una {@link Persona} (M07, RN-M07-005, RF-M07-008).
 *
 * <h2>Que significa exactamente que exista esta fila</h2>
 *
 * <p>Significa <b>"esta persona es paciente en esta organizacion"</b>, y nada mas. En particular
 * <b>NO significa que tenga Historia Clinica</b>: la HC es M09, la crea el modulo
 * {@code clinical} cuando exista, y la regla maestra 1 separa HC, Caso y Sesion entre si y de
 * esto. Por eso esta clase no tiene {@code historiaClinicaId} ni ninguna otra referencia clinica
 * — si la tuviera, activar un perfil crearia por transitividad el artefacto que RF-M07-010
 * prohibe crear automaticamente.
 *
 * <h2>Por que la activacion es idempotente y como se garantiza</h2>
 *
 * <p>RF-M07-008 pide activar el perfil "reutilizando la identidad". Activar dos veces no es un
 * error del operador que merezca un 409: es el mismo pedido repetido —el boton tocado dos veces,
 * el request reintentado tras un timeout— y la respuesta correcta es el perfil que ya existe.
 *
 * <p>La garantia no la da un {@code SELECT} previo, que tiene ventana de carrera: la da el unique
 * {@code uk_perfil_paciente_persona} de V27. Dos requests simultaneos no producen dos perfiles;
 * el segundo choca contra el unique y {@code PerfilPacienteService} responde con el que gano. El
 * pre-chequeo existe igual, pero solo para ahorrar el viaje en el caso comun, no como la
 * proteccion.
 *
 * <h2>{@link #activadoPor} es un {@code Long} pelado, y es a proposito</h2>
 *
 * <p>Es el {@code accountId} del actor, sin FK hacia {@code cuenta}: esa tabla es propiedad de
 * {@code identity} y una FK cruzaria la propiedad de datos entre modulos (AGENT.md seccion 4,
 * regla 1). Mismo criterio que usa la auditoria de {@code platform} para su actor.
 */
@Entity
@Table(name = "perfil_paciente")
public class PerfilPaciente extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	/**
	 * Persona que este perfil convierte en paciente. {@code Long} y no {@code @ManyToOne}: el
	 * perfil no necesita navegar a la persona —quien lo pide ya la tiene cargada— y una relacion
	 * JPA invitaria a leer la persona desde aca, que es como nacen los N+1 y las cargas
	 * accidentales.
	 */
	@Column(name = "persona_id", nullable = false, updatable = false)
	private Long personaId;

	@Column(name = "activado_en", nullable = false, updatable = false)
	private Instant activadoEn;

	@Column(name = "activado_por", nullable = false, updatable = false)
	private Long activadoPor;

	@Column(name = "motivo", length = 280)
	private String motivo;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deactivation_reason", length = 280)
	private String deactivationReason;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected PerfilPaciente() {
		// Requerido por JPA.
	}

	public PerfilPaciente(
			Long organizationId, Long personaId, Instant activadoEn, Long activadoPor, String motivo) {

		this.organizationId =
				exigirNoNulo(organizationId, "El perfil pertenece siempre a una organizacion");
		this.personaId = exigirNoNulo(personaId, "El perfil se activa siempre sobre una persona");
		this.activadoEn = exigirNoNulo(activadoEn, "La activacion registra siempre su instante");
		this.activadoPor = exigirNoNulo(activadoPor, "La activacion registra siempre a su actor");
		this.motivo = vacioEsNulo(motivo);
		this.active = true;
	}

	/**
	 * Baja logica del perfil, con motivo declarado.
	 *
	 * <p><b>Sin llamador en esta etapa</b>, igual que {@code Persona.deactivate}: RF-M07-005 es
	 * 03.02. Dar de baja el perfil NO da de baja a la persona — dejan de considerarla paciente y
	 * sigue siendo alguien que puede inscribirse a una clase, que es exactamente la separacion que
	 * RN-M07-006 pide.
	 */
	public void deactivate(Instant occurredAt, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"La baja de un perfil de paciente exige un motivo declarado");
		}
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason.strip();
	}

	/** {@code true} mientras la persona sea paciente vigente en la organizacion. */
	public boolean isVigente() {
		return active && deletedAt == null;
	}

	private static <T> T exigirNoNulo(T valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
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

	public Long getPersonaId() {
		return personaId;
	}

	public Instant getActivadoEn() {
		return activadoEn;
	}

	public Long getActivadoPor() {
		return activadoPor;
	}

	public String getMotivo() {
		return motivo;
	}

	public boolean isActive() {
		return active;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public String getDeactivationReason() {
		return deactivationReason;
	}

	public long getVersion() {
		return version;
	}
}
