package io.github.eliaxs1900.corsaecuanalysis.core;

import java.util.Map;

/**
 * Descripciones de DTC contrastadas con la definición oficial SAE J2012 (los
 * códigos P estándar que emite este ECU Delphi/Isuzu — confirmado por el P0251).
 * Los códigos propios de Opel (P1xxx) se marcan como tales, sin inventar.
 * Espejo del DtcCatalog de la app Android.
 */
public final class DtcCatalog {

    private DtcCatalog() {}

    private static final Map<String, String> SAE = Map.ofEntries(
            Map.entry("P0100", "Caudalímetro (MAF): circuito"),
            Map.entry("P0101", "Caudalímetro (MAF): rango/prestaciones"),
            Map.entry("P0102", "Caudalímetro (MAF): señal baja"),
            Map.entry("P0103", "Caudalímetro (MAF): señal alta"),
            Map.entry("P0105", "Presión colector/barométrica: circuito"),
            Map.entry("P0107", "Presión colector/barométrica: señal baja"),
            Map.entry("P0108", "Presión colector/barométrica: señal alta"),
            Map.entry("P0110", "Temp. aire admisión: circuito"),
            Map.entry("P0112", "Temp. aire admisión: señal baja"),
            Map.entry("P0113", "Temp. aire admisión: señal alta"),
            Map.entry("P0115", "Temp. refrigerante: circuito"),
            Map.entry("P0117", "Temp. refrigerante: señal baja"),
            Map.entry("P0118", "Temp. refrigerante: señal alta"),
            Map.entry("P0180", "Temp. combustible A: circuito"),
            Map.entry("P0182", "Temp. combustible A: señal baja"),
            Map.entry("P0183", "Temp. combustible A: señal alta"),
            Map.entry("P0234", "Sobrepresión de turbo (overboost)"),
            Map.entry("P0235", "Sensor presión turbo A: circuito"),
            Map.entry("P0237", "Sensor presión turbo A: señal baja"),
            Map.entry("P0238", "Sensor presión turbo A: señal alta"),
            Map.entry("P0251", "Bomba inyectora, control de caudal 'A' (válvula de derrame) — ¡ojo al EDU!"),
            Map.entry("P0253", "Bomba inyectora, control de caudal 'A': señal baja"),
            Map.entry("P0254", "Bomba inyectora, control de caudal 'A': señal alta"),
            Map.entry("P0335", "Sensor de cigüeñal (CKP): circuito"),
            Map.entry("P0336", "Sensor de cigüeñal (CKP): rango/prestaciones"),
            Map.entry("P0340", "Sensor de árbol de levas (CMP): circuito"),
            Map.entry("P0380", "Circuito de calentadores (glow plugs) A"),
            Map.entry("P0381", "Testigo de calentadores: circuito"),
            Map.entry("P0400", "Recirculación de gases (EGR): caudal"),
            Map.entry("P0401", "EGR: caudal insuficiente"),
            Map.entry("P0402", "EGR: caudal excesivo"),
            Map.entry("P0403", "EGR: circuito"),
            Map.entry("P0500", "Sensor de velocidad del vehículo (VSS)"),
            Map.entry("P0560", "Tensión del sistema"),
            Map.entry("P0562", "Tensión del sistema: baja"),
            Map.entry("P0563", "Tensión del sistema: alta"),
            Map.entry("P0605", "Módulo de control: memoria ROM interna"),
            Map.entry("P0220", "Sensor de pedal/acelerador B: circuito"),
            Map.entry("P0225", "Sensor de pedal/acelerador C: circuito"));

    public static String describe(String code) {
        String d = SAE.get(code);
        if (d != null) return d;
        if (code.startsWith("P1")) return "Código específico Opel (P1xxx) — requiere TIS 2000";
        if (code.startsWith("P0")) return "Código genérico SAE no catalogado";
        return "Código de fabricante";
    }
}
