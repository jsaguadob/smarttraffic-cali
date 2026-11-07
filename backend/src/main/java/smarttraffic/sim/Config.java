package smarttraffic.sim;

/** Parámetros de una simulación. Todos los umbrales son ajustables: no son valores de ingeniería de tráfico. */
public final class Config {
    public ModoSemaforos modoSemaforos = ModoSemaforos.FIJO;
    /** Si es true, los vehículos recalculan su ruta con la congestión actual al llegar a cada intersección. */
    public boolean reruteoDinamico = false;
    public boolean despachoAutomatico = true;

    public double dt = 0.1;
    public long semilla = 42;
    /** Vehículos que piden entrar a la red por minuto. */
    public double vehiculosPorMinuto = 40;
    public double probMoto = 0.15;
    public double probBus = 0.08;

    // Semáforos de tiempo fijo
    public double verdeFijo = 25;
    public double amarillo = 3;
    public double todoRojo = 1.5;

    // Semáforos inteligentes: P = pesoV * V + pesoT * T
    public double verdeMin = 8;
    public double verdeMax = 40;
    public double pesoV = 0.7;
    public double pesoT = 0.3;
    public double capacidadCola = 10;
    public double esperaReferencia = 45;
    public double distanciaCola = 60;
    public double radioEmergencia = 140;

    // Flota de emergencia disponible al mismo tiempo
    public int maxAmbulancias = 2;
    public int maxBomberos = 1;
    public int maxPolicias = 2;

    public Config copia() {
        Config c = new Config();
        c.modoSemaforos = modoSemaforos;
        c.reruteoDinamico = reruteoDinamico;
        c.despachoAutomatico = despachoAutomatico;
        c.dt = dt;
        c.semilla = semilla;
        c.vehiculosPorMinuto = vehiculosPorMinuto;
        c.probMoto = probMoto;
        c.probBus = probBus;
        c.verdeFijo = verdeFijo;
        c.amarillo = amarillo;
        c.todoRojo = todoRojo;
        c.verdeMin = verdeMin;
        c.verdeMax = verdeMax;
        c.pesoV = pesoV;
        c.pesoT = pesoT;
        c.capacidadCola = capacidadCola;
        c.esperaReferencia = esperaReferencia;
        c.distanciaCola = distanciaCola;
        c.radioEmergencia = radioEmergencia;
        c.maxAmbulancias = maxAmbulancias;
        c.maxBomberos = maxBomberos;
        c.maxPolicias = maxPolicias;
        return c;
    }

    /** Configuración de «tráfico tradicional»: semáforos fijos y rutas que no reaccionan a la congestión. */
    public static Config tradicional(long semilla, double vehiculosPorMinuto) {
        Config c = new Config();
        c.semilla = semilla;
        c.vehiculosPorMinuto = vehiculosPorMinuto;
        return c;
    }

    /** Configuración «SmartTraffic»: semáforos inteligentes, prioridad a emergencias y reruteo dinámico. */
    public static Config inteligente(long semilla, double vehiculosPorMinuto) {
        Config c = tradicional(semilla, vehiculosPorMinuto);
        c.modoSemaforos = ModoSemaforos.INTELIGENTE;
        c.reruteoDinamico = true;
        return c;
    }
}
