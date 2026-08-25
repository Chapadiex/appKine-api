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
 * Recurso fisico reservable de una sede: box, gimnasio, gabinete, sala grupal (M04).
 *
 * <h2>Los dos ejes temporales, que no son el mismo</h2>
 *
 * <p>Confundirlos es el error mas facil de cometer sobre esta entidad, y el que hace que un
 * recurso dado de baja siga apareciendo en la agenda o que uno en refaccion desaparezca del
 * historico:
 *
 * <pre>
 *   CICLO DE VIDA       active / deletedAt / deactivationReason
 *                       "este box ya no forma parte del catalogo". Irreversible, con motivo,
 *                       decidido por una persona. RF-M04-006.
 *
 *   VENTANA OPERATIVA   validFrom / validUntil
 *                       "este box entra en servicio el 1 de marzo", "sale por refaccion el 30
 *                       de junio". Planificacion, sin motivo y sin auditoria de baja.
 *                       Es lo que RF-M04-003 necesita para responder POR UNA FECHA.
 * </pre>
 *
 * <p>La regla completa esta en {@link #estaEnServicio(Instant)} y en ningun otro lado.
 *
 * <h2>Estado: DERIVADO, sin columna</h2>
 *
 * <pre>
 *   ACTIVO    active = 1, deletedAt IS NULL
 *   INACTIVO  active = 0, deletedAt = instante UTC de la baja
 * </pre>
 *
 * <p>Misma decision que {@code Consultorio}, por el mismo motivo: una columna {@code estado}
 * junto a un booleano {@code active} habilita la contradiccion "active=1, estado=INACTIVO" que
 * nadie sabe resolver. La base ademas lo impide con {@code ck_espacio_baja_coherente}.
 *
 * <h2>La regla en una linea</h2>
 *
 * <p>Un espacio inactivo <b>no se ofrece para reservas nuevas</b> (RN-M04-002) y <b>responde a
 * toda consulta sobre hechos viejos</b> conservando su nombre y su estado (RN-M04-003). Un
 * endpoint que devolviera 404 sobre un espacio dado de baja estaria borrando historia por la
 * puerta de atras.
 *
 * <h2>Por que la marca temporal es propia del modulo</h2>
 *
 * <p>{@link MarcaTemporal} duplica {@code organization.domain.TimestampedEntity}, y no es un
 * descuido: aquella es de otro modulo y ArchUnit rechaza importarla. El razonamiento completo,
 * con la alternativa descartada, esta en su javadoc.
 */
@Entity
@Table(name = "espacio")
public class Espacio extends MarcaTemporal {

	/** Capacidad de un box individual, que es el caso mas frecuente (RN-M04-005). */
	public static final int CAPACIDAD_POR_DEFECTO = 1;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	/**
	 * Sede a la que pertenece (RN-M04-001).
	 *
	 * <p>{@code updatable = false} y es deliberado: mover un espacio de sede cambiaria el
	 * significado de todos los hechos historicos que lo referencian —un turno atendido en la
	 * sede A pasaria a decir que ocurrio en la B— y RN-M04-003 lo prohibe. Un espacio que se
	 * muda es un espacio que se da de baja y otro que se crea.
	 */
	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "name", nullable = false, length = 160)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", nullable = false, length = 32)
	private EspacioTipo tipo;

	/** Personas simultaneas que admite (RN-M04-005). Siempre mayor que cero. */
	@Column(name = "capacidad", nullable = false)
	private int capacidad = CAPACIDAD_POR_DEFECTO;

	@Column(name = "notes", length = 280)
	private String notes;

	@Column(name = "valid_from", nullable = false)
	private Instant validFrom;

	/** Limite EXCLUSIVO de la ventana operativa. {@code null} = sin fin previsto. */
	@Column(name = "valid_until")
	private Instant validUntil;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deactivation_reason", length = 280)
	private String deactivationReason;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Espacio() {
		// Requerido por JPA.
	}

	/**
	 * Alta de un espacio.
	 *
	 * @param validFrom desde cuando esta en servicio. Obligatorio: sin un inicio explicito no
	 *                  hay forma de responder una consulta de disponibilidad sobre una fecha
	 *                  pasada, y usar {@code createdAt} como sustituto ata la ventana operativa
	 *                  al instante en que alguien cargo el dato, que no es lo mismo
	 */
	public Espacio(
			Long organizationId,
			Long consultorioId,
			String name,
			EspacioTipo tipo,
			Integer capacidad,
			String notes,
			Instant validFrom,
			Instant validUntil) {

		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.name = name;
		this.tipo = tipo;
		this.capacidad = capacidad == null ? CAPACIDAD_POR_DEFECTO : capacidad;
		this.notes = vacioEsNulo(notes);
		this.validFrom = validFrom;
		this.validUntil = validUntil;
		this.active = true;
		exigirVigenciaCoherente(this.validFrom, this.validUntil);
		exigirCapacidadPositiva(this.capacidad);
	}

	/**
	 * Aplica una edicion parcial: cada valor {@code null} deja el campo como estaba.
	 *
	 * <p>Semantica de PATCH y no de PUT. La contrapartida conocida es que no hay forma de
	 * BORRAR {@code notes} mandando {@code null}; se borra mandando cadena vacia, que se
	 * normaliza a {@code null}. Misma convencion que los campos institucionales de la sede.
	 *
	 * <p><b>La capacidad no se valida contra la ocupacion aca adentro</b>, y no es un olvido:
	 * la ocupacion la conocen otros modulos y solo puede leerse dentro de la transaccion que
	 * ya bloqueo la fila. Esa comprobacion vive en el servicio de aplicacion, que es quien
	 * tiene esa transaccion. Aca se protege lo unico que la entidad puede saber sola.
	 */
	public void updateDatos(
			String name,
			EspacioTipo tipo,
			Integer capacidad,
			String notes,
			Instant validFrom,
			Instant validUntil,
			boolean limpiarValidUntil) {

		if (name != null) {
			this.name = name;
		}
		if (tipo != null) {
			this.tipo = tipo;
		}
		if (capacidad != null) {
			exigirCapacidadPositiva(capacidad);
			this.capacidad = capacidad;
		}
		if (notes != null) {
			this.notes = vacioEsNulo(notes);
		}
		if (validFrom != null) {
			this.validFrom = validFrom;
		}
		// Dos intenciones distintas y no se pueden expresar con un solo parametro nulable:
		// "no toques el fin de la ventana" y "sacale el fin, que quede sin fin previsto".
		if (limpiarValidUntil) {
			this.validUntil = null;
		} else if (validUntil != null) {
			this.validUntil = validUntil;
		}
		exigirVigenciaCoherente(this.validFrom, this.validUntil);
	}

	/**
	 * Baja logica con motivo declarado (RF-M04-006).
	 *
	 * <p>No borra nada: el espacio deja de ofrecerse para reservas nuevas (RN-M04-002) y todo
	 * lo que ocurrio en el sigue siendo consultable con su nombre y su estado (RN-M04-003).
	 * <b>No hay reactivacion</b>: ningun RF de M04 la pide, y un recurso que vuelve tras una
	 * refaccion es una ventana operativa nueva, no una baja deshecha.
	 */
	public void deactivate(Instant occurredAt, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"La baja de un espacio exige un motivo declarado: sin el, la auditoria no "
							+ "responde por que seis meses despues");
		}
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason;
	}

	/** {@code true} cuando el espacio admite operaciones nuevas (ciclo de vida). */
	public boolean isOperable() {
		return active && deletedAt == null;
	}

	/**
	 * <b>La regla de RN-M04-002, completa y en un solo lugar.</b>
	 *
	 * <p>Un espacio se ofrece para una reserva en el instante {@code at} si y solo si esta
	 * vigente administrativamente Y dentro de su ventana operativa. Las dos condiciones, no
	 * una: un box activo que todavia no entro en servicio no se reserva, y uno que salio por
	 * refaccion tampoco, aunque nunca se haya dado de baja.
	 *
	 * <p>El limite superior es EXCLUSIVO para que dos ventanas consecutivas del mismo recurso
	 * no se solapen en el microsegundo del borde.
	 */
	public boolean estaEnServicio(Instant at) {
		if (!isOperable()) {
			return false;
		}
		if (at.isBefore(validFrom)) {
			return false;
		}
		return validUntil == null || at.isBefore(validUntil);
	}

	/**
	 * Lo mismo, sobre una ventana completa y no sobre un instante.
	 *
	 * <p>Exige que el espacio este en servicio durante TODO el intervalo {@code [desde, hasta)}
	 * y no solo al empezar: una sesion que arranca el ultimo dia de servicio de un box y
	 * termina despues no se puede reservar ahi, y evaluar solo el instante inicial la dejaria
	 * pasar.
	 */
	public boolean estaEnServicioDurante(Instant desde, Instant hasta) {
		if (!isOperable()) {
			return false;
		}
		if (desde.isBefore(validFrom)) {
			return false;
		}
		// hasta es exclusivo por los dos lados: la ventana pedida termina justo cuando el
		// recurso sale de servicio y eso es compatible.
		return validUntil == null || !hasta.isAfter(validUntil);
	}

	private static void exigirCapacidadPositiva(int capacidad) {
		if (capacidad <= 0) {
			throw new IllegalArgumentException(
					"La capacidad de un espacio debe ser mayor que cero (RN-M04-005): un recurso "
							+ "que no admite a nadie es una baja, y la baja lleva motivo");
		}
	}

	private static void exigirVigenciaCoherente(Instant validFrom, Instant validUntil) {
		if (validFrom == null) {
			throw new IllegalArgumentException(
					"La vigencia de un espacio exige un inicio explicito");
		}
		if (validUntil != null && !validUntil.isAfter(validFrom)) {
			throw new IllegalArgumentException(
					"El fin de vigencia de un espacio debe ser posterior a su inicio");
		}
	}

	private static String vacioEsNulo(String valor) {
		return valor == null || valor.isBlank() ? null : valor;
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

	public String getName() {
		return name;
	}

	public EspacioTipo getTipo() {
		return tipo;
	}

	public int getCapacidad() {
		return capacidad;
	}

	public String getNotes() {
		return notes;
	}

	public Instant getValidFrom() {
		return validFrom;
	}

	public Instant getValidUntil() {
		return validUntil;
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
