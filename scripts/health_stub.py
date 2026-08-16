from http.server import BaseHTTPRequestHandler, HTTPServer

# Change to 503 to simulate a DOWN health check.
HEALTH_STATUS = 200


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            self.send_response(HEALTH_STATUS)
            self.end_headers()
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        if self.path == "/shutdown":
            self.send_response(200)
            self.end_headers()
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        print(f"{self.command} {self.path} -> handled")


if __name__ == "__main__":
    print("Listening on 0.0.0.0:8082 (GET /health, POST /shutdown)")
    HTTPServer(("0.0.0.0", 8082), Handler).serve_forever()
