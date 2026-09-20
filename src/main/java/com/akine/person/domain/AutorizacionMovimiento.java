package com.akine.person.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Un hecho que movio el saldo de una {@link Autorizacion}, asentado para siempre (RF-M17-004).
 *
 * <h2>Append-only, y la ausencia es el diseño</h2>
 *
 * <p>No tiene {@code version}, ni {@code updatedAt}, ni baja logica, y su puerto no declara
 * {@code update} ni {@code delete}: <b>un ledger que se puede editar no es un ledger</b>. Mismo
 * diseño y mismo argumento que {@code turno_evento} (05.03), {@code caso_evento} (04.03) y
 * {@code plan_evento} (04.04), y es la regla maestra 10 aplicada.
 *
 * <p>Corolario: <b>la reversion no borra</b>. Compensa con una fila propia de tipo
 * {@link TipoMovimientoAutorizacion#REVERSION}, con motivo obligatorio, y el consumo original
 * queda donde estaba diciendo que ocurrio.
 *
 * <h2>Esto es la fuente de verdad; la columna es el saldo materializado</h2>
 *
 * <p>{@code autorizacion.cantidad_consumida} existe desde V44 y hasta 04.05 nadie la movia. No se
 * elimina —la elegibilidad de 03.06 ya la lee— sino que pasa a ser el resultado acumulado de estas
 * filas, escrito <b>en la misma transaccion</b>. Que las dos cosas vivan en el mismo modulo es lo
 * que permite garantizarlo; es exactamente la diferencia que 04.04 invoco para <b>no</b> guardar
 * {@code cantidadRealizada} en {@code plan_item}, donde el dueño del hecho era otro modulo.
 *
 * <h2>La cantidad es siempre positiva</h2>
 *
 * <p>El signo lo da {@link #tipo}. Ver {@link TipoMovimientoAutorizacion}.
 *
 * <h2>Lo que esta clase NO valida</h2>
 *
 * <p><b>No comprueba que haya saldo.</b> Eso no lo puede decidir una instancia: lo decide el
 * {@code UPDATE} condicional contra la base —{@code WHERE cantidad_autorizada - cantidad_consumida
 * >= :n}—, porque entre leer el saldo y escribirlo hay una ventana en la que otra sesion se lleva
 * la ultima unidad. Es el mismo reparto de responsabilidades con el que {@link Autorizacion} sabe
 * decidir un solapamiento entre dos instancias y no puede hacerlo cumplir sola.
 */
@Entity
@Table(name = "autorizacion_movimiento")
public class AutorizacionMovimiento {

	/** Largo del motivo, tal como lo declara {@code V50}. */
	public static final int MOTIVO_MAXIMO = 280;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "autorizacion_id", nullable = false, updatable = false)
	private Long autorizacionId;

	@Column(name = "persona_id", nullable = false, updatable = false)
	private Long personaId;

	@Column(name = "consultorio_id", updatable = false)
	private Long consultorioId;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", nullable = false, updatable = false, length = 24)
	private TipoMovimientoAutorizacion tipo;

	@Column(name = "cantidad", nullable = false, updatable = false)
	private Integer cantidad;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo_origen", nullable = false, updatable = false, length = 16)
	private TipoOrigenMovimiento tipoOrigen;

	@Column(name = "referencia_origen", nullable = false, updatable = false)
	private Long referenciaOrigen;

	@Column(name = "motivo", updatable = false, length = MOTIVO_MAXIMO)
	private String motivo;

	@Column(name = "movimiento_origen_id", updatable = false)
	private Long movimientoOrigenId;

	@Column(name = "ocurrio_en", nullable = false, updatable = false)
	private Instant ocurrioEn;

	@Column(name = "actor_cuenta_id", updatable = false)
	private Long actorCuentaId;

	/**
	 * Marca de insercion propia.
	 *
	 * <p>Esta clase <b>no</b> extiende {@link MarcaTemporal} a proposito: esa trae
	 * {@code updated_at}, y una fila de ledger que declara haber sido modificada es una
	 * contradiccion. La tabla tampoco tiene esa columna.
	 */
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected AutorizacionMovimiento() {
		// Requerido por JPA.
	}

	@SuppressWarnings({"java:S107", "checkstyle:ParameterNumber"})
	public AutorizacionMovimiento(
			Long organizationId,
			Long autorizacionId,
			Long personaId,
			Long consultorioId,
			TipoMovimientoAutorizacion tipo,
			int cantidad,
			TipoOrigenMovimiento tipoOrigen,
			Long referenciaOrigen,
			String motivo,
			Long movimientoOrigenId,
			Instant ocurrioEn,
			Long actorCuentaId) {

		this.organizationId = exigirNoNulo(organizationId, "El movimiento pertenece a un tenant");
		this.autorizacionId = exigirNoNulo(
				autorizacionId, "El movimiento mueve el saldo de una autorizacion concreta");
		this.personaId = exigirNoNulo(personaId, "El movimiento es de un paciente");
		this.consultorioId = consultorioId;
		this.tipo = exigirNoNulo(tipo, "El movimiento declara siempre que clase de hecho es");
		if (cantidad <= 0) {
			throw new IllegalArgumentException(
					"La cantidad de un movimiento es siempre positiva: el signo lo da el tipo, no "
							+ "el numero");
		}
		this.cantidad = cantidad;
		this.tipoOrigen = exigirNoNulo(
				tipoOrigen, "El movimiento declara siempre que clase de hecho lo produjo");
		this.referenciaOrigen = exigirNoNulo(
				referenciaOrigen, "El movimiento declara siempre cual fue ese hecho");
		this.motivo = exigirMotivo(tipo, motivo);
		if (movimientoOrigenId != null && tipo != TipoMovimientoAutorizacion.REVERSION) {
			throw new IllegalArgumentException(
					"Solo una reversion compensa a otro movimiento: una cadena de consumos que se "
							+ "apuntan entre si no la sabe leer nadie");
		}
		this.movimientoOrigenId = movimientoOrigenId;
		this.ocurrioEn = exigirNoNulo(ocurrioEn, "El movimiento declara cuando ocurrio el hecho");
		this.actorCuentaId = actorCuentaId;
		this.createdAt = ocurrioEn;
	}

	// =================================================================================
	// Preguntas
	// =================================================================================

	/** Lo que este hecho le suma o le resta al saldo disponible. Negativo si lo gasta. */
	public int efectoSobreElSaldo() {
		return tipo.descuentaSaldo() ? -cantidad : cantidad;
	}

	// =================================================================================
	// Invariantes
	// =================================================================================

	/**
	 * RF-M17-005: revertir exige motivo declarado.
	 *
	 * <p>Lo exige tambien {@code ck_movimiento_motivo_exigido} del lado del motor. Estar en los
	 * dos lados no es redundancia inutil: aca produce un 400 explicable y alla protege a cualquier
	 * camino futuro que no pase por esta clase.
	 */
	private static String exigirMotivo(TipoMovimientoAutorizacion tipo, String motivo) {
		String limpio = motivo == null || motivo.isBlank() ? null : motivo.strip();
		if (tipo.exigeMotivo() && limpio == null) {
			throw new IllegalArgumentException(
					"Revertir un consumo exige un motivo declarado: sin el, quien audite no puede "
							+ "distinguir un error de carga de un fraude");
		}
		if (limpio != null && limpio.length() > MOTIVO_MAXIMO) {
			return limpio.substring(0, MOTIVO_MAXIMO);
		}
		return limpio;
	}

	private static <T> T exigirNoNulo(T valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
	}

	// =================================================================================
	// Accesores
	// =================================================================================

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getAutorizacionId() {
		return autorizacionId;
	}

	public Long getPersonaId() {
		return personaId;
	}

	public Long getConsultorioId() {
		return consultorioId;
	}

	public TipoMovimientoAutorizacion getTipo() {
		return tipo;
	}

	public int getCantidad() {
		return cantidad;
	}

	public TipoOrigenMovimiento getTipoOrigen() {
		return tipoOrigen;
	}

	public Long getReferenciaOrigen() {
		return referenciaOrigen;
	}

	public String getMotivo() {
		return motivo;
	}

	public Long getMovimientoOrigenId() {
		return movimientoOrigenId;
	}

	public Instant getOcurrioEn() {
		return ocurrioEn;
	}

	public Long getActorCuentaId() {
		return actorCuentaId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
