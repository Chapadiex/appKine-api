/**
 * Puertos de persistencia del modulo {@code organization}.
 *
 * <p>Son interfaces PLANAS: sin Spring Data, sin {@code @Repository}, sin anotaciones de
 * infraestructura. Declaran las preguntas que el negocio necesita hacerle a la base, con los
 * tipos del dominio y nada mas.
 *
 * <p><b>Por que existen.</b> La direccion de las dependencias del monolito es
 * {@code infrastructure -> application/domain} (AGENT.md seccion 4), y ArchUnit la hace
 * cumplir: {@code whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()}. Si la capa
 * {@code application} inyectara directamente una interfaz de Spring Data —que vive en
 * {@code infrastructure}— la flecha apuntaria al reves y el negocio quedaria atado al
 * proveedor de persistencia.
 *
 * <p><b>Como se implementan.</b> La interfaz de Spring Data que ya vive en
 * {@code organization.infrastructure} extiende el puerto. No hay clase adaptadora en el medio:
 * Spring Data deriva los metodos del puerto por nombre igual que si estuvieran declarados en
 * ella. {@code application} inyecta el puerto y nunca ve la implementacion.
 *
 * <p>Los puertos pueden vivir en {@code domain} sin violar
 * {@code repositorios_solo_en_infrastructure} porque esa regla mira lo anotado con
 * {@code @Repository} o asignable a {@code org.springframework.data.repository.Repository}, y
 * una interfaz plana no es ninguna de las dos cosas.
 */
package com.akine.organization.domain.port;
