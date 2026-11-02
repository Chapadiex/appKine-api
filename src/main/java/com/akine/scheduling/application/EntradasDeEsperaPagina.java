package com.akine.scheduling.application;

import java.util.List;

public record EntradasDeEsperaPagina(List<EntradaDeEsperaView> entradas, long total) {
}
