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
            int oilC,            // temperatura de aceite (offset 42 -40)
            boolean brake, boolean clutch, boolean ac, boolean fullLoad,
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
        int oil = at(b, 42) - 40;
        boolean brake = (at(b, 28) & 0x18) != 0;
        boolean clutch = (at(b, 28) & 0x20) != 0;
        boolean ac = (at(b, 23) & 0x20) != 0 || (at(b, 26) & 0x02) != 0;
        boolean full = (at(b, 26) & 0x80) != 0;
        return new Live(rpm, coolant, target, boostKpa, boostBar, voltage, pedal, oil,
                brake, clutch, ac, full, b);
    }

    private static int at(List<Integer> b, int i) {
        return i < b.size() ? b.get(i) : 0;
    }
}
