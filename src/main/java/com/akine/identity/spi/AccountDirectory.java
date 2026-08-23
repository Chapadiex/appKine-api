package com.akine.identity.spi;

import java.util.Optional;

/**
 * Consulta de cuentas para otros modulos. <b>Solo lectura, sin excepciones.</b>
 *
 * <p>Lo consume 01.03 —la invitacion de colaboradores necesita saber si el email invitado ya
 * tiene cuenta y en que estado esta— y cualquier etapa posterior que necesite resolver el
 * nombre de una persona a partir de su id.
 *
 * <h2>Por que no hay ni un metodo de creacion</h2>
 *
 * <p>El diseño original proponia un {@code IdentityProvisioningSpi.provisionarCuentaInvitada}
 * para que {@code organization} creara cuentas al invitar. No existe y no puede existir: la
 * flecha {@code organization -> identity} esta prohibida sin excepciones (T-1, challenge B-2) y
 * cerraria un ciclo que {@code sin_ciclos_entre_modulos} rechaza — {@code identity} ya depende
 * de {@code organization.spi} para provisionar el tenant del alta.
 *
 * <p>La invitacion se parte en dos mitades que van en el sentido correcto: <b>invitar</b> es de
 * {@code organization} y no toca cuentas; <b>aceptar</b> es de {@code identity}, que crea o
 * vincula la cuenta y llama a {@code organization.spi} para activar la membership. Este
 * contrato le da a la primera mitad lo unico que necesita —saber si ya hay cuenta— sin darle la
 * capacidad de crear una.
 *
 * <h2>Y por que exponer la existencia por email no contradice la anti-enumeracion</h2>
 *
 * <p>El registro y el reset responden uniforme porque son endpoints <b>publicos</b>: cualquiera
 * los alcanza escribiendo una direccion. Este contrato es interno al proceso y sus consumidores
 * son modulos, no clientes HTTP. Lo que si obliga es a que ningun endpoint traduzca este
 * booleano a una respuesta distinguible: quien lo use tiene que decidir <b>que</b> hace, no
 * <b>contar</b> lo que averiguo.
 */
public interface AccountDirectory {

	/**
	 * Indica si ya existe una cuenta para ese email.
	 *
	 * <p>Compara por la forma canonica del email —minusculas y sin espacios—, que es la misma
	 * que impone {@code uk_cuenta_email_normalizado}: si comparara el texto crudo, dos escrituras
	 * de la misma direccion darian respuestas distintas.
	 *
	 * <p>Cuenta tambien las bloqueadas y las desactivadas: la pregunta es si la direccion esta
	 * tomada, y una cuenta dada de baja la sigue teniendo tomada porque su fila se conserva.
	 */
	boolean existeCuentaCon(String email);

	/** La cuenta, si existe. {@link Optional#empty()} si el id no corresponde a ninguna. */
	Optional<AccountSnapshot> cuenta(long accountId);

	/** La cuenta asociada a ese email, si existe. */
	Optional<AccountSnapshot> cuentaCon(String email);
}
