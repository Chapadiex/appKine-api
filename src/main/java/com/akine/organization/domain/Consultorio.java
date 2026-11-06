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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sede de una organizacion (M03).
 *
 * <p>Nacio minima en 01.01 porque el onboarding compuesto crea el primer consultorio en la
 * misma transaccion que la organizacion (ADR-0008) y porque el contexto de trabajo es
 * Organizacion + Consultorio (ADR-0009). <b>AKINE-02.01 la EXPANDE sobre la misma tabla</b>:
 * zona horaria propia, intervalo de agenda, datos institucionales y motivo de baja. <b>V140 la
 * vuelve a expandir</b>: direccion fisica estructurada, ubicacion geografica, si la agenda esta
 * habilitada, si la direccion se publica y si factura con datos fiscales propios.
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
 * <h2>Agenda habilitada no es un estado</h2>
 *
 * <p>{@code agendaHabilitada = false} es una sede ACTIVA que todavia no recibe turnos: existe,
 * se lee, se edita, se le cargan boxes y horario, pero la agenda no reserva, no crea series ni
 * recibe traslados hacia ella (409 {@code consultorio-sin-agenda}). No es un tercer estado porque
 * no pisa a los otros dos: una sede inactiva con la agenda habilitada sigue siendo inactiva.
 *
 * <h2>Dos direcciones, a proposito</h2>
 *
 * <p>La direccion <b>fisica</b> —donde se atiende— es la estructurada ({@code pais}, {@code calle},
 * {@code numero}...) y es la que se geocodifica y se le manda al paciente. La direccion
 * <b>fiscal</b> es {@code addressLine}, texto libre, y cuenta solo con
 * {@code datosFiscalesPropios}; si no, la sede factura con los datos de la organizacion. Son dos
 * hechos distintos: un consultorio puede atender en un local alquilado y facturar desde el
 * domicilio fiscal de la sociedad.
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

	/** Nombre corto para la agenda y los listados. Opcional. */
	@Column(name = "alias", length = 80)
	private String alias;

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

	// --- Datos fiscales propios (cuentan solo con datosFiscalesPropios) ---------------------

	@Column(name = "legal_name", length = 200)
	private String legalName;

	@Column(name = "tax_id", length = 32)
	private String taxId;

	/** Direccion FISCAL (desde V140). La fisica es la estructurada de abajo. */
	@Column(name = "address_line", length = 240)
	private String addressLine;

	@Column(name = "phone", length = 40)
	private String phone;

	@Column(name = "contact_email", length = 160)
	private String contactEmail;

	// --- Direccion fisica estructurada (V140) -------------------------------------------------

	/** ISO 3166-1 alpha-2, en mayusculas. */
	@Column(name = "pais", length = 2)
	private String pais;

	@Column(name = "provincia", length = 80)
	private String provincia;

	@Column(name = "ciudad", length = 120)
	private String ciudad;

	@Column(name = "calle", length = 160)
	private String calle;

	@Column(name = "numero", length = 20)
	private String numero;

	@Column(name = "codigo_postal", length = 16)
	private String codigoPostal;

	@Column(name = "piso", length = 60)
	private String piso;

	@Column(name = "referencias", length = 255)
	private String referencias;

	// --- Ubicacion geografica (V140): las dos coordenadas o ninguna ---------------------------

	@Column(name = "latitud", precision = 9, scale = 6)
	private BigDecimal latitud;

	@Column(name = "longitud", precision = 9, scale = 6)
	private BigDecimal longitud;

	@Enumerated(EnumType.STRING)
	@Column(name = "ubicacion_origen", length = 16)
	private UbicacionOrigen ubicacionOrigen;

	// --- Opciones (V140) ---------------------------------------------------------------------

	@Column(name = "agenda_habilitada", nullable = false)
	private boolean agendaHabilitada = true;

	@Column(name = "direccion_publica", nullable = false)
	private boolean direccionPublica = true;

	@Column(name = "datos_fiscales_propios", nullable = false)
	private boolean datosFiscalesPropios = false;

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
	 * Edicion parcial de la direccion fisica, con la misma semantica que {@link #updateDatos}:
	 * {@code null} deja, cadena vacia borra. El pais se guarda en mayusculas.
	 *
	 * @return {@code true} si algun campo quedo distinto de como estaba
	 */
	public boolean updateDireccion(
			String alias,
			String pais,
			String provincia,
			String ciudad,
			String calle,
			String numero,
			String codigoPostal,
			String piso,
			String referencias) {

		String antes = direccionFormateada() + "|" + this.alias + "|" + this.piso + "|" + this.referencias;
		if (alias != null) {
			this.alias = vacioEsNulo(alias);
		}
		if (pais != null) {
			String limpio = vacioEsNulo(pais);
			this.pais = limpio == null ? null : limpio.toUpperCase(Locale.ROOT);
		}
		if (provincia != null) {
			this.provincia = vacioEsNulo(provincia);
		}
		if (ciudad != null) {
			this.ciudad = vacioEsNulo(ciudad);
		}
		if (calle != null) {
			this.calle = vacioEsNulo(calle);
		}
		if (numero != null) {
			this.numero = vacioEsNulo(numero);
		}
		if (codigoPostal != null) {
			this.codigoPostal = vacioEsNulo(codigoPostal);
		}
		if (piso != null) {
			this.piso = vacioEsNulo(piso);
		}
		if (referencias != null) {
			this.referencias = vacioEsNulo(referencias);
		}
		String despues = direccionFormateada() + "|" + this.alias + "|" + this.piso + "|" + this.referencias;
		return !antes.equals(despues);
	}

	/**
	 * Fija la ubicacion geografica. Las dos coordenadas juntas: el CHECK de V140 lo exige y aca
	 * se exige antes, con un mensaje que el cliente pueda leer.
	 *
	 * @param origen quien la puso. {@code null} se toma como {@link UbicacionOrigen#MANUAL}
	 * @return {@code true} si cambio algo
	 */
	public boolean fijarUbicacion(BigDecimal latitud, BigDecimal longitud, UbicacionOrigen origen) {
		if (latitud == null || longitud == null) {
			throw new IllegalArgumentException(
					"La ubicacion necesita latitud y longitud juntas; para borrarla se mandan las dos vacias");
		}
		if (latitud.compareTo(BigDecimal.valueOf(-90)) < 0 || latitud.compareTo(BigDecimal.valueOf(90)) > 0) {
			throw new IllegalArgumentException("La latitud tiene que estar entre -90 y 90");
		}
		if (longitud.compareTo(BigDecimal.valueOf(-180)) < 0 || longitud.compareTo(BigDecimal.valueOf(180)) > 0) {
			throw new IllegalArgumentException("La longitud tiene que estar entre -180 y 180");
		}
		UbicacionOrigen nuevoOrigen = origen == null ? UbicacionOrigen.MANUAL : origen;
		boolean cambio = this.latitud == null || this.longitud == null
				|| this.latitud.compareTo(latitud) != 0
				|| this.longitud.compareTo(longitud) != 0
				|| this.ubicacionOrigen != nuevoOrigen;
		this.latitud = latitud;
		this.longitud = longitud;
		this.ubicacionOrigen = nuevoOrigen;
		return cambio;
	}

	/** Quita la ubicacion: la sede vuelve a tener direccion sin coordenadas. */
	public boolean borrarUbicacion() {
		boolean tenia = this.latitud != null || this.longitud != null;
		this.latitud = null;
		this.longitud = null;
		this.ubicacionOrigen = null;
		return tenia;
	}

	/**
	 * Opciones de la sede, PATCH: {@code null} deja lo que estaba.
	 *
	 * @return {@code true} si {@code agendaHabilitada} cambio, que es la unica de las tres que
	 *         altera lo que la agenda hace y por eso se audita aparte
	 */
	public boolean updateOpciones(
			Boolean agendaHabilitada, Boolean direccionPublica, Boolean datosFiscalesPropios) {

		boolean cambioAgenda = false;
		if (agendaHabilitada != null && agendaHabilitada != this.agendaHabilitada) {
			this.agendaHabilitada = agendaHabilitada;
			cambioAgenda = true;
		}
		if (direccionPublica != null) {
			this.direccionPublica = direccionPublica;
		}
		if (datosFiscalesPropios != null) {
			this.datosFiscalesPropios = datosFiscalesPropios;
		}
		return cambioAgenda;
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

	/**
	 * La direccion fisica en una linea: "Calle Numero, Piso, Ciudad, Provincia, CP, Pais", con
	 * las partes que haya. {@code null} si no hay ninguna.
	 */
	public String direccionFormateada() {
		List<String> partes = new ArrayList<>();
		String via = unir(" ", calle, numero);
		if (via != null) {
			partes.add(via);
		}
		agregar(partes, piso);
		agregar(partes, ciudad);
		agregar(partes, provincia);
		agregar(partes, codigoPostal);
		agregar(partes, pais);
		return partes.isEmpty() ? null : String.join(", ", partes);
	}

	/** Hay direccion escrita pero nadie la ubico todavia en el mapa. */
	public boolean isUbicacionPendiente() {
		return direccionFormateada() != null && latitud == null;
	}

	private static void agregar(List<String> partes, String valor) {
		if (valor != null && !valor.isBlank()) {
			partes.add(valor.strip());
		}
	}

	private static String unir(String separador, String a, String b) {
		boolean hayA = a != null && !a.isBlank();
		boolean hayB = b != null && !b.isBlank();
		if (hayA && hayB) {
			return a.strip() + separador + b.strip();
		}
		return hayA ? a.strip() : hayB ? b.strip() : null;
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

	public String getAlias() {
		return alias;
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

	public String getPais() {
		return pais;
	}

	public String getProvincia() {
		return provincia;
	}

	public String getCiudad() {
		return ciudad;
	}

	public String getCalle() {
		return calle;
	}

	public String getNumero() {
		return numero;
	}

	public String getCodigoPostal() {
		return codigoPostal;
	}

	public String getPiso() {
		return piso;
	}

	public String getReferencias() {
		return referencias;
	}

	public BigDecimal getLatitud() {
		return latitud;
	}

	public BigDecimal getLongitud() {
		return longitud;
	}

	public UbicacionOrigen getUbicacionOrigen() {
		return ubicacionOrigen;
	}

	public boolean isAgendaHabilitada() {
		return agendaHabilitada;
	}

	public boolean isDireccionPublica() {
		return direccionPublica;
	}

	public boolean isDatosFiscalesPropios() {
		return datosFiscalesPropios;
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
