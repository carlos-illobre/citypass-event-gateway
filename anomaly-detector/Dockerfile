FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY src/ ./src/

WORKDIR /app/src

# La imagen de Python no trae un usuario sin privilegios, así que se crea uno. El uid fijo
# hace que el contenedor se comporte igual en cualquier máquina, en vez de depender del
# número que le toque al crearlo.
#
# Va después de instalar y copiar: los archivos quedan de root y el proceso sólo los lee.
RUN useradd --system --uid 1001 --no-create-home detector
USER detector

EXPOSE 8084

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8084"]
