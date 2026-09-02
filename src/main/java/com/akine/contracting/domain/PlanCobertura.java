package com.akine.contracting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Plan de cobertura de un financiador (M15).
 *
 * <h2>RN-M15-001 sostenida por la estructura</h2>
 *
 * <p>"Un plan pertenece a un financiador": {@link #financiadorId} es {@code NOT NULL} y
 * {@code updatable = false}. Lo segundo es lo que hace que la regla valga tambien <i>despues</i>
 * del alta — un plan que pudiera mudarse de financiador reescribiria el significado de todas las
 * coberturas que ya lo referencian, sin que ninguna de ellas participe de esa edicion.
 *
 * <h2>Ciclo de vida y vigencia son DOS cosas, y esa es la distincion central de la clase</h2>
 *
 * <pre>
 *   CICLO DE VIDA   active / deleted_at.  Administrativo. "Este plan ya no se usa mas."
 *   VIGENCIA        vigencia_desde / hasta. Operativo.   "Este plan vale entre estas fechas."
 * </pre>
 *
 * <p>Se pueden combinar de las cuatro maneras y las cuatro tienen sentido. El caso borde que la
 * etapa nombra —"plan sin nuevas altas pero con pacientes vigentes"— es justamente uno de ellos:
 * un plan cuya {@link #vigenciaHasta} ya paso <b>no se ofrece</b> para una cobertura nueva
 * (RN-M15-002) y sin embargo las coberturas firmadas mientras estuvo vigente siguen resolviendo,
 * porque esas guardan su propia copia congelada. La misma separacion que {@code Espacio} (V19),
 * {@code ProfesionalDisponibilidad} (V23) y {@code OfertaServicioConsultorio} (V24) ya usan.
 *
 * <h2>{@link #vigenciaHasta} es INCLUSIVA</h2>
 *
 * <p>Es el ultimo dia en que el plan se puede elegir. Se declara porque V24 dejo una ambiguedad
 * real: su cabecera llama "EXCLUSIVA" a {@code oferta.vigencia_hasta} y {@code OfertaSnapshot}
 * la evalua inclusiva. Aca las dos mitades dicen lo mismo, {@code ck_plan_vigencia_coherente}
 * admite {@code hasta = desde} —un plan que vale un solo dia es un estado real— y
 * {@link #vigenteEl(LocalDate)} lo hace ejecutable.
 *
 * <h2>El copago es {@code BigDecimal} y jamas {@code double}</h2>
 *
 * <p>AGENT.md §5, y ArchUnit lo verifica ({@code sin_punto_flotante_en_el_dominio}). {@code null}
 * significa "sin copago declarado", que <b>no</b> es lo mismo que cero: cero es una decision
 * tomada, {@code null} es una decision que nadie tomo todavia. Viaja siempre con
 * {@link #moneda}, porque un importe sin moneda no es un importe.
 */
@Entity
@Table(name = "plan_cobertura")
public class PlanCobertura extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	/** RN-M15-001. Inmutable: ver la cabecera de la clase. */
	@Column(name = "financiador_id", nullable = false, updatable = false)
	private Long financiadorId;

	/** Clave estable dentro del financiador. Inmutable, igual que {@code Financiador.codigo}. */
	@Column(name = "codigo", nullable = false, updatable = false, length = 64)
	private String codigo;

	@Column(name = "nombre", nullable = false, length = 160)
	private String nombre;

	@Column(name = "descripcion", length = 500)
	private String descripcion;

	@Column(name = "vigencia_desde", nullable = false)
	private LocalDate vigenciaDesde;

	/** Ultimo dia INCLUSIVE. {@code null} = sin fin previsto, que es un estado real. */
	@Column(name = "vigencia_hasta")
	private LocalDate vigenciaHasta;

	@Column(name = "requiere_autorizacion", nullable = false)
	private boolean requiereAutorizacion;

	@Column(name = "requiere_credencial", nullable = false)
	private boolean requiereCredencial = true;

	@Column(name = "copago", precision = 12, scale = 2)
	private BigDecimal copago;

	@Column(name = "moneda", length = 3)
	private String moneda;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deactivation_reason", length = 280)
	private String deactivationReason;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected PlanCobertura() {
		// Requerido por JPA.
	}

	public PlanCobertura(
			Long organizationId,
			Long financiadorId,
			String codigo,
			String nombre,
			String descripcion,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta,
			boolean requiereAutorizacion,
			boolean requiereCredencial,
			BigDecimal copago,
			String moneda) {

		this.organizationId =
				exigirNoNulo(organizationId, "El plan pertenece siempre a una organizacion");
		this.financiadorId =
				exigirNoNulo(financiadorId, "Un plan pertenece siempre a un financiador (RN-M15-001)");
		this.codigo = exigirTexto(codigo, "El codigo del plan es obligatorio");
		this.nombre = exigirTexto(nombre, "El nombre del plan es obligatorio");
		this.descripcion = vacioEsNulo(descripcion);
		this.vigenciaDesde =
				exigirNoNulo(vigenciaDesde, "La vigencia del plan necesita una fecha de inicio");
		this.vigenciaHasta = vigenciaHasta;
		exigirVigenciaCoherente(this.vigenciaDesde, this.vigenciaHasta);
		this.requiereAutorizacion = requiereAutorizacion;
		this.requiereCredencial = requiereCredencial;
		aplicarCopago(copago, moneda);
		this.active = true;
	}

	/**
	 * Edicion parcial. Los {@code null} no se tocan, salvo el par copago/moneda: ver
	 * {@link #aplicarCopago}.
	 *
	 * <p><b>La vigencia se valida como un par, aunque llegue de a una.</b> Mandar solo
	 * {@code vigenciaHasta} tiene que compararse contra el {@code vigenciaDesde} que ya esta
	 * guardado; validar cada campo por separado dejaria pasar una ventana invertida que despues
	 * rechaza el CHECK con un 500 en vez de un 400.
	 */
	public void updateDatos(
			String nombre,
			String descripcion,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta,
			Boolean requiereAutorizacion,
			Boolean requiereCredencial,
			BigDecimal copago,
			String moneda) {

		if (nombre != null) {
			this.nombre = exigirTexto(nombre, "El nombre del plan es obligatorio");
		}
		if (descripcion != null) {
			this.descripcion = vacioEsNulo(descripcion);
		}
		LocalDate nuevoDesde = vigenciaDesde == null ? this.vigenciaDesde : vigenciaDesde;
		LocalDate nuevoHasta = vigenciaHasta == null ? this.vigenciaHasta : vigenciaHasta;
		exigirVigenciaCoherente(nuevoDesde, nuevoHasta);
		this.vigenciaDesde = nuevoDesde;
		this.vigenciaHasta = nuevoHasta;

		if (requiereAutorizacion != null) {
			this.requiereAutorizacion = requiereAutorizacion;
		}
		if (requiereCredencial != null) {
			this.requiereCredencial = requiereCredencial;
		}
		if (copago != null || moneda != null) {
			aplicarCopago(copago, moneda);
		}
	}

	/**
	 * Cierra la vigencia del plan a una fecha (RF-M15-005, "actualizar o cerrar vigencia").
	 *
	 * <p><b>Cerrar la vigencia NO es dar de baja.</b> El plan queda activo y consultable, sus
	 * coberturas historicas siguen resolviendo y lo unico que cambia es que deja de ofrecerse
	 * para selecciones posteriores a esa fecha. Son dos operaciones distintas a proposito: la
	 * baja exige motivo y es irreversible, cerrar la vigencia es una correccion de calendario.
	 */
	public void cerrarVigencia(LocalDate ultimoDia) {
		exigirNoNulo(ultimoDia, "Cerrar la vigencia exige el ultimo dia en que el plan vale");
		exigirVigenciaCoherente(this.vigenciaDesde, ultimoDia);
		this.vigenciaHasta = ultimoDia;
	}

	/** Baja logica con motivo declarado (RF-M15-005). No borra nada: RN-M15-003. */
	public void deactivate(Instant occurredAt, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"La baja de un plan exige un motivo declarado: sin el, la auditoria no responde "
							+ "por que seis meses despues");
		}
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason.strip();
	}

	/** {@code true} cuando el plan admite ediciones y bajas. Ciclo de vida, no vigencia. */
	public boolean isOperable() {
		return active && deletedAt == null;
	}

	/**
	 * El plan esta vigente ese dia local. <b>{@link #vigenciaHasta} es inclusiva.</b>
	 *
	 * <p>Se evalua dia por dia y nunca contra una ventana entera, por el mismo motivo que
	 * {@code OfertaSnapshot.vigenteEl}: un plan que vence el 15 no puede seguir eligiendose el 20
	 * porque la consulta abarco todo el mes.
	 */
	public boolean vigenteEl(LocalDate fecha) {
		if (fecha == null || fecha.isBefore(vigenciaDesde)) {
			return false;
		}
		return vigenciaHasta == null || !fecha.isAfter(vigenciaHasta);
	}

	/**
	 * El plan se puede ELEGIR para una cobertura o un convenio nuevo ese dia (RN-M15-002).
	 *
	 * <p>Exige las tres condiciones y no dos: el plan operable, el plan vigente ese dia, y
	 * <b>el financiador operable</b>. La tercera no la puede responder esta clase —no conoce a su
	 * financiador, y no debe: una relacion JPA hacia el abriria justamente la puerta a la cascada
	 * que RN-M15-003 prohibe— asi que llega por parametro, y quien lee tiene que resolverla.
	 */
	public boolean seleccionableEl(LocalDate fecha, boolean financiadorOperable) {
		return financiadorOperable && isOperable() && vigenteEl(fecha);
	}

	/**
	 * Copago y moneda se mueven juntos o no se mueven.
	 *
	 * <p>Es el mismo invariante que {@code ck_plan_copago_con_moneda} en la base, aplicado antes
	 * de escribir para que el rechazo sea un 400 que nombra el campo y no un 500 al cerrar la
	 * transaccion. Mandar los dos vacios borra el copago, que es un estado valido: "sin copago
	 * declarado" no es lo mismo que "copago cero".
	 */
	private void aplicarCopago(BigDecimal copago, String moneda) {
		String monedaNormalizada = moneda == null || moneda.isBlank()
				? null
				: moneda.strip().toUpperCase(java.util.Locale.ROOT);

		if (copago == null && monedaNormalizada == null) {
			this.copago = null;
			this.moneda = null;
			return;
		}
		if (copago == null || monedaNormalizada == null) {
			throw new IllegalArgumentException(
					"El copago y la moneda viajan juntos: un importe sin moneda no es un importe");
		}
		if (copago.signum() < 0) {
			throw new IllegalArgumentException(
					"El copago no puede ser negativo: un negativo no es un descuento, es un dato roto");
		}
		if (monedaNormalizada.length() != 3) {
			throw new IllegalArgumentException("La moneda se declara con su codigo ISO 4217 de 3 letras");
		}
		this.copago = copago;
		this.moneda = monedaNormalizada;
	}

	private static void exigirVigenciaCoherente(LocalDate desde, LocalDate hasta) {
		if (hasta != null && hasta.isBefore(desde)) {
			throw new IllegalArgumentException(
					"La vigencia del plan termina antes de empezar. vigenciaHasta es el ultimo dia "
							+ "INCLUSIVE, asi que puede coincidir con vigenciaDesde pero no ser anterior");
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

	public Long getFinanciadorId() {
		return financiadorId;
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

	public LocalDate getVigenciaDesde() {
		return vigenciaDesde;
	}

	public LocalDate getVigenciaHasta() {
		return vigenciaHasta;
	}

	public boolean isRequiereAutorizacion() {
		return requiereAutorizacion;
	}

	public boolean isRequiereCredencial() {
		return requiereCredencial;
	}

	public BigDecimal getCopago() {
		return copago;
	}

	public String getMoneda() {
		return moneda;
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
