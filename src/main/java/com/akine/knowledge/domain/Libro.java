package com.akine.knowledge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;

/**
 * Un PDF de la biblioteca de una organizacion.
 *
 * <p>El estado cambia solo por los metodos de esta clase. {@code hash_activo} es una columna
 * generada por la base: no se mapea.
 *
 * <p>{@code @DynamicUpdate}: el worker mueve {@code progreso} con un UPDATE dirigido que no
 * avanza la version (para no dar 409 a una edicion del admin mientras se procesa). Sin esto, el
 * flush de una edicion reescribiria {@code progreso}, {@code estado} y el resto con lo leido.
 */
@Entity
@DynamicUpdate
@Table(name = "libro")
public class Libro {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "titulo", nullable = false, length = 300)
	private String titulo;

	@Column(name = "autores", length = 500)
	private String autores;

	@Column(name = "edicion", length = 100)
	private String edicion;

	@Column(name = "area", length = 100)
	private String area;

	@Column(name = "storage_key", nullable = false, length = 64)
	private String storageKey;

	@Column(name = "nombre_archivo", nullable = false, length = 255)
	private String nombreArchivo;

	@Column(name = "content_type", nullable = false, length = 120)
	private String contentType;

	@Column(name = "tamano_bytes", nullable = false)
	private long tamanoBytes;

	@Column(name = "hash_sha256", nullable = false, length = 64, updatable = false)
	private String hashSha256;

	@Column(name = "paginas")
	private Integer paginas;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 20)
	private EstadoLibro estado = EstadoLibro.PENDIENTE;

	@Column(name = "progreso", nullable = false)
	private int progreso;

	@Column(name = "motivo_error", length = 500)
	private String motivoError;

	@Column(name = "reemplaza_libro_id")
	private Long reemplazaLibroId;

	@Column(name = "activo", nullable = false)
	private boolean activo = true;

	@Column(name = "procesando_desde")
	private Instant procesandoDesde;

	@Column(name = "declaracion_derechos_en", nullable = false, updatable = false)
	private Instant declaracionDerechosEn;

	@Column(name = "declaracion_derechos_por_cuenta_id", nullable = false, updatable = false)
	private Long declaracionDerechosPorCuentaId;

	@Column(name = "creado_por_cuenta_id", nullable = false, updatable = false)
	private Long creadoPorCuentaId;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Libro() {
	}

	/** Libro nuevo, PENDIENTE y activo. */
	public Libro(long organizationId, String titulo, String autores, String edicion, String area,
			String storageKey, String nombreArchivo, String contentType, long tamanoBytes,
			String hashSha256, Long reemplazaLibroId, Instant declaracionDerechosEn,
			long cuentaId) {
		this.organizationId = organizationId;
		this.titulo = titulo;
		this.autores = autores;
		this.edicion = edicion;
		this.area = area;
		this.storageKey = storageKey;
		this.nombreArchivo = nombreArchivo;
		this.contentType = contentType;
		this.tamanoBytes = tamanoBytes;
		this.hashSha256 = hashSha256;
		this.reemplazaLibroId = reemplazaLibroId;
		this.declaracionDerechosEn = declaracionDerechosEn;
		this.declaracionDerechosPorCuentaId = cuentaId;
		this.creadoPorCuentaId = cuentaId;
	}

	@PrePersist
	void alInsertar() {
		Instant ahora = Instant.now();
		if (createdAt == null) {
			createdAt = ahora;
		}
		updatedAt = ahora;
	}

	@PreUpdate
	void alActualizar() {
		updatedAt = Instant.now();
	}

	/** El estado que ve el usuario: INACTIVO tiene prioridad sobre el procesamiento. */
	public EstadoVisibleLibro estadoVisible() {
		if (!activo) {
			return EstadoVisibleLibro.INACTIVO;
		}
		return EstadoVisibleLibro.valueOf(estado.name());
	}

	public void marcarProcesando(Instant ahora) {
		this.estado = EstadoLibro.PROCESANDO;
		this.procesandoDesde = ahora;
		this.progreso = 0;
		this.motivoError = null;
	}

	public void actualizarProgreso(int porcentaje) {
		this.progreso = Math.max(0, Math.min(100, porcentaje));
	}

	public void marcarListo(int paginas) {
		this.estado = EstadoLibro.LISTO;
		this.paginas = paginas;
		this.progreso = 100;
		this.procesandoDesde = null;
		this.motivoError = null;
	}

	public void marcarError(String motivo) {
		this.estado = EstadoLibro.ERROR;
		this.procesandoDesde = null;
		this.motivoError = motivo != null && motivo.length() > 500 ? motivo.substring(0, 500) : motivo;
	}

	/** Vuelve a la cola, por ejemplo en un reintento manual. */
	public void reencolar() {
		this.estado = EstadoLibro.PENDIENTE;
		this.procesandoDesde = null;
		this.progreso = 0;
		this.motivoError = null;
	}

	public void desactivar() {
		this.activo = false;
	}

	public void activar() {
		this.activo = true;
	}

	/** Corta el vinculo con el libro reemplazado (el reemplazo ya quedo LISTO). */
	public void soltarReemplazo() {
		this.reemplazaLibroId = null;
	}

	public void editarMetadatos(String titulo, String autores, String edicion, String area) {
		this.titulo = titulo;
		this.autores = autores;
		this.edicion = edicion;
		this.area = area;
	}

	public Long getId() { return id; }
	public Long getOrganizationId() { return organizationId; }
	public String getTitulo() { return titulo; }
	public String getAutores() { return autores; }
	public String getEdicion() { return edicion; }
	public String getArea() { return area; }
	public String getStorageKey() { return storageKey; }
	public String getNombreArchivo() { return nombreArchivo; }
	public String getContentType() { return contentType; }
	public long getTamanoBytes() { return tamanoBytes; }
	public String getHashSha256() { return hashSha256; }
	public Integer getPaginas() { return paginas; }
	public EstadoLibro getEstado() { return estado; }
	public int getProgreso() { return progreso; }
	public String getMotivoError() { return motivoError; }
	public Long getReemplazaLibroId() { return reemplazaLibroId; }
	public boolean isActivo() { return activo; }
	public Instant getProcesandoDesde() { return procesandoDesde; }
	public Instant getDeclaracionDerechosEn() { return declaracionDerechosEn; }
	public Long getDeclaracionDerechosPorCuentaId() { return declaracionDerechosPorCuentaId; }
	public Long getCreadoPorCuentaId() { return creadoPorCuentaId; }
	public long getVersion() { return version; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
}
