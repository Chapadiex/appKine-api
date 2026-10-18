package com.akine.activity.domain;

import com.akine.activity.domain.exception.ProductoDadoDeBajaException;
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
import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;

/**
 * El producto vendible de pack sobre una Oferta (RF-M29-001). Define <b>que se vende y a cuanto</b>;
 * lo que una persona compro vive en {@link PaseServicio}, con su propio snapshot.
 *
 * <p><b>Producto != Pase comprado.</b> Editar el producto no toca un solo pase vendido: subirle el
 * precio al pack manana no puede cambiar lo que alguien pago hoy. Por eso la edicion es libre en
 * todo menos en la oferta y el tipo, que definen que es el producto.
 *
 * <p><b>La baja no cascadea</b> (misma regla que 02.06 con el Servicio): significa "no se vende
 * mas". Los pases vendidos conservan saldo y vigencia, y {@code pase_servicio.producto_id} es FK
 * {@code RESTRICT}, asi que el borrado fisico es imposible desde el motor.
 *
 * <p>{@code deleted_key} es una columna generada por el motor (V64) y no se mapea.
 */
@Entity
@Table(name = "producto_servicio")
public class ProductoServicio {

	public static final int NOMBRE_MAX = 160;
	public static final int DESCRIPCION_MAX = 500;
	public static final int MOTIVO_MAX = 280;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "oferta_id", nullable = false, updatable = false)
	private Long ofertaId;

	@Column(name = "nombre", nullable = false, length = NOMBRE_MAX)
	private String nombre;

	@Column(name = "descripcion", length = DESCRIPCION_MAX)
	private String descripcion;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", nullable = false, length = 16, updatable = false)
	private TipoProducto tipo;

	@Column(name = "creditos", nullable = false)
	private int creditos;

	@Column(name = "precio", nullable = false, precision = 12, scale = 2)
	private BigDecimal precio;

	@Column(name = "moneda", nullable = false, length = 3)
	private String moneda;

	@Column(name = "vigencia_dias", nullable = false)
	private int vigenciaDias;

	@Column(name = "vigencia_desde", nullable = false)
	private LocalDate vigenciaDesde;

	@Column(name = "vigencia_hasta")
	private LocalDate vigenciaHasta;

	@Column(name = "active", nullable = false)
	private boolean active;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deactivation_reason", length = MOTIVO_MAX)
	private String deactivationReason;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected ProductoServicio() {
		// Requerido por JPA.
	}

	/** Alta de un pack (RF-M29-001). */
	@SuppressWarnings("checkstyle:ParameterNumber")
	public static ProductoServicio pack(
			long organizationId,
			long consultorioId,
			long ofertaId,
			String nombre,
			String descripcion,
			int creditos,
			BigDecimal precio,
			String moneda,
			int vigenciaDias,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta) {

		return nuevo(TipoProducto.PACK, organizationId, consultorioId, ofertaId, nombre, descripcion,
				creditos, precio, moneda, vigenciaDias, vigenciaDesde, vigenciaHasta);
	}

	/**
	 * Alta de un abono (RF-M29-007, AKINE-08.08). Mismas columnas que el pack con otra lectura:
	 * {@code creditos} son las asistencias que permite <b>cada periodo</b> (CA-M29-007-06) y
	 * {@code vigenciaDias} la duracion maxima de un periodo. El periodo concreto se elige al vender.
	 */
	@SuppressWarnings("checkstyle:ParameterNumber")
	public static ProductoServicio abono(
			long organizationId,
			long consultorioId,
			long ofertaId,
			String nombre,
			String descripcion,
			int creditos,
			BigDecimal precio,
			String moneda,
			int vigenciaDias,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta) {

		return nuevo(TipoProducto.ABONO, organizationId, consultorioId, ofertaId, nombre, descripcion,
				creditos, precio, moneda, vigenciaDias, vigenciaDesde, vigenciaHasta);
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	private static ProductoServicio nuevo(
			TipoProducto tipo,
			long organizationId,
			long consultorioId,
			long ofertaId,
			String nombre,
			String descripcion,
			int creditos,
			BigDecimal precio,
			String moneda,
			int vigenciaDias,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta) {

		ProductoServicio p = new ProductoServicio();
		p.organizationId = organizationId;
		p.consultorioId = consultorioId;
		p.ofertaId = ofertaId;
		p.tipo = tipo;
		p.active = true;
		p.aplicar(nombre, descripcion, creditos, precio, moneda, vigenciaDias,
				exigir(vigenciaDesde, "El inicio de la ventana comercial es obligatorio"),
				vigenciaHasta);
		return p;
	}

	/**
	 * Edita el producto. {@code null} conserva el valor; {@code limpiarVigenciaHasta} deja la
	 * ventana sin fin. Los pases ya vendidos no se tocan: llevan su snapshot.
	 */
	@SuppressWarnings("checkstyle:ParameterNumber")
	public void editar(
			String nombre,
			String descripcion,
			Integer creditos,
			BigDecimal precio,
			String moneda,
			Integer vigenciaDias,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta,
			boolean limpiarVigenciaHasta) {

		if (!active) {
			throw new ProductoDadoDeBajaException(id == null ? 0 : id);
		}
		aplicar(
				nombre == null ? this.nombre : nombre,
				descripcion == null ? this.descripcion : descripcion,
				creditos == null ? this.creditos : creditos,
				precio == null ? this.precio : precio,
				moneda == null ? this.moneda : moneda,
				vigenciaDias == null ? this.vigenciaDias : vigenciaDias,
				vigenciaDesde == null ? this.vigenciaDesde : vigenciaDesde,
				limpiarVigenciaHasta ? null : vigenciaHasta == null ? this.vigenciaHasta : vigenciaHasta);
	}

	/**
	 * Baja logica con motivo obligatorio. <b>Idempotente</b>: dar de baja uno ya dado de baja
	 * devuelve {@code false} y no reescribe el instante ni el motivo originales.
	 */
	public boolean darDeBaja(String motivo, Instant ahora) {
		String limpio = motivo == null ? "" : motivo.strip();
		if (limpio.isEmpty()) {
			throw new IllegalArgumentException("El motivo de la baja es obligatorio");
		}
		if (limpio.length() > MOTIVO_MAX) {
			throw new IllegalArgumentException(
					"El motivo de la baja no puede superar los " + MOTIVO_MAX + " caracteres");
		}
		if (!active) {
			return false;
		}
		this.active = false;
		this.deletedAt = ahora;
		this.deactivationReason = limpio;
		return true;
	}

	/**
	 * Por que no se puede vender hoy, o vacio si se puede. Lo evalua la venta <b>dentro</b> de su
	 * transaccion.
	 */
	public Optional<String> impedimentoParaVenderEl(LocalDate fecha) {
		if (!active) {
			return Optional.of("esta dado de baja");
		}
		if (fecha.isBefore(vigenciaDesde)) {
			return Optional.of("todavia no esta a la venta (desde " + vigenciaDesde + ")");
		}
		if (vigenciaHasta != null && fecha.isAfter(vigenciaHasta)) {
			return Optional.of("ya no esta a la venta (hasta " + vigenciaHasta + ")");
		}
		return Optional.empty();
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	private void aplicar(
			String nombre,
			String descripcion,
			int creditos,
			BigDecimal precio,
			String moneda,
			int vigenciaDias,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta) {

		String nombreLimpio = nombre == null ? "" : nombre.strip();
		if (nombreLimpio.isEmpty() || nombreLimpio.length() > NOMBRE_MAX) {
			throw new IllegalArgumentException(
					"El nombre del producto es obligatorio y no puede superar " + NOMBRE_MAX
							+ " caracteres");
		}
		String descripcionLimpia = descripcion == null || descripcion.isBlank() ? null : descripcion.strip();
		if (descripcionLimpia != null && descripcionLimpia.length() > DESCRIPCION_MAX) {
			throw new IllegalArgumentException(
					"La descripcion no puede superar " + DESCRIPCION_MAX + " caracteres");
		}
		if (creditos <= 0) {
			// Cero creditos no es un pack chico: es un producto que no entrega nada.
			throw new IllegalArgumentException("La cantidad de creditos debe ser mayor a cero");
		}
		if (precio == null || precio.signum() <= 0) {
			// Un precio cero es una cortesia, y una cortesia no se modela como venta.
			throw new IllegalArgumentException("El precio debe ser mayor a cero");
		}
		if (precio.scale() > 2 || precio.precision() - precio.scale() > 10) {
			throw new IllegalArgumentException("El precio admite hasta 10 enteros y 2 decimales");
		}
		if (vigenciaDias <= 0) {
			throw new IllegalArgumentException("La vigencia en dias debe ser mayor a cero");
		}
		if (vigenciaHasta != null && vigenciaHasta.isBefore(vigenciaDesde)) {
			throw new IllegalArgumentException(
					"El fin de la ventana comercial no puede ser anterior a su inicio");
		}
		this.nombre = nombreLimpio;
		this.descripcion = descripcionLimpia;
		this.creditos = creditos;
		this.precio = precio;
		this.moneda = monedaIso(moneda);
		this.vigenciaDias = vigenciaDias;
		this.vigenciaDesde = vigenciaDesde;
		this.vigenciaHasta = vigenciaHasta;
	}

	private static String monedaIso(String moneda) {
		String codigo = moneda == null ? "" : moneda.strip().toUpperCase();
		try {
			return Currency.getInstance(codigo).getCurrencyCode();
		} catch (IllegalArgumentException noIso) {
			throw new IllegalArgumentException("La moneda debe ser un codigo ISO 4217: " + moneda);
		}
	}

	private static <T> T exigir(T valor, String mensaje) {
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

	public Long getConsultorioId() {
		return consultorioId;
	}

	public Long getOfertaId() {
		return ofertaId;
	}

	public String getNombre() {
		return nombre;
	}

	public String getDescripcion() {
		return descripcion;
	}

	public TipoProducto getTipo() {
		return tipo;
	}

	public int getCreditos() {
		return creditos;
	}

	public BigDecimal getPrecio() {
		return precio;
	}

	public String getMoneda() {
		return moneda;
	}

	public int getVigenciaDias() {
		return vigenciaDias;
	}

	public LocalDate getVigenciaDesde() {
		return vigenciaDesde;
	}

	public LocalDate getVigenciaHasta() {
		return vigenciaHasta;
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
