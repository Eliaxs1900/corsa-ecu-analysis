package obd;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sesión KWP2000 (ISO 14230) con direccionamiento físico a la ECU del motor
 * de un Opel Corsa C (dirección 0x11, tester 0xF1). Validado contra el coche:
 * solo funciona el init rápido (ATFI); el lento a 5 baudios no responde.
 *
 * El byte de formato de la cabecera lleva la longitud de los datos
 * (0x80 | n), así que hay que reajustar ATSH en cada petición.
 */
public final class Kwp {

    public static final int ECU_MOTOR = 0x11;
    public static final int TESTER = 0xF1;

    private final int ecu;

    private static final Map<Integer, String> ERRORES_NEGATIVOS = Map.of(
            0x10, "rechazo general",
            0x11, "servicio no soportado",
            0x12, "subfunción no soportada",
            0x21, "ECU ocupada, repite",
            0x22, "condiciones no válidas (¿motor en marcha?)",
            0x33, "acceso de seguridad requerido",
            0x78, "respuesta pendiente (la ECU necesita más tiempo)");

    private final Elm327 elm;
    private boolean inicializado;
    private String ultimaCabecera;

    public Kwp(Elm327 elm) {
        this(elm, ECU_MOTOR);
    }

    public Kwp(Elm327 elm, int ecu) {
        this.elm = elm;
        this.ecu = ecu;
    }

    /** Init rápido KWP2000. El ELM327 mantiene la sesión viva con TesterPresent periódicos. */
    public void init() throws IOException {
        elm.send("ATSP 5");
        elm.send("ATH1");
        elm.send(String.format("ATSH 81 %02X %02X", ecu, TESTER));
        String r = elm.send("ATFI", Elm327.TIMEOUT_INIT_MS);
        if (!r.toUpperCase().contains("OK")) {
            throw new IOException("Init rápido KWP fallido: " + r.replace('\n', ' '));
        }
        ultimaCabecera = String.format("81 %02X %02X", ecu, TESTER);
        inicializado = true;
    }

    /**
     * Envía un servicio KWP y devuelve los bytes de datos de la respuesta
     * (sin cabecera ni checksum). Lanza IOException con texto legible si la
     * ECU devuelve respuesta negativa (7F).
     */
    public List<Integer> peticion(int... datos) throws IOException {
        if (!inicializado) init();
        // Reajustar la cabecera solo si cambia la longitud: ahorra un viaje
        // por petición, clave para la tasa de muestreo del registrador (olog).
        String cabecera = String.format("%02X %02X %02X", 0x80 | datos.length, ecu, TESTER);
        if (!cabecera.equals(ultimaCabecera)) {
            elm.send("ATSH " + cabecera);
            ultimaCabecera = cabecera;
        }
        StringBuilder hex = new StringBuilder();
        for (int b : datos) hex.append(String.format("%02X", b));
        String resp = elm.send(hex.toString(), Elm327.TIMEOUT_INIT_MS);
        if (resp.toUpperCase().contains("BUS INIT") && resp.toUpperCase().contains("ERROR")) {
            inicializado = false;
            throw new IOException("La sesión KWP se cayó (BUS INIT ERROR); reintenta con 'opel'");
        }
        List<Integer> data = extraerDatos(resp);
        if (data.isEmpty()) {
            throw new IOException("Sin datos en la respuesta: " + resp.replace('\n', ' '));
        }
        if (data.get(0) == 0x7F) {
            int codigo = data.size() > 2 ? data.get(2) : -1;
            throw new IOException(String.format("Respuesta negativa 7F %02X: %s",
                    codigo, ERRORES_NEGATIVOS.getOrDefault(codigo, "código desconocido")));
        }
        int esperado = datos[0] + 0x40;
        if (data.get(0) != esperado) {
            throw new IOException(String.format("SID inesperado %02X (esperaba %02X): %s",
                    data.get(0), esperado, resp.replace('\n', ' ')));
        }
        return data;
    }

    /**
     * Quita cabecera y checksum de cada línea de mensaje hex. Dos formatos:
     * corto {@code 8N tgt src <N datos> cs} (longitud en el byte de formato) y
     * largo {@code 80 tgt src LL <LL datos> cs} (byte de longitud aparte, para
     * respuestas de más de 63 bytes como la de 21 01).
     */
    static List<Integer> extraerDatos(String respuesta) {
        List<Integer> data = new ArrayList<>();
        for (String linea : respuesta.split("\n")) {
            String[] tokens = linea.trim().split("\\s+");
            if (tokens.length < 5) continue; // cabecera+checksum+al menos 1 dato
            List<Integer> bytes = new ArrayList<>();
            boolean esHex = true;
            for (String t : tokens) {
                if (t.matches("[0-9A-Fa-f]{2}")) bytes.add(Integer.parseInt(t, 16));
                else { esHex = false; break; }
            }
            if (!esHex) continue; // "BUS INIT: OK" y similares
            int longitud = bytes.get(0) & 0x3F;
            int inicio = longitud == 0 ? 4 : 3;
            if (longitud == 0) longitud = bytes.get(3);
            int fin = Math.min(inicio + longitud, bytes.size() - 1);
            data.addAll(bytes.subList(inicio, fin));
        }
        return data;
    }

    // ---- Servicios concretos ----

    public record Dtc(String codigo, int estado) {
        public boolean activo() { return (estado & 0x80) != 0; }
    }

    /**
     * ReadDiagnosticTroubleCodesByStatus (0x18). Respuesta: 58 NN [hi lo estado]...
     * Los dos bytes de código usan el mismo esquema de bits que los DTC OBD.
     */
    public List<Dtc> leerDtc() throws IOException {
        List<Integer> data;
        try {
            data = peticion(0x18, 0x00, 0xFF, 0x00); // todos los DTC identificados
        } catch (IOException e) {
            data = peticion(0x18, 0x02, 0xFF, 0x00); // variante: solo almacenados
        }
        List<Dtc> dtcs = new ArrayList<>();
        for (int i = 2; i + 2 < data.size(); i += 3) {
            int hi = data.get(i), lo = data.get(i + 1), estado = data.get(i + 2);
            if (hi == 0 && lo == 0) continue;
            char letra = switch (hi >> 6) {
                case 0 -> 'P';
                case 1 -> 'C';
                case 2 -> 'B';
                default -> 'U';
            };
            dtcs.add(new Dtc(String.format("%c%02X%02X", letra, hi & 0x3F, lo), estado));
        }
        return dtcs;
    }

    /** ClearDiagnosticInformation (0x14) de todos los grupos. */
    public boolean borrarDtc() throws IOException {
        List<Integer> data = peticion(0x14, 0xFF, 0x00);
        return data.get(0) == 0x54;
    }

    /** ReadEcuIdentification (0x1A). Devuelve el texto ASCII del identificador pedido. */
    public String identificacion(int opcion) throws IOException {
        List<Integer> data = peticion(0x1A, opcion);
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i < data.size(); i++) {
            int b = data.get(i);
            sb.append(b >= 0x20 && b < 0x7F ? (char) b : String.format("[%02X]", b));
        }
        return sb.toString();
    }

    /** ReadDataByLocalIdentifier (0x21) crudo, para explorar datos en vivo. */
    public List<Integer> datosLocales(int id) throws IOException {
        return peticion(0x21, id);
    }
}
