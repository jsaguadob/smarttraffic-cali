package smarttraffic.red;

/**
 * Red vial de demostración: una zona ficticia de 16 intersecciones inspirada en la cuadrícula del
 * centro de Cali (calles y carreras), con calles de un solo sentido, un hospital y una estación
 * de emergencias. No representa el mapa real de la ciudad.
 */
public final class RedCali {
    private RedCali() {}

    private static final String[] CALLES = {"Calle 25", "Calle 13", "Calle 9", "Calle 5"};
    private static final String[] CARRERAS = {"Carrera 1", "Carrera 15", "Carrera 24", "Carrera 39"};
    private static final double[] XS = {0, 255, 505, 750};
    private static final double[] YS = {0, 205, 410, 610};
    // pequeñas variaciones para que el mapa no parezca una cuadrícula perfecta
    private static final double[][] DX = {{0, 10, -8, 14}, {-12, 6, 9, -6}, {8, -10, 12, 4}, {-6, 12, -9, 10}};
    private static final double[][] DY = {{4, -9, 12, 0}, {-8, 6, -12, 9}, {10, -5, 7, -11}, {-4, 9, -6, 5}};

    public static RedVial crear() {
        RedVial red = new RedVial("Zona Centro (ficticia, inspirada en Cali)");
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                boolean borde = r == 0 || r == 3 || c == 0 || c == 3;
                TipoNodo tipo = borde ? TipoNodo.ENTRADA_SALIDA : TipoNodo.INTERIOR;
                red.agregarInterseccion(new Interseccion(id(r, c), CALLES[r] + " con " + CARRERAS[c], XS[c] + DX[r][c], YS[r] + DY[r][c], tipo));
            }
        }
        red.agregarInterseccion(new Interseccion("HOSP", "Hospital Universitario (ficticio)", -190, 210, TipoNodo.BASE_EMERGENCIAS));
        red.agregarInterseccion(new Interseccion("EST", "Estación de Bomberos y Policía (ficticia)", 960, 415, TipoNodo.BASE_EMERGENCIAS));

        // Calles horizontales (una por fila). Calle 13: solo hacia el este. Calle 9: solo hacia el oeste.
        double[] velCalle = {50, 40, 40, 50};
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 3; c++) {
                String a = id(r, c), b = id(r, c + 1), base = "H" + r + c;
                if (r == 1) red.agregarVia(base + "E", a, b, CALLES[r], null, velCalle[r]);
                else if (r == 2) red.agregarVia(base + "O", b, a, CALLES[r], null, velCalle[r]);
                else red.agregarCalle(base, a, b, true, CALLES[r], velCalle[r]);
            }
        }
        // Carreras verticales (una por columna). Carrera 15: solo hacia el sur. Carrera 24: solo hacia el norte.
        double[] velCarrera = {40, 50, 50, 40};
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 3; r++) {
                String a = id(r, c), b = id(r + 1, c), base = "V" + r + c;
                if (c == 1) red.agregarVia(base + "S", a, b, CARRERAS[c], null, velCarrera[c]);
                else if (c == 2) red.agregarVia(base + "N", b, a, CARRERAS[c], null, velCarrera[c]);
                else red.agregarCalle(base, a, b, true, CARRERAS[c], velCarrera[c]);
            }
        }
        red.agregarCalle("ACC-HOSP", "HOSP", id(1, 0), true, "Acceso al hospital", 50);
        red.agregarCalle("ACC-EST", "EST", id(2, 3), true, "Acceso a la estación", 50);
        return red;
    }

    public static String id(int fila, int columna) {
        return "N" + fila + columna;
    }
}
