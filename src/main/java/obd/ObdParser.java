package obd;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Decodificación de respuestas OBD-II (modos 01 y 03). */
public final class ObdParser {

    private ObdParser() {}

    /**
     * Extrae los bytes de datos de una respuesta, descartando cabeceras si ATH1
     * está activo. Con cabeceras ISO/KWP el formato por línea es:
     * {@code 86 F1 11 41 00 BE 3E B8 11 CS} — 3 bytes de cabecera y 1 de checksum.
     * Sin cabeceras: {@code 41 00 BE 3E B8 11}.
     */
    public static List<Integer> bytesDeDatos(String respuesta, String prefijoEsperado) {
        List<Integer> datos = new ArrayList<>();
        for (String linea : respuesta.split("\n")) {
            String[] tokens = linea.trim().split("\\s+");
            List<Integer> bytes = new ArrayList<>();
            for (String t : tokens) {
                if (t.length() == 2 && t.matches("[0-9A-Fa-f]{2}")) {
                    bytes.add(Integer.parseInt(t, 16));
                } else {
                    bytes.clear();
                    break; // línea de texto (BUS INIT, SEARCHING...), no de datos
                }
            }
            if (bytes.isEmpty()) continue;
            int inicio = indicePrefijo(bytes, prefijoEsperado);
            if (inicio < 0) continue;
            // Si había cabecera delante del prefijo, también hay checksum al final.
            int fin = inicio > 0 ? bytes.size() - 1 : bytes.size();
            datos.addAll(bytes.subList(inicio, fin));
        }
        return datos;
    }

    private static int indicePrefijo(List<Integer> bytes, String prefijoEsperado) {
        int p = Integer.parseInt(prefijoEsperado, 16);
        for (int i = 0; i < bytes.size(); i++) {
            if (bytes.get(i) == p) return i;
        }
        return -1;
    }

    /** Decodifica los pares de bytes de una respuesta al modo 03 en códigos P/C/B/U. */
    public static List<String> decodificarDtc(String respuesta) {
        List<Integer> datos = bytesDeDatos(respuesta, "43");
        List<String> codigos = new ArrayList<>();
        // datos = [43, A1, B1, A2, B2, A3, B3, ...] posiblemente repetido por líneas
        int i = 0;
        while (i < datos.size()) {
            if (datos.get(i) == 0x43) { i++; continue; }
            if (i + 1 >= datos.size()) break;
            int a = datos.get(i), b = datos.get(i + 1);
            i += 2;
            if (a == 0 && b == 0) continue; // relleno
            char letra = switch (a >> 6) {
                case 0 -> 'P';
                case 1 -> 'C';
                case 2 -> 'B';
                default -> 'U';
            };
            codigos.add(String.format("%c%02X%02X", letra, a & 0x3F, b));
        }
        return codigos;
    }

    /** PIDs básicos de datos en vivo con su fórmula de conversión. */
    public static Map<String, String> pidsBasicos() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("0105", "Temperatura refrigerante");
        m.put("010C", "Régimen motor (rpm)");
        m.put("010D", "Velocidad (km/h)");
        m.put("010B", "Presión colector admisión (kPa)");
        m.put("0104", "Carga motor (%)");
        return m;
    }

    public static String interpretarPid(String pid, List<Integer> datos) {
        // datos empieza por [41, PID, A, B?]
        if (datos.size() < 3) return "(sin datos)";
        int a = datos.get(2);
        int b = datos.size() > 3 ? datos.get(3) : 0;
        return switch (pid) {
            case "0105" -> (a - 40) + " °C";
            case "010C" -> String.format("%.0f rpm", (a * 256 + b) / 4.0);
            case "010D" -> a + " km/h";
            case "010B" -> a + " kPa";
            case "0104" -> String.format("%.1f %%", a * 100.0 / 255);
            default -> datos.toString();
        };
    }
}
