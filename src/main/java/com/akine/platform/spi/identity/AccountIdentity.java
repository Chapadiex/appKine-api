package com.akine.platform.spi.identity;

/**
 * Nombre y direccion de una cuenta, para las pantallas que muestran personas y no numeros.
 *
 * <p>Es un record y jamas la entity {@code Cuenta}: la entity es propiedad de {@code identity} y
 * exponerla dejaria que otro modulo la modificara y la guardara, con lo que dejaria de haber un
 * propietario de la tabla.
 *
 * <p><b>Lo que no lleva, a proposito:</b> ni el hash de la credencial, ni el estado de la
 * cuenta, ni el contador de intentos fallidos. Este contrato responde "¿quien es esta cuenta?",
 * que es lo unico que necesita quien ya tiene delante una fila que la referencia. El estado de
 * una cuenta es materia de {@code identity} y se consulta por sus propios endpoints.
 *
 * @param accountId      id de la cuenta
 * @param nombreCompleto nombre y apellido, tal como se muestran
 * @param email          direccion tal como la escribio la persona; para mostrar, no para comparar
 */
public record AccountIdentity(long accountId, String nombreCompleto, String email) {
}
