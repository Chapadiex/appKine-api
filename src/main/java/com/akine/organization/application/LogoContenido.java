package com.akine.organization.application;

/** Un logo con su contenido, para servirlo. */
public record LogoContenido(String contentType, byte[] contenido, String sha256) {
}
