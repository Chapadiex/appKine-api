package com.akine.activity.application;

/**
 * La clase, y si esta llamada fue la que la creo.
 *
 * <p>El booleano decide el codigo HTTP: <b>201 si se creo, 200 si se devolvio una existente</b> por
 * idempotencia. Sin el, un reintento contestaria 201 sobre algo que ya existia y el cliente no
 * podria distinguir "programe la clase" de "ya estaba".
 */
public record ResultadoDeProgramacion(ClaseView clase, boolean creada) {
}
