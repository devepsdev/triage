"""Tienda ficticia sobre la que ocurren las averías. Todos los datos son inventados."""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pg8000.dbapi

LOG = "/var/log/app/app.log"


def salida(mensaje):
    print(mensaje, flush=True)


def escribir_log(linea):
    with open(LOG, "a", encoding="utf-8") as archivo:
        archivo.write(linea + "\n")


def pedidos():
    conexion = pg8000.dbapi.connect(host="db", user="tienda", password="tienda-ficticia", database="tienda", timeout=3)
    try:
        cursor = conexion.cursor()
        cursor.execute("SELECT id, producto, cantidad FROM pedidos ORDER BY id")
        return [{"id": fila[0], "producto": fila[1], "cantidad": fila[2]} for fila in cursor.fetchall()]
    finally:
        conexion.close()


PORTADA = "<!doctype html><title>Tienda</title><h1>Tienda de ejemplo</h1><p>Pedidos en línea.</p>"
DEMO = "<!doctype html><title>Café Alba</title><h1>Café Alba</h1><p>Demo de la tienda.</p>"


class Tienda(BaseHTTPRequestHandler):
    def log_message(self, *argumentos):
        pass

    def responder(self, codigo, tipo, cuerpo):
        datos = cuerpo.encode("utf-8")
        self.send_response(codigo)
        self.send_header("Content-Type", tipo)
        self.send_header("Content-Length", str(len(datos)))
        self.end_headers()
        self.wfile.write(datos)

    def terminar(self, ruta, codigo, tipo, cuerpo):
        salida(f'GET {ruta} {codigo} ua="{self.headers.get("User-Agent", "-")}"')
        self.responder(codigo, tipo, cuerpo)

    def do_GET(self):
        ruta = self.path.split("?")[0]
        if ruta == "/salud":
            self.responder(200, "application/json", '{"estado":"ok"}')
            return

        try:
            escribir_log(f"GET {ruta}")
        except OSError as error:
            salida(f"ERROR no se pudo escribir {LOG}: {error.strerror}")
            self.terminar(ruta, 500, "text/plain; charset=utf-8", "Error interno")
            return

        if ruta == "/":
            self.terminar(ruta, 200, "text/html; charset=utf-8", PORTADA)
        elif ruta == "/demo/":
            self.terminar(ruta, 200, "text/html; charset=utf-8", DEMO)
        elif ruta == "/api/pedidos":
            try:
                self.terminar(ruta, 200, "application/json", json.dumps(pedidos(), ensure_ascii=False))
            except Exception as error:  # cualquier fallo de la base de datos es un 503 para el cliente
                salida(f"ERROR conexion con la base de datos: {error}")
                self.terminar(ruta, 503, "text/plain; charset=utf-8", "Servicio no disponible")
        else:
            self.terminar(ruta, 404, "text/plain; charset=utf-8", "No encontrado")


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), Tienda).serve_forever()
