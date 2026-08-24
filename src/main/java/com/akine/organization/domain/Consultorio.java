package com.akine.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Sede de una organizacion (M03).
 *
 * <p>Nacio minima en 01.01 porque el onboarding compuesto crea el primer consultorio en la
 * misma transaccion que la organizacion (ADR-0008) y porque el contexto de trabajo es
 * Organizacion + Consultorio (ADR-0009). <b>AKINE-02.01 la EXPANDE sobre la misma tabla</b>:
 * zona horaria propia, intervalo de agenda, datos institucionales y motivo de baja.
 *
 * <h2>Estados: dos, y DERIVADOS. No hay columna `estado`</h2>
 *
 * <pre>
 *   ACTIVO    active = 1, deleted_at IS NULL
 *   INACTIVO  active = 0, deleted_at = instante UTC de la baja
 * </pre>
 *
 * <p>El estado no se persiste, igual que el estado operativo de la organizacion se deriva de
 * la suscripcion y nunca es una columna ({@code OrganizationService.operationalStatus}). Una
 * columna {@code estado} junto a un booleano {@code active} habilita la contradiccion
 * "active=1, estado=INACTIVO" que nadie sabe resolver, y obliga a un invariante extra que
 * ningun RF de M03 pide. Si alguna vez hace falta un tercer estado —"suspendido por
 * refaccion"— ese es el momento de expandir a columna, no antes.
 *
 * <p><b>La regla en una linea:</b> una sede inactiva no origina hechos nuevos, pero responde a
 * toda consulta sobre hechos viejos. Un endpoint que devolviera 404 sobre una sede inactiva
 * estaria borrando historia por la puerta de atras (RF-M03-004, regla maestra 10).
 *
 * <h2>Zona horaria</h2>
 *
 * <p>Es de la SEDE y no de la organizacion: la regla local que importa —dia operativo, agenda,
 * corte de caja— es la del lugar fisico donde se atiende. {@code organization.timezone} queda
 * como el valor que se propone al crear una sede nueva. Cambiarla <b>no reinterpreta el
 * pasado</b>: los instantes ya guardados son UTC y no se tocan; cambia como se proyectan de
 * aca en adelante.
 *
 * <h2>Unicidad del nombre</h2>
 *
 * <p>{@code uk_consultorio_org_name_vigente (organization_id, name, deleted_key)}: unico dentro
 * del tenant <b>entre las sedes vigentes</b>. Dos organizaciones distintas pueden tener su
 * "Sede Centro", y una sede dada de baja libera su nombre. El por que de la columna generada
 * esta en la migracion V18.
 */
@Entity
@Table(name = "consultorio")
public class Consultorio extends TimestampedEntity {

	/**
	 * Intervalo de agenda por defecto, en minutos.
	 *
	 * <p>Mismo valor que el {@code DEFAULT} de la columna en V16. Duplicarlo es deliberado: el
	 * default de la base cubre las filas que ya existian y este cubre las que crea la
	 * aplicacion, y que sean el mismo numero es lo que hace que las dos poblaciones se
	 * comporten igual.
	 */
	public static final int SLOT_MINUTES_POR_DEFECTO = 30;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "name", nullable = false, length = 160)
	private String name;

	/** Zona IANA efectiva de la sede. Validada por {@code ZonasHorarias} antes de llegar aca. */
	@Column(name = "timezone", nullable = false, length = 64)
	private String timezone;

	/**
	 * Intervalo por defecto de la agenda, en minutos.
	 *
	 * <p><b>Decision revisable, pendiente de confirmacion del usuario.</b> RF-M03-002 nombra
	 * "horario general e intervalo inicial"; 02.01 persiste solo el intervalo y NO crea ninguna
	 * tabla hija de horarios. El motivo es RN-M03-004: el horario general no sustituye la
	 * disponibilidad profesional, y modelarlo antes de que exista esa disponibilidad (M05/M12)
	 * invita a que la agenda de F5 lo tome como fuente de verdad, que es lo que la regla
	 * prohibe. Es la opcion C de la D-2 del diseno de la etapa.
	 */
	@Column(name = "slot_minutes", nullable = false)
	private int slotMinutes = SLOT_MINUTES_POR_DEFECTO;

	@Column(name = "legal_name", length = 200)
	private String legalName;

	@Column(name = "tax_id", length = 32)
	private String taxId;

	@Column(name = "address_line", length = 240)
	private String addressLine;

	@Column(name = "phone", length = 40)
	private String phone;

	@Column(name = "contact_email", length = 160)
	private String contactEmail;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	/** Motivo declarado de la baja (RF-M03-004). NULL mientras la sede este activa. */
	@Column(name = "deactivation_reason", length = 280)
	private String deactivationReason;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Consultorio() {
		// Requerido por JPA.
	}

	/**
	 * Alta de una sede.
	 *
	 * @param timezone zona IANA ya validada. Obligatoria: sin ella no hay forma de calcular
	 *                 ninguna regla local y el fallback a la zona de la organizacion —que
	 *                 existio durante la ventana V16..V18— hace que editar la organizacion
	 *                 mueva en silencio el dia operativo historico de la sede
	 */
	public Consultorio(Long organizationId, String name, String timezone, Integer slotMinutes) {
		this.organizationId = organizationId;
		this.name = name;
		this.timezone = timezone;
		this.slotMinutes = slotMinutes == null ? SLOT_MINUTES_POR_DEFECTO : slotMinutes;
		this.active = true;
	}

	public void rename(String name) {
		this.name = name;
	}

	/**
	 * Aplica una edicion parcial: cada valor {@code null} deja el campo como estaba.
	 *
	 * <p>Semantica de PATCH y no de PUT: el cliente manda lo que cambia. La contrapartida
	 * conocida es que no hay forma de BORRAR un campo institucional mandando {@code null}; se
	 * borra mandando una cadena vacia, que se normaliza a {@code null} en la capa de aplicacion.
	 */
	public void updateDatos(
			String name,
			String timezone,
			Integer slotMinutes,
			String legalName,
			String taxId,
			String addressLine,
			String phone,
			String contactEmail) {

		if (name != null) {
			this.name = name;
		}
		if (timezone != null) {
			this.timezone = timezone;
		}
		if (slotMinutes != null) {
			this.slotMinutes = slotMinutes;
		}
		if (legalName != null) {
			this.legalName = vacioEsNulo(legalName);
		}
		if (taxId != null) {
			this.taxId = vacioEsNulo(taxId);
		}
		if (addressLine != null) {
			this.addressLine = vacioEsNulo(addressLine);
		}
		if (phone != null) {
			this.phone = vacioEsNulo(phone);
		}
		if (contactEmail != null) {
			this.contactEmail = vacioEsNulo(contactEmail);
		}
	}

	/**
	 * Baja logica con motivo declarado (RF-M03-004).
	 *
	 * <p>Libera cupo del limite {@code MAX_CONSULTORIOS} —el conteo solo mira filas activas—
	 * sin invalidar nada de lo que ocurrio en esa sede. Nunca hay borrado fisico.
	 *
	 * <p>El motivo es obligatorio: una baja sin motivo no se puede revisar seis meses despues,
	 * que es cuando se revisa.
	 */
	public void deactivate(Instant occurredAt, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"La baja de una sede exige un motivo declarado: sin el, la auditoria no "
							+ "responde por que seis meses despues");
		}
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason;
	}

	/** {@code true} cuando la sede admite operaciones nuevas (RN-M03-003). */
	public boolean isOperable() {
		return active && deletedAt == null;
	}

	private static String vacioEsNulo(String valor) {
		return valor.isBlank() ? null : valor;
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public String getName() {
		return name;
	}

	public String getTimezone() {
		return timezone;
	}

	public int getSlotMinutes() {
		return slotMinutes;
	}

	public String getLegalName() {
		return legalName;
	}

	public String getTaxId() {
		return taxId;
	}

	public String getAddressLine() {
		return addressLine;
	}

	public String getPhone() {
		return phone;
	}

	public String getContactEmail() {
		return contactEmail;
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
