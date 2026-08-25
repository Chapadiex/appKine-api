package com.akine.identity.application;

import java.time.Instant;

/**
 * Una invitacion como la ve <b>el invitado</b>, antes de decidir (RF-M05-002).
 *
 * <p>Es deliberadamente distinta de {@link InvitacionView} y no una version recortada: son dos
 * publicos con dos preguntas distintas. El administrador quiere saber quien no respondio; el
 * invitado quiere saber <b>quien lo invita, a que y que le van a pedir a continuacion</b>.
 *
 * <p><b>Que NO lleva, y por que.</b> Ni ids de sede, ni de cuenta, ni el estado interno, ni la
 * version. Quien presenta el token no pertenece a la organizacion —puede no tener ni cuenta— y
 * cada id que se le entregue es una pieza mas para adivinar el resto. Lo unico que se le dice de
 * la organizacion es su nombre, que es lo que necesita para reconocer si la invitacion tiene
 * sentido.
 *
 * @param organizacionNombre  a donde lo invitan. Sin esto el correo es indistinguible de spam
 * @param consultorioNombre   a que sede, o {@code null} si el vinculo es de toda la organizacion
 * @param roleCode            con que rol
 * @param email               a que direccion se emitio, para que confirme que es la suya
 * @param expiraEn            hasta cuando sirve el enlace
 * @param requiereRegistro    {@code true} si al aceptar hay que crear la cuenta. Es lo que le
 *                            dice a la pantalla si pedir nombre y contrasena o no
 */
public record InvitacionPreview(
		String organizacionNombre,
		String consultorioNombre,
		String roleCode,
		String email,
		Instant expiraEn,
		boolean requiereRegistro) {
}
