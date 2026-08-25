package com.akine.resource.domain;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Lo que especialidad, practica, nomenclador y vigencia de nomenclador tienen en comun (M06).
 *
 * <h2>Por que una sola superclase y no cuatro entidades independientes</h2>
 *
 * <p>Los cuatro conceptos de M06 comparten <b>exactamente</b> el mismo par de invariantes, y
 * son las que esta etapa existe para sostener:
 *
 * <pre>
 *   DUENIO       organizationId == null  -> concepto GLOBAL de plataforma
 *                organizationId != null  -> concepto CONTEXTUAL de ese tenant
 *
 *   CICLO DE VIDA   active / deletedAt / deactivationReason
 *   VIGENCIA        validFrom / validUntil
 * </pre>
 *
 * <p>Escribirlas cuatro veces garantiza que en algun momento las cuatro copias dejen de decir
 * lo mismo, y la primera en divergir seria la que decide si un concepto historico sigue
 * resolviendo. Con una superclase, {@link #estaVigente(Instant)} y {@link #esGlobal()} tienen
 * <b>un solo cuerpo</b>.
 *
 * <p>Es {@code @MappedSuperclass} y no {@code @Inheritance}: cada concepto tiene su propia
 * tabla, sus propios uniques y sus propios indices. No hay ninguna consulta polimorfica sobre
 * "conceptos" y no tiene que haberla — una tabla unica con un discriminador mezclaria en el
 * mismo indice cosas que se consultan por caminos distintos.
 *
 * <h2>Los dos ejes temporales, que no son el mismo</h2>
 *
 * <p>Es la misma distincion que {@code Espacio} ya hace, y confundirlas es el error que hace
 * que una practica dada de baja siga apareciendo en un selector o que una sesion vieja deje de
 * decir que prestacion fue:
 *
 * <pre>
 *   CICLO DE VIDA   "esta practica ya no forma parte del catalogo". Irreversible, con motivo,
 *                   decidido por una persona. RN-M06-001: baja LOGICA, nunca borrado.
 *
 *   VIGENCIA        "este codigo rige entre marzo y diciembre". Planificacion, sin motivo.
 *                   Es lo que RN-M06-003 necesita para que un convenio referencie la version
 *                   aplicable y no "el codigo".
 * </pre>
 *
 * <h2>La regla en una linea</h2>
 *
 * <p>Un concepto se <b>ofrece para una seleccion nueva</b> solo si esta vigente
 * ({@link #estaVigente(Instant)}); un concepto <b>resuelve para un historico</b> siempre,
 * este activo o no (RN-M06-001, RN-M06-002). Un endpoint que devolviera 404 sobre un concepto
 * dado de baja estaria borrando historia por la puerta de atras.
 */
@MappedSuperclass
public abstract class CatalogoConcepto extends MarcaTemporal {

	/** Tope de los nombres visibles, igual que en el resto del modulo. */
	public static final int LARGO_MAXIMO_NOMBRE = 160;

	/** Tope de las claves estables. */
	public static final int LARGO_MAXIMO_CODIGO = 48;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * Tenant propietario, o {@code null} si el concepto es <b>global de plataforma</b>.
	 *
	 * <p>Es la excepcion a ADR-0004 que ADR-0021 autoriza. {@code null} significa "de
	 * plataforma", no "falta el dato": son dos poblaciones que conviven en la misma tabla y el
	 * discriminador es el dato.
	 *
	 * <p>{@code updatable = false} y es deliberado: un concepto no cambia de duenio. Promover
	 * uno contextual a global cambiaria el significado de todos los hechos historicos que lo
	 * referencian —pasarian a decir que usaron un concepto de plataforma— y ademas lo haria
	 * visible para todos los demas tenants sin que ninguno lo haya pedido. El camino correcto
	 * es una solicitud de alta (RF-M06-005).
	 */
	@Column(name = "organization_id", updatable = false)
	private Long organizationId;

	/**
	 * Clave estable. Es lo que los convenios y las sesiones guardan, y por eso
	 * {@code updatable = false}: renombrar es cambiar {@link #name}, no el codigo. Un codigo
	 * mutable haria que un historico de 2024 apunte a un concepto que hoy significa otra cosa,
	 * que es exactamente lo que RN-M06-002 prohibe.
	 */
	@Column(name = "codigo", nullable = false, updatable = false, length = LARGO_MAXIMO_CODIGO)
	private String codigo;

	@Column(name = "name", nullable = false, length = LARGO_MAXIMO_NOMBRE)
	private String name;

	@Column(name = "descripcion", length = 280)
	private String descripcion;

	@Column(name = "valid_from", nullable = false)
	private Instant validFrom;

	/** Limite EXCLUSIVO de la vigencia. {@code null} = sin fin previsto. */
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

	protected CatalogoConcepto() {
		// Requerido por JPA.
	}

	protected CatalogoConcepto(
			Long organizationId,
			String codigo,
			String name,
			String descripcion,
			Instant validFrom,
			Instant validUntil) {

		this.organizationId = organizationId;
		this.codigo = exigirTexto(codigo, "El codigo del concepto es obligatorio");
		this.name = exigirTexto(name, "El nombre del concepto es obligatorio");
		this.descripcion = vacioEsNulo(descripcion);
		this.validFrom = validFrom;
		this.validUntil = validUntil;
		this.active = true;
		exigirVigenciaCoherente(this.validFrom, this.validUntil);
	}

	/**
	 * {@code true} si el concepto es del catalogo de plataforma y lo ve todo el SaaS.
	 *
	 * <p>Un concepto global solo lo administra el administrador de plataforma; uno contextual,
	 * el administrador del tenant que lo creo. La distincion es la razon de ser de la etapa.
	 */
	public boolean esGlobal() {
		return organizationId == null;
	}

	/** {@code true} cuando el concepto admite operaciones nuevas (ciclo de vida). */
	public boolean isOperable() {
		return active && deletedAt == null;
	}

	/**
	 * <b>La regla de "que se puede elegir hoy", completa y en un solo lugar.</b>
	 *
	 * <p>Un concepto se ofrece para una seleccion nueva si y solo si esta vigente
	 * administrativamente Y dentro de su ventana de vigencia. Las dos, no una: una practica
	 * activa que entra en vigor el mes que viene no se puede elegir hoy, y una que salio de
	 * vigencia tampoco, aunque nunca se haya dado de baja.
	 *
	 * <p>El limite superior es EXCLUSIVO para que dos vigencias consecutivas del mismo concepto
	 * no se solapen en el microsegundo del borde.
	 */
	public boolean estaVigente(Instant at) {
		if (!isOperable()) {
			return false;
		}
		if (at.isBefore(validFrom)) {
			return false;
		}
		return validUntil == null || at.isBefore(validUntil);
	}

	/**
	 * {@code true} si la ventana de este concepto se solapa con {@code [desde, hasta)}.
	 *
	 * <p>Es la comprobacion que impide dos vigencias pisadas del mismo codigo (RF-M06-003), y
	 * vive aca —no en el servicio— porque es aritmetica de intervalos y no depende de nada
	 * externo. Con los dos extremos superiores exclusivos, dos ventanas consecutivas
	 * {@code [a, b)} y {@code [b, c)} NO se solapan, que es lo que un cierre de vigencia
	 * seguido de una apertura tiene que producir.
	 *
	 * <p>{@code null} en cualquiera de los dos finales significa "abierta", y una ventana
	 * abierta se solapa con todo lo que empiece despues de su inicio.
	 */
	public boolean seSolapaCon(Instant desde, Instant hasta) {
		boolean empiezaAntesDeQueTermineElOtro = hasta == null || validFrom.isBefore(hasta);
		boolean terminaDespuesDeQueEmpieceElOtro = validUntil == null || validUntil.isAfter(desde);
		return empiezaAntesDeQueTermineElOtro && terminaDespuesDeQueEmpieceElOtro;
	}

	/**
	 * Aplica una edicion parcial: cada valor {@code null} deja el campo como estaba.
	 *
	 * <p>Semantica de PATCH y no de PUT. Para borrar {@code descripcion} se manda cadena vacia,
	 * que se normaliza a {@code null}: misma convencion que el resto del modulo.
	 *
	 * <p>Ni el codigo ni el duenio estan aca, y no es un olvido: los dos son
	 * {@code updatable = false} por lo que dice su javadoc.
	 */
	public void updateDatos(
			String name,
			String descripcion,
			Instant validFrom,
			Instant validUntil,
			boolean limpiarValidUntil) {

		if (name != null) {
			this.name = exigirTexto(name, "El nombre del concepto es obligatorio");
		}
		if (descripcion != null) {
			this.descripcion = vacioEsNulo(descripcion);
		}
		if (validFrom != null) {
			this.validFrom = validFrom;
		}
		// Dos intenciones distintas que un solo parametro nulable no puede expresar:
		// "no toques el fin de la vigencia" y "sacale el fin, que quede abierta".
		if (limpiarValidUntil) {
			this.validUntil = null;
		} else if (validUntil != null) {
			this.validUntil = validUntil;
		}
		exigirVigenciaCoherente(this.validFrom, this.validUntil);
	}

	/**
	 * Baja logica con motivo declarado (RN-M06-001).
	 *
	 * <p>No borra nada: el concepto deja de ofrecerse para selecciones nuevas y todo lo que lo
	 * referencia sigue resolviendo con su nombre y su estado (RN-M06-002). <b>No hay
	 * reactivacion</b>: un concepto que vuelve es una vigencia nueva, no una baja deshecha, y
	 * modelarlo como baja deshecha borraria el rastro de que dejo de ofrecerse alguna vez.
	 */
	public void deactivate(Instant occurredAt, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"La baja de un concepto de catalogo exige un motivo declarado: sin el, la "
							+ "auditoria no responde por que seis meses despues");
		}
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason.strip();
	}

	private static void exigirVigenciaCoherente(Instant validFrom, Instant validUntil) {
		if (validFrom == null) {
			throw new IllegalArgumentException(
					"La vigencia de un concepto de catalogo exige un inicio explicito");
		}
		if (validUntil != null && !validUntil.isAfter(validFrom)) {
			throw new IllegalArgumentException(
					"El fin de vigencia debe ser posterior a su inicio");
		}
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

	public String getCodigo() {
		return codigo;
	}

	public String getName() {
		return name;
	}

	public String getDescripcion() {
		return descripcion;
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
