package com.akine.activity.application;

/**
 * La inscripcion cancelada y, si la hubo, la que entro en su lugar.
 *
 * <p><b>La promocion viaja en la respuesta a proposito.</b> Quien dio la baja en el mostrador es
 * quien tiene delante el telefono de la persona que acaba de entrar, y descubrirlo recargando la
 * lista es descubrirlo tarde. El aviso automatico sale igual por el outbox; esto es para el humano
 * que esta atendiendo.
 *
 * @param promovida {@code null} si no habia nadie esperando, o si el lugar no se pudo otorgar
 */
public record ResultadoDeCancelacion(
		InscripcionView inscripcion, InscripcionView promovida, CuposView cupos) {
}
