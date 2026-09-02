package com.akine.person.domain;

import com.akine.contracting.spi.ReferenciaDeCobertura;
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
 * La cobertura de un paciente ante un financiador, o la modalidad Particular (M08).
 *
 * <h2>Guarda una COPIA CONGELADA del plan, nunca un puntero</h2>
 *
 * <p>Los nueve campos que van de {@link #financiadorCodigo} a {@link #referenciaCapturadaEl} son
 * lo que el plan decia <b>el dia en que esta cobertura se firmo</b>. No se releen jamas del
 * catalogo: si manana renombran el plan, o le bajan el copago, o le cierran la vigencia, esta
 * fila no cambia. Es el mismo patron con que {@code obligacion} congela el precio de la oferta en
 * AKINE-07.01, y la copia la entrega
 * {@link com.akine.contracting.spi.CoberturaCatalogoDirectory#congelar}.
 *
 * <p>{@link #financiadorId} y {@link #planId} tambien se guardan, y para dos cosas nada mas:
 * trazabilidad y el unique del numero de afiliado. <b>Nunca para resolver texto.</b> Ese es el
 * defecto que el javadoc de {@link ReferenciaDeCobertura} existe para hacer imposible de escribir
 * por descuido.
 *
 * <h2>Vigencia y ciclo de vida son dos cosas</h2>
 *
 * <ul>
 *   <li><b>Finalizar la vigencia</b> ({@link #finalizarVigencia}) es fijar {@code vigenciaHasta}.
 *       La cobertura queda ACTIVA y consultable, y deja de aplicar despues de esa fecha. Es
 *       RF-M08-003 y es lo que pasa cuando el paciente cambia de obra social.</li>
 *   <li><b>Dar de baja</b> ({@link #deactivate}) es sacarla del ciclo de vida con motivo: se
 *       cargo mal. Sigue siendo legible, y ningun hecho ya registrado se toca (RN-M08-003).</li>
 * </ul>
 *
 * <p>{@code vigenciaHasta} es el <b>ultimo dia INCLUSIVE</b>, igual que en {@code plan_cobertura}.
 */
@Entity
@Table(name = "cobertura_paciente")
public class CoberturaPaciente extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "persona_id", nullable = false, updatable = false)
	private Long personaId;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", nullable = false, length = 16, updatable = false)
	private TipoCobertura tipo;

	// --- Referencia congelada. Toda updatable = false: una copia que se puede editar no es una
	// copia congelada, es una cache mal implementada.
	@Column(name = "financiador_id", updatable = false)
	private Long financiadorId;

	@Column(name = "financiador_codigo", length = 64, updatable = false)
	private String financiadorCodigo;

	@Column(name = "financiador_nombre", length = 160, updatable = false)
	private String financiadorNombre;

	@Column(name = "financiador_tipo", length = 24, updatable = false)
	private String financiadorTipo;

	@Column(name = "plan_id", updatable = false)
	private Long planId;

	@Column(name = "plan_codigo", length = 64, updatable = false)
	private String planCodigo;

	@Column(name = "plan_nombre", length = 160, updatable = false)
	private String planNombre;

	@Column(name = "requeria_autorizacion", updatable = false)
	private Boolean requeriaAutorizacion;

	@Column(name = "requeria_credencial", updatable = false)
	private Boolean requeriaCredencial;

	@Column(name = "copago", precision = 12, scale = 2, updatable = false)
	private BigDecimal copago;

	@Column(name = "moneda", length = 3, updatable = false)
	private String moneda;

	@Column(name = "referencia_capturada_el", updatable = false)
	private Instant referenciaCapturadaEl;

	// --- Datos propios de la afiliacion, editables (RF-M08-002).
	@Column(name = "numero_afiliado", length = 64)
	private String numeroAfiliado;

	@Column(name = "credencial_vigencia_hasta")
	private LocalDate credencialVigenciaHasta;

	@Column(name = "vigencia_desde", nullable = false)
	private LocalDate vigenciaDesde;

	@Column(name = "vigencia_hasta")
	private LocalDate vigenciaHasta;

	@Column(name = "principal", nullable = false)
	private boolean principal;

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

	protected CoberturaPaciente() {
		// Requerido por JPA.
	}

	/**
	 * Cobertura PARTICULAR: sin financiador y sin credencial (RN-M08-001).
	 *
	 * <p>No recibe {@link ReferenciaDeCobertura} porque no hay nada que congelar. Es la razon por
	 * la que "Particular siempre esta disponible" se cumple sin ninguna fila de catalogo.
	 */
	public static CoberturaPaciente particular(
			Long organizationId,
			Long personaId,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta,
			boolean principal,
			String observaciones) {

		CoberturaPaciente cobertura = new CoberturaPaciente();
		cobertura.inicializar(organizationId, personaId, vigenciaDesde, vigenciaHasta, principal,
				observaciones);
		cobertura.tipo = TipoCobertura.PARTICULAR;
		return cobertura;
	}

	/**
	 * Cobertura FINANCIADA, con la referencia al plan ya congelada.
	 *
	 * <p><b>Recibe la copia y no el id.</b> Si este constructor aceptara un {@code planId} y
	 * resolviera los textos por su cuenta, la garantia de RN-M08-003 dependeria de que nadie
	 * olvidara la copia; recibiendo el record entero, olvidarla no compila.
	 */
	public static CoberturaPaciente financiada(
			Long organizationId,
			Long personaId,
			ReferenciaDeCobertura referencia,
			String numeroAfiliado,
			LocalDate credencialVigenciaHasta,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta,
			boolean principal,
			String observaciones) {

		if (referencia == null) {
			throw new IllegalArgumentException(
					"Una cobertura financiada exige la referencia congelada del plan");
		}

		CoberturaPaciente cobertura = new CoberturaPaciente();
		cobertura.inicializar(organizationId, personaId, vigenciaDesde, vigenciaHasta, principal,
				observaciones);
		cobertura.tipo = TipoCobertura.FINANCIADA;
		cobertura.financiadorId = referencia.financiadorId();
		cobertura.financiadorCodigo = referencia.financiadorCodigo();
		cobertura.financiadorNombre = referencia.financiadorNombre();
		cobertura.financiadorTipo = referencia.financiadorTipo();
		cobertura.planId = referencia.planId();
		cobertura.planCodigo = referencia.planCodigo();
		cobertura.planNombre = referencia.planNombre();
		cobertura.requeriaAutorizacion = referencia.requeriaAutorizacion();
		cobertura.requeriaCredencial = referencia.requeriaCredencial();
		cobertura.copago = referencia.copago();
		cobertura.moneda = referencia.moneda();
		cobertura.referenciaCapturadaEl = referencia.capturadaEl();
		cobertura.numeroAfiliado = vacioEsNulo(numeroAfiliado);
		cobertura.credencialVigenciaHasta = credencialVigenciaHasta;

		if (Boolean.TRUE.equals(cobertura.requeriaCredencial)
				&& cobertura.numeroAfiliado == null) {
			// El plan lo exigia ESE DIA. Que manana deje de exigirlo no invalida esta cobertura,
			// y por eso la condicion se evalua contra la copia y no contra el plan vivo.
			throw new IllegalArgumentException(
					"El plan exige numero de afiliado para esta cobertura");
		}
		return cobertura;
	}

	private void inicializar(
			Long organizationId,
			Long personaId,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta,
			boolean principal,
			String observaciones) {

		this.organizationId =
				exigirNoNulo(organizationId, "La cobertura pertenece siempre a una organizacion");
		this.personaId = exigirNoNulo(personaId, "La cobertura pertenece siempre a un paciente");
		this.vigenciaDesde =
				exigirNoNulo(vigenciaDesde, "La vigencia de la cobertura necesita una fecha de inicio");
		exigirVigenciaCoherente(vigenciaDesde, vigenciaHasta);
		this.vigenciaHasta = vigenciaHasta;
		this.principal = principal;
		this.observaciones = vacioEsNulo(observaciones);
		this.active = true;
	}

	// =================================================================================
	// Preguntas
	// =================================================================================

	/**
	 * La cobertura aplica ese dia (RF-M08-004).
	 *
	 * <p>Se evalua <b>dia por dia</b> y nunca contra una ventana entera, misma regla que
	 * {@code PlanCoberturaSnapshot.seleccionableEl}. <b>No mira la vigencia de la credencial</b>:
	 * una credencial vencida es una alerta para el mostrador, no una cobertura invalida. Vencerla
	 * automaticamente daria de baja coberturas reales por un dato que se copia a mano.
	 */
	public boolean vigenteEl(LocalDate fecha) {
		if (!active || fecha == null || fecha.isBefore(vigenciaDesde)) {
			return false;
		}
		return vigenciaHasta == null || !fecha.isAfter(vigenciaHasta);
	}

	/** Las dos vigencias se pisan en al menos un dia. Extremos INCLUSIVOS y null = sin fin. */
	public boolean seSolapaCon(LocalDate desde, LocalDate hasta) {
		boolean terminaAntes = vigenciaHasta != null && vigenciaHasta.isBefore(desde);
		boolean empiezaDespues = hasta != null && vigenciaDesde.isAfter(hasta);
		return !terminaAntes && !empiezaDespues;
	}

	/** La credencial esta vencida ese dia. Informativo: no invalida la cobertura. */
	public boolean credencialVencidaEl(LocalDate fecha) {
		return credencialVigenciaHasta != null && fecha != null
				&& fecha.isAfter(credencialVigenciaHasta);
	}

	public boolean isOperable() {
		return active && deletedAt == null;
	}

	// =================================================================================
	// Mutaciones
	// =================================================================================

	/**
	 * Edicion de los datos NO historicos (RF-M08-002).
	 *
	 * <p>Lo que no esta en esta firma no se puede cambiar, y esa ausencia es la regla: el tipo, el
	 * plan y los nueve campos de la copia congelada son {@code updatable = false}. Cambiar de plan
	 * es <b>otra cobertura</b> —se finaliza la vigente y se agrega la nueva— porque editarla en el
	 * lugar reescribiria retroactivamente con que cobertura se atendio al paciente el mes pasado.
	 *
	 * <p>Cada parametro nulo significa "no lo toques", salvo {@code vigenciaHasta}, que se explica
	 * en {@link #finalizarVigencia}.
	 */
	public void updateDatos(
			String numeroAfiliado,
			LocalDate credencialVigenciaHasta,
			LocalDate vigenciaDesde,
			String observaciones) {

		if (numeroAfiliado != null) {
			this.numeroAfiliado = vacioEsNulo(numeroAfiliado);
		}
		if (credencialVigenciaHasta != null) {
			this.credencialVigenciaHasta = credencialVigenciaHasta;
		}
		if (vigenciaDesde != null) {
			exigirVigenciaCoherente(vigenciaDesde, this.vigenciaHasta);
			this.vigenciaDesde = vigenciaDesde;
		}
		if (observaciones != null) {
			this.observaciones = vacioEsNulo(observaciones);
		}
		if (Boolean.TRUE.equals(requeriaCredencial) && this.numeroAfiliado == null) {
			throw new IllegalArgumentException(
					"El plan exige numero de afiliado para esta cobertura");
		}
	}

	/**
	 * Cierra la vigencia (RF-M08-003). <b>No es dar de baja</b>: la cobertura queda ACTIVA.
	 *
	 * <p>Es lo que pasa cuando el paciente cambia de obra social: la anterior deja de aplicar a
	 * partir de esa fecha y sigue explicando con que se lo atendio antes.
	 */
	public void finalizarVigencia(LocalDate hasta) {
		exigirVigenciaCoherente(this.vigenciaDesde, exigirNoNulo(
				hasta, "Finalizar la vigencia exige la fecha del ultimo dia"));
		this.vigenciaHasta = hasta;
	}

	/**
	 * Marca o desmarca la cobertura como principal (RF-M08-004).
	 *
	 * <p>Que no haya dos principales solapadas <b>no lo decide esta clase</b>: es un invariante
	 * entre filas y lo verifica el servicio bajo el lock de {@code cobertura_persona_lock}.
	 */
	public void marcarPrincipal(boolean principal) {
		this.principal = principal;
	}

	/** Baja logica con motivo obligatorio. No borra: RN-M08-003 y regla maestra 10. */
	public void deactivate(Instant occurredAt, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("La baja de una cobertura exige un motivo declarado");
		}
		this.active = false;
		this.principal = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason.strip();
	}

	// =================================================================================
	// Utilidades
	// =================================================================================

	private static void exigirVigenciaCoherente(LocalDate desde, LocalDate hasta) {
		if (hasta != null && hasta.isBefore(desde)) {
			throw new IllegalArgumentException(
					"La vigencia de la cobertura no puede terminar antes de empezar");
		}
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

	// =================================================================================
	// Lectura
	// =================================================================================

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getPersonaId() {
		return personaId;
	}

	public TipoCobertura getTipo() {
		return tipo;
	}

	public Long getFinanciadorId() {
		return financiadorId;
	}

	public String getFinanciadorCodigo() {
		return financiadorCodigo;
	}

	public String getFinanciadorNombre() {
		return financiadorNombre;
	}

	public String getFinanciadorTipo() {
		return financiadorTipo;
	}

	public Long getPlanId() {
		return planId;
	}

	public String getPlanCodigo() {
		return planCodigo;
	}

	public String getPlanNombre() {
		return planNombre;
	}

	public Boolean getRequeriaAutorizacion() {
		return requeriaAutorizacion;
	}

	public Boolean getRequeriaCredencial() {
		return requeriaCredencial;
	}

	public BigDecimal getCopago() {
		return copago;
	}

	public String getMoneda() {
		return moneda;
	}

	public Instant getReferenciaCapturadaEl() {
		return referenciaCapturadaEl;
	}

	public String getNumeroAfiliado() {
		return numeroAfiliado;
	}

	public LocalDate getCredencialVigenciaHasta() {
		return credencialVigenciaHasta;
	}

	public LocalDate getVigenciaDesde() {
		return vigenciaDesde;
	}

	public LocalDate getVigenciaHasta() {
		return vigenciaHasta;
	}

	public boolean isPrincipal() {
		return principal;
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
