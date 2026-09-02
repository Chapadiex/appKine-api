package com.akine.person.domain;

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
 * Metadata de un documento ADMINISTRATIVO vinculado a una Persona (M07/M25).
 *
 * <p><b>El binario no esta aca.</b> Vive detras de {@code AdjuntoStoragePort}, direccionado por
 * {@link #getStorageKey()}. Los motivos estan en la cabecera de {@code V40}; el que importa para
 * leer esta clase es que {@code storageKey} es <b>opaca</b> —un UUID sin guiones generado por el
 * servidor— y que {@link #getNombreArchivo()} <b>nunca</b> toca el disco. Sin cadena de origen
 * externo en la ruta, el path traversal deja de ser una clase de bug posible.
 *
 * <p>{@link #getContentType()} es el tipo <b>detectado por los bytes</b>, no el declarado por el
 * cliente. Ver {@link TipoDeArchivo}.
 *
 * <p>La baja es LOGICA (RF-M25-004, RN-M07-004) y no borra el binario: una baja es "esto ya no
 * corresponde", no "esto nunca existio", y el caso borde "descarga tras baja" de la etapa se
 * resuelve dejando que el historico siga resolviendo.
 */
@Entity
@Table(name = "adjunto_administrativo")
public class AdjuntoAdministrativo extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "persona_id", nullable = false, updatable = false)
	private Long personaId;

	@Column(name = "consultorio_id", updatable = false)
	private Long consultorioId;

	@Enumerated(EnumType.STRING)
	@Column(name = "categoria", nullable = false, length = 32)
	private CategoriaAdjunto categoria;

	@Column(name = "titulo", length = 160)
	private String titulo;

	@Column(name = "nombre_archivo", nullable = false, length = 255, updatable = false)
	private String nombreArchivo;

	@Column(name = "content_type", nullable = false, length = 120, updatable = false)
	private String contentType;

	@Column(name = "tamano_bytes", nullable = false, updatable = false)
	private long tamanoBytes;

	@Column(name = "checksum_sha256", nullable = false, length = 64, updatable = false)
	private String checksumSha256;

	@Column(name = "storage_key", nullable = false, length = 64, updatable = false)
	private String storageKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 24)
	private EstadoAdjunto estado = EstadoAdjunto.DISPONIBLE;

	@Column(name = "subido_por", nullable = false, updatable = false)
	private Long subidoPor;

	@Column(name = "subido_en", nullable = false, updatable = false)
	private Instant subidoEn;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deactivation_reason", length = 280)
	private String deactivationReason;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected AdjuntoAdministrativo() {
		// JPA.
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	public AdjuntoAdministrativo(
			Long organizationId,
			Long personaId,
			Long consultorioId,
			CategoriaAdjunto categoria,
			String titulo,
			String nombreArchivo,
			String contentType,
			long tamanoBytes,
			String checksumSha256,
			String storageKey,
			Long subidoPor,
			Instant subidoEn) {

		this.organizationId =
				exigirNoNulo(organizationId, "El adjunto pertenece siempre a una organizacion");
		this.personaId = exigirNoNulo(personaId, "El adjunto se vincula siempre a una persona");
		this.consultorioId = consultorioId;
		this.categoria = exigirNoNulo(categoria, "El adjunto se clasifica siempre");
		this.titulo = vacioEsNulo(titulo);
		this.nombreArchivo = exigirTexto(nombreArchivo, "El nombre del archivo es obligatorio");
		this.contentType = exigirTexto(contentType, "El tipo de contenido es obligatorio");
		if (tamanoBytes <= 0) {
			throw new IllegalArgumentException("Un adjunto vacio no es un adjunto");
		}
		this.tamanoBytes = tamanoBytes;
		this.checksumSha256 = exigirTexto(checksumSha256, "El checksum es obligatorio");
		this.storageKey = exigirTexto(storageKey, "La clave de almacenamiento es obligatoria");
		this.subidoPor = exigirNoNulo(subidoPor, "La carga registra siempre a su actor");
		this.subidoEn = exigirNoNulo(subidoEn, "La carga registra siempre su instante");
		this.estado = EstadoAdjunto.DISPONIBLE;
		this.active = true;
	}

	/**
	 * Reclasifica el documento (RF-M25-003).
	 *
	 * <p>Lo unico que se puede editar de un adjunto es COMO esta descripto. El contenido, su
	 * nombre, su tipo y su checksum son inmutables —{@code updatable = false} en las cuatro
	 * columnas—: cambiar el archivo de una fila reescribiria un hecho, y la forma correcta de
	 * reemplazar un documento es dar de baja el viejo y subir el nuevo.
	 */
	public void reclasificar(CategoriaAdjunto categoria, String titulo) {
		if (categoria != null) {
			this.categoria = categoria;
		}
		if (titulo != null) {
			this.titulo = vacioEsNulo(titulo);
		}
	}

	/** El almacenamiento ya no tiene el contenido. La metadata se conserva. */
	public void marcarNoDisponible() {
		this.estado = EstadoAdjunto.NO_DISPONIBLE;
	}

	public void deactivate(Instant occurredAt, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"La baja de un adjunto exige un motivo declarado: sin el, la auditoria no "
							+ "responde por que seis meses despues");
		}
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason.strip();
	}

	public boolean isVigente() {
		return active && deletedAt == null;
	}

	public boolean isDescargable() {
		return estado == EstadoAdjunto.DISPONIBLE;
	}

	private static <T> T exigirNoNulo(T valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
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

	public Long getPersonaId() {
		return personaId;
	}

	public Long getConsultorioId() {
		return consultorioId;
	}

	public CategoriaAdjunto getCategoria() {
		return categoria;
	}

	public String getTitulo() {
		return titulo;
	}

	public String getNombreArchivo() {
		return nombreArchivo;
	}

	public String getContentType() {
		return contentType;
	}

	public long getTamanoBytes() {
		return tamanoBytes;
	}

	public String getChecksumSha256() {
		return checksumSha256;
	}

	/** <b>No sale por la API.</b> RN-M25-002: es la ruta interna que no se expone. */
	public String getStorageKey() {
		return storageKey;
	}

	public EstadoAdjunto getEstado() {
		return estado;
	}

	public Long getSubidoPor() {
		return subidoPor;
	}

	public Instant getSubidoEn() {
		return subidoEn;
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
