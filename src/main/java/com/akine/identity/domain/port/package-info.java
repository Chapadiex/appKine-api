/**
 * Puertos del modulo {@code identity}: persistencia y servicios tecnicos.
 *
 * <p>Son interfaces PLANAS: sin Spring Data, sin {@code @Repository}, sin anotaciones de
 * infraestructura. Declaran las preguntas que el negocio le hace a la base —y las capacidades
 * tecnicas que necesita— con los tipos del dominio y nada mas.
 *
 * <p><b>Por que existen.</b> La direccion de las dependencias es
 * {@code infrastructure -> application/domain}, y ArchUnit la hace cumplir
 * ({@code whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()}). Si {@code application}
 * inyectara una interfaz de Spring Data —que vive en {@code infrastructure}— la flecha
 * apuntaria al reves y el negocio quedaria atado al proveedor de persistencia.
 *
 * <p><b>Como se implementan.</b> La interfaz de Spring Data que vive en
 * {@code identity.infrastructure} extiende el puerto; Spring Data deriva los metodos por
 * nombre igual que si estuvieran declarados en ella. No hay clase adaptadora en el medio y
 * {@code application} nunca ve la implementacion. Es el mismo patron que
 * {@code organization.domain.port}.
 *
 * <p>Los puertos pueden vivir en {@code domain} sin violar
 * {@code repositorios_solo_en_infrastructure} porque esa regla mira lo anotado con
 * {@code @Repository} o asignable a {@code org.springframework.data.repository.Repository}, y
 * una interfaz plana no es ninguna de las dos cosas.
 */
package com.akine.identity.domain.port;
