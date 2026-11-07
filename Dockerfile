# Servidor de SmartTraffic (API y simulación). La pantalla se publica aparte (Netlify) o la entrega este mismo servidor.
FROM eclipse-temurin:21-jdk AS compilar
WORKDIR /app
COPY build.sh .
COPY backend backend
RUN ./build.sh

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 smarttraffic && mkdir /datos && chown smarttraffic /datos
COPY --from=compilar /app/backend/out/main /app/clases
COPY --from=compilar /app/backend/lib/h2.jar /app/backend/lib/postgresql.jar /app/lib/
USER smarttraffic
WORKDIR /datos
# El puerto lo define la plataforma con la variable PORT (si no existe, 8080).
EXPOSE 8080
CMD ["java", "-cp", "/app/clases:/app/lib/h2.jar:/app/lib/postgresql.jar", "smarttraffic.Main"]
