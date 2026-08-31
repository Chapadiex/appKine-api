package com.akine.offering.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Lo que una habilitacion de Oferta tiene, sea de profesional o de espacio (AKINE-02.07).
 *
 * <h2>Por que una base compartida y NO una tabla polimorfica</h2>
 *
 * <p>Las dos habilitaciones comparten exactamente estas columnas —tenant, sede, oferta, ventana
 * de vigencia y ciclo de vida— y difieren en una sola: a que recurso apuntan. Compartir el
 * comportamiento en un {@code @MappedSuperclass} evita duplicar doscientas lineas <b>sin</b>
 * fusionar las dos tablas, que es lo que no se quiere: una tabla unica con {@code tipo_recurso}
 * obligaria a una FK nullable por destino y haria imposible el unique que impide habilitar dos
 * veces el mismo recurso. Ver la cabecera de {@code V26}.
 *
 * <h2>Habilitacion NO es permiso</h2>
 *
 * <p>Nada de lo que hay aca otorga acceso. Esta jerarquia no se consulta jamas desde
 * {@code PermissionGuard} y no existe ningun camino por el que pueda hacerlo. Una habilitacion
 * responde <b>"puede prestar esto"</b>; un permiso responde <b>"puede tocar esto"</b>. Un
 * {@code ORG_ADMIN} sin habilitacion administra la oferta y no la presta; un profesional
 * habilitado sin membership vigente no entra siquiera. Se cruzan recien en la agenda, que va a
 * exigir las dos.
 *
 * <h2>Vigencia y ciclo de vida son dos ejes distintos</h2>
 *
 * <p>{@code active}/{@code deletedAt} dicen si la habilitacion existe; {@code validFrom}/
 * {@code validUntil} dicen si rige hoy. Un profesional que se va tres meses NO se borra de las
 * habilitaciones: se le pone fin de vigencia, y el historico sigue explicando por que atendio lo
 * que atendio. Es la misma separacion que ya hacen {@code Espacio} entre activo y en servicio, y
 * {@code OfertaServicioConsultorio} entre estado y vigencia.
 */
@MappedSuperclass
public abstract class Habilitacion extends MarcaTemporal {

	@Column(name = "organization_id", nullable = false)
	private Long organizationId;

	/**
	 * Sede de la oferta.
	 *
	 * <p>Redundante con la oferta a proposito: la agenda va a preguntar "quien esta habilitado en
	 * esta sede" sin querer pasar por {@code oferta}, y sin esta columna ese indice no se puede
	 * construir. La coherencia la sostiene la capa de aplicacion, que la copia de la oferta.
	 */
	@Column(name = "consultorio_id", nullable = false)
	private Long consultorioId;

	@Column(name = "oferta_id", nullable = false)
	private Long ofertaId;

	@Column(name = "valid_from", nullable = false)
	private Instant validFrom;

	/** EXCLUSIVO. {@code null} = sin fin previsto, que es un estado real y no un dato faltante. */
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

	/**
	 * Id de la fila. Lo declara cada subclase porque la {@code @Id} vive alli, no aca.
	 *
	 * <p>Se declara abstracto para que {@code offering.spi.OfertaDirectory} pueda proyectar las dos
	 * tablas de habilitacion con un solo metodo. Sin esto, la unica alternativa es duplicar la
	 * proyeccion —y con ella la logica de vigencia— una vez por tabla, que es exactamente el par de
	 * lugares donde el mismo bug de bordes se arregla en uno solo.
	 */
	public abstract Long getId();

	protected Habilitacion() {
		// Requerido por JPA.
	}

	protected Habilitacion(
			Long organizationId, Long consultorioId, Long ofertaId,
			Instant validFrom, Instant validUntil) {

		this.organizationId = exigir(organizationId, "La habilitacion exige una organizacion");
		this.consultorioId = exigir(consultorioId, "La habilitacion exige una sede");
		this.ofertaId = exigir(ofertaId, "La habilitacion exige una oferta");
		this.validFrom = validFrom == null ? Instant.now() : validFrom;
		this.validUntil = validUntil;
		exigirVigenciaCoherente(this.validFrom, this.validUntil);
	}

	/**
	 * Baja logica con motivo declarado.
	 *
	 * <p>Quitar una habilitacion no borra: cierra. El historico tiene que poder explicar por que
	 * un profesional atendio una oferta el anio pasado.
	 */
	public void deactivate(Instant occurredAt, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"La baja de una habilitacion exige un motivo declarado: sin el, la auditoria "
							+ "no responde por que seis meses despues");
		}
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason.strip();
	}

	/** Mueve el fin de vigencia sin dar de baja la fila. */
	public void reprogramarVigencia(Instant nuevoDesde, Instant nuevoHasta) {
		Instant desde = nuevoDesde == null ? this.validFrom : nuevoDesde;
		exigirVigenciaCoherente(desde, nuevoHasta);
		this.validFrom = desde;
		this.validUntil = nuevoHasta;
	}

	/** {@code true} cuando la fila no fue dada de baja. */
	public boolean isOperable() {
		return active && deletedAt == null;
	}

	/**
	 * {@code true} si en {@code at} la habilitacion existe Y esta dentro de su ventana.
	 *
	 * <p>Las dos condiciones, no una: una habilitacion dada de baja nunca rige, y una vigente pero
	 * fuera de ventana tampoco.
	 */
	public boolean rigeEn(Instant at) {
		if (!isOperable() || at.isBefore(validFrom)) {
			return false;
		}
		return validUntil == null || at.isBefore(validUntil);
	}

	private static Long exigir(Long valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
	}

	private static void exigirVigenciaCoherente(Instant desde, Instant hasta) {
		if (hasta != null && !hasta.isAfter(desde)) {
			throw new IllegalArgumentException(
					"El fin de vigencia de una habilitacion tiene que ser posterior a su inicio: "
							+ "el fin es exclusivo, asi que una ventana de largo cero no habilita "
							+ "nada");
		}
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getConsultorioId() {
		return consultorioId;
	}

	public Long getOfertaId() {
		return ofertaId;
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

	/** El id del recurso habilitado: la membership o el espacio, segun la subclase. */
	public abstract Long getRecursoId();
}
