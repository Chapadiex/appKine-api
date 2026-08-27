package com.akine.offering.domain;

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
 * Concepto del catalogo GLOBAL de la plataforma: QUE existe (M27/M06, regla maestra 14).
 *
 * <h2>Por que es global y no tiene {@code organizationId}</h2>
 *
 * <p>RN-M27-001 lo dice sin dejar lugar a dudas: "un Servicio es catalogo global/conceptual". No
 * hay dos poblaciones que discriminar como en {@code especialidad} o {@code practica}
 * (ADR-0021): con una sola poblacion, un centinela {@code owner_key} no distinguiria nada. La
 * excepcion a ADR-0004 esta declarada en ADR-0023, que consolida las cuatro anteriores.
 * <b>COMO</b> un centro concreto presta este concepto no vive aca: vive en
 * {@link OfertaServicioConsultorio}. Meter configuracion de sede en esta clase seria duplicar esa
 * capacidad en dos lugares.
 *
 * <h2>La regla que esta clase existe para proteger: los {@code *Default} son PROPUESTA, no regla</h2>
 *
 * <p>{@link #modalidadDefault}, {@link #requiereCasoClinicoDefault} y
 * {@link #generaRegistroClinicoDefault} son un valor sugerido para cuando alguien crea una
 * {@link OfertaServicioConsultorio} sobre este servicio, y <b>nunca son la fuente de verdad de
 * una oferta ya creada</b>. Dos citas textuales lo exigen:
 *
 * <ul>
 *   <li>RF-M06-006: "los defaults no reemplazan la configuracion concreta de cada Oferta".
 *   <li>RN-M06-005: la naturaleza "sirve para clasificacion y no debe imponer por si sola
 *       comportamiento clinico".
 * </ul>
 *
 * <p>Por eso esta clase <b>no tiene ninguna referencia a {@link OfertaServicioConsultorio}</b> ni
 * la Oferta tiene un {@code @ManyToOne} de solo-lectura hacia estos campos: la unica forma de que
 * cambiar un default aca "se sienta" en una oferta existente seria que la oferta leyera este
 * objeto en tiempo de ejecucion, y eso es exactamente lo que las dos citas prohiben. La copia
 * ocurre una sola vez, en el momento de crear la oferta (tarea de aplicacion, fuera de este
 * modulo de dominio), y desde ahi las dos historias son independientes. Los tests
 * {@code una_oferta_no_hereda_los_defaults_del_servicio_al_editarse} y
 * {@code cambiar_un_default_del_servicio_no_toca_las_ofertas_existentes} de {@code OfertaTest}
 * hacen ejecutable exactamente esto — son CA-M03-007-06 y CA-M06-006-06.
 *
 * <h2>Regla maestra 15: ninguna decision depende del nombre</h2>
 *
 * <p>{@link #codigo} y {@link #nombre} son texto libre para mostrar y para buscar, nunca para
 * decidir. RN-M06-006 lo dice con nombres propios: "no deben existir condicionales funcionales
 * por nombres como Pilates, RPG, Yoga u Osteopatia". <b>No debe agregarse jamas, en este modulo
 * ni en ningun consumidor futuro, un {@code if} que compare {@link #codigo} o {@link #nombre}
 * contra un valor literal para decidir comportamiento.</b> Lo que decide comportamiento son los
 * campos tipados: {@link Naturaleza} para clasificar (y ni siquiera esta, ver su propio javadoc),
 * y los {@code requiere_*}/{@code modalidad} de la Oferta para todo lo demas. Si un dia hace
 * falta una regla "para servicios de tipo X", X tiene que ser una columna nueva y tipada, nunca
 * una comparacion de {@link #codigo} o {@link #nombre}.
 *
 * <h2>Ciclo de vida: baja logica con motivo, sin cascada</h2>
 *
 * <p>RF-M27-002 prohibe el borrado fisico. La baja deja {@link #active} en {@code false} y no
 * afecta a ninguna {@link OfertaServicioConsultorio} vigente que ya referencie este servicio
 * (RN-M03-006: no afectar historicos) — lo unico que impide es que se creen ofertas NUEVAS sobre
 * un servicio inactivo, y esa comprobacion no es un CHECK de la base (la FK
 * {@code fk_oferta_servicio} es RESTRICT sin mas) sino una regla de aplicacion, ver
 * {@code ServicioInactivoException}.
 */
@Entity
@Table(name = "servicio")
public class Servicio extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * Codigo estable del servicio en el catalogo global. {@code updatable = false}: renombrar es
	 * cambiar {@link #nombre}, no el codigo. Un codigo mutable haria que una referencia vieja a
	 * "KINE-DEPORTIVA" pase a significar otra cosa sin que nadie lo haya pedido explicitamente.
	 */
	@Column(name = "codigo", nullable = false, updatable = false, length = 64)
	private String codigo;

	@Column(name = "nombre", nullable = false, length = 160)
	private String nombre;

	@Column(name = "descripcion", length = 500)
	private String descripcion;

	@Enumerated(EnumType.STRING)
	@Column(name = "naturaleza", nullable = false, length = 24)
	private Naturaleza naturaleza;

	@Enumerated(EnumType.STRING)
	@Column(name = "modalidad_default", nullable = false, length = 16)
	private Modalidad modalidadDefault;

	@Column(name = "requiere_caso_clinico_default", nullable = false)
	private boolean requiereCasoClinicoDefault;

	@Column(name = "genera_registro_clinico_default", nullable = false)
	private boolean generaRegistroClinicoDefault;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deactivation_reason", length = 280)
	private String deactivationReason;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Servicio() {
		// Requerido por JPA.
	}

	public Servicio(
			String codigo,
			String nombre,
			String descripcion,
			Naturaleza naturaleza,
			Modalidad modalidadDefault,
			boolean requiereCasoClinicoDefault,
			boolean generaRegistroClinicoDefault) {

		this.codigo = exigirTexto(codigo, "El codigo del servicio es obligatorio");
		this.nombre = exigirTexto(nombre, "El nombre del servicio es obligatorio");
		this.descripcion = vacioEsNulo(descripcion);
		this.naturaleza = exigirNoNulo(naturaleza, "La naturaleza del servicio es obligatoria");
		this.modalidadDefault =
				exigirNoNulo(modalidadDefault, "La modalidad por defecto del servicio es obligatoria");
		this.requiereCasoClinicoDefault = requiereCasoClinicoDefault;
		this.generaRegistroClinicoDefault = generaRegistroClinicoDefault;
		this.active = true;
	}

	/**
	 * Aplica una edicion parcial: cada valor {@code null} deja el campo como estaba.
	 *
	 * <p>Semantica de PATCH y no de PUT, misma convencion que {@code Espacio} y
	 * {@code CatalogoConcepto}. Ni {@link #codigo} esta aca —es {@code updatable = false}— ni
	 * cambiar cualquiera de estos valores toca una sola {@link OfertaServicioConsultorio} ya
	 * creada: ver la seccion de la clase sobre por que los defaults no se leen en tiempo real.
	 */
	public void updateDatos(
			String nombre,
			String descripcion,
			Naturaleza naturaleza,
			Modalidad modalidadDefault,
			Boolean requiereCasoClinicoDefault,
			Boolean generaRegistroClinicoDefault) {

		if (nombre != null) {
			this.nombre = exigirTexto(nombre, "El nombre del servicio es obligatorio");
		}
		if (descripcion != null) {
			this.descripcion = vacioEsNulo(descripcion);
		}
		if (naturaleza != null) {
			this.naturaleza = naturaleza;
		}
		if (modalidadDefault != null) {
			this.modalidadDefault = modalidadDefault;
		}
		if (requiereCasoClinicoDefault != null) {
			this.requiereCasoClinicoDefault = requiereCasoClinicoDefault;
		}
		if (generaRegistroClinicoDefault != null) {
			this.generaRegistroClinicoDefault = generaRegistroClinicoDefault;
		}
	}

	/**
	 * Baja logica con motivo declarado (RF-M27-002).
	 *
	 * <p>No borra nada y no cascadea: las ofertas vigentes que referencian este servicio siguen
	 * operando sin cambios (RN-M03-006). Lo unico que la baja impide es la creacion de ofertas
	 * NUEVAS sobre este servicio, y esa comprobacion vive en la capa de aplicacion que construye
	 * la {@link OfertaServicioConsultorio}, no aca: esta clase no conoce a sus ofertas.
	 */
	public void deactivate(Instant occurredAt, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"La baja de un servicio exige un motivo declarado: sin el, la auditoria no "
							+ "responde por que seis meses despues");
		}
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason.strip();
	}

	/** {@code true} cuando el servicio admite crear ofertas nuevas sobre el (ciclo de vida). */
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

	public String getCodigo() {
		return codigo;
	}

	public String getNombre() {
		return nombre;
	}

	public String getDescripcion() {
		return descripcion;
	}

	public Naturaleza getNaturaleza() {
		return naturaleza;
	}

	public Modalidad getModalidadDefault() {
		return modalidadDefault;
	}

	public boolean isRequiereCasoClinicoDefault() {
		return requiereCasoClinicoDefault;
	}

	public boolean isGeneraRegistroClinicoDefault() {
		return generaRegistroClinicoDefault;
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
