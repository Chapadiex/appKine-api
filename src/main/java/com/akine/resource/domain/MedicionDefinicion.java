package com.akine.resource.domain;

import com.akine.resource.spi.MedicionTipo;
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

/**
 * Definicion de una medida del examen fisico: que se mide, en que unidad y con que rango
 * (M06, al servicio de RF-M14-004).
 *
 * <h2>Por que NO extiende {@code CatalogoConcepto}</h2>
 *
 * <p>Comparte con los cuatro conceptos de 02.05 el eje del <b>duenio</b> —global o contextual— y
 * el de la <b>baja logica</b>, pero no el tercero, que es el que aquella superclase existe para
 * sostener: la <b>vigencia</b> {@code validFrom}/{@code validUntil}. Ese eje esta en V20 porque
 * RN-M06-003 necesita que un convenio referencie la version aplicable de un codigo —el mismo
 * codigo tiene varias filas con ventanas distintas—. Aca no hay nada equivalente: una definicion
 * de medida no se republica por periodos, y heredar el eje obligaria a decidir que significa
 * registrar una medicion contra una definicion fuera de vigencia, pregunta que ningun RF hace.
 *
 * <p>Heredar por "es parecido" habria puesto dos columnas obligatorias sin semantica en una tabla
 * que no las usa. Un test discontinuado se da de <b>baja</b>, que es lo que la etapa pide.
 *
 * <h2>El tipo manda sobre todo lo demas</h2>
 *
 * <p>{@link MedicionTipo} decide si hay unidad, si hay rango y que columna de valor puede llevar
 * la medicion tomada. Las tres reglas las verifica ademas el motor con los CHECK de V51: la
 * validacion de aplicacion da un mensaje que nombra el problema, la de la base impide la fila
 * corrupta y vale tambien para cualquier escritura que no pase por JPA.
 *
 * <h2>El rango se evalua AL REGISTRAR y nunca al leer</h2>
 *
 * <p>{@code minimo} y {@code maximo} se pueden editar. Cuando se estrechan, las mediciones ya
 * tomadas <b>no</b> se vuelven invalidas: fueron validas contra la version vigente en su momento,
 * y esa version queda copiada en la fila de la medicion. Revalidar al leer convertiria un cambio
 * administrativo en una correccion retroactiva de historia clinica.
 */
@Entity
@Table(name = "medicion_definicion")
public class MedicionDefinicion extends MarcaTemporal {

	/** Tope del nombre visible, igual que en el resto del modulo. */
	public static final int LARGO_MAXIMO_NOMBRE = 160;

	/** Tope de la clave estable, igual que en el resto del modulo. */
	public static final int LARGO_MAXIMO_CODIGO = 48;

	/** Tope de la unidad. Corta: "grados", "cm", "kg", "puntos", "mmHg". */
	public static final int LARGO_MAXIMO_UNIDAD = 24;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * Tenant propietario, o {@code null} si la definicion es <b>global de plataforma</b>.
	 *
	 * <p>Excepcion a ADR-0004 autorizada por ADR-0021: {@code null} significa "de plataforma", no
	 * "falta el dato". {@code updatable = false} porque una definicion no cambia de duenio —
	 * promover una contextual a global cambiaria el significado de todas las mediciones que la
	 * referencian y la haria visible para tenants que no la pidieron.
	 */
	@Column(name = "organization_id", updatable = false)
	private Long organizationId;

	/**
	 * Clave estable. {@code updatable = false}: renombrar es cambiar {@link #name}, no el codigo.
	 * Un codigo mutable haria que una medicion de 2024 apunte a un test que hoy significa otra
	 * cosa.
	 */
	@Column(name = "codigo", nullable = false, updatable = false, length = LARGO_MAXIMO_CODIGO)
	private String codigo;

	@Column(name = "name", nullable = false, length = LARGO_MAXIMO_NOMBRE)
	private String name;

	@Column(name = "descripcion", length = 280)
	private String descripcion;

	/**
	 * {@code updatable = false}, y es la decision menos obvia de la clase.
	 *
	 * <p>Cambiarle el tipo a una definicion ya usada dejaria mediciones cuyo valor vive en una
	 * columna que el tipo nuevo no admite: filas que el CHECK de V52 no puede reparar y que
	 * ninguna pantalla sabria dibujar. Lo que corresponde es dar de baja la definicion y crear
	 * otra — que ademas deja rastro de que el test cambio de naturaleza.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", nullable = false, updatable = false, length = 16)
	private MedicionTipo tipo;

	/**
	 * Unidad de medida. Obligatoria en los tipos numericos, prohibida en los otros dos.
	 *
	 * <p>SI es editable, a diferencia del tipo: corregir "cm" por "centimetros" es legitimo. Lo
	 * que hace inofensivo ese cambio es que la medicion tomada <b>copia</b> la unidad en su fila,
	 * asi que las viejas siguen diciendo lo que decian.
	 */
	@Column(name = "unidad", length = LARGO_MAXIMO_UNIDAD)
	private String unidad;

	@Column(name = "minimo", precision = 10, scale = 3)
	private BigDecimal minimo;

	/** Techo INCLUSIVE: un EVA de 10 es un valor legitimo de la escala. */
	@Column(name = "maximo", precision = 10, scale = 3)
	private BigDecimal maximo;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deactivation_reason", length = 280)
	private String deactivationReason;

	/**
	 * Control optimista <b>y</b> el numero que la medicion copia como {@code definicionVersion}.
	 *
	 * <p>Los dos usos conviven sin conflicto: cada edicion del catalogo la avanza, y eso es
	 * exactamente lo que permite decir contra que redaccion del test —con su rango y su unidad de
	 * entonces— se valido una medicion vieja.
	 */
	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected MedicionDefinicion() {
		// Requerido por JPA.
	}

	public MedicionDefinicion(
			Long organizationId,
			String codigo,
			String name,
			String descripcion,
			MedicionTipo tipo,
			String unidad,
			BigDecimal minimo,
			BigDecimal maximo) {

		this.organizationId = organizationId;
		this.codigo = exigirTexto(codigo, "El codigo de la definicion es obligatorio");
		this.name = exigirTexto(name, "El nombre de la definicion es obligatorio");
		this.descripcion = vacioEsNulo(descripcion);
		if (tipo == null) {
			throw new IllegalArgumentException(
					"El tipo de la definicion es obligatorio: sin el no se sabe que columna de "
							+ "valor admite la medicion");
		}
		this.tipo = tipo;
		this.unidad = vacioEsNulo(unidad);
		this.minimo = minimo;
		this.maximo = maximo;
		this.active = true;
		exigirCoherencia(this.tipo, this.unidad, this.minimo, this.maximo);
	}

	/** {@code true} si la definicion es del catalogo de plataforma y la ve todo el SaaS. */
	public boolean esGlobal() {
		return organizationId == null;
	}

	/**
	 * {@code true} cuando la definicion admite mediciones NUEVAS.
	 *
	 * <p>Una definicion dada de baja sigue siendo legible y sus mediciones siguen siendo
	 * comparables: la baja <b>no cascadea</b>, exactamente como la de un servicio global en 02.06.
	 */
	public boolean isOperable() {
		return active && deletedAt == null;
	}

	/**
	 * Edicion parcial: cada valor {@code null} deja el campo como estaba (semantica de PATCH).
	 *
	 * <p>Ni el codigo, ni el duenio, ni el tipo estan aca: los tres son {@code updatable = false}
	 * por lo que dice su javadoc. {@code limpiarRango} existe porque "no toques el rango" y
	 * "saca el rango" son dos intenciones distintas que un nulable no puede expresar — la misma
	 * forma que {@code CatalogoConcepto#updateDatos} usa para {@code validUntil}.
	 */
	public void updateDatos(
			String name,
			String descripcion,
			String unidad,
			BigDecimal minimo,
			BigDecimal maximo,
			boolean limpiarRango) {

		if (name != null) {
			this.name = exigirTexto(name, "El nombre de la definicion es obligatorio");
		}
		if (descripcion != null) {
			this.descripcion = vacioEsNulo(descripcion);
		}
		if (unidad != null) {
			this.unidad = vacioEsNulo(unidad);
		}
		if (limpiarRango) {
			this.minimo = null;
			this.maximo = null;
		} else {
			if (minimo != null) {
				this.minimo = minimo;
			}
			if (maximo != null) {
				this.maximo = maximo;
			}
		}
		exigirCoherencia(this.tipo, this.unidad, this.minimo, this.maximo);
	}

	/**
	 * Baja logica con motivo declarado (RN-M06-001).
	 *
	 * <p>No borra nada y <b>no cascadea</b>: las mediciones existentes siguen legibles y
	 * comparables, y lo unico que se impide es registrar nuevas. <b>No hay reactivacion</b>: una
	 * definicion que vuelve es una definicion nueva, y modelarlo como baja deshecha borraria el
	 * rastro de que el test dejo de usarse alguna vez.
	 */
	public void deactivate(Instant occurredAt, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"La baja de una definicion de medicion exige un motivo declarado: sin el, la "
							+ "auditoria no responde por que seis meses despues");
		}
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason.strip();
	}

	/**
	 * Las tres reglas que el tipo impone, en un solo lugar.
	 *
	 * <p>Duplican los CHECK de V51 a proposito: la base impide la fila corrupta, esta impide un
	 * 500 con un mensaje que no nombra nada.
	 */
	private static void exigirCoherencia(
			MedicionTipo tipo, String unidad, BigDecimal minimo, BigDecimal maximo) {

		if (tipo.esNumerico() && unidad == null) {
			throw new IllegalArgumentException(
					"Una medida " + tipo + " exige unidad: un numero sin unidad no es una "
							+ "medicion, y el dia que el catalogo cambie de escala nadie podra "
							+ "saber que decia el valor anterior");
		}
		if (!tipo.esNumerico() && unidad != null) {
			throw new IllegalArgumentException(
					"Una medida " + tipo + " no lleva unidad");
		}
		if (!tipo.esNumerico() && (minimo != null || maximo != null)) {
			throw new IllegalArgumentException(
					"Una medida " + tipo + " no lleva rango: un minimo sobre un texto o un "
							+ "booleano no significa nada");
		}
		if (minimo != null && maximo != null && maximo.compareTo(minimo) < 0) {
			throw new IllegalArgumentException(
					"El maximo del rango no puede ser menor que el minimo");
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

	public MedicionTipo getTipo() {
		return tipo;
	}

	public String getUnidad() {
		return unidad;
	}

	public BigDecimal getMinimo() {
		return minimo;
	}

	public BigDecimal getMaximo() {
		return maximo;
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
