package com.akine.contracting.domain;

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
 * Obra social, prepaga u otro pagador externo con el que trabaja una organizacion (M15).
 *
 * <h2>Es de la organizacion, no de la sede y no global</h2>
 *
 * <p>{@code organization_id} es {@code NOT NULL}: la obra social con la que trabaja un centro,
 * con su codigo interno y su contacto administrativo, es dato de ese centro. Dos organizaciones
 * pueden tener "OSDE" con datos distintos y ninguna ve la del otro. <b>No existe el financiador
 * global de plataforma en 03.03</b>, y la consecuencia de esa decision —la celda "Catalogo
 * global" de la matriz de permisos queda sin cumplirse— esta declarada en la cabecera de V41 y
 * en {@link PermissionCodes}.
 *
 * <h2>Que hace estable a una referencia historica</h2>
 *
 * <p>La garantia que 03.04 y 03.05 heredan —"una cobertura firmada ayer no cambia porque hoy
 * alguien edito el financiador"— tiene dos mitades y esta clase aporta la primera:
 *
 * <ol>
 *   <li><b>{@link #codigo} es inmutable</b> ({@code updatable = false}). Renombrar es cambiar
 *       {@link #nombre}; el codigo es lo que las referencias guardan, y mutarlo haria que una
 *       referencia vieja pase a significar otra cosa sin que nadie lo haya pedido. Mismo
 *       criterio, y mismo motivo, que {@code Servicio.codigo} en M27.</li>
 *   <li><b>No hay borrado fisico</b> (RN-M15-003): la baja deja {@link #active} en
 *       {@code false} y la fila sigue resolviendo con su nombre y su codigo.</li>
 * </ol>
 *
 * <p>La segunda mitad <b>no vive aca y no puede vivir aca</b>: es que el consumidor COPIE la
 * referencia a sus propias columnas en vez de leer esta fila cada vez que muestra una cobertura.
 * Eso lo entrega {@code contracting.spi.ReferenciaDeCobertura}, que es un record de copia y no un
 * puntero, exactamente como {@code obligacion} congela el precio de la oferta en AKINE-07.01.
 *
 * <h2>Ciclo de vida: baja logica con motivo, sin cascada</h2>
 *
 * <p>Dar de baja un financiador <b>no da de baja sus planes</b> ni toca ninguna cobertura ya
 * firmada. Lo unico que se impide es crear planes NUEVOS bajo el, y que sus planes se ofrezcan
 * para selecciones nuevas — y esa segunda parte la decide quien lee, combinando el estado del
 * plan con el de su financiador. La garantia es estructural: esta clase no tiene ninguna
 * relacion JPA hacia sus planes, asi que no hay por donde cascadear aunque alguien quisiera.
 */
@Entity
@Table(name = "financiador")
public class Financiador extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	/**
	 * Clave estable dentro de la organizacion. {@code updatable = false} a proposito: ver la
	 * seccion de la clase sobre referencias historicas.
	 */
	@Column(name = "codigo", nullable = false, updatable = false, length = 64)
	private String codigo;

	@Column(name = "nombre", nullable = false, length = 160)
	private String nombre;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", nullable = false, length = 24)
	private TipoFinanciador tipo;

	/** Identidad fiscal ya normalizada a 11 digitos. {@code null} es un estado real. */
	@Column(name = "cuit", length = 13)
	private String cuit;

	@Column(name = "email_contacto", length = 254)
	private String emailContacto;

	@Column(name = "telefono_contacto", length = 40)
	private String telefonoContacto;

	@Column(name = "observaciones", length = 500)
	private String observaciones;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deactivation_reason", length = 280)
	private String deactivationReason;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Financiador() {
		// Requerido por JPA.
	}

	public Financiador(
			Long organizationId,
			String codigo,
			String nombre,
			TipoFinanciador tipo,
			String cuit,
			String emailContacto,
			String telefonoContacto,
			String observaciones) {

		this.organizationId =
				exigirNoNulo(organizationId, "El financiador pertenece siempre a una organizacion");
		this.codigo = exigirTexto(codigo, "El codigo del financiador es obligatorio");
		this.nombre = exigirTexto(nombre, "El nombre del financiador es obligatorio");
		this.tipo = exigirNoNulo(tipo, "El tipo de financiador es obligatorio");
		this.cuit = normalizarCuit(cuit);
		this.emailContacto = vacioEsNulo(emailContacto);
		this.telefonoContacto = vacioEsNulo(telefonoContacto);
		this.observaciones = vacioEsNulo(observaciones);
		this.active = true;
	}

	/**
	 * Normaliza un CUIT a 11 digitos, o {@code null}.
	 *
	 * <p>Se guarda sin guiones para que {@code uk_financiador_cuit_vigente} sirva de algo:
	 * {@code 30-71234567-8} y {@code 30712345678} son el mismo contribuyente y sin normalizar el
	 * unique los tomaria por dos. Es la misma leccion que el padron aprendio con el documento en
	 * 03.01, donde {@code 27888999} choco con una ficha guardada como {@code 27.888.999}.
	 *
	 * <p>Un valor que no queda en 11 digitos <b>se rechaza</b> en vez de guardarse tal cual: el
	 * CHECK {@code ck_financiador_cuit_normalizado} lo rechazaria igual, y hacerlo aca produce un
	 * 400 que nombra el campo en vez de un 500 al cerrar la transaccion.
	 */
	public static String normalizarCuit(String valor) {
		if (valor == null || valor.isBlank()) {
			return null;
		}
		String soloDigitos = valor.replaceAll("[^0-9]", "");
		if (soloDigitos.length() != 11) {
			throw new IllegalArgumentException(
					"El CUIT tiene que tener 11 digitos: se recibieron " + soloDigitos.length());
		}
		return soloDigitos;
	}

	/**
	 * Edicion parcial: cada valor {@code null} deja el campo como estaba.
	 *
	 * <p>Semantica de PATCH y no de PUT, misma convencion que {@code Servicio}, {@code Espacio} y
	 * {@code Persona}. El {@link #codigo} no esta y no es un olvido: es {@code updatable = false}
	 * y ni siquiera viaja en el comando.
	 *
	 * <p>Para borrar un campo opcional se manda cadena vacia, que {@link #vacioEsNulo} traduce a
	 * {@code null}. Es la unica forma de distinguir "no lo toques" de "borralo" en un PATCH.
	 */
	public void updateDatos(
			String nombre,
			TipoFinanciador tipo,
			String cuit,
			String emailContacto,
			String telefonoContacto,
			String observaciones) {

		if (nombre != null) {
			this.nombre = exigirTexto(nombre, "El nombre del financiador es obligatorio");
		}
		if (tipo != null) {
			this.tipo = tipo;
		}
		if (cuit != null) {
			this.cuit = normalizarCuit(cuit);
		}
		if (emailContacto != null) {
			this.emailContacto = vacioEsNulo(emailContacto);
		}
		if (telefonoContacto != null) {
			this.telefonoContacto = vacioEsNulo(telefonoContacto);
		}
		if (observaciones != null) {
			this.observaciones = vacioEsNulo(observaciones);
		}
	}

	/**
	 * Baja logica con motivo declarado (RF-M15-003).
	 *
	 * <p>No borra nada y no cascadea: los planes de este financiador conservan sus filas y las
	 * coberturas y convenios ya firmados siguen resolviendo (RN-M15-003). Lo que se impide es
	 * crear planes nuevos bajo el y que sus planes se ofrezcan para selecciones nuevas.
	 */
	public void deactivate(Instant occurredAt, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"La baja de un financiador exige un motivo declarado: sin el, la auditoria no "
							+ "responde por que seis meses despues");
		}
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason.strip();
	}

	/** {@code true} cuando admite planes nuevos y selecciones nuevas. */
	public boolean isOperable() {
		return active && deletedAt == null;
	}

	private static String exigirTexto(String valor, String mensaje) {
		if (valor == null || valor.isBlank()) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor.strip();
	}

	private static <T> T exigirNoNulo(T valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
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

	public String getNombre() {
		return nombre;
	}

	public TipoFinanciador getTipo() {
		return tipo;
	}

	public String getCuit() {
		return cuit;
	}

	public String getEmailContacto() {
		return emailContacto;
	}

	public String getTelefonoContacto() {
		return telefonoContacto;
	}

	public String getObservaciones() {
		return observaciones;
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
