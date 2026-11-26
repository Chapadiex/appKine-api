package com.akine.organization.domain;

import java.time.Instant;

/** Una aceptacion del consentimiento de IA: quien, cuando y sobre que version del texto. */
public record ConsentimientoIa(long organizationId, String version, long accountId, String nombre, Instant aceptadoAt) {
}
