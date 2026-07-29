package obd;

import java.util.List;

/**
 * Decodifica el bloque `21 01` (payload tras 61 01, offset 0 = primer byte de
 * datos) del Y17DTL a magnitudes reales. Espejo de ObdDecoder de la app Android;
 * ambos derivan del mapa empírico en docs/mapa-21-01.md.
 */
public final class LiveDecoder {

    public record Live(
            int rpm,
            int coolantC,        // refrigerante real (offset 41 /2)
            int coolantTargetC,  // consigna termostato (offset 38 -40)
            int boostKpa,        // presión turbo (offset 50, kPa abs)
            double boostBar,     // sobrepresión relativa
            double voltage,      // tensión batería (offset 30 x0.234)
            int pedalPct,        // acelerador (offset 55)
            int intakeRaw,       // offset 42 en crudo (NTC, escala INVERSA)
            int intakeApproxC,   // ≈ °C estimado con la curva NTC de la propia ECU
            boolean brake, boolean clutch, boolean ac, boolean fullLoad,
            boolean engineRunning,   // rpm > 0
            /** ECU alimentada (llave en 2 o motor en marcha). Con la llave en 0 reporta 0 V y manda basura. */
            boolean ecuPowered,
            List<Integer> raw) {}

    private LiveDecoder() {}

    public static Live decode(List<Integer> b) {
        if (b == null || b.size() < 56) return null;
        int rpm = (at(b, 33) * 256 + at(b, 34)) / 8;
        int coolant = at(b, 41) / 2;
        int target = at(b, 38) - 40;
        int boostKpa = at(b, 50);
        double boostBar = (at(b, 50) - 100) / 100.0;
        double voltage = at(b, 30) * 0.234;
        int pedal = Math.min(100, at(b, 55) * 100 / 255);
        int intakeRaw = at(b, 42);
        int intake = ntcToCelsius(intakeRaw);
        // Con la llave en 0 la ECU pierde alimentación (0 V) y manda basura: freno y
        // embrague salen "pisados". Con la llave en 2 los datos SÍ valen aunque el
        // motor esté parado, así que se filtra por alimentación, no por rpm.
        boolean running = rpm > 0;
        boolean powered = at(b, 30) >= 20;   // ~4,7 V; con contacto real da 46-57
        boolean brake = powered && (at(b, 28) & 0x18) != 0;
        boolean clutch = powered && (at(b, 28) & 0x20) != 0;
        boolean ac = powered && ((at(b, 23) & 0x20) != 0 || (at(b, 26) & 0x02) != 0);
        boolean full = powered && (at(b, 26) & 0x80) != 0;
        return new Live(rpm, coolant, target, boostKpa, boostBar, voltage, pedal,
                intakeRaw, intake, brake, clutch, ac, full, running, powered, b);
    }

    /**
     * Curva del termistor (NTC) de la propia ECU, deducida de la pareja
     * offset 40 (crudo) ↔ offset 41 (°C linealizados) en un calentamiento
     * completo. Escala INVERSA: crudo alto = frío. Calibrada de 43 a 87 °C.
     */
    private static final int[][] NTC = {
            {49, 87}, {62, 80}, {75, 75}, {88, 71}, {101, 66}, {114, 63},
            {127, 60}, {140, 56}, {153, 52}, {166, 49}, {179, 46}, {187, 43}};

    public static int ntcToCelsius(int raw) {
        if (raw <= NTC[0][0]) return NTC[0][1];
        if (raw >= NTC[NTC.length - 1][0]) return NTC[NTC.length - 1][1];
        for (int i = 1; i < NTC.length; i++) {
            if (NTC[i][0] >= raw) {
                int r0 = NTC[i - 1][0], t0 = NTC[i - 1][1], r1 = NTC[i][0], t1 = NTC[i][1];
                return r1 == r0 ? t0 : t0 + (t1 - t0) * (raw - r0) / (r1 - r0);
            }
        }
        return NTC[NTC.length - 1][1];
    }

    private static int at(List<Integer> b, int i) {
        return i < b.size() ? b.get(i) : 0;
    }
}
