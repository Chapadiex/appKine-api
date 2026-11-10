package com.akine.knowledge.domain;

/** Los permisos que {@code knowledge} pide, como texto (el enum vive en {@code organization}). */
public final class PermissionCodes {

	/** Gestionar la biblioteca de libros de la organizacion. Solo {@code ORG_ADMIN}. */
	public static final String BIBLIOTECA_MANAGE = "biblioteca:manage";

	private PermissionCodes() {
	}
}
