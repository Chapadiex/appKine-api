package com.akine.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Identidad unica de una persona en AKINE (RF-M02-001, ADR-0009).
 *
 * <p>Es GLOBAL: no tiene {@code organization_id} y no lo va a tener. La misma cuenta trabaja
 * en N organizaciones; el vinculo con cada una es la {@code membership}, que es de
 * {@code organization}. Por eso aca tampoco hay rol: el rol es contextual (RN-M02-002) y
 * guardarlo en la cuenta significaria que una persona tiene el mismo rol en todos lados.
 *
 * <p>{@code passwordHash} es Argon2id en formato PHC y <b>no sale del modulo</b>: no hay
 * getter que lo devuelva hacia afuera de {@code identity}, no viaja en ningun DTO y no se
 * loguea. La verificacion se hace pasandolo al hasher, nunca comparando strings.
 */
@Entity
@Table(name = "cuenta")
// @DynamicUpdate porque `ultimo_login_en` e `intentos_fallidos` los mueve el login con UPDATE
// NATIVOS que no tocan `version` (CuentaRepositoryPort#registrarLoginExitoso). Sin esto, el flush
// de cualquier edicion de la cuenta —un bloqueo, una activacion, un cambio de contrasena— reescribe
// TODAS las columnas con lo que leyo antes del login, el `WHERE version = N` pasa igual, y la marca
// de login vuelve atras sin que nada falle. Es el mismo mecanismo que `Autorizacion`.
@org.hibernate.annotations.DynamicUpdate
public class Cuenta {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "email", nullable = false, length = 320)
	private String email;

	/**
	 * Clave de identidad. Sin setter: el unico metodo que la cambia es
	 * {@link #prepararBootstrapDePlataforma(String)} (DP-14), sobre una cuenta que nunca tuvo
	 * credencial. {@code uk_cuenta_email_normalizado} sigue garantizando "una persona, una cuenta".
	 */
	@Column(name = "email_normalizado", nullable = false, length = 320)
	private String emailNormalizado;

	@Column(name = "nombre", nullable = false, length = 120)
	private String nombre;

	@Column(name = "apellido", nullable = false, length = 120)
	private String apellido;

	@Column(name = "password_hash", length = 255)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 30)
	private EstadoCuenta estado;

	@Column(name = "intentos_fallidos", nullable = false)
	private int intentosFallidos;

	@Column(name = "ultimo_login_en")
	private Instant ultimoLoginEn;

	@Column(name = "bloqueada_en")
	private Instant bloqueadaEn;

	@Column(name = "bloqueada_motivo", length = 300)
	private String bloqueadaMotivo;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Version
	@Column(name = "version", nullable = false)
	private Long version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Cuenta() {
		// Requerido por JPA.
	}

	/**
	 * Alta de una cuenta. Nace siempre en {@link AccountStateMachine#ESTADO_INICIAL}: tanto
	 * el alta self-service como la invitacion exigen confirmar el correo antes de habilitar
	 * el acceso, asi que no hay un segundo constructor que permita nacer ACTIVA.
	 *
	 * @param passwordHash hash Argon2id ya calculado, o {@code null} para una cuenta invitada
	 *                     que fijara su credencial al activarse. <b>Jamas la contrasena en
	 *                     claro</b>: este constructor no hashea nada
	 */
	public Cuenta(String email, String nombre, String apellido, String passwordHash) {
		this.email = email == null ? null : email.strip();
		this.emailNormalizado = EmailNormalizado.of(email);
		this.nombre = nombre;
		this.apellido = apellido;
		this.passwordHash = passwordHash;
		this.estado = AccountStateMachine.ESTADO_INICIAL;
		this.intentosFallidos = 0;
		this.active = true;
	}

	@PrePersist
	void alInsertar() {
		Instant ahora = Instant.now();
		if (createdAt == null) {
			createdAt = ahora;
		}
		updatedAt = ahora;
	}

	@PreUpdate
	void alActualizar() {
		updatedAt = Instant.now();
	}

	/**
	 * Aplica una transicion de estado validandola contra {@link AccountStateMachine}.
	 *
	 * <p>Es el UNICO camino para cambiar el estado: no hay {@code setEstado}. Si lo hubiera,
	 * cualquier servicio podria saltearse la tabla de transiciones y dejar una cuenta
	 * desactivada de vuelta en activa sin que nadie lo note.
	 *
	 * @param motivo obligatorio cuando la transicion lo exige (bloqueo, desactivacion)
	 * @throws com.akine.identity.domain.exception.InvalidAccountTransitionException si la
	 *         transicion no esta permitida
	 * @throws IllegalArgumentException si falta el motivo que la transicion exige
	 */
	public void transicionarA(EstadoCuenta nuevo, String motivo, Instant ahora) {
		AccountStateMachine.assertTransitionAllowed(estado, nuevo);
		if (AccountStateMachine.requiresReason(nuevo) && (motivo == null || motivo.isBlank())) {
			throw new IllegalArgumentException(
					"La transicion a " + nuevo + " exige un motivo declarado por el actor");
		}

		this.estado = nuevo;
		switch (nuevo) {
			case BLOQUEADA -> {
				this.bloqueadaEn = ahora;
				this.bloqueadaMotivo = motivo;
			}
			case ACTIVA -> {
				// Desbloquear limpia la marca: dejarla haria creer que sigue suspendida.
				this.bloqueadaEn = null;
				this.bloqueadaMotivo = null;
				this.intentosFallidos = 0;
			}
			case DESACTIVADA -> {
				// Baja logica (ADR-0004): la fila se conserva para que los historicos que la
				// referencian sigan siendo legibles (RN-M02-004).
				this.active = false;
				this.deletedAt = ahora;
				this.bloqueadaMotivo = motivo;
			}
			default -> {
				// PENDIENTE_ACTIVACION no es destino de ninguna transicion: solo alta.
			}
		}
	}

	/**
	 * Fija la credencial. La recibe ya hasheada: esta clase no conoce el algoritmo y no
	 * puede convertirse en el lugar donde alguien guarde una contrasena en claro por error.
	 */
	public void fijarPasswordHash(String nuevoHash) {
		if (nuevoHash == null || nuevoHash.isBlank()) {
			throw new IllegalArgumentException("El hash de la credencial no puede estar vacio");
		}
		this.passwordHash = nuevoHash;
		this.intentosFallidos = 0;
	}

	/**
	 * Re-apunta la cuenta sembrada por {@code V15} a la casilla del operador y la deja esperando
	 * su enlace de activacion (DP-14, AKINE-A-4).
	 *
	 * <p><b>Es la unica excepcion a "nadie vuelve a PENDIENTE_ACTIVACION"</b>, y por eso no pasa
	 * por {@link AccountStateMachine}: {@code V15} sembro la cuenta {@code ACTIVA} y sin
	 * credencial, un estado que el alta nunca produce, y {@code activar} exige
	 * {@code PENDIENTE_ACTIVACION}. Queda acotada a lo que la hace inofensiva:
	 * <ul>
	 *   <li>la cuenta <b>no tiene credencial</b>, o sea que nunca pudo entrar: no se le quita el
	 *       acceso a nadie;</li>
	 *   <li>esta viva y en {@code ACTIVA} o {@code PENDIENTE_ACTIVACION}: una cuenta bloqueada o
	 *       desactivada lo esta por decision de una persona, y un arranque no la revierte.</li>
	 * </ul>
	 *
	 * @throws IllegalStateException si la cuenta tiene credencial o no esta en un estado admitido
	 * @throws IllegalArgumentException si el email esta vacio
	 */
	public void prepararBootstrapDePlataforma(String nuevoEmail) {
		if (passwordHash != null) {
			throw new IllegalStateException(
					"El bootstrap de plataforma solo opera sobre una cuenta sin credencial");
		}
		if (!admiteBootstrapDePlataforma()) {
			throw new IllegalStateException(
					"El bootstrap de plataforma no opera sobre una cuenta en estado " + estado);
		}
		String normalizado = EmailNormalizado.of(nuevoEmail);
		this.email = nuevoEmail.strip();
		this.emailNormalizado = normalizado;
		this.estado = EstadoCuenta.PENDIENTE_ACTIVACION;
	}

	/**
	 * Indica si la cuenta puede ser destino del bootstrap de plataforma: viva, sin credencial y
	 * en {@code ACTIVA} o {@code PENDIENTE_ACTIVACION}.
	 */
	public boolean admiteBootstrapDePlataforma() {
		return passwordHash == null && active
				&& (estado == EstadoCuenta.ACTIVA || estado == EstadoCuenta.PENDIENTE_ACTIVACION);
	}

	/**
	 * Registra un login exitoso: limpia el contador y deja la marca temporal.
	 *
	 * <p><b>El login NO usa este metodo ni {@link #registrarLoginFallido()}</b>: persistirlos por
	 * la entidad movia {@code version} y hacia chocar dos logins simultaneos de la misma cuenta
	 * (deadlock o 409). El login va por {@code CuentaRepositoryPort#registrarLoginExitoso} y
	 * {@code #registrarLoginFallido}. Estos quedan como la regla del dominio en memoria.
	 */
	public void registrarLoginExitoso(Instant ahora) {
		this.intentosFallidos = 0;
		this.ultimoLoginEn = ahora;
	}

	/**
	 * Registra un intento fallido.
	 *
	 * <p>El contador es observabilidad, NO lockout: bloquear una cuenta por intentos
	 * fallidos le regala a cualquiera la posibilidad de dejar afuera al administrador de un
	 * centro escribiendo mal su contrasena diez veces. Contra la fuerza bruta protege el
	 * rate limit; este numero solo alimenta la alerta de actividad sospechosa.
	 *
	 * @return la cantidad de fallos consecutivos ya acumulados
	 */
	public int registrarLoginFallido() {
		this.intentosFallidos++;
		return this.intentosFallidos;
	}

	/** Indica si la cuenta puede autenticarse ahora mismo. */
	public boolean puedeAutenticarse() {
		return estado.permiteLogin() && active && passwordHash != null;
	}

	public Long getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getEmailNormalizado() {
		return emailNormalizado;
	}

	public String getNombre() {
		return nombre;
	}

	public String getApellido() {
		return apellido;
	}

	/**
	 * Hash de la credencial. Solo para pasarselo al verificador dentro de {@code identity}:
	 * no puede viajar a un DTO, a un log ni a otro modulo.
	 */
	public String getPasswordHash() {
		return passwordHash;
	}

	public EstadoCuenta getEstado() {
		return estado;
	}

	public int getIntentosFallidos() {
		return intentosFallidos;
	}

	public Instant getUltimoLoginEn() {
		return ultimoLoginEn;
	}

	public Instant getBloqueadaEn() {
		return bloqueadaEn;
	}

	public String getBloqueadaMotivo() {
		return bloqueadaMotivo;
	}

	public boolean isActive() {
		return active;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
