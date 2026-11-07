# Servidor de teselas FALSO para probar la alineación: cada tesela dibuja su z/x/y y una marca roja en la lat/lon del objetivo.
import math, io, sys, json
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from PIL import Image, ImageDraw

LAT0, LON0, R = 3.4516, -76.5320, 6371008.8
def a_grados(x, y):   # misma proyección local que el servidor, escrita aparte
    return LAT0 - math.degrees(y / R), LON0 + math.degrees(x / (R * math.cos(math.radians(LAT0))))
OBJ = a_grados(float(sys.argv[2]), float(sys.argv[3]))   # N11 en metros locales
PEDIDAS = []

def pixel_mercator(lat, lon, z):
    n = 256 * 2 ** z
    return (lon + 180) / 360 * n, (1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * n

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        if self.path == '/pedidas':
            body = json.dumps(PEDIDAS).encode()
            self.send_response(200); self.send_header('Access-Control-Allow-Origin', '*'); self.send_header('Content-Type', 'application/json'); self.end_headers(); self.wfile.write(body); return
        try:
            z, x, y = [int(v) for v in self.path.split('?')[0].replace('.png', '').strip('/').split('/')]
        except ValueError:
            self.send_response(404); self.end_headers(); return
        PEDIDAS.append([z, x, y, self.headers.get('Referer', '')])
        im = Image.new('RGB', (256, 256), (223, 230, 238)); d = ImageDraw.Draw(im)
        d.rectangle([0, 0, 255, 255], outline=(170, 180, 195)); d.text((8, 8), f'{z}/{x}/{y}', fill=(90, 100, 115))
        px, py = pixel_mercator(OBJ[0], OBJ[1], z); px -= x * 256; py -= y * 256
        if -10 < px < 266 and -10 < py < 266:
            d.rectangle([px - 4, py - 4, px + 4, py + 4], fill=(255, 0, 0))
        b = io.BytesIO(); im.save(b, 'PNG'); data = b.getvalue()
        self.send_response(200); self.send_header('Content-Type', 'image/png'); self.send_header('Access-Control-Allow-Origin', '*'); self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)

ThreadingHTTPServer(('127.0.0.1', int(sys.argv[1])), H).serve_forever()
