package com.akine.billing.domain;

import com.akine.billing.domain.exception.EgresoConPagosException;
import com.akine.billing.domain.exception.EgresoNoConfirmableException;
import com.akine.billing.domain.exception.EgresoSinComprobanteException;
import com.akine.billing.domain.exception.EgresoYaAnuladoException;
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
 * Lo que el centro <b>debe</b> a un beneficiario (M22).
 *
 * <h2>Del lado del egreso tambien son tres cosas</h2>
 *
 * <p>Este repositorio ya separo {@code Obligacion != Cobro != Caja} del lado del paciente. Del lado
 * del profesional pasa lo mismo: <b>{@code Egreso} es lo que se debe, {@link PagoEgreso} es el acto
 * de saldarlo y {@link MovimientoCaja} es el hecho monetario.</b>
 *
 * <p>Colapsarlas en "un egreso es sacar plata de la caja" —que es lo que RF-M20-003 ya hace, para
 * comprar cafe— vuelve inexpresables dos cosas que la propia etapa declara: <b>la liquidacion que
 * se debe y todavia no se pago</b>, y <b>el pago parcial</b>, porque un movimiento de caja no tiene
 * saldo.
 *
 * <h2>Lo que esta clase NO tiene es parte del diseno</h2>
 *
 * <p>Sin {@code sesionId}, sin {@code obligacionId}, sin {@code personaId}, sin {@code cobroId}.
 * RN-M22-003 dice que pagar a un profesional no modifica sesiones historicas, y la forma fuerte de
 * cumplir una regla asi no es escribir un {@code if}: es que el modelo <b>no tenga como</b> nombrar
 * la sesion. Como contrapartida, esta etapa no calcula liquidaciones — y no debe: el plan dice
 * "sin inventar regla remunerativa".
 *
 * <p>Sin {@code movimientoCajaId} tampoco: es el movimiento el que apunta al pago, no al reves.
 *
 * <h2>{@code @Version} aca si, y no contradice a {@link JornadaCaja}</h2>
 *
 * <p>Aquella no la tiene porque todas sus escrituras son SQL nativo y no la incrementarian. Las dos
 * decisiones conviven porque <b>las dos fases de este agregado no se superponen</b>: la version
 * gobierna solo el {@code BORRADOR}, donde las escrituras son ediciones por JPA y dos
 * administrativos si pueden pisarse; el {@code UPDATE} nativo del saldo ocurre solo en
 * {@code CONFIRMADO}, donde el egreso ya no se edita. Un borrador no tiene pagos y un confirmado no
 * se edita, asi que la version y el saldo nunca corren la misma carrera.
 *
 * <p>Y <b>no hay {@code OPTIMISTIC_FORCE_INCREMENT}</b>: la reciproca que 04.02 pago —si el padre
 * ademas queda sucio, la version avanza dos veces y el cliente come un 409 del que no puede salir—
 * pasaria exactamente eso, porque las escrituras del saldo tocan columnas de esta misma tabla.
 */
@Entity
@Table(name = "egreso")
public class Egreso {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	/** Un egreso se paga desde la caja de una sede, igual que la jornada de 07.03. */
	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Enumerated(EnumType.STRING)
	@Column(name = "categoria", nullable = false, length = 32)
	private CategoriaEgreso categoria;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo_beneficiario", nullable = false, length = 16)
	private TipoBeneficiario tipoBeneficiario;

	/**
	 * La membership del colaborador, o NULL si el beneficiario es externo.
	 *
	 * <p><b>Se valida al crear el borrador y no se vuelve a validar nunca mas.</b> Es deliberado:
	 * si el profesional se fue el 30 de septiembre, <b>el centro le sigue debiendo septiembre</b>,
	 * y lo contrario convertiria una desvinculacion en una forma de no pagar.
	 */
	@Column(name = "beneficiario_membership_id")
	private Long beneficiarioMembershipId;

	/**
	 * {@code "M:<membershipId>"} o {@code "E:<documento|nombre>"}.
	 *
	 * <p>Existe para una sola cosa: que el unique del comprobante pueda incluir <b>de quien</b> es.
	 * Dos proveedores distintos emiten legitimamente su propia {@code FACTURA_B 0001-00000123}.
	 */
	@Column(name = "beneficiario_clave", nullable = false, length = 80)
	private String beneficiarioClave;

	/**
	 * Congelado al crear. Mismo criterio que {@code obligacion.snapshot_nombre}: una liquidacion de
	 * septiembre tiene que poder leerse en marzo sin depender de que la membership siga existiendo.
	 */
	@Column(name = "beneficiario_nombre", nullable = false, length = 160)
	private String beneficiarioNombre;

	@Column(name = "beneficiario_documento", length = 32)
	private String beneficiarioDocumento;

	@Column(name = "periodo_desde")
	private LocalDate periodoDesde;

	@Column(name = "periodo_hasta")
	private LocalDate periodoHasta;

	@Column(name = "concepto", nullable = false, length = 280)
	private String concepto;

	/** Declarado por una persona. Esta etapa no calcula liquidaciones. */
	@Column(name = "importe_total", nullable = false, precision = 12, scale = 2)
	private BigDecimal importeTotal;

	/** Lo mueve un {@code UPDATE} condicional, nunca esta clase. Ver {@code EgresoRepositoryPort}. */
	@Column(name = "saldo_pendiente", nullable = false, precision = 12, scale = 2)
	private BigDecimal saldoPendiente;

	@Column(name = "moneda", nullable = false, length = 3)
	private String moneda;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 16)
	private EstadoEgreso estado;

	@Column(name = "comprobante_tipo", length = 24)
	private String comprobanteTipo;

	@Column(name = "comprobante_numero", length = 40)
	private String comprobanteNumero;

	@Column(name = "comprobante_fecha")
	private LocalDate comprobanteFecha;

	@Column(name = "registrado_en", nullable = false, updatable = false)
	private Instant registradoEn;

	@Column(name = "registrado_por_cuenta_id", nullable = false, updatable = false)
	private Long registradoPorCuentaId;

	@Column(name = "confirmado_en")
	private Instant confirmadoEn;

	@Column(name = "confirmado_por_cuenta_id")
	private Long confirmadoPorCuentaId;

	@Column(name = "anulado_en")
	private Instant anuladoEn;

	@Column(name = "anulado_por_cuenta_id")
	private Long anuladoPorCuentaId;

	@Column(name = "motivo_anulacion", length = 280)
	private String motivoAnulacion;

	@Column(name = "idempotency_key", length = 80, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_hash", length = 64, updatable = false)
	private String requestHash;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Egreso() {
		// Requerido por JPA.
	}

	/** Nace siempre {@code BORRADOR} y con el saldo igual al total: todavia no salio un peso. */
	@SuppressWarnings("checkstyle:ParameterNumber")
	public Egreso(
			long organizationId,
			long consultorioId,
			CategoriaEgreso categoria,
			TipoBeneficiario tipoBeneficiario,
			Long beneficiarioMembershipId,
			String beneficiarioClave,
			String beneficiarioNombre,
			String beneficiarioDocumento,
			LocalDate periodoDesde,
			LocalDate periodoHasta,
			String concepto,
			BigDecimal importeTotal,
			String moneda,
			Instant registradoEn,
			long registradoPorCuentaId,
			String idempotencyKey,
			String requestHash) {

		if (importeTotal == null || importeTotal.signum() <= 0) {
			throw new IllegalArgumentException(
					"Un egreso mueve un importe positivo: " + importeTotal);
		}
		if ((tipoBeneficiario == TipoBeneficiario.COLABORADOR) != (beneficiarioMembershipId != null)) {
			throw new IllegalArgumentException(
					"Un beneficiario COLABORADOR exige su membership, y un EXTERNO no la admite");
		}
		if (periodoDesde != null && periodoHasta != null && periodoDesde.isAfter(periodoHasta)) {
			throw new IllegalArgumentException("El periodo empieza despues de terminar");
		}
		if ((periodoDesde == null) != (periodoHasta == null)) {
			throw new IllegalArgumentException(
					"Un periodo con una sola punta no dice nada: van los dos o ninguno");
		}
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.categoria = categoria;
		this.tipoBeneficiario = tipoBeneficiario;
		this.beneficiarioMembershipId = beneficiarioMembershipId;
		this.beneficiarioClave = beneficiarioClave;
		this.beneficiarioNombre = beneficiarioNombre;
		this.beneficiarioDocumento = beneficiarioDocumento;
		this.periodoDesde = periodoDesde;
		this.periodoHasta = periodoHasta;
		this.concepto = concepto;
		this.importeTotal = importeTotal;
		this.saldoPendiente = importeTotal;
		this.moneda = moneda;
		this.estado = EstadoEgreso.BORRADOR;
		this.registradoEn = registradoEn;
		this.registradoPorCuentaId = registradoPorCuentaId;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
	}

	/**
	 * Reemplaza los datos editables del borrador.
	 *
	 * <p>El llamador ya verifico que sigue siendo borrador; esta clase no lo repite porque el
	 * control de estado vive en el servicio, que es quien puede responder 409.
	 */
	@SuppressWarnings("checkstyle:ParameterNumber")
	public void editarBorrador(
			CategoriaEgreso categoria, LocalDate periodoDesde, LocalDate periodoHasta,
			String concepto, BigDecimal importeTotal, String comprobanteTipo,
			String comprobanteNumero, LocalDate comprobanteFecha) {

		if (importeTotal == null || importeTotal.signum() <= 0) {
			throw new IllegalArgumentException(
					"Un egreso mueve un importe positivo: " + importeTotal);
		}
		if ((periodoDesde == null) != (periodoHasta == null)) {
			throw new IllegalArgumentException(
					"Un periodo con una sola punta no dice nada: van los dos o ninguno");
		}
		if (periodoDesde != null && periodoDesde.isAfter(periodoHasta)) {
			throw new IllegalArgumentException("El periodo empieza despues de terminar");
		}
		this.categoria = categoria;
		this.periodoDesde = periodoDesde;
		this.periodoHasta = periodoHasta;
		this.concepto = concepto;
		this.importeTotal = importeTotal;
		// El borrador no tiene pagos, asi que el saldo lo sigue al total. `ck_egreso_borrador_sin_pagos`
		// lo exige desde la base: sin esto, editar el importe dejaria un saldo que no significa nada.
		this.saldoPendiente = importeTotal;
		this.comprobanteTipo = comprobanteTipo;
		this.comprobanteNumero = comprobanteNumero;
		this.comprobanteFecha = comprobanteFecha;
	}

	/**
	 * Punto de no retorno: a partir de aca el egreso no se edita mas y admite pagos.
	 *
	 * @throws EgresoSinComprobanteException <b>400</b>: falta el respaldo documental. Un egreso
	 *                                       confirmado sin comprobante es plata que salio sin papel
	 * @throws EgresoNoConfirmableException  <b>409</b>: ya no era un borrador
	 */
	public void confirmar(Instant cuando, long confirmadoPorCuentaId) {
		if (estado != EstadoEgreso.BORRADOR) {
			throw new EgresoNoConfirmableException(id, estado.name());
		}
		if (comprobanteTipo == null || comprobanteTipo.isBlank()
				|| comprobanteNumero == null || comprobanteNumero.isBlank()) {
			throw new EgresoSinComprobanteException(id);
		}
		this.estado = EstadoEgreso.CONFIRMADO;
		this.confirmadoEn = cuando;
		this.confirmadoPorCuentaId = confirmadoPorCuentaId;
	}

	/**
	 * Anula el compromiso. <b>No toca la caja</b>: un egreso que solo se debia nunca movio plata.
	 *
	 * <p>El control mira el <b>saldo</b> y no el estado, por lo mismo que {@code Obligacion.anular}:
	 * anular algo que ya se pago dejaria plata fuera del cajon sin ningun compromiso que la
	 * justifique. Primero se anulan los pagos —que es lo que la devuelve— y despues el compromiso.
	 *
	 * @throws EgresoYaAnuladoException  <b>409</b>
	 * @throws EgresoConPagosException   <b>409</b>, con lo ya pagado para que la pantalla ofrezca
	 *                                   la accion correcta en vez de decir "no se puede"
	 */
	public void anular(String motivo, Instant cuando, long anuladoPorCuentaId) {
		if (estado == EstadoEgreso.ANULADO) {
			throw new EgresoYaAnuladoException(id);
		}
		if (saldoPendiente.compareTo(importeTotal) != 0) {
			throw new EgresoConPagosException(id, importeTotal.subtract(saldoPendiente));
		}
		this.estado = EstadoEgreso.ANULADO;
		this.saldoPendiente = BigDecimal.ZERO.setScale(importeTotal.scale());
		this.anuladoEn = cuando;
		this.anuladoPorCuentaId = anuladoPorCuentaId;
		this.motivoAnulacion = motivo;
	}

	/** Solo un egreso confirmado puede mover la caja, y solo mientras le quede saldo. */
	public boolean admitePago() {
		return estado.admitePago() && saldoPendiente.signum() > 0;
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

	public CategoriaEgreso getCategoria() {
		return categoria;
	}

	public TipoBeneficiario getTipoBeneficiario() {
		return tipoBeneficiario;
	}

	public Long getBeneficiarioMembershipId() {
		return beneficiarioMembershipId;
	}

	public String getBeneficiarioClave() {
		return beneficiarioClave;
	}

	public String getBeneficiarioNombre() {
		return beneficiarioNombre;
	}

	public String getBeneficiarioDocumento() {
		return beneficiarioDocumento;
	}

	public LocalDate getPeriodoDesde() {
		return periodoDesde;
	}

	public LocalDate getPeriodoHasta() {
		return periodoHasta;
	}

	public String getConcepto() {
		return concepto;
	}

	public BigDecimal getImporteTotal() {
		return importeTotal;
	}

	public BigDecimal getSaldoPendiente() {
		return saldoPendiente;
	}

	public String getMoneda() {
		return moneda;
	}

	public EstadoEgreso getEstado() {
		return estado;
	}

	public String getComprobanteTipo() {
		return comprobanteTipo;
	}

	public String getComprobanteNumero() {
		return comprobanteNumero;
	}

	public LocalDate getComprobanteFecha() {
		return comprobanteFecha;
	}

	public Instant getRegistradoEn() {
		return registradoEn;
	}

	public Long getRegistradoPorCuentaId() {
		return registradoPorCuentaId;
	}

	public Instant getConfirmadoEn() {
		return confirmadoEn;
	}

	public Instant getAnuladoEn() {
		return anuladoEn;
	}

	public String getMotivoAnulacion() {
		return motivoAnulacion;
	}

	public String getRequestHash() {
		return requestHash;
	}

	public long getVersion() {
		return version;
	}
}
