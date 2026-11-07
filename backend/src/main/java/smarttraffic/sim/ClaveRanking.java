package smarttraffic.sim;

/** Clave del ranking de congestión: se ordena por puntaje y, a igual puntaje, por índice de vía. */
record ClaveRanking(double puntaje, int indice) implements Comparable<ClaveRanking> {
    @Override
    public int compareTo(ClaveRanking o) {
        int c = Double.compare(puntaje, o.puntaje);
        return c != 0 ? c : Integer.compare(indice, o.indice);
    }
}
