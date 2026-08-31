package com.akine.clinical.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Contexto clinico longitudinal de una Persona dentro de una organizacion (M09, DP-03).
 *
 * <h2>De quien es esta historia</h2>
 *
 * <p>De la <b>organizacion</b>, no de la sede y no del paciente en abstracto. Por eso hay
 * {@code organizationId} y <b>no hay {@code consultorioId}</b>: la misma historia se consulta
 * desde cualquier sede del mismo tenant por quien tenga membership vigente y permiso clinico, y
 * nunca se comparte con otra organizacion. La sede desde la que se accedio no se pierde —viaja en
 * la auditoria— pero no es un dato de la historia.
 *
 * <h2>Lo que esta clase NO tiene, y que es la mitad de su diseño</h2>
 *
 * <p><b>No tiene sesiones.</b> RN-M09-001 lo dice sin rodeos: la Historia Clinica no es una lista
 * plana de sesiones. Tampoco tiene Casos —RN-M09-002 los pone como el nivel que organiza los
 * problemas concretos, y la regla maestra 1 los separa— ni adjuntos ni evoluciones. Cuando 04.02
 * y 04.03 existan, esas cosas van a colgar de {@link #id}, que ya es estable.
 *
 * <p><b>No tiene nombre, documento ni telefono del paciente.</b> RN-M09-003 prohibe duplicar los
 * datos administrativos: viven en {@code persona} y se leen por {@code person.spi}. Copiarlos aca
 * produciria dos verdades que divergen en cuanto alguien corrija un apellido mal tipeado.
 *
 * <h2>Por que cuelga de la Persona y no del PerfilPaciente</h2>
 *
 * <p>Porque el perfil se puede dar de baja y volver a activar (RF-M07-005), y la identidad no. Si
 * la historia colgara del perfil, una baja clinica dejaria la historia huerfana o la reactivacion
 * la duplicaria. Que la persona <b>tenga</b> perfil vigente sigue siendo precondicion para abrir
 * la historia; lo verifica la aplicacion contra {@code person.spi}, no una clave foranea.
 */
@Entity
@Table(name = "historia_clinica")
public class HistoriaClinica extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	/**
	 * Persona titular. {@code Long} y no {@code @ManyToOne}: la persona es de otro modulo y una
	 * relacion JPA hacia su entity seria justamente el acceso directo que AGENT.md seccion 4
	 * regla 1 prohibe.
	 */
	@Column(name = "persona_id", nullable = false, updatable = false)
	private Long personaId;

	@Column(name = "abierta_en", nullable = false, updatable = false)
	private Instant abiertaEn;

	@Column(name = "abierta_por", nullable = false, updatable = false)
	private Long abiertaPor;

	@Column(name = "resumen", length = 2000)
	private String resumen;

	@Column(name = "resumen_actualizado_en")
	private Instant resumenActualizadoEn;

	@Column(name = "resumen_actualizado_por")
	private Long resumenActualizadoPor;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deactivation_reason", length = 280)
	private String deactivationReason;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected HistoriaClinica() {
		// Requerido por JPA.
	}

	public HistoriaClinica(Long organizationId, Long personaId, Instant abiertaEn, Long abiertaPor) {
		this.organizationId =
				exigirNoNulo(organizationId, "La historia pertenece siempre a una organizacion");
		this.personaId = exigirNoNulo(personaId, "La historia pertenece siempre a una persona");
		this.abiertaEn = exigirNoNulo(abiertaEn, "La apertura registra siempre su instante");
		this.abiertaPor = exigirNoNulo(abiertaPor, "La apertura registra siempre a su actor");
		this.active = true;
	}

	/**
	 * Reemplaza el resumen clinico minimo y deja quien y cuando (RF-M09-001, RN-M09-004).
	 *
	 * <p>El texto vacio <b>borra</b> el resumen y limpia tambien su autoria: un resumen en blanco
	 * con autor y fecha diria que alguien escribio algo que no esta, y el CHECK
	 * {@code ck_historia_clinica_resumen_trazable} lo rechazaria de todos modos.
	 *
	 * <p>Lo que esta version reemplaza <b>no queda guardado</b>, y es una limitacion consciente
	 * del recorte: la trazabilidad del contenido anterior es del timeline clinico, que es 04.02 y
	 * esta cortada. Lo que si queda es el rastro del acceso de escritura, que se audita siempre.
	 */
	public void actualizarResumen(String texto, Instant occurredAt, Long actorAccountId) {
		String limpio = vacioEsNulo(texto);
		if (limpio == null) {
			this.resumen = null;
			this.resumenActualizadoEn = null;
			this.resumenActualizadoPor = null;
			return;
		}
		this.resumen = limpio;
		this.resumenActualizadoEn =
				exigirNoNulo(occurredAt, "Un resumen escrito registra siempre su instante");
		this.resumenActualizadoPor =
				exigirNoNulo(actorAccountId, "Un resumen escrito registra siempre a su autor");
	}

	/**
	 * Baja logica de la historia, con motivo declarado.
	 *
	 * <p><b>Sin llamador en esta etapa.</b> Dar de baja una historia clinica no es una operacion
	 * que 04.01 exponga —ni deberia exponerse a la ligera—, pero el metodo existe porque la
	 * columna existe y porque el unique depende del centinela: si mañana alguien escribe la baja,
	 * tiene que pasar por aca y no por un {@code UPDATE} suelto que se olvide del motivo.
	 */
	public void deactivate(Instant occurredAt, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"La baja de una historia clinica exige un motivo declarado");
		}
		this.active = false;
		this.deletedAt = exigirNoNulo(occurredAt, "La baja registra siempre su instante");
		this.deactivationReason = reason.strip();
	}

	/** {@code true} mientras la historia sea la vigente de esa persona en la organizacion. */
	public boolean isVigente() {
		return active && deletedAt == null;
	}

	/** {@code true} si la historia pertenece a esa organizacion. Cross-tenant se trata como inexistente. */
	public boolean perteneceA(long organizationId) {
		return this.organizationId != null && this.organizationId == organizationId;
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

	public Instant getAbiertaEn() {
		return abiertaEn;
	}

	public Long getAbiertaPor() {
		return abiertaPor;
	}

	public String getResumen() {
		return resumen;
	}

	public Instant getResumenActualizadoEn() {
		return resumenActualizadoEn;
	}

	public Long getResumenActualizadoPor() {
		return resumenActualizadoPor;
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
