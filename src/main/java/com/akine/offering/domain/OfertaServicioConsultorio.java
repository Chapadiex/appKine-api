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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * COMO una sede concreta presta un {@link Servicio} global (M27/M03, regla maestra 14).
 *
 * <h2>Por que esta clase no lee del {@code Servicio}</h2>
 *
 * <p>Esta entidad guarda sus PROPIOS {@link #modalidad}, {@link #requiereCasoClinico},
 * {@link #generaRegistroClinico}, etc. — no un puntero de solo lectura a los
 * {@code *Default} de {@link Servicio}. Es la decision de diseno mas importante de la etapa:
 * RF-M06-006 exige que "los defaults no reemplazan la configuracion concreta de cada Oferta", y
 * la unica forma de garantizar eso en el tiempo es que esta clase NUNCA vuelva a mirar al
 * Servicio despues de creada. {@code servicioId} es una referencia estable a QUE concepto
 * materializa esta oferta (para la FK y para poder listar "que ofertas usan este servicio"), no
 * un canal por el que los defaults se filtran en cada lectura.
 *
 * <p>Quien crea una oferta puede copiar los {@code *Default} del servicio como punto de partida
 * —eso es tarea de la capa de aplicacion, fuera de este modulo de dominio— pero una vez copiados
 * son datos de esta oferta, con su propio ciclo de vida. Ver el javadoc de {@link Servicio} y los
 * tests {@code una_oferta_no_hereda_los_defaults_del_servicio_al_editarse} y
 * {@code cambiar_un_default_del_servicio_no_toca_las_ofertas_existentes} de {@code OfertaTest}
 * (CA-M03-007-06 y CA-M06-006-06).
 *
 * <h2>Por que lleva {@code organizationId} y {@code Servicio} no</h2>
 *
 * <p>No existe la oferta global: toda oferta pertenece a una sede real. Por eso
 * {@link #organizationId} es {@code NOT NULL} y no lleva {@code owner_key} —no hay filas con
 * {@code organization_id NULL} que discriminar, a diferencia del catalogo clinico de
 * {@code resource} (ADR-0021)—. Se parece a {@code Espacio}, no al catalogo global.
 *
 * <h2>Los dos ejes temporales, que no son el mismo (mismo patron que {@code Espacio})</h2>
 *
 * <pre>
 *   CICLO DE VIDA       active / deletedAt / deactivationReason
 *                       "esta oferta ya no forma parte de lo que ofrece la sede". Irreversible,
 *                       con motivo, decidido por una persona. RN-M27-007.
 *
 *   VENTANA OPERATIVA   vigenciaDesde / vigenciaHasta
 *                       "esta oferta se puede reservar desde tal fecha, hasta tal otra". Es DATE
 *                       y no Instant porque la migracion V24 declara la columna como DATE: la
 *                       vigencia de una oferta se piensa en dias, no en instantes.
 * </pre>
 *
 * <h2>Regla maestra 15, sobre {@link #nombreComercial}</h2>
 *
 * <p>El nombre comercial es lo que ve el paciente en la cartelera y en el turnero — texto libre
 * para mostrar, nunca para decidir. RN-M06-006 lo dice con nombres propios: "no deben existir
 * condicionales funcionales por nombres como Pilates, RPG, Yoga u Osteopatia". Ningun codigo,
 * presente o futuro, puede comparar {@link #nombreComercial} contra un literal para decidir
 * comportamiento; lo que decide son los campos tipados de esta misma clase.
 */
@Entity
@Table(name = "oferta_servicio_consultorio")
public class OfertaServicioConsultorio extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	/**
	 * Sede que presta la oferta (RN-M03-006). {@code updatable = false}: mover una oferta de sede
	 * cambiaria el significado de todo lo que la referencia — misma razon que
	 * {@code Espacio.consultorioId}. Una oferta que se muda de sede es una que se da de baja y
	 * otra que se crea.
	 */
	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	/**
	 * Concepto global que esta oferta materializa (regla maestra 14). {@code updatable = false}
	 * por el mismo motivo que {@code consultorioId}: cambiar a que Servicio apunta una oferta ya
	 * creada cambiaria retroactivamente que concepto dice haber prestado. Ver la seccion de la
	 * clase sobre por que esta referencia nunca se lee para derivar comportamiento.
	 */
	@Column(name = "servicio_id", nullable = false, updatable = false)
	private Long servicioId;

	@Column(name = "nombre_comercial", nullable = false, length = 160)
	private String nombreComercial;

	@Column(name = "descripcion", length = 500)
	private String descripcion;

	@Enumerated(EnumType.STRING)
	@Column(name = "modalidad", nullable = false, length = 16)
	private Modalidad modalidad;

	@Column(name = "duracion_minutos", nullable = false)
	private int duracionMinutos;

	@Column(name = "capacidad", nullable = false)
	private int capacidad;

	@Column(name = "precio_base", precision = 12, scale = 2)
	private BigDecimal precioBase;

	@Column(name = "moneda", length = 3)
	private String moneda;

	/**
	 * Como cobra esta oferta. Cerrado a un vocabulario desde AKINE-08.06 y {@code V64}; ver
	 * {@link EsquemaCobro}. {@code null} = el centro todavia no lo declaro.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "esquema_cobro", length = 32)
	private EsquemaCobro esquemaCobro;

	/**
	 * Cuando nace la deuda. {@code null} si y solo si {@link #esquemaCobro} es {@code null}; la
	 * coherencia entre los dos la hace cumplir {@link PoliticaDeDevengo} y, en la base,
	 * {@code ck_oferta_politica_devengo}.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "momento_devengo", length = 16)
	private MomentoDevengo momentoDevengo;

	/**
	 * Si una ausencia devenga igual. Solo lo lee la rama {@link MomentoDevengo#ASISTENCIA}.
	 *
	 * <p>Nace en {@code false} porque cobrar un no-show es una politica de centro y aplicarla por
	 * defecto seria decidirla por el usuario — es textual del devengador de 07.01.
	 */
	@Column(name = "devenga_no_show", nullable = false)
	private boolean devengaNoShow;

	@Column(name = "admite_obra_social", nullable = false)
	private boolean admiteObraSocial;

	@Column(name = "requiere_caso_clinico", nullable = false)
	private boolean requiereCasoClinico;

	@Column(name = "genera_registro_clinico", nullable = false)
	private boolean generaRegistroClinico;

	@Column(name = "requiere_profesional", nullable = false)
	private boolean requiereProfesional;

	@Column(name = "requiere_espacio", nullable = false)
	private boolean requiereEspacio;

	@Column(name = "vigencia_desde", nullable = false)
	private LocalDate vigenciaDesde;

	/** Limite EXCLUSIVO de la vigencia. {@code null} = sin fin previsto (estado real, RN-M27-006). */
	@Column(name = "vigencia_hasta")
	private LocalDate vigenciaHasta;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deactivation_reason", length = 280)
	private String deactivationReason;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected OfertaServicioConsultorio() {
		// Requerido por JPA.
	}

	public OfertaServicioConsultorio(
			Long organizationId,
			Long consultorioId,
			Long servicioId,
			String nombreComercial,
			String descripcion,
			Modalidad modalidad,
			int duracionMinutos,
			int capacidad,
			BigDecimal precioBase,
			String moneda,
			PoliticaDeDevengo politicaDeDevengo,
			boolean admiteObraSocial,
			boolean requiereCasoClinico,
			boolean generaRegistroClinico,
			boolean requiereProfesional,
			boolean requiereEspacio,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta) {

		this.organizationId =
				exigirNoNulo(organizationId, "La organizacion de la oferta es obligatoria");
		this.consultorioId = exigirNoNulo(consultorioId, "La sede de la oferta es obligatoria");
		this.servicioId = exigirNoNulo(servicioId, "El servicio que materializa la oferta es obligatorio");
		this.nombreComercial =
				exigirTexto(nombreComercial, "El nombre comercial de la oferta es obligatorio");
		this.descripcion = vacioEsNulo(descripcion);
		this.modalidad = exigirNoNulo(modalidad, "La modalidad de la oferta es obligatoria");
		this.duracionMinutos = duracionMinutos;
		this.capacidad = capacidad;
		this.precioBase = precioBase;
		this.moneda = moneda;
		aplicarPolitica(politicaDeDevengo == null
				? PoliticaDeDevengo.SIN_DECLARAR
				: politicaDeDevengo);
		this.admiteObraSocial = admiteObraSocial;
		this.requiereCasoClinico = requiereCasoClinico;
		this.generaRegistroClinico = generaRegistroClinico;
		this.requiereProfesional = requiereProfesional;
		this.requiereEspacio = requiereEspacio;
		this.vigenciaDesde = exigirNoNulo(vigenciaDesde, "El inicio de vigencia es obligatorio");
		this.vigenciaHasta = vigenciaHasta;
		this.active = true;

		exigirDuracionPositiva(this.duracionMinutos);
		exigirCapacidadPositiva(this.capacidad);
		exigirCapacidadGrupalCoherente(this.modalidad, this.capacidad);
		exigirVigenciaCoherente(this.vigenciaDesde, this.vigenciaHasta);
		exigirPrecioConMoneda(this.precioBase, this.moneda);
	}

	/**
	 * Aplica una edicion parcial: cada valor {@code null} deja el campo como estaba.
	 *
	 * <p>Semantica de PATCH y no de PUT, misma convencion que {@code Espacio}. Ni
	 * {@code consultorioId} ni {@code servicioId} estan aca: los dos son {@code updatable =
	 * false} por lo que dicen sus javadocs.
	 *
	 * <p>{@code limpiarVigenciaHasta} existe porque un solo parametro nulable no alcanza para
	 * expresar dos intenciones distintas: "no toques el fin de vigencia" y "sacale el fin, que
	 * quede sin fin previsto" — misma razon que en {@code Espacio.updateDatos}.
	 *
	 * <p>{@code limpiarPrecio} existe por el mismo motivo, y ademas porque precio y moneda viajan
	 * juntos: limpiar uno sin el otro dejaria el estado "precio sin moneda", que
	 * {@link #exigirPrecioConMoneda} rechaza.
	 *
	 * <p>{@code limpiarPolitica} idem, para la {@link PoliticaDeDevengo}: los tres valores viajan
	 * juntos porque no son independientes —ver esa clase—, asi que se reemplazan de una o no se
	 * tocan. Limpiar el esquema sin limpiar el momento dejaria el estado "momento sin esquema",
	 * que el record rechaza y que {@code ck_oferta_politica_devengo} tambien.
	 */
	public void updateDatos(
			String nombreComercial,
			String descripcion,
			Modalidad modalidad,
			Integer duracionMinutos,
			Integer capacidad,
			BigDecimal precioBase,
			String moneda,
			boolean limpiarPrecio,
			PoliticaDeDevengo politicaDeDevengo,
			boolean limpiarPolitica,
			Boolean admiteObraSocial,
			Boolean requiereCasoClinico,
			Boolean generaRegistroClinico,
			Boolean requiereProfesional,
			Boolean requiereEspacio,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta,
			boolean limpiarVigenciaHasta) {

		if (nombreComercial != null) {
			this.nombreComercial =
					exigirTexto(nombreComercial, "El nombre comercial de la oferta es obligatorio");
		}
		if (descripcion != null) {
			this.descripcion = vacioEsNulo(descripcion);
		}
		if (modalidad != null) {
			this.modalidad = modalidad;
		}
		if (duracionMinutos != null) {
			this.duracionMinutos = duracionMinutos;
		}
		if (capacidad != null) {
			this.capacidad = capacidad;
		}
		if (limpiarPrecio) {
			this.precioBase = null;
			this.moneda = null;
		} else if (precioBase != null || moneda != null) {
			this.precioBase = precioBase;
			this.moneda = moneda;
		}
		if (limpiarPolitica) {
			aplicarPolitica(PoliticaDeDevengo.SIN_DECLARAR);
		} else if (politicaDeDevengo != null) {
			aplicarPolitica(politicaDeDevengo);
		}
		if (admiteObraSocial != null) {
			this.admiteObraSocial = admiteObraSocial;
		}
		if (requiereCasoClinico != null) {
			this.requiereCasoClinico = requiereCasoClinico;
		}
		if (generaRegistroClinico != null) {
			this.generaRegistroClinico = generaRegistroClinico;
		}
		if (requiereProfesional != null) {
			this.requiereProfesional = requiereProfesional;
		}
		if (requiereEspacio != null) {
			this.requiereEspacio = requiereEspacio;
		}
		if (vigenciaDesde != null) {
			this.vigenciaDesde = vigenciaDesde;
		}
		if (limpiarVigenciaHasta) {
			this.vigenciaHasta = null;
		} else if (vigenciaHasta != null) {
			this.vigenciaHasta = vigenciaHasta;
		}

		exigirDuracionPositiva(this.duracionMinutos);
		exigirCapacidadPositiva(this.capacidad);
		exigirCapacidadGrupalCoherente(this.modalidad, this.capacidad);
		exigirVigenciaCoherente(this.vigenciaDesde, this.vigenciaHasta);
		exigirPrecioConMoneda(this.precioBase, this.moneda);
	}

	/**
	 * Baja logica con motivo declarado (RN-M27-007).
	 *
	 * <p>No borra nada: la oferta deja de admitir reservas nuevas y conserva su nombre y su
	 * estado para todo lo que ya la referencio. No hay reactivacion: una oferta que vuelve es una
	 * oferta nueva, no una baja deshecha.
	 */
	public void deactivate(Instant occurredAt, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"La baja de una oferta exige un motivo declarado: sin el, la auditoria no "
							+ "responde por que seis meses despues");
		}
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason.strip();
	}

	/** {@code true} cuando la oferta admite operaciones nuevas (ciclo de vida). */
	public boolean isOperable() {
		return active && deletedAt == null;
	}

	/**
	 * {@code true} si en la fecha {@code at} la oferta esta vigente administrativamente Y dentro
	 * de su ventana operativa. Mismo criterio que {@code Espacio.estaEnServicio}: las dos
	 * condiciones, no una.
	 */
	public boolean estaVigente(LocalDate at) {
		if (!isOperable()) {
			return false;
		}
		if (at.isBefore(vigenciaDesde)) {
			return false;
		}
		return vigenciaHasta == null || at.isBefore(vigenciaHasta);
	}

	private static void exigirDuracionPositiva(int duracionMinutos) {
		if (duracionMinutos <= 0) {
			throw new IllegalArgumentException(
					"La duracion de una oferta debe ser mayor a cero minutos");
		}
	}

	private static void exigirCapacidadPositiva(int capacidad) {
		if (capacidad <= 0) {
			throw new IllegalArgumentException(
					"La capacidad de una oferta debe ser mayor que cero (RF-M27-003)");
		}
	}

	/**
	 * {@code ck_oferta_grupal_capacidad} de la migracion V24: una oferta GRUPAL de capacidad uno
	 * es una individual mal rotulada, y el motor de inscripciones de F2 la trataria como un grupo
	 * de una sola persona. Decision revisable, anotada en la cabecera de V24: aflojar esto a
	 * "capacidad > 0" es borrar el CHECK de la base y esta comprobacion, nada mas.
	 */
	private static void exigirCapacidadGrupalCoherente(Modalidad modalidad, int capacidad) {
		if (modalidad == Modalidad.GRUPAL && capacidad <= 1) {
			throw new IllegalArgumentException(
					"Una oferta GRUPAL exige capacidad mayor a uno: con capacidad uno es una "
							+ "oferta INDIVIDUAL mal rotulada");
		}
	}

	/**
	 * {@code ck_oferta_vigencia_coherente}: el limite superior es EXCLUSIVO, asi que
	 * {@code vigenciaHasta == vigenciaDesde} tambien se rechaza — seria una oferta reservable
	 * durante cero dias.
	 */
	private static void exigirVigenciaCoherente(LocalDate vigenciaDesde, LocalDate vigenciaHasta) {
		if (vigenciaDesde == null) {
			throw new IllegalArgumentException("La vigencia de una oferta exige un inicio explicito");
		}
		if (vigenciaHasta != null && !vigenciaHasta.isAfter(vigenciaDesde)) {
			throw new IllegalArgumentException(
					"El fin de vigencia de una oferta debe ser posterior a su inicio");
		}
	}

	/** {@code ck_oferta_precio_con_moneda}: un precio sin moneda no es un precio. */
	private static void exigirPrecioConMoneda(BigDecimal precioBase, String moneda) {
		if ((precioBase == null) != (moneda == null)) {
			throw new IllegalArgumentException(
					"El precio y la moneda de una oferta viajan juntos: los dos o ninguno");
		}
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

	public Long getConsultorioId() {
		return consultorioId;
	}

	public Long getServicioId() {
		return servicioId;
	}

	public String getNombreComercial() {
		return nombreComercial;
	}

	public String getDescripcion() {
		return descripcion;
	}

	public Modalidad getModalidad() {
		return modalidad;
	}

	public int getDuracionMinutos() {
		return duracionMinutos;
	}

	public int getCapacidad() {
		return capacidad;
	}

	public BigDecimal getPrecioBase() {
		return precioBase;
	}

	public String getMoneda() {
		return moneda;
	}

	public EsquemaCobro getEsquemaCobro() {
		return esquemaCobro;
	}

	public MomentoDevengo getMomentoDevengo() {
		return momentoDevengo;
	}

	public boolean isDevengaNoShow() {
		return devengaNoShow;
	}

	/** Como y cuando cobra esta oferta. Nunca {@code null}: sin declarar es un valor, no un hueco. */
	public PoliticaDeDevengo getPoliticaDeDevengo() {
		return new PoliticaDeDevengo(esquemaCobro, momentoDevengo, devengaNoShow);
	}

	/**
	 * Escribe los tres campos de una. <b>Unico camino de escritura</b>: el record ya valido la
	 * coherencia en su constructor, asi que no hay forma de dejar la entidad en un estado que la
	 * base rechazaria.
	 */
	private void aplicarPolitica(PoliticaDeDevengo politica) {
		this.esquemaCobro = politica.esquemaCobro();
		this.momentoDevengo = politica.momentoDevengo();
		this.devengaNoShow = politica.devengaNoShow();
	}

	public boolean isAdmiteObraSocial() {
		return admiteObraSocial;
	}

	public boolean isRequiereCasoClinico() {
		return requiereCasoClinico;
	}

	public boolean isGeneraRegistroClinico() {
		return generaRegistroClinico;
	}

	public boolean isRequiereProfesional() {
		return requiereProfesional;
	}

	public boolean isRequiereEspacio() {
		return requiereEspacio;
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
