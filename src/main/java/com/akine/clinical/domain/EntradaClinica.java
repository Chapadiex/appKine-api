package com.akine.clinical.domain;

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
 * La cabecera de un hecho clinico registrado en una Historia Clinica (RF-M09-006).
 *
 * <h2>Lo que esta fila es, y lo que deliberadamente no es</h2>
 *
 * <p>Es la <b>identidad</b> del hecho: que clase de hecho es, cuando ocurrio, de que historia
 * cuelga y quien lo registro. Nada de eso cambia nunca, y por eso casi todas sus columnas son
 * {@code updatable = false}.
 *
 * <p><b>No tiene el texto.</b> El contenido vive en {@link EntradaClinicaVersion}, una fila por
 * version. La razon esta en RF-M09-006: la enmienda tiene que <b>preservar</b> el original y las
 * versiones tienen que poder consultarse. Una columna {@code cuerpo} con {@code @Version} encima
 * protege contra escrituras concurrentes y borra el texto anterior — resuelve un problema
 * distinto del que esta clase tiene. Es el mismo argumento por el que {@link AntecedenteClinico}
 * es una fila y no una columna de texto de la historia.
 *
 * <h2>El contador de versiones vive aca, y ese es el punto</h2>
 *
 * <p>{@link #ultimoNumeroVersion} es lo que numera las enmiendas. <b>No hay ningun
 * {@code MAX(numero_version) + 1}</b>: dos enmiendas concurrentes calculando ese MAX devuelven el
 * mismo numero y dejan dos "version 3" sin criterio para desempatarlas. El numero sale del
 * contador de esta fila, que se incrementa en la misma transaccion que inserta la version —el
 * patron de 06.05 y la regla 2 del Paquete B—.
 *
 * <p>La leccion de 02.07 aplica y esta contemplada: un {@code @Version} sobre el padre no protege
 * una escritura que solo toca tablas hijas. Aca la escritura <b>si</b> toca al padre, porque el
 * contador es suyo, y ademas la aplicacion lo lee con {@code OPTIMISTIC_FORCE_INCREMENT}. El
 * perdedor de la carrera recibe conflicto y reintenta con el numero siguiente.
 *
 * <h2>La baja no borra, y no toca las versiones</h2>
 *
 * <p>{@link #deactivate} es baja logica con motivo obligatorio. Las versiones de contenido
 * <b>nunca</b> se dan de baja: una version es un hecho pasado y darla de baja seria reescribir
 * historia clinica, que ADR-0011 prohibe. Una entrada dada de baja sale del timeline y sigue
 * siendo consultable por su id — que es lo que distingue "no lo muestres" de "no existio".
 */
@Entity
@Table(name = "entrada_clinica")
public class EntradaClinica extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	/**
	 * Historia de la que cuelga. {@code Long} y no {@code @ManyToOne} por el mismo motivo que en
	 * {@link HistoriaClinica}: la relacion se resuelve por id y las consultas acotan por tenant
	 * antes que por nada.
	 *
	 * <p><b>No hay {@code casoId}.</b> El Caso Clinico es 04.03 y un "caso por defecto" para que
	 * el timeline pueda agrupar es un Caso mal hecho que despues hay que desarmar (challenge
	 * seccion 4). Llega nullable cuando el Caso exista.
	 */
	@Column(name = "historia_clinica_id", nullable = false, updatable = false)
	private Long historiaClinicaId;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", nullable = false, length = 24, updatable = false)
	private TipoEntradaClinica tipo;

	/**
	 * Cuando ocurrio el hecho, que <b>no</b> es cuando se tipeo.
	 *
	 * <p>Una evolucion se carga al final del dia y el timeline tiene que ordenarla por el momento
	 * clinico, no por el administrativo. {@link #registradaEn} guarda el otro.
	 */
	@Column(name = "ocurrio_en", nullable = false, updatable = false)
	private Instant ocurrioEn;

	@Enumerated(EnumType.STRING)
	@Column(name = "origen", nullable = false, length = 24, updatable = false)
	private OrigenEntradaClinica origen;

	@Column(name = "referencia_origen", updatable = false)
	private Long referenciaOrigen;

	@Column(name = "ultimo_numero_version", nullable = false)
	private int ultimoNumeroVersion = 1;

	@Column(name = "registrada_en", nullable = false, updatable = false)
	private Instant registradaEn;

	@Column(name = "registrada_por", nullable = false, updatable = false)
	private Long registradaPor;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deactivation_reason", length = 280)
	private String deactivationReason;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected EntradaClinica() {
		// Requerido por JPA.
	}

	/**
	 * Una entrada nueva, con su primera version ya contada.
	 *
	 * <p>{@link #ultimoNumeroVersion} nace en {@code 1} y no en {@code 0} porque toda entrada se
	 * crea junto con su version 1 en la misma transaccion: una cabecera sin contenido no es un
	 * estado que esta etapa admita.
	 */
	public EntradaClinica(
			Long organizationId,
			Long historiaClinicaId,
			TipoEntradaClinica tipo,
			Instant ocurrioEn,
			OrigenEntradaClinica origen,
			Long referenciaOrigen,
			Instant registradaEn,
			Long registradaPor) {

		this.organizationId =
				exigirNoNulo(organizationId, "La entrada pertenece siempre a una organizacion");
		this.historiaClinicaId =
				exigirNoNulo(historiaClinicaId, "La entrada pertenece siempre a una historia");
		this.tipo = exigirNoNulo(tipo, "La entrada declara siempre su tipo");
		this.ocurrioEn = exigirNoNulo(ocurrioEn, "La entrada declara siempre cuando ocurrio");
		this.origen = exigirNoNulo(origen, "La entrada declara siempre como nacio");
		this.referenciaOrigen = exigirReferenciaCoherente(origen, referenciaOrigen);
		this.registradaEn = exigirNoNulo(registradaEn, "El registro deja siempre su instante");
		this.registradaPor = exigirNoNulo(registradaPor, "El registro deja siempre a su autor");
		this.ultimoNumeroVersion = 1;
		this.active = true;
	}

	/**
	 * Reserva el numero de la proxima version y lo devuelve.
	 *
	 * <p>Es el unico camino para numerar una enmienda. Lo que serializa dos enmiendas
	 * concurrentes no es este metodo —que corre en memoria— sino la combinacion de leer esta fila
	 * con {@code OPTIMISTIC_FORCE_INCREMENT} y el unique
	 * {@code uk_entrada_version_numero} debajo: el perdedor recibe conflicto en el commit.
	 */
	public int siguienteNumeroDeVersion() {
		this.ultimoNumeroVersion += 1;
		return this.ultimoNumeroVersion;
	}

	/**
	 * Baja logica de la entrada, con motivo obligatorio.
	 *
	 * <p>El motivo lo exige tambien la base ({@code ck_entrada_clinica_baja_coherente}): una
	 * entrada clinica que desaparece del timeline sin explicacion es lo que RN-M09-004 quiere
	 * impedir. Las versiones de contenido no se tocan.
	 */
	public void deactivate(Instant occurredAt, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"La baja de una entrada clinica exige un motivo declarado");
		}
		this.active = false;
		this.deletedAt = exigirNoNulo(occurredAt, "La baja registra siempre su instante");
		this.deactivationReason = reason.strip();
	}

	/** {@code true} mientras la entrada siga formando parte del timeline. */
	public boolean isVigente() {
		return active && deletedAt == null;
	}

	/** {@code true} si la entrada fue enmendada al menos una vez. */
	public boolean fueEnmendada() {
		return ultimoNumeroVersion > 1;
	}

	private static Long exigirReferenciaCoherente(
			OrigenEntradaClinica origen, Long referenciaOrigen) {

		if (origen.exigeReferencia() && referenciaOrigen == null) {
			throw new IllegalArgumentException(
					"Una entrada de origen " + origen + " declara siempre la fila que la origino");
		}
		if (!origen.exigeReferencia() && referenciaOrigen != null) {
			throw new IllegalArgumentException(
					"Una entrada manual no referencia ninguna entidad de origen");
		}
		return referenciaOrigen;
	}

	private static <T> T exigirNoNulo(T valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getHistoriaClinicaId() {
		return historiaClinicaId;
	}

	public TipoEntradaClinica getTipo() {
		return tipo;
	}

	public Instant getOcurrioEn() {
		return ocurrioEn;
	}

	public OrigenEntradaClinica getOrigen() {
		return origen;
	}

	public Long getReferenciaOrigen() {
		return referenciaOrigen;
	}

	public int getUltimoNumeroVersion() {
		return ultimoNumeroVersion;
	}

	public Instant getRegistradaEn() {
		return registradaEn;
	}

	public Long getRegistradaPor() {
		return registradaPor;
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
