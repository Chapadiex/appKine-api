package com.akine.clinical.domain;

import com.akine.clinical.domain.exception.PlanNoEditableException;
import com.akine.clinical.domain.exception.TransicionDePlanInvalidaException;
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
 * El Plan de Tratamiento de un Caso Clinico: identidad, estado y numeracion (M11, RF-M11-001).
 *
 * <h2>Plan != Turno != Sesion</h2>
 *
 * <p>Es la regla maestra 2 y esta clase es donde se hace verdad, por lo que <b>no</b> tiene:
 *
 * <ul>
 *   <li><b>No tiene contador de sesiones realizadas ni canceladas.</b> No en esta clase y no en
 *       {@link PlanItem}: la columna no existe en el esquema. El avance se <b>deriva</b> contando
 *       sesiones cerradas del Caso por oferta, a traves de
 *       {@code clinical.spi.RealizadoEnElCasoProbe}. El dueño de ese dato es {@code encounter}, y
 *       una columna de {@code clinical} que solo otro modulo puede mantener correcta es la forma
 *       habitual en que un contador se desincroniza (challenge seccion 1).</li>
 *   <li><b>No crea turnos ni series.</b> La version propone una frecuencia semanal y una duracion
 *       estimada, que es una <b>regla de recurrencia sugerida</b> y nada mas. Agendar es de
 *       {@code scheduling}, y este modulo no lo importa ni por el {@code spi}.</li>
 *   <li><b>No se cierra solo al completar la cantidad estimada</b> (RN-M11-004). El sistema avisa;
 *       decide el profesional.</li>
 * </ul>
 *
 * <h2>Lo que si vive aca: el contador de versiones</h2>
 *
 * <p>{@link #ultimoNumeroVersion} numera {@link PlanTratamientoVersion}, igual que
 * {@code entrada_clinica.ultimo_numero_version} en 04.02, y por el mismo motivo: dos
 * {@code MAX(numero_version) + 1} simultaneos devuelven el mismo numero y despues hay dos
 * "version 3" sin criterio de desempate.
 *
 * <p>Y eso tiene una consecuencia que hay que dejar escrita porque ya se pago: <b>modificar el plan
 * ensucia esta fila</b>, asi que el {@code UPDATE ... WHERE version = N} que emite JPA ya serializa
 * dos modificaciones concurrentes. <b>No lleva {@code OPTIMISTIC_FORCE_INCREMENT}</b>: forzarlo la
 * haria avanzar dos veces y la respuesta devolveria {@code leida + 1}, o sea un 409 del que el
 * cliente no puede salir. La regla que quedo de 04.02: <b>force-increment solo donde la escritura
 * no toca ninguna columna del padre.</b>
 *
 * <h2>Un solo plan ACTIVO por Caso</h2>
 *
 * <p>Lo hace cumplir {@code uk_plan_activo_por_caso} sobre la columna generada {@code activo_key},
 * no esta clase: el Caso <b>es</b> el problema terapeutico, y dos planes activos para el mismo
 * problema significan que en realidad son dos problemas. Activar un plan nuevo <b>finaliza el
 * anterior en la misma transaccion</b>, asi que no hay ventana en la que existan dos; y si dos
 * activaciones llegan juntas, la segunda choca contra el unique y recibe 409.
 *
 * <p><b>No tiene {@code active} ni baja logica.</b> Un plan no se borra: se finaliza, con motivo.
 * Misma decision, y mismo argumento, que {@link CasoClinico}.
 */
@Entity
@Table(name = "plan_tratamiento")
public class PlanTratamiento extends MarcaTemporal {

	/** Largo del motivo de suspension y de finalizacion, tal como los declara {@code V49}. */
	public static final int MOTIVO_MAXIMO = 500;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	/**
	 * Caso del que cuelga el plan (RN-M11-002).
	 *
	 * <p>Del <b>Caso</b> y no de la Historia: la historia es el contexto longitudinal del paciente
	 * y un plan que colgara de ella no sabria que problema esta tratando. {@code Long} y no
	 * {@code @ManyToOne} por el mismo criterio que el resto del modulo.
	 */
	@Column(name = "caso_clinico_id", nullable = false, updatable = false)
	private Long casoClinicoId;

	/**
	 * Correlativo dentro del Caso. Lo asigna {@code plan_numerador} con
	 * {@code UPDATE ultimo_numero + 1}, <b>nunca</b> con {@code MAX + 1}.
	 *
	 * <p>Ademas es el discriminador de {@code activo_key}: el plan ACTIVO vale 0 ahi y los demas su
	 * propio numero. Por eso es inmutable — cambiarlo desarmaria el unique de plan activo.
	 */
	@Column(name = "numero_plan", nullable = false, updatable = false)
	private Integer numeroPlan;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 16)
	private EstadoPlan estado;

	@Column(name = "ultimo_numero_version", nullable = false)
	private int ultimoNumeroVersion;

	@Column(name = "creado_en", nullable = false, updatable = false)
	private Instant creadoEn;

	@Column(name = "creado_por", nullable = false, updatable = false)
	private Long creadoPor;

	/** Instante de la PRIMERA activacion. No se limpia: un plan que estuvo vigente lo estuvo. */
	@Column(name = "activado_en")
	private Instant activadoEn;

	@Column(name = "activado_por")
	private Long activadoPor;

	@Column(name = "suspendido_en")
	private Instant suspendidoEn;

	@Column(name = "motivo_suspension", length = MOTIVO_MAXIMO)
	private String motivoSuspension;

	@Column(name = "finalizado_en")
	private Instant finalizadoEn;

	@Column(name = "finalizado_por")
	private Long finalizadoPor;

	@Column(name = "motivo_finalizacion", length = MOTIVO_MAXIMO)
	private String motivoFinalizacion;

	/**
	 * Bloqueo optimista.
	 *
	 * <p>Dos profesionales del equipo modificando el mismo plan son el caso normal, no el raro. Sin
	 * esto, el segundo pisa al primero en silencio — y con el contador de versiones viviendo en
	 * esta fila, ademas numerarian igual.
	 */
	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected PlanTratamiento() {
		// Requerido por JPA.
	}

	public PlanTratamiento(
			Long organizationId,
			Long casoClinicoId,
			int numeroPlan,
			Instant creadoEn,
			Long creadoPor) {

		this.organizationId = exigirNoNulo(organizationId, "El plan pertenece a una organizacion");
		this.casoClinicoId =
				exigirNoNulo(casoClinicoId, "El plan cuelga siempre de un caso clinico");
		if (numeroPlan <= 0) {
			throw new IllegalArgumentException("El correlativo del plan es positivo");
		}
		this.numeroPlan = numeroPlan;
		this.estado = EstadoPlan.BORRADOR;
		this.ultimoNumeroVersion = 1;
		this.creadoEn = exigirNoNulo(creadoEn, "La creacion registra siempre su instante");
		this.creadoPor = exigirNoNulo(creadoPor, "La creacion registra siempre a su actor");
	}

	/**
	 * Reserva el numero de la proxima version y lo deja anotado en esta fila.
	 *
	 * <p>Sale del contador y <b>nunca</b> de un {@code MAX(numero_version) + 1}. Ensucia la
	 * cabecera a proposito: es lo que hace que el {@code UPDATE} versionado de JPA serialice dos
	 * modificaciones concurrentes sin necesidad de forzar el incremento.
	 */
	public int siguienteNumeroDeVersion() {
		exigirEditable();
		this.ultimoNumeroVersion = ultimoNumeroVersion + 1;
		return ultimoNumeroVersion;
	}

	/**
	 * Pasa el plan a ACTIVO (RF-M11-006).
	 *
	 * <p>Solo desde BORRADOR: un plan FINALIZADO no se reabre —se crea uno nuevo— y un SUSPENDIDO
	 * se <b>reanuda</b>, que es otra operacion con otro evento. Reactivar lo ya activo es el mismo
	 * pedido y no un conflicto: devuelve {@code false} y el servicio responde 200.
	 *
	 * @return {@code true} si este llamado produjo la activacion
	 */
	public boolean activar(Instant occurredAt, Long actorAccountId) {
		if (estado == EstadoPlan.ACTIVO) {
			return false;
		}
		if (estado != EstadoPlan.BORRADOR) {
			throw new TransicionDePlanInvalidaException(id, estado, "activar");
		}
		this.estado = EstadoPlan.ACTIVO;
		this.activadoEn = exigirNoNulo(occurredAt, "La activacion registra siempre su instante");
		this.activadoPor = exigirNoNulo(actorAccountId, "La activacion registra siempre a su actor");
		return true;
	}

	/**
	 * Discontinua el tratamiento sin cerrarlo (RF-M11-006).
	 *
	 * <p><b>El motivo lo exige la entidad y no el DTO</b>, por lo mismo que el cierre de un caso:
	 * que la regla viva aca significa que ningun camino de escritura —ni uno futuro que no pase por
	 * el controller— puede frenar un tratamiento sin explicar por que.
	 *
	 * @return {@code true} si este llamado produjo la suspension; {@code false} si ya estaba
	 *         suspendido, en cuyo caso el motivo original queda intacto
	 */
	public boolean suspender(String motivo, Instant occurredAt) {
		String limpio = exigirTexto(motivo, "La suspension de un plan exige un motivo");
		if (estado == EstadoPlan.SUSPENDIDO) {
			return false;
		}
		if (estado != EstadoPlan.ACTIVO) {
			throw new TransicionDePlanInvalidaException(id, estado, "suspender");
		}
		this.estado = EstadoPlan.SUSPENDIDO;
		this.suspendidoEn = exigirNoNulo(occurredAt, "La suspension registra siempre su instante");
		this.motivoSuspension = limpio;
		return true;
	}

	/**
	 * Retoma un tratamiento suspendido (RF-M11-006).
	 *
	 * <p>Las dos columnas de la suspension se limpian porque el CHECK
	 * {@code ck_plan_tratamiento_suspension} no admite un plan ACTIVO con instante de suspension, y
	 * porque una consulta por {@code suspendido_en IS NOT NULL} devolveria planes vigentes. <b>No
	 * se pierde nada</b>: la suspension anterior, con su motivo, esta en {@code plan_evento}, que es
	 * append-only.
	 *
	 * @return {@code true} si este llamado produjo la reanudacion
	 */
	public boolean reanudar(Instant occurredAt) {
		if (estado == EstadoPlan.ACTIVO) {
			return false;
		}
		if (estado != EstadoPlan.SUSPENDIDO) {
			throw new TransicionDePlanInvalidaException(id, estado, "reanudar");
		}
		this.estado = EstadoPlan.ACTIVO;
		this.suspendidoEn = null;
		this.motivoSuspension = null;
		if (activadoEn == null) {
			// Defensa del CHECK de activacion: un SUSPENDIDO siempre tuvo activacion, asi que
			// llegar aca sin ella significaria una fila escrita por fuera de esta clase.
			this.activadoEn = exigirNoNulo(occurredAt, "La reanudacion registra su instante");
		}
		return true;
	}

	/**
	 * Termina el plan, con motivo (RF-M11-006).
	 *
	 * <p>Es terminal: no se reabre. Tambien la llama la <b>activacion de otro plan</b> del mismo
	 * Caso, con el motivo que lo explica, porque un solo plan puede estar activo a la vez.
	 *
	 * <p><b>Finalizar dos veces no es un conflicto</b>: es el mismo pedido, y el motivo original
	 * queda intacto. Pisarlo con el nuevo perderia el que explica el final — mismo criterio que el
	 * cierre de un caso.
	 *
	 * @return {@code true} si este llamado produjo la finalizacion
	 */
	public boolean finalizar(String motivo, Instant occurredAt, Long actorAccountId) {
		String limpio = exigirTexto(motivo, "La finalizacion de un plan exige un motivo");
		if (estado == EstadoPlan.FINALIZADO) {
			return false;
		}
		this.estado = EstadoPlan.FINALIZADO;
		this.suspendidoEn = null;
		this.motivoSuspension = null;
		this.finalizadoEn = exigirNoNulo(occurredAt, "La finalizacion registra siempre su instante");
		this.finalizadoPor =
				exigirNoNulo(actorAccountId, "La finalizacion registra siempre a su actor");
		this.motivoFinalizacion = limpio;
		return true;
	}

	/** {@code true} mientras el plan sea el vigente del Caso. */
	public boolean estaActivo() {
		return estado == EstadoPlan.ACTIVO;
	}

	/** {@code true} si el contenido se edita en el lugar, sin crear version (RF-M11-004). */
	public boolean esBorrador() {
		return estado == EstadoPlan.BORRADOR;
	}

	/**
	 * Exige que el plan admita cambios de contenido.
	 *
	 * <p>Es <b>409 y no 403</b>: quien opera tiene el permiso, y lo que no admite la operacion es el
	 * estado. Un 403 lo mandaria a pedirle a su administrador un permiso que ya tiene.
	 */
	public void exigirEditable() {
		if (!estado.admiteCambios()) {
			throw new PlanNoEditableException(id, estado.name());
		}
	}

	/** {@code true} si el plan pertenece a ese caso. Otro caso se trata como inexistente. */
	public boolean perteneceACaso(long casoClinicoId) {
		return this.casoClinicoId != null && this.casoClinicoId == casoClinicoId;
	}

	private static <T> T exigirNoNulo(T valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
	}

	private static String exigirTexto(String valor, String mensaje) {
		String limpio = valor == null || valor.isBlank() ? null : valor.strip();
		if (limpio == null) {
			throw new IllegalArgumentException(mensaje);
		}
		if (limpio.length() > MOTIVO_MAXIMO) {
			throw new IllegalArgumentException(
					"El motivo no puede superar los " + MOTIVO_MAXIMO + " caracteres");
		}
		return limpio;
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getCasoClinicoId() {
		return casoClinicoId;
	}

	public Integer getNumeroPlan() {
		return numeroPlan;
	}

	public EstadoPlan getEstado() {
		return estado;
	}

	public int getUltimoNumeroVersion() {
		return ultimoNumeroVersion;
	}

	public Instant getCreadoEn() {
		return creadoEn;
	}

	public Long getCreadoPor() {
		return creadoPor;
	}

	public Instant getActivadoEn() {
		return activadoEn;
	}

	public Long getActivadoPor() {
		return activadoPor;
	}

	public Instant getSuspendidoEn() {
		return suspendidoEn;
	}

	public String getMotivoSuspension() {
		return motivoSuspension;
	}

	public Instant getFinalizadoEn() {
		return finalizadoEn;
	}

	public Long getFinalizadoPor() {
		return finalizadoPor;
	}

	public String getMotivoFinalizacion() {
		return motivoFinalizacion;
	}

	public long getVersion() {
		return version;
	}
}
