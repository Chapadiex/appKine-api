package com.akine.person.domain;

import com.akine.contracting.spi.ArancelCongelado;
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
 * Autorizacion de un financiador para atender a un paciente (M17, RF-M17-001/003/006).
 *
 * <h2>1. Autorizado y consumido son DOS columnas (RN-M17-001)</h2>
 *
 * <p>{@link #cantidadAutorizada} la fija el financiador y la escribe esta etapa.
 * {@link #cantidadConsumida} la mueve la sesion clinica, que es RF-M17-004 y una integracion
 * <b>posterior</b>: nace en cero y <b>nada en este modulo la incrementa</b>. El saldo que
 * {@link #saldo()} devuelve hoy es, por lo tanto, el saldo inicial — y eso hay que saberlo antes
 * de creer que la resta ya se mueve sola.
 *
 * <p>Existe ahora y no despues porque RF-M17-003 pide devolver "autorizadas, consumidas y
 * restantes", y sin las dos mitades no hay resta que devolver.
 *
 * <h2>2. El snapshot del convenio es una COPIA, y es opcional</h2>
 *
 * <p>Las seis columnas {@code convenio*} y {@code requeria*} son lo que el convenio exigia el dia
 * en que esta autorizacion se registro, copiadas de
 * {@code contracting.spi.ArancelDirectory#congelar}. En pasado a proposito: dicen lo que pedia
 * <b>ese dia</b>. Mismo patron que las nueve columnas congeladas de {@link CoberturaPaciente} y que
 * el precio de la obligacion en 07.01, y por el mismo motivo — cambiar el convenio manana no puede
 * reescribir con que reglas se autorizo al paciente el mes pasado.
 *
 * <p><b>Y es opcional</b>, que es la diferencia con la cobertura. {@code congelar} devuelve vacio
 * cuando no hay convenio vigente para ese plan y esa sede, o cuando lo hay pero la practica no
 * tiene arancel cargado. Ninguna de las dos cosas puede impedir registrar un numero de
 * autorizacion que el financiador ya otorgo: el mostrador no se puede quedar sin cargar un dato
 * real porque la grilla de aranceles este incompleta. Viaja entera o no viaja, nunca a medias.
 *
 * <h2>3. VENCIDA y AGOTADA se calculan, no se guardan</h2>
 *
 * <p>{@link #estado} solo toma los cuatro valores que decide una persona. Ver
 * {@link EstadoAutorizacion}: materializar el vencimiento exigiria un job, y un job que no corre
 * deja autorizaciones vencidas que el sistema cree vigentes.
 *
 * <h2>4. Lo que esta clase NO puede hacer cumplir sola</h2>
 *
 * <p>Dos autorizaciones APROBADAS de la misma cobertura y la misma practica no se pueden solapar:
 * contarian el saldo dos veces. {@link #seSolapaCon(LocalDate, LocalDate)} sabe decidirlo entre
 * dos instancias, y eso es todo lo que una entidad puede aportar. Que la regla se cumpla frente a
 * dos aprobaciones concurrentes depende del lock de {@code autorizacion_persona_lock}, tomado en
 * {@code READ_COMMITTED} y <b>antes</b> de leer nada. Ningun unique de MySQL 8.4 expresa un
 * solapamiento de intervalos.
 */
@Entity
@Table(name = "autorizacion")
public class Autorizacion extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "persona_id", nullable = false, updatable = false)
	private Long personaId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "cobertura_id", nullable = false, updatable = false)
	private Long coberturaId;

	@Column(name = "orden_medica_id")
	private Long ordenMedicaId;

	@Column(name = "practica_id", nullable = false, updatable = false)
	private Long practicaId;

	@Column(name = "numero", nullable = false, length = 64)
	private String numero;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 16)
	private EstadoAutorizacion estado = EstadoAutorizacion.PENDIENTE;

	@Column(name = "motivo", length = 280)
	private String motivo;

	@Column(name = "cantidad_autorizada")
	private Integer cantidadAutorizada;

	@Column(name = "cantidad_consumida", nullable = false)
	private int cantidadConsumida;

	@Column(name = "vigencia_desde", nullable = false)
	private LocalDate vigenciaDesde;

	@Column(name = "vigencia_hasta")
	private LocalDate vigenciaHasta;

	// --- Copia congelada del convenio. updatable = false: una copia que se puede editar no es
	// una copia, es un campo mas que alguien va a "corregir" el dia que el convenio cambie.
	@Column(name = "convenio_id", updatable = false)
	private Long convenioId;

	@Column(name = "convenio_codigo", length = 64, updatable = false)
	private String convenioCodigo;

	@Column(name = "convenio_nombre", length = 160, updatable = false)
	private String convenioNombre;

	@Column(name = "requeria_orden", updatable = false)
	private Boolean requeriaOrden;

	@Column(name = "requeria_autorizacion", updatable = false)
	private Boolean requeriaAutorizacion;

	@Column(name = "requeria_credencial", updatable = false)
	private Boolean requeriaCredencial;

	@Column(name = "referencia_capturada_el", updatable = false)
	private Instant referenciaCapturadaEl;

	@Column(name = "adjunto_id")
	private Long adjuntoId;

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

	protected Autorizacion() {
		// JPA.
	}

	/**
	 * Registra la autorizacion (RF-M17-001).
	 *
	 * <p>{@code convenio} puede ser {@code null} —no hay convenio vigente, o la practica no tiene
	 * arancel—, y en ese caso <b>ninguna</b> de las seis columnas de la copia se escribe. No se
	 * acepta un {@code convenioId} suelto: recibir el record entero es lo que hace que olvidar la
	 * copia no compile, exactamente como {@code CoberturaPaciente.financiada} en 03.04.
	 *
	 * <p>{@code estadoInicial} admite PENDIENTE —se pidio y falta respuesta— o APROBADA —el
	 * financiador ya la otorgo, tipicamente por telefono, y el mostrador la carga ya resuelta—.
	 * OBSERVADA y RECHAZADA <b>no</b> se pueden cargar de entrada: son el resultado de una
	 * respuesta a un pedido, y sin el pedido registrado no hay nada que observar ni rechazar.
	 */
	@SuppressWarnings({"java:S107", "checkstyle:ParameterNumber"})
	public Autorizacion(
			long organizationId,
			long personaId,
			long consultorioId,
			long coberturaId,
			Long ordenMedicaId,
			long practicaId,
			String numero,
			EstadoAutorizacion estadoInicial,
			Integer cantidadAutorizada,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta,
			ArancelCongelado convenio,
			String observaciones) {

		this.organizationId = organizationId;
		this.personaId = personaId;
		this.consultorioId = consultorioId;
		this.coberturaId = coberturaId;
		this.ordenMedicaId = ordenMedicaId;
		this.practicaId = practicaId;
		this.numero = exigirTexto(
				numero,
				"La autorizacion necesita el numero que devolvio el financiador: sin el no se "
						+ "puede presentar la liquidacion");
		this.estado = exigirEstadoInicial(estadoInicial);
		this.cantidadAutorizada = exigirCantidad(cantidadAutorizada);
		aplicarVigencia(vigenciaDesde, vigenciaHasta);
		copiarConvenio(convenio);
		this.observaciones = vacioEsNulo(observaciones);
	}

	// =================================================================================
	// Mutaciones
	// =================================================================================

	/**
	 * Edicion parcial de los datos NO historicos.
	 *
	 * <p>Lo que NO esta y no es un olvido: la cobertura, la practica, la sede y las seis columnas
	 * congeladas son {@code updatable = false}. Cambiar de cobertura o de practica es OTRA
	 * autorizacion, porque reescribiria contra que se autorizo al paciente. Y {@link #estado} no
	 * esta porque se mueve con {@link #resolver}, nunca por asignacion.
	 */
	public void updateDatos(
			String numero,
			Long ordenMedicaId,
			Integer cantidadAutorizada,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta,
			String observaciones) {

		if (numero != null) {
			this.numero = exigirTexto(numero, "El numero de autorizacion no puede quedar vacio");
		}
		if (ordenMedicaId != null) {
			this.ordenMedicaId = ordenMedicaId;
		}
		if (cantidadAutorizada != null) {
			this.cantidadAutorizada = exigirCantidadNoMenorAlConsumo(cantidadAutorizada);
		}
		if (vigenciaDesde != null || vigenciaHasta != null) {
			aplicarVigencia(
					vigenciaDesde == null ? this.vigenciaDesde : vigenciaDesde,
					vigenciaHasta == null ? this.vigenciaHasta : vigenciaHasta);
		}
		if (observaciones != null) {
			this.observaciones = vacioEsNulo(observaciones);
		}
	}

	/**
	 * Aplica la respuesta del financiador (RF-M17-001, casos borde "autorizacion parcial" y
	 * "renovacion").
	 *
	 * <p>Solo desde un estado que {@link EstadoAutorizacion#admiteResolucion() admita resolucion}.
	 * Quien comprueba esa precondicion y traduce el rechazo a 409 es el servicio: esta clase
	 * asume que ya se verifico, igual que el resto del dominio de este modulo.
	 *
	 * <p><b>Aprobar puede otorgar MENOS de lo pedido, y eso es la autorizacion parcial.</b> Si la
	 * accion trae cantidad o vigencia, pisan a las declaradas al cargar: lo que vale es lo que el
	 * financiador concedio, no lo que el centro pidio.
	 */
	public void resolver(
			AccionSobreAutorizacion accion,
			String motivo,
			Integer cantidadAutorizada,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta) {

		if (accion.exigeMotivo() && (motivo == null || motivo.isBlank())) {
			throw new IllegalArgumentException(
					"Observar o rechazar una autorizacion exige un motivo declarado: sin el, el "
							+ "mostrador no sabe que corregir");
		}
		if (accion == AccionSobreAutorizacion.APROBAR) {
			if (cantidadAutorizada != null) {
				this.cantidadAutorizada = exigirCantidadNoMenorAlConsumo(cantidadAutorizada);
			}
			if (vigenciaDesde != null || vigenciaHasta != null) {
				aplicarVigencia(
						vigenciaDesde == null ? this.vigenciaDesde : vigenciaDesde,
						vigenciaHasta == null ? this.vigenciaHasta : vigenciaHasta);
			}
		}
		this.estado = accion.destino();
		this.motivo = vacioEsNulo(motivo);
	}

	public void vincularDocumento(long adjuntoId) {
		this.adjuntoId = adjuntoId;
	}

	public void desvincularDocumento() {
		this.adjuntoId = null;
	}

	public void deactivate(Instant occurredAt, String reason) {
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason;
	}

	// =================================================================================
	// Preguntas
	// =================================================================================

	public Vigencia vigencia() {
		return new Vigencia(vigenciaDesde, vigenciaHasta);
	}

	/**
	 * Sesiones que quedan, o {@code null} si no hay tope declarado.
	 *
	 * <p>RF-M17-003. <b>Hoy siempre vale lo autorizado</b>, porque nadie mueve el consumo todavia:
	 * ver la cabecera de la clase.
	 */
	public Integer saldo() {
		return cantidadAutorizada == null ? null : cantidadAutorizada - cantidadConsumida;
	}

	/** Sin saldo. Una autorizacion sin tope declarado nunca esta agotada. */
	public boolean agotada() {
		Integer saldo = saldo();
		return saldo != null && saldo <= 0;
	}

	/** Vencida ese dia: tiene fin declarado y ya paso. Vencida NO es dada de baja. */
	public boolean vencidaEl(LocalDate fecha) {
		return vigenciaHasta != null && fecha != null && fecha.isAfter(vigenciaHasta);
	}

	/** Dias que faltan para el vencimiento, o {@code null} si no vence. RF-M17-006. */
	public Long diasParaVencer(LocalDate fecha) {
		return vigencia().diasHasta(fecha);
	}

	/**
	 * Habilita a atender ese dia: activa, APROBADA, vigente y con saldo.
	 *
	 * <p>Las cuatro condiciones hacen falta y ninguna sobra. Es lo que
	 * {@code ElegibilidadAdministrativaService} consulta, y <b>no consume nada</b>: preguntar si
	 * se puede atender y descontar una sesion son dos cosas distintas (RN-M17-001).
	 */
	public boolean habilitaEl(LocalDate fecha) {
		return active && estado.habilita() && vigencia().cubre(fecha) && !agotada();
	}

	/** Se solapa con ese periodo. Lo que ningun unique de MySQL puede expresar. */
	public boolean seSolapaCon(LocalDate desde, LocalDate hasta) {
		return vigencia().seSolapaCon(new Vigencia(desde, hasta));
	}

	public boolean isOperable() {
		return active;
	}

	public boolean tieneConvenioCongelado() {
		return convenioId != null;
	}

	// =================================================================================
	// Invariantes
	// =================================================================================

	private void aplicarVigencia(LocalDate desde, LocalDate hasta) {
		Vigencia validada = new Vigencia(desde, hasta);
		this.vigenciaDesde = validada.desde();
		this.vigenciaHasta = validada.hasta();
	}

	/** La copia entera, o ninguna columna. Nunca a medias: es el CHECK de V44 dicho en Java. */
	private void copiarConvenio(ArancelCongelado convenio) {
		if (convenio == null) {
			return;
		}
		this.convenioId = convenio.convenioId();
		this.convenioCodigo = convenio.convenioCodigo();
		this.convenioNombre = convenio.convenioNombre();
		this.requeriaOrden = convenio.requeriaOrden();
		this.requeriaAutorizacion = convenio.requeriaAutorizacion();
		this.requeriaCredencial = convenio.requeriaCredencial();
		this.referenciaCapturadaEl = convenio.capturadoEl();
	}

	private static EstadoAutorizacion exigirEstadoInicial(EstadoAutorizacion estado) {
		EstadoAutorizacion elegido = estado == null ? EstadoAutorizacion.PENDIENTE : estado;
		if (elegido != EstadoAutorizacion.PENDIENTE && elegido != EstadoAutorizacion.APROBADA) {
			throw new IllegalArgumentException(
					"Una autorizacion se carga PENDIENTE o APROBADA. Observarla o rechazarla es "
							+ "responder a un pedido, y sin el pedido registrado no hay nada que "
							+ "responder");
		}
		return elegido;
	}

	private static Integer exigirCantidad(Integer cantidad) {
		if (cantidad != null && cantidad <= 0) {
			throw new IllegalArgumentException(
					"La cantidad autorizada tiene que ser mayor que cero. Sin tope declarado se "
							+ "expresa omitiendola, no con cero");
		}
		return cantidad;
	}

	/**
	 * Ademas de positiva, no puede quedar por debajo de lo ya consumido.
	 *
	 * <p>Hoy el consumo es siempre cero y esto no puede dispararse. Esta escrito igual porque el
	 * dia que RF-M17-004 lo mueva, recortar una autorizacion por debajo de lo ya atendido dejaria
	 * un saldo negativo que la base rechaza con un error de constraint sin explicar nada.
	 */
	private Integer exigirCantidadNoMenorAlConsumo(Integer cantidad) {
		Integer validada = exigirCantidad(cantidad);
		if (validada != null && validada < cantidadConsumida) {
			throw new IllegalArgumentException(
					"La cantidad autorizada no puede quedar por debajo de las " + cantidadConsumida
							+ " sesiones ya consumidas");
		}
		return validada;
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

	// =================================================================================
	// Accesores
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

	public Long getConsultorioId() {
		return consultorioId;
	}

	public Long getCoberturaId() {
		return coberturaId;
	}

	public Long getOrdenMedicaId() {
		return ordenMedicaId;
	}

	public Long getPracticaId() {
		return practicaId;
	}

	public String getNumero() {
		return numero;
	}

	public EstadoAutorizacion getEstado() {
		return estado;
	}

	public String getMotivo() {
		return motivo;
	}

	public Integer getCantidadAutorizada() {
		return cantidadAutorizada;
	}

	public int getCantidadConsumida() {
		return cantidadConsumida;
	}

	public LocalDate getVigenciaDesde() {
		return vigenciaDesde;
	}

	public LocalDate getVigenciaHasta() {
		return vigenciaHasta;
	}

	public Long getConvenioId() {
		return convenioId;
	}

	public String getConvenioCodigo() {
		return convenioCodigo;
	}

	public String getConvenioNombre() {
		return convenioNombre;
	}

	public Boolean getRequeriaOrden() {
		return requeriaOrden;
	}

	public Boolean getRequeriaAutorizacion() {
		return requeriaAutorizacion;
	}

	public Boolean getRequeriaCredencial() {
		return requeriaCredencial;
	}

	public Instant getReferenciaCapturadaEl() {
		return referenciaCapturadaEl;
	}

	public Long getAdjuntoId() {
		return adjuntoId;
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
