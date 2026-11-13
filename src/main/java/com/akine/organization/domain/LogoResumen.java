package com.akine.organization.domain;

/** Lo que se sabe de un logo sin leer su contenido. */
public interface LogoResumen {

	LogoVariante getVariante();

	String getContentType();

	String getSha256();
}
