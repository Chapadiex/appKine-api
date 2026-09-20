package com.akine.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Un hecho monetario real: entro o salio plata (RN-M20-002).
 *
 * <h2>Append-only. Lo que esta clase NO tiene es el diseno</h2>
 *
 * <p>Sin {@code version}, sin {@code updated_at}, sin baja logica, y el puerto de persistencia no
 * declara {@code update} ni {@code delete}. <b>Un historial que se puede editar no es un
 * historial.</b> Mismo diseno que {@code turno_evento} (V38), {@code caso_evento} (V47),
 * {@code plan_evento} (V49) y {@code autorizacion_movimiento} (V50), y es la regla maestra 10.
 *
 * <p>Corolario: un movimiento erroneo <b>se compensa</b>, con una fila propia de tipo
 * {@link TipoMovimiento#REVERSION_DE_INGRESO} o {@link TipoMovimiento#REVERSION_DE_EGRESO}, con
 * motivo obligatorio y puntero al original. Y la compensacion cae en la jornada que esta abierta
 * <b>hoy</b>, nunca reescribiendo la cerrada: esa ya fue arqueada, y si el error afecto el conteo
 * su {@code diferencia} ya lo registro. Reescribirla borraria la unica evidencia de que hubo un
 * desvio — y ademas, fisicamente, la plata sale del cajon hoy.
 *
 * <h2>Solo el efectivo afecta el arqueo</h2>
 *
 * <p>Un arqueo es contar billetes. Una tarjeta liquida en 18 dias a una cuenta que el sistema no
 * modela y una transferencia entra a un banco: sumarlas al saldo del cajon garantiza que el conteo
 * no cuadre nunca, y una caja que nunca cuadra deja de ser un control. Pero excluirlas del registro
 * haria que la pantalla mintiera sobre el dia —RF-M20-004 pide listar la operatoria, no listar
 * efectivo—, asi que se registran todas y {@link #afectaArqueo()} decide cuales mueven el saldo.
 *
 * <p><b>La autoridad es la columna generada {@code afecta_arqueo} de la base</b>, que no puede
 * divergir del medio; este metodo es el mismo predicado para quien tiene el objeto en memoria.
 */
@Entity
@Table(name = "movimiento_caja")
public class MovimientoCaja {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * Desnormalizado por ADR-0004, que no exceptua a las tablas hijas.
	 *
	 * <p>Y aca hay un motivo extra sobre el de {@code cobro_medio}: {@code jornadaCajaId} es
	 * <b>nullable</b>, asi que para un movimiento no-efectivo sin jornada esta columna no es una
	 * redundancia — es el unico camino al tenant.
	 */
	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	/**
	 * NULL cuando la plata nunca toco el cajon.
	 *
	 * <p>Un cobro en efectivo sin jornada abierta se rechaza —la plata entra igual y ningun arqueo
	 * podria encontrarla—; uno con tarjeta o transferencia no necesita jornada. La base lo sostiene
	 * con {@code CHECK (medio <> 'EFECTIVO' OR jornada_caja_id IS NOT NULL)}.
	 */
	@Column(name = "jornada_caja_id", updatable = false)
	private Long jornadaCajaId;

	/**
	 * Propia, no heredada de la jornada.
	 *
	 * <p>Es lo que permite listar la operatoria de un dia que incluye movimientos sin jornada, y lo
	 * que hace que el caso borde "movimiento tardio" tenga una respuesta en vez de una excepcion.
	 */
	@Column(name = "fecha_negocio", nullable = false, updatable = false)
	private LocalDate fechaNegocio;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", nullable = false, length = 24, updatable = false)
	private TipoMovimiento tipo;

	@Enumerated(EnumType.STRING)
	@Column(name = "medio", nullable = false, length = 24, updatable = false)
	private MedioDePago medio;

	/** <b>Siempre positivo:</b> el signo lo da el {@link TipoMovimiento}. */
	@Column(name = "importe", nullable = false, precision = 12, scale = 2, updatable = false)
	private BigDecimal importe;

	@Column(name = "moneda", nullable = false, length = 3, updatable = false)
	private String moneda;

	@Column(name = "concepto", length = 160, updatable = false)
	private String concepto;

	/** Obligatorio en las reversiones: plata que se saca sin explicacion es lo que una auditoria busca. */
	@Column(name = "motivo", length = 280, updatable = false)
	private String motivo;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo_origen", nullable = false, length = 16, updatable = false)
	private OrigenMovimiento tipoOrigen;

	@Column(name = "referencia_origen", updatable = false)
	private Long referenciaOrigen;

	/** Trazabilidad de RF-M24-006. <b>Cruza jornadas a proposito.</b> */
	@Column(name = "movimiento_origen_id", updatable = false)
	private Long movimientoOrigenId;

	@Column(name = "registrado_en", nullable = false, updatable = false)
	private Instant registradoEn;

	@Column(name = "registrado_por_cuenta_id", nullable = false, updatable = false)
	private Long registradoPorCuentaId;

	@Column(name = "idempotency_key", length = 80, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_hash", length = 64, updatable = false)
	private String requestHash;

	protected MovimientoCaja() {
		// Requerido por JPA.
	}

	/**
	 * Constructor unico: un movimiento nace completo o no nace.
	 *
	 * <p>No hay setters ni metodos de modificacion, por lo mismo que {@code Cobro} tampoco los
	 * tiene: es un hecho consumado.
	 */
	public MovimientoCaja(
			long organizationId,
			long consultorioId,
			Long jornadaCajaId,
			LocalDate fechaNegocio,
			TipoMovimiento tipo,
			MedioDePago medio,
			BigDecimal importe,
			String moneda,
			String concepto,
			String motivo,
			OrigenMovimiento tipoOrigen,
			Long referenciaOrigen,
			Long movimientoOrigenId,
			Instant registradoEn,
			long registradoPorCuentaId,
			String idempotencyKey,
			String requestHash) {

		if (importe == null || importe.signum() <= 0) {
			throw new IllegalArgumentException(
					"Un movimiento de caja mueve un importe positivo; el signo lo da el tipo: " + importe);
		}
		if (medio == MedioDePago.EFECTIVO && jornadaCajaId == null) {
			throw new IllegalArgumentException(
					"Un movimiento en efectivo pertenece siempre a una jornada de caja");
		}
		if (tipo.esReversion() && (motivo == null || motivo.isBlank() || movimientoOrigenId == null)) {
			throw new IllegalArgumentException(
					"Una reversion exige motivo y el movimiento que compensa");
		}
		if (!tipo.esReversion() && movimientoOrigenId != null) {
			throw new IllegalArgumentException(
					"Solo una reversion apunta a un movimiento anterior");
		}
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.jornadaCajaId = jornadaCajaId;
		this.fechaNegocio = fechaNegocio;
		this.tipo = tipo;
		this.medio = medio;
		this.importe = importe;
		this.moneda = moneda;
		this.concepto = normalizar(concepto);
		this.motivo = normalizar(motivo);
		this.tipoOrigen = tipoOrigen;
		this.referenciaOrigen = referenciaOrigen;
		this.movimientoOrigenId = movimientoOrigenId;
		this.registradoEn = registradoEn;
		this.registradoPorCuentaId = registradoPorCuentaId;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
	}

	/**
	 * Si este movimiento mueve el saldo que se cuenta al arquear.
	 *
	 * <p>La autoridad es la columna generada de la base; este metodo es el mismo predicado para
	 * quien ya tiene el objeto. Ver el javadoc de la clase.
	 */
	public boolean afectaArqueo() {
		return medio == MedioDePago.EFECTIVO;
	}

	private static String normalizar(String texto) {
		return texto == null || texto.isBlank() ? null : texto.trim();
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

	public Long getJornadaCajaId() {
		return jornadaCajaId;
	}

	public LocalDate getFechaNegocio() {
		return fechaNegocio;
	}

	public TipoMovimiento getTipo() {
		return tipo;
	}

	public MedioDePago getMedio() {
		return medio;
	}

	public BigDecimal getImporte() {
		return importe;
	}

	public String getMoneda() {
		return moneda;
	}

	public String getConcepto() {
		return concepto;
	}

	public String getMotivo() {
		return motivo;
	}

	public OrigenMovimiento getTipoOrigen() {
		return tipoOrigen;
	}

	public Long getReferenciaOrigen() {
		return referenciaOrigen;
	}

	public Long getMovimientoOrigenId() {
		return movimientoOrigenId;
	}

	public Instant getRegistradoEn() {
		return registradoEn;
	}

	public Long getRegistradoPorCuentaId() {
		return registradoPorCuentaId;
	}

	public String getRequestHash() {
		return requestHash;
	}
}
