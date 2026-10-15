package com.akine.activity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Lo que una persona compro (RF-M29-002, RN-M29-002): creditos con vigencia, sobre la oferta del
 * producto.
 *
 * <h2>El saldo solo se mueve por UPDATE condicional</h2>
 *
 * <p>{@code creditos_disponibles} y {@code estado} se mapean {@code updatable = false}: los escribe
 * el INSERT de la venta y despues <b>solo</b> los mueven los UPDATE nativos del repositorio
 * ({@code consumirUno}, {@code devolverUno}), que suben {@code version}. Si JPA pudiera
 * escribirlos, cualquier {@code save()} los pisaria con un valor leido antes y el saldo volveria
 * atras sin que nada falle (la leccion de {@code cupo_ocupado} en 08.02 y de
 * {@code cantidad_consumida} en 04.05). {@code @DynamicUpdate} es el cinturon: aunque alguien
 * agregara una edicion, el flush no tocaria columnas que no cambio.
 *
 * <h2>El snapshot se congela</h2>
 *
 * <p>Nombre, precio, moneda, creditos y oferta se copian del producto al vender. Editar el producto
 * despues no cambia lo que se compro.
 *
 * <h2>Sin baja logica</h2>
 *
 * <p>Un pase no se da de baja: se anula (estado con motivo, 08.07). Ver V64.
 */
@Entity
@Table(name = "pase_servicio")
@DynamicUpdate
public class PaseServicio {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "persona_id", nullable = false, updatable = false)
	private Long personaId;

	@Column(name = "producto_id", nullable = false, updatable = false)
	private Long productoId;

	@Column(name = "snapshot_nombre", nullable = false, length = 160, updatable = false)
	private String snapshotNombre;

	@Column(name = "snapshot_precio", nullable = false, precision = 12, scale = 2, updatable = false)
	private BigDecimal snapshotPrecio;

	@Column(name = "snapshot_moneda", nullable = false, length = 3, updatable = false)
	private String snapshotMoneda;

	@Column(name = "snapshot_creditos", nullable = false, updatable = false)
	private int snapshotCreditos;

	@Column(name = "snapshot_oferta_id", nullable = false, updatable = false)
	private Long snapshotOfertaId;

	@Column(name = "creditos_iniciales", nullable = false, updatable = false)
	private int creditosIniciales;

	@Column(name = "creditos_disponibles", nullable = false, updatable = false)
	private int creditosDisponibles;

	@Column(name = "vigencia_desde", nullable = false, updatable = false)
	private LocalDate vigenciaDesde;

	@Column(name = "vigencia_hasta", nullable = false, updatable = false)
	private LocalDate vigenciaHasta;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 16, updatable = false)
	private EstadoPase estado;

	@Column(name = "idempotency_key", length = 80, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_hash", length = 64, updatable = false)
	private String requestHash;

	@Column(name = "vendido_por_cuenta_id", nullable = false, updatable = false)
	private Long vendidoPorCuentaId;

	@Column(name = "vendido_en", nullable = false, updatable = false)
	private Instant vendidoEn;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected PaseServicio() {
		// Requerido por JPA.
	}

	/**
	 * La venta: copia el snapshot comercial del producto y fija la vigencia.
	 *
	 * <p><b>La vigencia es de {@code vigencia_dias} dias corridos contando el de la compra</b>:
	 * un pack de 30 dias comprado el 1 vale hasta el 30 inclusive. El producto debe haber pasado ya
	 * {@link ProductoServicio#impedimentoParaVenderEl}: esta fabrica no revalida la ventana.
	 *
	 * @param hoy la fecha de la venta en la zona de la sede
	 */
	public static PaseServicio vender(
			ProductoServicio producto,
			long personaId,
			LocalDate hoy,
			String idempotencyKey,
			String requestHash,
			long vendidoPorCuentaId,
			Instant ahora) {

		if ((idempotencyKey == null) != (requestHash == null)) {
			// ck_pase_idempotencia_completa lo impide igual; aca se dice cual es el problema.
			throw new IllegalArgumentException("La clave de idempotencia viaja con su huella");
		}
		PaseServicio p = new PaseServicio();
		p.organizationId = producto.getOrganizationId();
		p.consultorioId = producto.getConsultorioId();
		p.personaId = personaId;
		p.productoId = producto.getId();
		p.snapshotNombre = producto.getNombre();
		p.snapshotPrecio = producto.getPrecio();
		p.snapshotMoneda = producto.getMoneda();
		p.snapshotCreditos = producto.getCreditos();
		p.snapshotOfertaId = producto.getOfertaId();
		p.creditosIniciales = producto.getCreditos();
		p.creditosDisponibles = producto.getCreditos();
		p.vigenciaDesde = hoy;
		p.vigenciaHasta = hoy.plusDays(producto.getVigenciaDias() - 1L);
		p.estado = EstadoPase.ACTIVO;
		p.idempotencyKey = idempotencyKey;
		p.requestHash = requestHash;
		p.vendidoPorCuentaId = vendidoPorCuentaId;
		p.vendidoEn = ahora;
		return p;
	}

	/** {@code true} si el dia esta dentro de la vigencia, ambos extremos inclusive. */
	public boolean vigenteEl(LocalDate fecha) {
		return !fecha.isBefore(vigenciaDesde) && !fecha.isAfter(vigenciaHasta);
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

	public Long getPersonaId() {
		return personaId;
	}

	public Long getProductoId() {
		return productoId;
	}

	public String getSnapshotNombre() {
		return snapshotNombre;
	}

	public BigDecimal getSnapshotPrecio() {
		return snapshotPrecio;
	}

	public String getSnapshotMoneda() {
		return snapshotMoneda;
	}

	public int getSnapshotCreditos() {
		return snapshotCreditos;
	}

	public Long getSnapshotOfertaId() {
		return snapshotOfertaId;
	}

	public int getCreditosIniciales() {
		return creditosIniciales;
	}

	public int getCreditosDisponibles() {
		return creditosDisponibles;
	}

	public LocalDate getVigenciaDesde() {
		return vigenciaDesde;
	}

	public LocalDate getVigenciaHasta() {
		return vigenciaHasta;
	}

	public EstadoPase getEstado() {
		return estado;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getRequestHash() {
		return requestHash;
	}

	public Long getVendidoPorCuentaId() {
		return vendidoPorCuentaId;
	}

	public Instant getVendidoEn() {
		return vendidoEn;
	}

	public long getVersion() {
		return version;
	}
}
