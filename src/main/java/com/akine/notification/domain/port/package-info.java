/**
 * Puertos de {@code notification}: persistencia y canal de salida.
 *
 * <p>Son interfaces PLANAS: sin Spring Data, sin {@code @Repository}, sin anotaciones de
 * infraestructura. Declaran lo que el negocio necesita —guardar, reclamar un lote, enviar un
 * mail— con tipos del dominio y nada mas.
 *
 * <p><b>Por que existen.</b> ArchUnit exige
 * {@code whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()}. Si {@code application}
 * inyectara la interfaz de Spring Data —que vive en {@code infrastructure}— la flecha
 * apuntaria al reves. La interfaz de Spring Data extiende el puerto y Spring deriva los
 * metodos igual que si estuvieran declarados en ella; no hay adaptador en el medio.
 *
 * <p>Estos puertos no violan {@code repositorios_solo_en_infrastructure} porque esa regla mira
 * lo anotado con {@code @Repository} o asignable a {@code Repository}, y una interfaz plana no
 * es ninguna de las dos cosas.
 */
package com.akine.notification.domain.port;
