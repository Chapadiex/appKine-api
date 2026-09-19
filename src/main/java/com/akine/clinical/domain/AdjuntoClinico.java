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
 * Metadata de un documento CLINICO vinculado a una Historia Clinica (M09 / M25).
 *
 * <h2>El ancla es la Historia, y eso es todo el diseño</h2>
 *
 * <p>Colgar de {@code historiaClinicaId} y no de {@code personaId} no es una diferencia de
 * nombre: es lo que arrastra la politica de DP-03 detras del documento. Un estudio guardado en
 * {@code adjunto_administrativo} se autorizaria por <b>pertenencia al tenant</b> —que es como se
 * lee la ficha de una Persona— y por lo tanto sin justificacion declarada, sin relacion
 * asistencial y sin auditoria de la lectura. RN-M25-005 lo prohibe y {@code V40} ya lo habia
 * anticipado: "el dia que hagan falta categorias clinicas, la tabla es otra y el modulo tambien".
 *
 * <p>{@link #getEntradaClinicaId()} es <b>opcional</b>. El caso normal es "este informe motivo
 * esta evolucion"; el otro es un estudio que el paciente trae al mostrador y que no respalda
 * ninguna entrada. Exigir la entrada obligaria a inventar una evolucion vacia para poder
 * adjuntar, o sea a escribir informacion falsa por una restriccion de esquema.
 *
 * <h2>El binario no esta aca</h2>
 *
 * <p>Vive detras de {@code ContenidoClinicoStoragePort}, en una raiz de disco <b>separada</b> de
 * la de los adjuntos administrativos, direccionado por {@link #getStorageKey()}. La clave es
 * <b>opaca</b> —un UUID sin guiones generado por el servidor— y {@link #getNombreArchivo()}
 * <b>nunca</b> toca el disco. Sin cadena de origen externo en la ruta, el path traversal deja de
 * ser una clase de bug posible (RN-M25-002).
 *
 * <p>{@link #getContentType()} es el tipo <b>detectado por los bytes</b>, no el declarado por el
 * cliente. Ver {@link TipoDeContenidoClinico}.
 *
 * <h2>La baja es logica y no borra el binario</h2>
 *
 * <p>RF-M25-004 y regla maestra 10. El archivo se queda en disco a proposito (challenge seccion
 * 5): un job de limpieza necesita una politica de retencion que nadie escribio, y borrar aca
 * haria irreversible una baja por error sobre un estudio clinico. Un adjunto dado de baja sale
 * del listado vigente y <b>se sigue descargando</b>, que es lo que distingue "no lo muestres" de
 * "no existio".
 */
@Entity
@Table(name = "adjunto_clinico")
public class AdjuntoClinico extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "historia_clinica_id", nullable = false, updatable = false)
	private Long historiaClinicaId;

	/**
	 * Entrada que el documento respalda, o {@code null}.
	 *
	 * <p>{@code updatable = false}: el vinculo se declara al subir. Reapuntar un estudio a otra
	 * evolucion cambiaria el significado de un hecho ya registrado, y la forma correcta de
	 * corregirlo es dar de baja y volver a subir — que deja las dos decisiones consultables.
	 *
	 * <p>Que la entrada pertenezca a la MISMA historia no lo puede exigir la base: un CHECK no
	 * consulta otra tabla. Lo hace cumplir {@code AdjuntoClinicoService} antes de insertar.
	 */
	@Column(name = "entrada_clinica_id", updatable = false)
	private Long entradaClinicaId;

	/** Sede desde la que se cargo. Dato del HECHO, no de propiedad: la HC es de la organizacion. */
	@Column(name = "consultorio_id", updatable = false)
	private Long consultorioId;

	@Enumerated(EnumType.STRING)
	@Column(name = "categoria", nullable = false, length = 32)
	private CategoriaAdjuntoClinico categoria;

	/**
	 * El tope real del titulo, y el mismo que {@code V46} le puso a la columna.
	 *
	 * <p>La regla vive aca y no solo en el DTO de reclasificacion porque la subida entra por
	 * <b>multipart</b>: ahi el titulo es un {@code @RequestParam} suelto, sin
	 * {@code @Valid} que lo mire. Validarlo en el constructor lo cubre por los dos
	 * caminos, y lo cubre <b>antes</b> de que la subida escriba el binario: un rechazo mas tarde
	 * dejaria el archivo en disco sin fila que lo referencie.
	 */
	public static final int TITULO_MAXIMO = 160;

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
	private EstadoAdjuntoClinico estado = EstadoAdjuntoClinico.DISPONIBLE;

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

	protected AdjuntoClinico() {
		// Requerido por JPA.
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	public AdjuntoClinico(
			Long organizationId,
			Long historiaClinicaId,
			Long entradaClinicaId,
			Long consultorioId,
			CategoriaAdjuntoClinico categoria,
			String titulo,
			String nombreArchivo,
			String contentType,
			long tamanoBytes,
			String checksumSha256,
			String storageKey,
			Long subidoPor,
			Instant subidoEn) {

		this.organizationId =
				exigirNoNulo(organizationId, "El adjunto clinico pertenece siempre a una organizacion");
		this.historiaClinicaId =
				exigirNoNulo(historiaClinicaId, "El adjunto clinico cuelga siempre de una historia");
		this.entradaClinicaId = entradaClinicaId;
		this.consultorioId = consultorioId;
		this.categoria = exigirNoNulo(categoria, "El adjunto clinico se clasifica siempre");
		this.titulo = tituloAceptable(titulo);
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
		this.estado = EstadoAdjuntoClinico.DISPONIBLE;
		this.active = true;
	}

	/**
	 * Reclasifica el documento (RF-M25-003).
	 *
	 * <p>Lo unico editable es COMO esta descripto: categoria y titulo. El contenido, su nombre,
	 * su tipo, su checksum y la entrada que respalda son inmutables —{@code updatable = false} en
	 * las cinco columnas—: cambiar el archivo de una fila reescribiria un hecho clinico, y la
	 * forma correcta de reemplazar un documento es dar de baja el viejo y subir el nuevo, que
	 * deja las dos versiones consultables.
	 */
	public void reclasificar(CategoriaAdjuntoClinico categoria, String titulo) {
		if (categoria != null) {
			this.categoria = categoria;
		}
		if (titulo != null) {
			this.titulo = tituloAceptable(titulo);
		}
	}

	/** El almacenamiento ya no tiene el contenido. La metadata se conserva. */
	public void marcarNoDisponible() {
		this.estado = EstadoAdjuntoClinico.NO_DISPONIBLE;
	}

	/**
	 * Baja logica con motivo obligatorio. No toca el binario.
	 *
	 * <p>El motivo no es una formalidad: un estudio que desaparece de una historia clinica sin
	 * explicacion es exactamente lo que RN-M09-004 y ADR-0011 quieren impedir.
	 */
	public void deactivate(Instant occurredAt, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"La baja de un adjunto clinico exige un motivo declarado: sin el, la "
							+ "auditoria no responde por que seis meses despues");
		}
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason.strip();
	}

	public boolean isVigente() {
		return active && deletedAt == null;
	}

	public boolean isDescargable() {
		return estado == EstadoAdjuntoClinico.DISPONIBLE;
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

	/** El titulo saneado, o {@code null}, rechazando lo que no entraria en la columna. */
	private static String tituloAceptable(String valor) {
		String limpio = vacioEsNulo(valor);
		if (limpio != null && limpio.length() > TITULO_MAXIMO) {
			throw new IllegalArgumentException(
					"El titulo del adjunto no puede superar los " + TITULO_MAXIMO + " caracteres");
		}
		return limpio;
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

	public Long getEntradaClinicaId() {
		return entradaClinicaId;
	}

	public Long getConsultorioId() {
		return consultorioId;
	}

	public CategoriaAdjuntoClinico getCategoria() {
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

	public EstadoAdjuntoClinico getEstado() {
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
