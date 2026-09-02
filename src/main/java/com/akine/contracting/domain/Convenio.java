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
import java.time.LocalDate;

/**
 * Acuerdo economico entre una SEDE y un plan de un financiador (M16).
 *
 * <h2>RN-M16-001 sostenida por la estructura: el convenio es del CONSULTORIO</h2>
 *
 * <p>{@link #consultorioId} es {@code NOT NULL} y {@code updatable = false}. El financiador y el
 * plan son de la organizacion —los modela M15— pero <b>lo que se negocio con ellos es de la
 * sede</b>: dos sedes de la misma organizacion pueden tener aranceles distintos para la misma
 * practica bajo el mismo plan, y eso no es una anomalia sino el caso normal de una cadena.
 *
 * <p>{@link #financiadorId} y {@link #planId} tambien son inmutables. Mudar un convenio de plan
 * reescribiria el significado de todo lo que ya se liquido bajo el, sin que ninguna de esas
 * liquidaciones participe de la edicion.
 *
 * <h2>La regla que define la etapa, y que esta clase NO puede hacer cumplir sola</h2>
 *
 * <p>Dos convenios de la misma {@code (consultorio, financiador, plan)} no se pueden solapar en el
 * tiempo (RN-M16-002). {@link #seSolapaCon(Convenio)} sabe decidirlo entre dos instancias, y eso
 * es todo lo que una entidad puede aportar: <b>que la regla se cumpla frente a dos escrituras
 * concurrentes depende del lock de {@code convenio_lock}</b>, que toma {@code ConvenioService}
 * antes de leer nada. Ningun unique de MySQL expresa un solapamiento de intervalos.
 *
 * <h2>Ciclo de vida y vigencia son DOS cosas</h2>
 *
 * <pre>
 *   CICLO DE VIDA   active / deleted_at.       Administrativo. "Este convenio ya no se usa."
 *   VIGENCIA        vigencia_desde / hasta.    Operativo.      "Se aplica entre estas fechas."
 * </pre>
 *
 * <p>Misma separacion que {@code PlanCobertura} (V41) y por el mismo motivo: cerrar la vigencia es
 * una correccion de calendario y deja el convenio ACTIVO y consultable; darlo de baja lo saca del
 * ciclo de vida y exige motivo. RF-M16-002 y RF-M16-003 son las dos operaciones y son distintas.
 *
 * <p>{@link #vigenciaHasta} es INCLUSIVA: ultimo dia en que el convenio se aplica.
 */
@Entity
@Table(name = "convenio")
public class Convenio extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	/** RN-M16-001. Inmutable: un convenio no se muda de sede. */
	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "financiador_id", nullable = false, updatable = false)
	private Long financiadorId;

	@Column(name = "plan_id", nullable = false, updatable = false)
	private Long planId;

	/** Clave estable. Inmutable: es lo que un snapshot economico guarda para poder explicarse. */
	@Column(name = "codigo", nullable = false, updatable = false, length = 64)
	private String codigo;

	@Column(name = "nombre", nullable = false, length = 160)
	private String nombre;

	@Enumerated(EnumType.STRING)
	@Column(name = "modalidad", nullable = false, length = 24)
	private ModalidadConvenio modalidad;

	@Column(name = "vigencia_desde", nullable = false)
	private LocalDate vigenciaDesde;

	/** ULTIMO dia en que se aplica, INCLUSIVE. {@code null} = sin fin previsto. */
	@Column(name = "vigencia_hasta")
	private LocalDate vigenciaHasta;

	@Column(name = "moneda", nullable = false, length = 3)
	private String moneda;

	@Column(name = "requiere_orden", nullable = false)
	private boolean requiereOrden;

	@Column(name = "requiere_autorizacion", nullable = false)
	private boolean requiereAutorizacion;

	@Column(name = "requiere_credencial", nullable = false)
	private boolean requiereCredencial;

	@Column(name = "limite_sesiones_mensual")
	private Integer limiteSesionesMensual;

	@Column(name = "documentacion_requerida", length = 500)
	private String documentacionRequerida;

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

	protected Convenio() {
		// Requerido por JPA.
	}

	@SuppressWarnings("java:S107")
	public Convenio(
			long organizationId,
			long consultorioId,
			long financiadorId,
			long planId,
			String codigo,
			String nombre,
			ModalidadConvenio modalidad,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta,
			String moneda,
			boolean requiereOrden,
			boolean requiereAutorizacion,
			boolean requiereCredencial,
			Integer limiteSesionesMensual,
			String documentacionRequerida,
			String observaciones) {

		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.financiadorId = financiadorId;
		this.planId = planId;
		this.codigo = codigo;
		this.nombre = nombre;
		this.modalidad = modalidad;
		aplicarVigencia(vigenciaDesde, vigenciaHasta);
		this.moneda = exigirMoneda(moneda);
		this.requiereOrden = requiereOrden;
		this.requiereAutorizacion = requiereAutorizacion;
		this.requiereCredencial = requiereCredencial;
		this.limiteSesionesMensual = exigirLimite(limiteSesionesMensual);
		this.documentacionRequerida = documentacionRequerida;
		this.observaciones = observaciones;
	}

	/**
	 * Edicion parcial. Lo que llega en {@code null} NO se toca.
	 *
	 * <p><b>La vigencia se valida como PAR aunque llegue de a una:</b> mandar solo
	 * {@code vigenciaHasta} se compara contra el {@code vigenciaDesde} guardado. Validar solo el
	 * campo que llega deja pasar la inversion mas facil de cometer.
	 *
	 * <p>Ni el codigo, ni la sede, ni el financiador, ni el plan estan aca: son la identidad del
	 * convenio y lo que ya se liquido bajo el los referencia.
	 */
	@SuppressWarnings("java:S107")
	public void updateDatos(
			String nombre,
			ModalidadConvenio modalidad,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta,
			Boolean requiereOrden,
			Boolean requiereAutorizacion,
			Boolean requiereCredencial,
			Integer limiteSesionesMensual,
			String documentacionRequerida,
			String observaciones) {

		if (nombre != null) {
			this.nombre = nombre;
		}
		if (modalidad != null) {
			this.modalidad = modalidad;
		}
		if (vigenciaDesde != null || vigenciaHasta != null) {
			aplicarVigencia(
					vigenciaDesde == null ? this.vigenciaDesde : vigenciaDesde,
					vigenciaHasta == null ? this.vigenciaHasta : vigenciaHasta);
		}
		if (requiereOrden != null) {
			this.requiereOrden = requiereOrden;
		}
		if (requiereAutorizacion != null) {
			this.requiereAutorizacion = requiereAutorizacion;
		}
		if (requiereCredencial != null) {
			this.requiereCredencial = requiereCredencial;
		}
		if (limiteSesionesMensual != null) {
			this.limiteSesionesMensual = exigirLimite(limiteSesionesMensual);
		}
		if (documentacionRequerida != null) {
			this.documentacionRequerida = documentacionRequerida;
		}
		if (observaciones != null) {
			this.observaciones = observaciones;
		}
	}

	/** Baja logica con motivo obligatorio. No hay reactivacion. */
	public void deactivate(Instant occurredAt, String reason) {
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason;
	}

	// =================================================================================
	// Reglas
	// =================================================================================

	/** El periodo en que este convenio se aplica. */
	public Vigencia vigencia() {
		return new Vigencia(vigenciaDesde, vigenciaHasta);
	}

	/**
	 * {@code true} si los dos convenios comparten al menos un dia de vigencia.
	 *
	 * <p><b>No comprueba que sean del mismo alcance</b>, y eso es deliberado: quien decide que dos
	 * convenios compiten es la consulta que los trajo —la que filtra por {@code (consultorio,
	 * financiador, plan)} bajo el lock—. Meter ese filtro aca duplicaria el criterio en dos
	 * lugares y permitiria que un dia digan cosas distintas.
	 */
	public boolean seSolapaCon(Convenio otro) {
		return vigencia().seSolapaCon(otro.vigencia());
	}

	/** {@code true} mientras no haya sido dado de baja. Ciclo de vida, no vigencia. */
	public boolean isOperable() {
		return active;
	}

	/**
	 * {@code true} si el convenio se aplica ese dia: operable Y dentro de su vigencia.
	 *
	 * <p>Se evalua <b>dia por dia</b> y nunca contra una ventana entera, misma regla que
	 * {@code PlanCoberturaSnapshot.seleccionableEl} y {@code OfertaSnapshot.vigenteEl}.
	 */
	public boolean aplicaEl(LocalDate fecha) {
		return active && vigencia().cubre(fecha);
	}

	// =================================================================================
	// Invariantes internas
	// =================================================================================

	private void aplicarVigencia(LocalDate desde, LocalDate hasta) {
		// El constructor de Vigencia es el que valida, para que la regla viva en un solo lugar.
		Vigencia validada = new Vigencia(desde, hasta);
		this.vigenciaDesde = validada.desde();
		this.vigenciaHasta = validada.hasta();
	}

	private static String exigirMoneda(String moneda) {
		if (moneda == null || moneda.isBlank()) {
			throw new IllegalArgumentException(
					"El convenio necesita una moneda: sus aranceles no pueden tener importe sin ella");
		}
		return moneda.strip().toUpperCase(java.util.Locale.ROOT);
	}

	private static Integer exigirLimite(Integer limite) {
		if (limite != null && limite <= 0) {
			throw new IllegalArgumentException(
					"El limite mensual de sesiones tiene que ser mayor que cero. "
							+ "Sin tope pactado se expresa omitiendolo, no con cero");
		}
		return limite;
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

	public Long getConsultorioId() {
		return consultorioId;
	}

	public Long getFinanciadorId() {
		return financiadorId;
	}

	public Long getPlanId() {
		return planId;
	}

	public String getCodigo() {
		return codigo;
	}

	public String getNombre() {
		return nombre;
	}

	public ModalidadConvenio getModalidad() {
		return modalidad;
	}

	public LocalDate getVigenciaDesde() {
		return vigenciaDesde;
	}

	public LocalDate getVigenciaHasta() {
		return vigenciaHasta;
	}

	public String getMoneda() {
		return moneda;
	}

	public boolean isRequiereOrden() {
		return requiereOrden;
	}

	public boolean isRequiereAutorizacion() {
		return requiereAutorizacion;
	}

	public boolean isRequiereCredencial() {
		return requiereCredencial;
	}

	public Integer getLimiteSesionesMensual() {
		return limiteSesionesMensual;
	}

	public String getDocumentacionRequerida() {
		return documentacionRequerida;
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
